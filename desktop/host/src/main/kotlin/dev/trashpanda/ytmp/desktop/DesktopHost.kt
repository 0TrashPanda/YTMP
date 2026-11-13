package dev.trashpanda.ytmp.desktop

import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.host.ApiException
import dev.trashpanda.ytmp.host.CacheSettings
import dev.trashpanda.ytmp.host.CachedSource
import dev.trashpanda.ytmp.host.CastOutputs
import dev.trashpanda.ytmp.host.FollowerSource
import dev.trashpanda.ytmp.host.MpdOutputs
import dev.trashpanda.ytmp.host.NearbyPlaying
import dev.trashpanda.ytmp.host.NearbySpeakers
import dev.trashpanda.ytmp.host.OutputDrivers
import dev.trashpanda.ytmp.host.RoomLink
import dev.trashpanda.ytmp.host.SonosOutputs
import dev.trashpanda.ytmp.host.YoutubeTvOutputs
import dev.trashpanda.ytmp.host.ZoneOutputs
import dev.trashpanda.ytmp.host.discover
import dev.trashpanda.ytmp.host.HostOptions
import dev.trashpanda.ytmp.host.HostSources
import dev.trashpanda.ytmp.host.LocalMusic
import dev.trashpanda.ytmp.host.LyricsLookup
import dev.trashpanda.ytmp.host.RemoteSourceModule
import dev.trashpanda.ytmp.host.SongCache
import dev.trashpanda.ytmp.host.ytmpModule
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.OutputKind
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
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
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
    /** Songs' audio: this computer's music, the song cache, else YouTube through the module. */
    private val audio = local.audio(cache.audio(ytm))

    // Speakers and TVs on this computer's network, which your server can't reach: the room you're
    // in plays there from this computer (NearbySpeakers, like the phone's). They fetch the audio
    // from this computer on the network (lanServer).
    private val audioBase = { localAddress: InetAddress -> "http://${localAddress.hostAddress}:${lanServer()}" }
    private val casts = CastOutputs(scope, audioBaseUrl = audioBase)
    private val sonos = SonosOutputs(scope, audioBase)
    private val mpd = MpdOutputs(scope, audioBase)
    private val zones = ZoneOutputs(scope, audioBase, mpd, local)
    private val tvs = YoutubeTvOutputs(scope)
    val outputs = OutputDrivers(scope, listOf(casts, sonos, mpd, zones, tvs))
    val nearby = NearbySpeakers(scope, outputs.all)
    /** Problems with the speakers (another app took one), numbered, for the window to show once. */
    private val notices = ArrayDeque<NearbyNotice>()
    private var noticeCount = 0
    @Volatile private var lanPort = 0

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
        scope.launch {
            nearby.notices.collect { text ->
                synchronized(notices) {
                    notices.addLast(NearbyNotice(++noticeCount, text))
                    while (notices.size > KEPT_NOTICES) notices.removeFirst()
                }
            }
        }
        // Always looking, so Play on lists them at once; a moment after the start, which matters more.
        scope.launch {
            delay(DISCOVERY_DELAY_MS)
            runCatching {
                casts.discover()
                sonos.discover()
                mpd.discover()
                zones.discover()
                tvs.discover()
            }.onFailure { log.warn("Looking for speakers failed: {}", it.toString()) }
        }
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

    /**
     * This computer on its network, for the speakers only: the audio of the songs in the room
     * they play, nothing else. Started when a speaker first needs it (Windows asks once whether
     * YTMP may use the network then); the port stays the same, so that answer keeps working.
     */
    @Synchronized
    private fun lanServer(): Int {
        if (lanPort != 0) return lanPort
        val saved = settings.current.lanPort
        val port = saved.takeIf { it in 1024..65535 && runCatching { ServerSocket(it).close() }.isSuccess } ?: ServerSocket(0).use { it.localPort }
        embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/api/audio/{songId}") {
                    val songId = call.parameters["songId"]!!
                    if (!nearby.plays(songId)) return@get call.respond(HttpStatusCode.NotFound)
                    audio.respond(call, songId, call.request.header(HttpHeaders.Range))
                }
            }
        }.start(wait = false)
        if (port != saved) settings.update { it.copy(lanPort = port) }
        log.info("Speakers get the audio from port {}", port)
        lanPort = port
        return port
    }

    private fun nearbyState(after: Int): NearbyState {
        val devices = outputs.devices.value.map { NearbyDevice(it.id, it.name, it.kind) }
        return NearbyState(devices, nearby.playing.value, synchronized(notices) { notices.filter { it.id > after } }, noticeCount)
    }

    private fun Route.desktopRoutes() {
        get("/health") { call.respondText("ok") }
        route("/desktop") {
            // Play on: the speakers here and what plays on them (`?after=` the last notice seen).
            get("/nearby") { call.respond(nearbyState(call.request.queryParameters["after"]?.toIntOrNull() ?: Int.MAX_VALUE)) }
            post("/nearby/play") {
                val play = call.receive<NearbyPlay>()
                val device = outputs.devices.value.firstOrNull { it.id == play.deviceId }
                    ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "That speaker isn't on this network now")
                val link = RoomLink(play.hostUrl, play.code, play.guestToken)
                nearby.turnOn(play.code, device.id, device.name) { FollowerSource(link) }
                call.respond(nearbyState(Int.MAX_VALUE))
            }
            post("/nearby/stop") {
                nearby.turnOff(call.receive<NearbyStop>().deviceId)
                call.respond(nearbyState(Int.MAX_VALUE))
            }
            post("/nearby/volume") {
                val change = call.receive<NearbyVolume>()
                nearby.setVolume(change.deviceId, change.volume)
                call.respond(nearbyState(Int.MAX_VALUE))
            }
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

    @Serializable
    data class NearbyDevice(val id: String, val name: String, val kind: OutputKind)

    @Serializable
    data class NearbyNotice(val id: Int, val text: String)

    /** Like the phone app's bridge `nearby` (shown: its party screens, none here), with the notices after the one asked for. */
    @Serializable
    data class NearbyState(
        val devices: List<NearbyDevice>,
        val playing: NearbyPlaying?,
        val notices: List<NearbyNotice>,
        val lastNotice: Int,
        val shown: List<String> = emptyList(),
    )

    @Serializable
    data class NearbyPlay(val deviceId: String, val hostUrl: String, val code: String, val guestToken: String)

    @Serializable
    data class NearbyStop(val deviceId: String)

    @Serializable
    data class NearbyVolume(val deviceId: String, val volume: Double)

    companion object {
        private val log = LoggerFactory.getLogger(DesktopHost::class.java)
        private val uploads = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
        private const val PLACE = "computer"
        private const val KEPT_PLAYING = 3
        private const val KEPT_NOTICES = 20
        private const val DISCOVERY_DELAY_MS = 3_000L
    }
}

/** What's kept for this computer (`desktop.json`): the cache's size, and the music on it. Both off at first. */
class DesktopSettings(private val file: File) {
    @Serializable
    data class Values(
        val cacheBytes: Long = 0,
        val music: Boolean = false,
        val folders: List<String> = emptyList(),
        /** The port speakers get the audio from (DesktopHost.lanServer), kept so the firewall's answer keeps working. */
        val lanPort: Int = 0,
    )

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
