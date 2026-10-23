package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.AccountIdentity
import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.SearchSuggestions
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.core.SourceBlocked
import dev.trashpanda.ytmp.protocol.ApiError
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.HostInfo
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.ShareAddress
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.SongCachePlace
import dev.trashpanda.ytmp.protocol.SongCacheSettings
import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("ytmp")

class ApiException(val status: HttpStatusCode, val code: ErrorCode, message: String) : Exception(message)

/** Thrown by a source when it can't do what was asked (e.g. YouTube refused). */
open class SourceException(message: String) : Exception(message)

/** YouTube's bot check: no lookups from this network for a while (see [SourceBlocked]). */
class SourceBlockedException(message: String) : SourceException(message), SourceBlocked

/** Streams a song's audio through the host, for clients whose direct stream URL doesn't work. */
fun interface AudioProxy {
    suspend fun respond(call: ApplicationCall, songId: String, range: String?)
}

/** What a host offers besides its rooms: where songs, pages, lyrics and audio come from. */
data class HostSources(
    val search: SongSearch,
    /** Search suggestions while typing. */
    val suggestions: SearchSuggestions? = null,
    /** Similar songs (YTM's radio), for Find similar. */
    val similar: RadioSource? = null,
    /** Artist, album, playlist and podcast pages, and Home. */
    val catalog: CatalogSource? = null,
    /** Lyrics for the songs (LRCLIB, then the source's own). */
    val lyrics: LyricsLookup? = null,
    /** Songs' audio through the host, for players whose direct link doesn't work (and speakers). */
    val audio: AudioProxy? = null,
    /** Songs made into tagged files to keep (Download in the song menu); local music isn't offered. */
    val downloads: SongFiles? = null,
    /** Songs kept so they start at once and need no lookup (docs/implementation/song-cache.md); [audio] and the rooms' streams go through it. */
    val cache: SongCache? = null,
    /** Local music (the Local tab): a Raspberry Pi's music folder, the music on the hosting phone. */
    val local: LocalMusic? = null,
    /** Amplifier zones, for another host that takes their player over. */
    val zones: ZoneOutputs? = null,
    /** The owner's YouTube Music account (phone hosts), for requests from the host's own app. */
    val personal: PersonalCatalog? = null,
    /**
     * Whose YouTube Music a request may use, instead of [personal]: on a server, the account
     * the request proves. Null: none (the general pages).
     */
    val personalFor: ((ApplicationCall) -> PersonalCatalog?)? = null,
    /**
     * Songs that go with a song in the song graph, for whoever asks (their account's listening,
     * where their auth server allows it), mixed into Find similar and Related. Null: none.
     */
    val songGraph: (suspend (call: ApplicationCall, seedSongId: String) -> List<Song>)? = null,
)

/** How this host behaves. The Linux server and a phone differ. */
data class HostOptions(
    val kind: HostKind,
    /** Base URL other devices can use to reach this host, or null if the page's own address works. */
    val shareUrl: () -> String? = { null },
    /** All the addresses other devices may reach this host at, when there are several (see [HostInfo.shareAddresses]). */
    val shareAddresses: () -> List<ShareAddress> = { emptyList() },
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
    /** An account token was accepted (a phone keeps the latest, to sync the account's rooms). */
    val onAccountToken: (token: String, account: AccountIdentity) -> Unit = { _, _ -> },
    /** The hosting device itself proved [AccountIdentity] (a phone: its owner's account). */
    val onLocalAccount: (AccountIdentity) -> Unit = {},
    /**
     * The account of whoever owns the hosting device (a phone), or null. Logged in with it on
     * another device, they may use the device's own YouTube Music sign-in ([HostSources.personal]).
     */
    val deviceOwner: () -> String? = { null },
    /**
     * No guests: the API (but logging in and audio for speakers) and rooms need an account
     * of [auth]. A server setting (`accounts.guests = false`).
     */
    val accountsOnly: Boolean = false,
    /**
     * `/api/me/…` (your YouTube Music) may be used by pages of other hosts (CORS): the pages of
     * rooms on someone else's phone or server use your account's YouTube Music here. A server.
     */
    val crossOriginPersonal: Boolean = false,
    /**
     * The song cache's size and place can be changed here (a phone's Settings → Playback), by
     * the hosting device itself. Null: they're fixed (a server's ytmp.toml).
     */
    val cacheSettings: CacheSettings? = null,
)

/** Where the song cache may be kept and how big it is, as a setting (a phone). */
interface CacheSettings {
    /** Where it can be: the phone's own storage, an SD card when there is one. */
    fun places(): List<SongCachePlace>

    fun place(): String?

    /** The phone's own music is matched to YouTube Music and listed with the cached songs. */
    fun localSongs(): Boolean = false

    /** Phone songs still to look up. */
    fun checking(): Int = 0

    /** Saves [settings] and applies them (a new place moves what's cached). */
    suspend fun change(settings: SongCacheSettings)
}

/** What works without an account on an accounts-only host: what's needed to log in, and audio (speakers can't log in). */
// A TV becoming a party screen has no account (/api/screens); linking it needs someone in the room.
private val OPEN_WITHOUT_ACCOUNT = listOf("/api/host", "/api/auth/", "/api/account", "/api/admin/", "/api/audio/", "/api/screens")

fun isLoopback(call: ApplicationCall): Boolean {
    val address = call.request.origin.remoteAddress
    return address == "localhost" || address.startsWith("127.") || address == "::1" || address == "0:0:0:0:0:0:0:1"
}

/**
 * A YTMP host's HTTP side, the same on a server and a phone: the API (`/api`, its parts in
 * [roomRoutes], [catalogRoutes], [personalRoutes], [screenRoutes]), the room connection
 * (`/ws`) and the web app ([webApp], a folder; null: only the API). [extraApi]: more `/api`
 * routes (the account API on a server, linking an auth server on a phone).
 */
fun Application.ytmpModule(
    rooms: RoomManager,
    sources: HostSources,
    options: HostOptions,
    webApp: File? = null,
    extraApi: Route.() -> Unit = {},
) {
    val host = HostContext(rooms, sources, options)
    install(ContentNegotiation) { json(ProtocolJson) }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 30.seconds
    }
    install(CallLogging) {
        level = Level.INFO
        // Client reports are logged themselves. Of the web app: the party screen page (a TV
        // loading it), and anything that failed.
        filter { call ->
            val path = call.request.path()
            (path.startsWith("/api") && path != "/api/log") || path == "/tv" || ((call.response.status()?.value ?: 0) >= 400 && path != "/favicon.ico")
        }
        // Plain text: the log also goes to files.
        disableDefaultColors()
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(ErrorInfo(e.code, e.message ?: ""))) }
        exception<SourceException> { call, e ->
            log.warn("{} {}: {}", call.request.httpMethod.value, call.request.path(), e.message)
            call.respond(HttpStatusCode.BadGateway, ApiError(ErrorInfo(ErrorCode.INVALID, e.message ?: "Source error")))
        }
    }

    /** Accounts-only hosts: everything in /api but [OPEN_WITHOUT_ACCOUNT] needs an account. */
    val accountsOnly = createRouteScopedPlugin("AccountsOnly") {
        onCall { call ->
            val path = call.request.path()
            // Your YouTube Music from another host's page: a browser asks first (OPTIONS), then
            // comes with a token just for that (personalFor).
            val personalCall = path.startsWith("/api/me/") && (call.request.httpMethod == HttpMethod.Options || sources.personalFor?.invoke(call) != null)
            if (OPEN_WITHOUT_ACCOUNT.none { path == it || path.startsWith(it) } && !personalCall && with(host) { call.account() } == null) {
                throw ApiException(HttpStatusCode.Unauthorized, ErrorCode.PERMISSION_DENIED, "Log in to use this server")
            }
        }
    }

    routing {
        route("/api") {
            if (options.accountsOnly) install(accountsOnly)
            roomRoutes(host)
            catalogRoutes(host)
            personalRoutes(host)
            screenRoutes(host)
            // The song cache, in the hosting phone's settings (a server has its own, for its admin).
            songCacheRoutes("/cache", sources.cache, options.cacheSettings) { options.isLocal(it) }
            clientLogRoute()
            // Another YTMP host that takes over a zones player asks what this host's zones had before.
            get("/zones/handover") {
                val zoneHost = call.request.queryParameters["host"]
                val port = call.request.queryParameters["port"]?.toIntOrNull()
                val zones = sources.zones
                if (zones == null || zoneHost == null || port == null) return@get call.respond(HttpStatusCode.NotFound)
                call.respond(zones.handover(ZoneOutputs.Player(zoneHost, port)))
            }
            extraApi()
        }
        roomSocket(host)

        if (webApp != null && webApp.isDirectory) {
            webApp(webApp)
        } else {
            log.warn("No web app found at {}; only the API is served", webApp)
        }
    }
}

@Serializable
private data class ClientLogReport(val message: String, val room: String? = null)

private const val CLIENT_LOG_MAX_LENGTH = 500

private val clientLogLimit = PerMinuteLimit(perMinute = 20)

/**
 * Players in browsers report problems (a link that stopped working, long buffering) here, so
 * they end up in the host's log next to what the host saw.
 */
private fun Route.clientLogRoute() {
    post("/log") {
        val from = call.request.origin.remoteAddress
        val report = runCatching { call.receive<ClientLogReport>() }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Expected {\"message\": …}")
        if (clientLogLimit.allow(from)) {
            val text = report.message.replace(Regex("\\s+"), " ").take(CLIENT_LOG_MAX_LENGTH)
            log.info("client {}{}: {}", from, report.room?.take(12)?.let { " in $it" }.orEmpty(), text)
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/** At most [perMinute] requests per address a minute (client reports, TV codes), so a broken page can't flood the host. */
internal class PerMinuteLimit(private val perMinute: Int) {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    fun allow(address: String): Boolean {
        val minute = System.currentTimeMillis() / 60_000
        if (counts.size > 1000) counts.clear()
        val (at, count) = counts.compute(address) { _, old -> if (old == null || old.first != minute) minute to 1 else minute to old.second + 1 }!!
        return at == minute && count <= perMinute
    }
}

/** Proves you own a room without an account: the owner token from creating it. */
const val OWNER_TOKEN_HEADER = "X-Ytmp-Owner"

/** The token in `Authorization: Bearer …`, if any. */
fun ApplicationCall.bearerToken(): String? =
    request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.ifEmpty { null }

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
