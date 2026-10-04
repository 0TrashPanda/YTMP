package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.protocol.YoutubeAccountStatus
import dev.trashpanda.ytmp.protocol.YoutubeHistory
import dev.trashpanda.ytmp.protocol.YoutubeHistorySetting
import dev.trashpanda.ytmp.protocol.CreatePlaylistRequest
import dev.trashpanda.ytmp.protocol.CreatePlaylistResponse
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.MyPlaylists
import dev.trashpanda.ytmp.protocol.SaveSongsRequest
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.SearchSuggestions
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
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.SuggestionsResponse
import dev.trashpanda.ytmp.protocol.wireName
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
import io.ktor.server.routing.put
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
    /** Whether this request may stream audio through the host (a server can limit it to save bandwidth). */
    val mayProxyAudio: (ApplicationCall) -> Boolean = { true },
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
    /** Similar songs (YTM's radio), for Find similar. */
    similar: RadioSource? = null,
    /** Artist, album and playlist pages. */
    catalog: CatalogSource? = null,
    /** The owner's YouTube Music account (phone hosts), for requests from the host's own app. */
    personal: PersonalCatalog? = null,
    /** Search suggestions while typing. */
    suggestions: SearchSuggestions? = null,
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
                val token = call.bearerToken()
                val account = token?.let(options.auth::verify)
                // The creator's own roles, if they saved a template to their account.
                val template = if (account != null) options.auth.roleTemplate(token) else null
                val room = rooms.create(name, request.visibility, ownerAccount = account?.id, template = template)
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
                val type = call.request.queryParameters["type"]?.let(::searchType) ?: SearchType.ALL
                call.respond(search.search(query, type))
            }
            get("/search/suggestions") {
                // Typing goes on without them, so no source (or an empty box) is just no suggestions.
                val query = call.request.queryParameters["q"].orEmpty()
                call.respond(SuggestionsResponse(if (query.isBlank()) emptyList() else suggestions?.suggestions(query).orEmpty()))
            }
            get("/similar") {
                val songId = call.request.queryParameters["id"]?.trim().orEmpty()
                if (songId.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Missing id")
                val source = similar ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No radio source")
                call.respond(SearchResponse(source.radio(songId).filter { it.id != songId }))
            }
            get("/artists/{id}") {
                val source = catalog ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No artist pages here")
                call.respond(source.artist(call.parameters["id"]!!))
            }
            get("/albums/{id}") {
                val source = catalog ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No album pages here")
                call.respond(source.album(call.parameters["id"]!!))
            }
            get("/playlists/{id}") {
                val source = catalog ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No playlist pages here")
                call.respond(source.playlist(call.parameters["id"]!!))
            }
            get("/podcasts/{id}") {
                val source = catalog ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No podcast pages here")
                call.respond(source.podcast(call.parameters["id"]!!))
            }
            get("/home") {
                // The owner's own home page in their app; everyone else gets the general one.
                // ?fresh=1: pulled down to refresh, so not from the cache.
                val fresh = call.request.queryParameters["fresh"] == "1"
                if (personal != null && options.isLocal(call) && personal.account() != null) {
                    return@get call.respond(if (fresh) personal.freshHome() else personal.home())
                }
                val source = catalog ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No home page here")
                call.respond(if (fresh) source.freshHome() else source.home())
            }
            route("/me") {
                fun ApplicationCall.owner(): PersonalCatalog =
                    personal?.takeIf { options.isLocal(this) } ?: throw forbidden()
                get("/youtube") {
                    val mine = personal?.takeIf { options.isLocal(call) }
                    call.respond(
                        YoutubeAccountStatus(available = mine != null, account = mine?.account(), history = mine?.historySetting() ?: YoutubeHistory.SOLO),
                    )
                }
                put("/youtube/history") {
                    val owner = call.owner()
                    owner.setHistorySetting(call.receive<YoutubeHistorySetting>().history)
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/youtube") {
                    call.owner().signOut()
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/library") { call.respond(call.owner().library()) }
                get("/playlists") { call.respond(MyPlaylists(call.owner().ownPlaylists())) }
                post("/playlists") {
                    val owner = call.owner()
                    val request = call.receive<CreatePlaylistRequest>()
                    val title = request.title.trim()
                    if (title.isEmpty() || title.length > 150) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Playlist name must be 1-150 characters")
                    call.respond(CreatePlaylistResponse(owner.createPlaylist(title, request.songIds)))
                }
                get("/playlists/{id}") { call.respond(call.owner().playlist(call.parameters["id"]!!)) }
                post("/playlists/{id}/songs") {
                    val owner = call.owner()
                    owner.addToPlaylist(call.parameters["id"]!!, call.receive<SaveSongsRequest>().songIds)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/likes/{songId}") { call.respond(LikeStatus(call.owner().liked(call.parameters["songId"]!!))) }
                put("/likes/{songId}") {
                    val owner = call.owner()
                    owner.setLiked(call.parameters["songId"]!!, call.receive<LikeStatus>().liked)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            get("/audio/{songId}") {
                val proxy = audio ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No audio source")
                if (!options.mayProxyAudio(call)) {
                    throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "This server doesn't stream audio to devices outside its network")
                }
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

            val first = (incoming.receiveCatching().getOrNull() as? Frame.Text)?.let { decode(it) }
            val room = when (first) {
                is ClientMessage.Hello -> rooms[first.roomCode]
                is ClientMessage.Attach -> rooms[first.roomCode]
                else -> null
            }
            if (room == null) {
                outbox.send(ServerMessage.Rejected(RejectReason.ROOM_NOT_FOUND))
                outgoing.close()
                sender.join()
                return@webSocket
            }
            val participantId = if (first is ClientMessage.Attach) {
                room.attach(first, outbox, local = options.isLocal(call))
            } else {
                val hello = first as ClientMessage.Hello
                val account = hello.accountToken?.let(options.auth::verify)
                if (hello.accountToken != null && account == null) log.info("Room {}: account token not accepted, joining as a guest", room.code)
                room.join(hello, outbox, local = options.isLocal(call), account = account)
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

        if (webApp != null && webApp.isDirectory) {
            webApp(webApp)
        } else {
            log.warn("No web app found at {}; only the API is served", webApp)
        }
    }
}

/** "community_playlists" -> [SearchType.COMMUNITY_PLAYLISTS]. */
private fun searchType(value: String): SearchType =
    SearchType.entries.firstOrNull { it.wireName == value }
        ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Unknown search type: $value")

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
