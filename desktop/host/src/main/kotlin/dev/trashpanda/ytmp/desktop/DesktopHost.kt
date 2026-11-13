package dev.trashpanda.ytmp.desktop

import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.host.ApiException
import dev.trashpanda.ytmp.host.CacheSettings
import dev.trashpanda.ytmp.host.CachedSource
import dev.trashpanda.ytmp.host.HostOptions
import dev.trashpanda.ytmp.host.HostSources
import dev.trashpanda.ytmp.host.LocalMusic
import dev.trashpanda.ytmp.host.LyricsLookup
import dev.trashpanda.ytmp.host.RemoteSourceModule
import dev.trashpanda.ytmp.host.SongCache
import dev.trashpanda.ytmp.host.ytmpModule
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.SongCachePlace
import dev.trashpanda.ytmp.protocol.SongCacheSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * The desktop app's own host (docs/implementation/desktop-app.md#this-computers-host): the
 * shared host code for this computer, like the phone's host, without rooms (they're on your
 * server) and without accounts. Only on 127.0.0.1, and only with the key the app made for this
 * start: the app passes the window's requests (`ytmphost://`) on to it with that key.
 */
class DesktopHost(private val config: Config) {
    data class Config(
        val port: Int,
        val key: String,
        val dir: File,
        /** Where the window reaches this host, for links in what it answers (covers). */
        val windowBase: String,
        val moduleUrl: String,
        val moduleKey: String,
        val device: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient(ClientCIO) {
        install(ContentNegotiation) { json(ProtocolJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false
    }
    /** YouTube Music: the app's own module on this computer. */
    private val ytm = RemoteSourceModule(http, config.moduleUrl.trimEnd('/'), config.moduleKey)
    private val cached = CachedSource(ytm, RadioSource { seed -> ytm.radio(seed) }, ytm, ytm)
    val settings = DesktopSettings(File(config.dir, "desktop.json"))
    val library = ComputerLibrary(config.device, File(config.dir, "music-index.json"), config.windowBase.trimEnd('/'), scope)
    val local = LocalMusic()
    val cache = SongCache(File(config.dir, "song-cache"), settings.cacheBytes, lookup = ytm, audio = ytm, scope = scope)
    /** Songs the window plays now or next: the cache doesn't remove them. */
    @Volatile private var playing: Set<String> = emptySet()
    /** No rooms here, but the shared host API is built around a room manager. */
    private val rooms = RoomManager(local.streams(cache.streams), scope)

    private val cacheControls = object : CacheSettings {
        override fun places() = listOf(SongCachePlace(PLACE, "This computer", cache.dir.let { it.mkdirs(); it.usableSpace }))

        override fun place() = PLACE

        override suspend fun change(settings: SongCacheSettings) {
            this@DesktopHost.settings.update { it.copy(cacheBytes = settings.maxBytes.coerceAtLeast(0)) }
            cache.setMaxBytes(settings.maxBytes.coerceAtLeast(0))
        }
    }

    fun applyMusic() {
        val s = settings.current
        library.setFolders(s.folders.map(::File))
        if (s.music && s.folders.isNotEmpty()) local.add(library) else local.remove(library.id)
    }

    fun start() {
        cache.inUse = { playing }
        applyMusic()
        embeddedServer(CIO, port = config.port, host = "127.0.0.1") { module() }.start(wait = true)
    }

    fun Application.module() {
        // Only the app (with this start's key); the health check is for the app to see it's up.
        intercept(ApplicationCallPipeline.Plugins) {
            if (call.request.path() == "/api/health") return@intercept
            val given = call.request.header(HttpHeaders.Authorization).orEmpty().toByteArray()
            if (!MessageDigest.isEqual(given, "Bearer ${config.key}".toByteArray())) {
                call.respond(HttpStatusCode.Unauthorized)
                finish()
            }
        }
        ytmpModule(
            rooms,
            HostSources(
                search = cached,
                suggestions = cached,
                similar = local.radio(cached),
                catalog = local.catalog(cached),
                recognizer = null,
                lyrics = LyricsLookup({ song -> if (song.id.startsWith("ytm:")) ytm.lyrics(song.id) else null }),
                audio = local.audio(cache.audio(ytm)),
                cache = cache,
                local = local,
            ),
            HostOptions(
                kind = HostKind.COMPUTER,
                // Reachable only from this computer, with the key: every request is the owner's.
                isLocal = { true },
                roomCreateDenied = { "Rooms are on your YTMP server" },
                cacheSettings = cacheControls,
            ),
            extraApi = { desktopRoutes() },
        )
    }

    private fun Route.desktopRoutes() {
        get("/health") { call.respondText("ok") }
        route("/desktop") {
            get("/music") { call.respond(musicStatus()) }
            put("/music") {
                val change = call.receive<MusicChange>()
                settings.update { s -> s.copy(music = change.music ?: s.music, folders = change.folders?.map { File(it).absolutePath }?.distinct() ?: s.folders) }
                applyMusic()
                call.respond(musicStatus())
            }
            post("/music/rescan") {
                library.rescan(wait = true)
                call.respond(musicStatus())
            }
            // The window plays [song] (it counts as a play, like a room's) or has it next.
            post("/started") {
                val song = call.receive<Song>()
                playing = (playing + song.id).toList().takeLast(KEPT_PLAYING).toSet()
                cache.started(song)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/known") {
                val song = call.receive<Song>()
                playing = (playing + song.id).toList().takeLast(KEPT_PLAYING).toSet()
                cache.know(song)
                call.respond(HttpStatusCode.NoContent)
            }
            // The server asked for one of this computer's songs, for another player in a room.
            post("/upload") {
                val upload = call.receive<Upload>()
                val file = library.fileOf(upload.songId) ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "That song isn't on this computer")
                send(file, upload)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    private suspend fun musicStatus(): MusicStatus {
        val s = settings.current
        return MusicStatus(
            music = s.music,
            folders = s.folders.map { MusicFolderInfo(it, File(it).name.ifEmpty { it }) },
            songs = if (s.music) library.tracks().size else 0,
            scanning = library.scanning,
            libraryId = library.id,
        )
    }

    private suspend fun send(file: File, upload: Upload) = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val request = HttpRequest.newBuilder(URI(upload.url))
            .timeout(Duration.ofMinutes(10))
            .header("X-Device-Secret", upload.secret)
            .header("Content-Type", ComputerLibrary.TYPES[file.extension.lowercase()] ?: "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofFile(file.toPath()))
            .build()
        val response = uploads.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) throw ApiException(HttpStatusCode.BadGateway, ErrorCode.INVALID, "The server answered ${response.statusCode()}")
        log.info("Sent {} to the server ({} KB in {} ms)", upload.songId, file.length() / 1024, (System.nanoTime() - started) / 1_000_000)
    }

    @Serializable
    data class MusicChange(val music: Boolean? = null, val folders: List<String>? = null)

    @Serializable
    data class MusicFolderInfo(val path: String, val name: String)

    @Serializable
    data class MusicStatus(val music: Boolean, val folders: List<MusicFolderInfo>, val songs: Int, val scanning: Boolean, val libraryId: String)

    @Serializable
    data class Upload(val songId: String, val url: String, val secret: String)

    companion object {
        private val log = LoggerFactory.getLogger(DesktopHost::class.java)
        private val uploads = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
        private const val PLACE = "computer"
        private const val KEPT_PLAYING = 3
    }
}

/** What's kept for this computer (`desktop.json`): the cache's size, and the music on it. Both off at first. */
class DesktopSettings(private val file: File) {
    @Serializable
    data class Values(val cacheBytes: Long = 0, val music: Boolean = false, val folders: List<String> = emptyList())

    @Volatile var current: Values = runCatching { json.decodeFromString<Values>(file.readText()) }.getOrDefault(Values())
        private set

    val cacheBytes get() = current.cacheBytes

    @Synchronized
    fun update(change: (Values) -> Values) {
        current = change(current)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Values.serializer(), current))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

fun main() {
    val env = System.getenv()
    fun need(name: String) = env[name]?.takeIf { it.isNotEmpty() } ?: error("$name is missing")
    val config = DesktopHost.Config(
        port = need("YTMP_HOST_PORT").toInt(),
        key = need("YTMP_HOST_KEY"),
        dir = File(need("YTMP_HOST_DIR")),
        windowBase = need("YTMP_HOST_WINDOW_BASE"),
        moduleUrl = need("YTMP_MODULE_URL"),
        moduleKey = need("YTMP_MODULE_KEY"),
        device = need("YTMP_DEVICE"),
    )
    DesktopHost(config).start()
}
