package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.protocol.ApiError
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.SearchResponse
import dev.trashpanda.ytmp.protocol.ServerMessage
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteReadChannel
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

fun Application.ytmpModule(
    rooms: RoomManager,
    search: SongSearch,
    ytm: RemoteSourceModule?,
    frontendDir: File?,
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
        exception<RemoteSourceModule.ModuleException> { call, e ->
            call.respond(HttpStatusCode.BadGateway, ApiError(ErrorInfo(ErrorCode.INVALID, e.message ?: "Module error")))
        }
    }

    routing {
        route("/api") {
            post("/rooms") {
                val name = call.receive<CreateRoomRequest>().name.trim()
                if (name.isEmpty() || name.length > 64) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Room name must be 1-64 characters")
                val room = rooms.create(name)
                log.info("Created room {} ({})", room.code, room.name)
                call.respond(CreateRoomResponse(room.code, room.ownerToken))
            }
            get("/rooms/{code}") {
                val room = rooms[call.parameters["code"]!!] ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Room not found")
                call.respond(RoomInfo(room.code, room.name))
            }
            get("/search") {
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                if (query.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Missing q")
                call.respond(SearchResponse(search.search(query)))
            }
            get("/audio/{songId}") {
                val module = ytm ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No audio source")
                module.audio(call.parameters["songId"]!!, call.request.header(HttpHeaders.Range)) { response ->
                    call.respond(ProxiedAudio(response, response.bodyAsChannel()))
                }
            }
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
            val participantId = room.join(hello, outbox)
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

        if (frontendDir != null && frontendDir.isDirectory) {
            webApp(frontendDir)
        } else {
            log.warn("No frontend found at {}; only the API is served", frontendDir)
        }
    }
}

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

/** Passes the module's audio response (status, range headers, body) straight through. */
private class ProxiedAudio(response: HttpResponse, private val body: ByteReadChannel) : OutgoingContent.ReadChannelContent() {
    override val status = response.status
    override val contentType: ContentType? = response.contentType()
    override val contentLength: Long? = response.contentLength()
    override val headers: Headers = headersOf(
        *listOfNotNull(
            HttpHeaders.AcceptRanges to listOf("bytes"),
            response.headers[HttpHeaders.ContentRange]?.let { HttpHeaders.ContentRange to listOf(it) },
        ).toTypedArray(),
    )

    override fun readFrom(): ByteReadChannel = body
}
