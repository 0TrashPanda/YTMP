package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.protocol.ApiError
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.HostInfo
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.RoomListResponse
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.SearchResponse
import dev.trashpanda.ytmp.protocol.ServerMessage
import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("ytmp")

/** Messages a slow client may have queued before it is disconnected. */
private const val OUTBOX_CAPACITY = 1024

class ApiException(val status: HttpStatusCode, val code: ErrorCode, message: String) : Exception(message)

/** Thrown by a source when it can't do what was asked (e.g. YouTube refused). */
open class SourceException(message: String) : Exception(message)

/** Streams a song's audio through the host, for clients whose direct stream URL doesn't work. */
fun interface AudioProxy {
    suspend fun respond(call: ApplicationCall, songId: String, range: String?)
}

/** How this host behaves. The Linux server and a phone differ. */
data class HostOptions(
    val kind: HostKind,
    /** Base URL other devices can use to reach this host, or null if the page's own address works. */
    val shareUrl: () -> String? = { null },
    /**
     * Only the hosting device itself may create, list and close rooms (a phone: friends on
     * the Wi-Fi can join, but not start rooms on someone else's phone).
     */
    val localOnlyRoomManagement: Boolean = false,
    val supportsPrivateRooms: Boolean = false,
    /** Whether a request comes from the hosting device itself. Overridable for tests. */
    val isLocal: (ApplicationCall) -> Boolean = ::isLoopback,
    /** Which accounts can join. */
    val auth: HostAuth = NoAuth,
)

fun isLoopback(call: ApplicationCall): Boolean {
    val address = call.request.origin.remoteAddress
    return address == "localhost" || address.startsWith("127.") || address == "::1" || address == "0:0:0:0:0:0:0:1"
}

fun Application.ytmpModule(
    rooms: RoomManager,
    search: SongSearch,
    audio: AudioProxy?,
    webApp: File?,
    options: HostOptions,
    /** More `/api` routes: the account API on a server, linking an auth server on a phone. */
    extraApi: Route.() -> Unit = {},
) {
    install(ContentNegotiation) { json(ProtocolJson) }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 30.seconds
    }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api") }
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(ErrorInfo(e.code, e.message ?: ""))) }
        exception<SourceException> { call, e ->
            call.respond(HttpStatusCode.BadGateway, ApiError(ErrorInfo(ErrorCode.INVALID, e.message ?: "Source error")))
        }
    }

    fun ApplicationCall.mayManageRooms() = !options.localOnlyRoomManagement || options.isLocal(this)

    routing {
        route("/api") {
            get("/host") {
                call.respond(
                    HostInfo(
                        kind = options.kind,
                        shareUrl = options.shareUrl(),
                        canCreateRooms = call.mayManageRooms(),
                        supportsPrivateRooms = options.supportsPrivateRooms,
                        authServers = options.auth.servers(),
                    ),
                )
            }
            get("/rooms") {
                // Only the hosting phone may see its rooms; a server never lists them.
                if (!options.localOnlyRoomManagement || !options.isLocal(call)) throw forbidden()
                call.respond(RoomListResponse(rooms.list.value))
            }
            post("/rooms") {
                if (!call.mayManageRooms()) throw forbidden()
                val request = call.receive<CreateRoomRequest>()
                val name = request.name.trim()
                if (name.isEmpty() || name.length > 64) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Room name must be 1-64 characters")
                if (request.visibility == RoomVisibility.PRIVATE && !options.supportsPrivateRooms) {
                    throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Solo rooms aren't supported here")
                }
                val account = call.bearerToken()?.let(options.auth::verify)
                val room = rooms.create(name, request.visibility, ownerAccount = account?.id)
                log.info("Created room {} ({}, {}{})", room.code, room.name, room.visibility, account?.let { ", owner ${it.id}" } ?: "")
                call.respond(CreateRoomResponse(room.code, room.ownerToken))
            }
            get("/rooms/{code}") {
                val room = rooms[call.parameters["code"]!!]
                // A private room doesn't exist for anyone but the hosting device.
                if (room == null || (room.visibility == RoomVisibility.PRIVATE && !options.isLocal(call))) {
                    throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Room not found")
                }
                call.respond(room.info)
            }
            delete("/rooms/{code}") {
                if (!options.localOnlyRoomManagement || !options.isLocal(call)) throw forbidden()
                rooms.close(call.parameters["code"]!!)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/search") {
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                if (query.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Missing q")
                call.respond(SearchResponse(search.search(query)))
            }
            get("/audio/{songId}") {
                val proxy = audio ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No audio source")
                proxy.respond(call, call.parameters["songId"]!!, call.request.header(HttpHeaders.Range))
            }
            extraApi()
        }

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

            val hello = (incoming.receiveCatching().getOrNull() as? Frame.Text)?.let { decode(it) } as? ClientMessage.Hello
            val room = hello?.let { rooms[it.roomCode] }
            if (hello == null || room == null) {
                outbox.send(ServerMessage.Rejected(RejectReason.ROOM_NOT_FOUND))
                outgoing.close()
                sender.join()
                return@webSocket
            }
            val account = hello.accountToken?.let(options.auth::verify)
            if (hello.accountToken != null && account == null) log.info("Room {}: account token not accepted, joining as a guest", room.code)
            val participantId = room.join(hello, outbox, local = options.isLocal(call), account = account)
            if (participantId == null) {
                outgoing.close()
                sender.join()
                return@webSocket
            }
            try {
                for (frame in incoming) {
                    val message = (frame as? Frame.Text)?.let { decode(it) } ?: continue
                    room.handle(participantId, message)
                }
            } finally {
                room.disconnect(participantId, outbox)
                outgoing.close()
            }
        }

        if (webApp != null && webApp.isDirectory) {
            webApp(webApp)
        } else {
            log.warn("No web app found at {}; only the API is served", webApp)
        }
    }
}

/** The token in `Authorization: Bearer …`, if any. */
fun ApplicationCall.bearerToken(): String? =
    request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.ifEmpty { null }

private fun forbidden() = ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only the hosting device can do this")

/**
 * Serves the built web app. It is a single-page app, so unknown page paths get index.html;
 * missing files (scripts, images) get a real 404 instead, so a stale page fails visibly.
 */
private fun Route.webApp(dir: File) {
    val index = File(dir, "index.html")
    staticFiles("/", dir) {
        cacheControl { file ->
            // Hashed build files never change; everything else must be revalidated.
            if ("/_app/immutable/" in file.invariantSeparatorsPath) listOf(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public))
            else listOf(CacheControl.NoCache(null))
        }
        fallback { requestedPath, call ->
            if (requestedPath.startsWith("_app/") || '.' in requestedPath.substringAfterLast('/')) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondFile(index)
            }
        }
    }
}

private fun decode(frame: Frame.Text): ClientMessage? =
    runCatching { ProtocolJson.decodeFromString(ClientMessage.serializer(), frame.readText()) }
        .onFailure { log.debug("Ignoring bad message: {}", it.message) }
        .getOrNull()
