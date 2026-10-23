package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.Room
import dev.trashpanda.ytmp.core.RoomCodeTaken
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.HostInfo
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.ROOM_CODE
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.RoomListResponse
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ytmp")

/** Messages a slow client may have queued before it is disconnected. */
private const val OUTBOX_CAPACITY = 1024

/** `/api/host` and the rooms: list, create, look up, close. */
internal fun Route.roomRoutes(host: HostContext) = with(host) {
    get("/host") {
        // The hosting device's own page, logged in: whose device this is.
        if (options.isLocal(call)) call.account()
        call.respond(
            HostInfo(
                kind = options.kind,
                shareUrl = options.shareUrl(),
                canCreateRooms = call.mayManageRooms(),
                supportsPrivateRooms = options.supportsPrivateRooms,
                authServers = options.auth.servers(),
                accountsOnly = options.accountsOnly(),
                shareAddresses = options.shareAddresses(),
            ),
        )
    }
    get("/rooms") {
        // The hosting phone sees all its rooms; on a server, you see the rooms your account owns.
        if (options.localOnlyRoomManagement) {
            if (!options.isLocal(call)) throw onlyTheHostingDevice()
            return@get call.respond(RoomListResponse(rooms.list.value))
        }
        val me = call.account()?.id ?: return@get call.respond(RoomListResponse(emptyList()))
        call.respond(RoomListResponse(rooms.all().filter { it.ownerAccount == me }.map { it.info }))
    }
    post("/rooms") {
        if (!call.mayManageRooms()) throw onlyTheHostingDevice()
        val request = call.receive<CreateRoomRequest>()
        val token = call.bearerToken()
        val account = call.account()
        // The creator's own roles, if they saved a template to their account (a clone keeps the
        // room's), and their default room password.
        val template = if (account != null && token != null) options.auth.roleTemplate(token) else null
        val room = rooms.createFor(request, account?.id, template.takeIf { request.clone == null }, options.supportsPrivateRooms, passwordHash = template?.roomPassword)
        val base = options.shareUrl() ?: call.request.origin.let { o ->
            val defaultPort = (o.scheme == "https" && o.serverPort == 443) || (o.scheme == "http" && o.serverPort == 80)
            "${o.scheme}://${o.serverHost}${if (defaultPort) "" else ":${o.serverPort}"}"
        }
        call.respond(CreateRoomResponse(room.code, room.ownerToken, url = "$base/room/${room.code}"))
    }
    get("/rooms/{code}") {
        val room = rooms[call.parameters["code"]!!]
        // A private room doesn't exist for anyone but the hosting device and its owner.
        if (room == null || (room.visibility == RoomVisibility.PRIVATE && !options.isLocal(call) && !call.owns(room))) {
            throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Room not found")
        }
        call.respond(room.info)
    }
    delete("/rooms/{code}") {
        val room = rooms[call.parameters["code"]!!] ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Room not found")
        val hostingPhone = options.localOnlyRoomManagement && options.isLocal(call)
        if (!hostingPhone && !call.owns(room)) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only the room's owner can close it")
        rooms.close(room.code)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** `/ws`: a connection to a room (docs/implementation/protocol.md). */
internal fun Route.roomSocket(host: HostContext) = with(host) {
    webSocket("/ws") {
        val outgoing = Channel<ServerMessage>(OUTBOX_CAPACITY)
        val outbox = Outbox { message -> if (outgoing.trySend(message).isFailure) outgoing.close() }
        val sender = launch {
            for (message in outgoing) {
                send(Frame.Text(ProtocolJson.encodeToString(ServerMessage.serializer(), message)))
                if (message is ServerMessage.Rejected) break
            }
            close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
        }

        val first = (incoming.receiveCatching().getOrNull() as? Frame.Text)?.let { decode(it) }
        val room = when (first) {
            is ClientMessage.Hello -> rooms[first.roomCode]
            is ClientMessage.Attach -> rooms[first.roomCode]
            else -> null
        }
        if (room == null) {
            // A room that moved to another of its owner's hosts: say where.
            val code = (first as? ClientMessage.Hello)?.roomCode ?: (first as? ClientMessage.Attach)?.roomCode
            val movedTo = code?.let(rooms::movedTo)
            outbox.send(
                if (movedTo != null) ServerMessage.Rejected(RejectReason.ROOM_MOVED, movedTo.ifEmpty { null })
                else ServerMessage.Rejected(RejectReason.ROOM_NOT_FOUND),
            )
            outgoing.close()
            sender.join()
            return@webSocket
        }
        val participantId = if (first is ClientMessage.Attach) {
            room.attach(first, outbox, local = options.isLocal(call), accountsOnly = options.accountsOnly())
        } else {
            val hello = first as ClientMessage.Hello
            val account = hello.accountToken?.let { token -> options.auth.verify(token)?.also { options.onAccountToken(token, it) } }
            if (hello.accountToken != null && account == null) log.info("Room {}: account token not accepted, joining as a guest", room.code)
            // A party screen someone in the room linked comes without an account.
            if (account == null && options.accountsOnly() && !room.isScreen(hello.guestToken)) {
                outbox.send(ServerMessage.Rejected(RejectReason.ACCOUNT_REQUIRED))
                null
            } else {
                room.join(hello, outbox, local = options.isLocal(call), account = account)
            }
        }
        if (participantId == null) {
            outgoing.close()
            sender.join()
            return@webSocket
        }
        try {
            for (frame in incoming) {
                val message = (frame as? Frame.Text)?.let { decode(it) } ?: continue
                room.handle(participantId, message, outbox)
            }
        } finally {
            room.disconnect(participantId, outbox)
            outgoing.close()
        }
    }
}

private fun decode(frame: Frame.Text): ClientMessage? =
    runCatching { ProtocolJson.decodeFromString(ClientMessage.serializer(), frame.readText()) }
        .onFailure { log.debug("Ignoring bad message: {}", it.message) }
        .getOrNull()

private fun onlyTheHostingDevice() = ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only the hosting device can do this")

/**
 * Creates a room for [request] (also a clone of another room), owned by [ownerAccount] if
 * set. Throws [ApiException] for a request that can't be done here.
 */
fun RoomManager.createFor(
    request: CreateRoomRequest,
    ownerAccount: String?,
    template: RoleTemplate?,
    supportsPrivateRooms: Boolean,
    /** Joining needs this password (hashed), e.g. the owner's default one. */
    passwordHash: String? = null,
): Room {
    val name = request.name.trim()
    if (name.isEmpty() || name.length > 64) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Room name must be 1-64 characters")
    if (request.visibility == RoomVisibility.PRIVATE && !supportsPrivateRooms) {
        throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Solo rooms aren't supported here")
    }
    val code = request.code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    if (code != null && !ROOM_CODE.matches(code)) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Room codes are 3 to 12 letters and digits")
    val room = try {
        create(name, request.visibility, ownerAccount = ownerAccount, template = template, clone = request.clone, passwordHash = passwordHash, chosenCode = code)
    } catch (e: RoomCodeTaken) {
        throw ApiException(HttpStatusCode.Conflict, ErrorCode.INVALID, "The code ${e.code} is taken. Pick another one")
    }
    LoggerFactory.getLogger("dev.trashpanda.ytmp.host.Application").info(
        "Created room {} ({}, {}{}{})", room.code, room.name, room.visibility, ownerAccount?.let { ", owner $it" } ?: "",
        request.clone?.let { ", a clone with ${it.queue.size} songs queued" } ?: "",
    )
    return room
}
