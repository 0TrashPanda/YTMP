package dev.trashpanda.ytmp.host

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import android.os.Build
import android.net.ConnectivityManager
import dev.trashpanda.ytmp.Log
import dev.trashpanda.ytmp.OnDeviceYtm
import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.core.FinishedPlay
import dev.trashpanda.ytmp.core.RoomHooks
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.RoomRadio
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.core.personalized
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.ShareAddress
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ScreenUnlinkRequest
import dev.trashpanda.ytmp.protocol.YoutubeHistory
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.Inet4Address

/**
 * The host that runs inside the app: rooms, the room WebSocket and the web app, on
 * [PORT]. The app's own WebView uses it at [LOCAL_URL]; friends on the same Wi-Fi reach it at
 * [lanUrl]. Only the phone itself can create rooms or join its solo rooms.
 */
class LocalHost(private val context: Context) {
    val ytm by lazy { OnDeviceYtm(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Chromecasts on the network; they fetch the audio from this phone. */
    val casts = CastOutputs(scope) { localAddress -> "http://${localAddress.hostAddress}:$PORT" }
    val castFinder = CastFinder(context, casts)

    /**
     * The party screen of a room on a server, on a TV here that the server can't reach. When it
     * ends, the room hears so (best effort: else the screen just stays offline there).
     */
    val castScreens = CastScreens(scope, casts) { screen ->
        val (url, request) = screen.unlink() ?: return@CastScreens
        withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(ProtocolJson.encodeToString(ScreenUnlinkRequest.serializer(), request).toByteArray()) }
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Sonos speakers on the network; they fetch the audio from this phone too. */
    val sonos = SonosOutputs(scope) { localAddress -> "http://${localAddress.hostAddress}:$PORT" }
    val sonosFinder = SonosFinder(context, sonos)
    /** MPDs on the network (e.g. a Raspberry Pi with a speaker); they fetch the audio from this phone too. */
    val mpd = MpdOutputs(scope) { localAddress -> "http://${localAddress.hostAddress}:$PORT" }
    val mpdFinder = MpdFinder(context, mpd)
    /** Amplifier zones the house describes in a file on its network (fed by one of those MPDs). */
    /** Local music (the Local tab): what a Raspberry Pi shares, and the music on this phone. */
    val local = LocalMusic()
    private val phoneLibrary = PhoneLibrary(context) { lanUrl() ?: "http://127.0.0.1:$PORT" }
    private val localSettings = context.getSharedPreferences("ytmp.local", Context.MODE_PRIVATE)

    /** The music on this phone shows in the Local tab (Settings → Local music), and Android lets us read it. */
    val phoneMusicOn: Boolean get() = localSettings.getBoolean(PHONE_MUSIC, false) && canReadMusic()

    fun canReadMusic() = context.checkSelfPermission(MUSIC_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun setPhoneMusic(on: Boolean) {
        localSettings.edit { putBoolean(PHONE_MUSIC, on) }
        if (phoneMusicOn) local.add(phoneLibrary) else local.remove(PhoneLibrary.ID)
    }
    val zones = ZoneOutputs(scope, { localAddress -> "http://${localAddress.hostAddress}:$PORT" }, mpd, local)
    val zoneFinder = ZoneFinder(context, zones)
    /** On another Wi-Fi network, the devices found on the old one go from the list. */
    private val wifi = WifiWatch(context) {
        Log.i(TAG, "On another network: looking for speakers and TVs again")
        castFinder.forgetAll()
        sonosFinder.forgetAll()
        mpdFinder.forgetAll()
        zoneFinder.forgetAll()
    }
    /** Speakers here that play a room on another host (a server), which can't reach them. */
    /** Every kind of speaker and TV on the network. */
    val outputs = OutputDrivers(scope, listOf(casts, sonos, mpd, zones))
    val nearby = NearbySpeakers(scope, outputs.all)
    val authLink = AuthLink(context, PORT)
    /** The phone owner's YouTube Music account. */
    val youtube = PhoneYoutubeAccount(context) { ytm }

    /** YTM lookups, remembered for a while. Lambdas, so Python still starts on first use. */
    private val cached = CachedSource(
        search = { query, type -> ytm.search(query, type) },
        radio = { ytm.radio(it) },
        catalog = object : CatalogSource {
            override suspend fun artist(id: String) = ytm.artist(id)
            override suspend fun album(id: String) = ytm.album(id)
            override suspend fun playlist(id: String) = ytm.playlist(id)
            override suspend fun podcast(id: String) = ytm.podcast(id)
            override suspend fun home() = ytm.home()
        },
        suggest = { ytm.suggestions(it) },
    )
    private val plays = PlayReporter(authLink.auth, scope)
    val rooms: RoomManager = RoomManager(
        streams = local.streams(object : StreamResolver {
            override suspend fun resolveStream(songId: String) = ytm.resolveStream(songId)
            override suspend fun resolve(songId: String) = ytm.resolve(songId)
        }),
        scope = scope, outputs = outputs.devices, store = PhoneRoomStore(context),
        radio = RoomRadio(
            local.radio(cached),
            // Every room here is the phone owner's, so radio goes through their YouTube Music.
            owner = { _, seed -> if (LocalMusic.isLocal(seed)) null else youtube.personalized()?.radio(seed) },
            // The song graph of the owner's server: their listening, and everyone's there.
            graph = { owner, seed ->
                (owner ?: authLink.ownerAccount)?.let(sync::tokenFor)?.let { authLink.auth.similar(it, seed) }.orEmpty().filter { local.playable(it.id) }
            },
        ),
        hooks = RoomHooks(
            onPlayFinished = { play ->
                plays.report(play)
                addToYoutubeHistory(play)
            },
            onRoomSaved = { sync.saved(it) },
            onRoomClosed = { sync.closed(it) },
            onRoomDisowned = { account, syncId -> sync.closed(account, syncId) },
        ),
        // Where you are in podcast episodes, on your server: resume them in any room or device.
        bookmarks = AccountBookmarks(authLink.auth, scope),
    )

    /** Your rooms here and on your server (docs/features/room-sync.md). */
    private val sync: RoomSync by lazy { RoomSync(context, authLink, { rooms }, ::lanUrl, scope) }

    /**
     * Songs played on this phone go into the owner's YouTube Music history, so its suggestions
     * learn from them (see [YoutubeHistory]). Like YouTube, a play counts after 30 seconds.
     */
    private fun addToYoutubeHistory(play: FinishedPlay) {
        if (play.heardMs < HISTORY_MIN_MS && play.skipped) return
        scope.launch {
            val wanted = when (youtube.historySetting()) {
                YoutubeHistory.OFF -> false
                YoutubeHistory.SOLO -> play.visibility == RoomVisibility.PRIVATE
                YoutubeHistory.ALL -> true
            }
            // Only YouTube Music songs go into YouTube Music's history (not local music).
            if (!wanted || youtube.account() == null || !play.item.song.id.startsWith("ytm:")) return@launch
            try {
                youtube.addToHistory(play.item.song.id)
                Log.d(TAG, "Added ${play.item.song.id} to the YouTube Music history")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Couldn't add ${play.item.song.id} to the YouTube Music history: ${e.message}")
            }
        }
    }

    fun start() {
        val webApp = installWebApp()
        rooms.startCleanup()
        sync.start()
        outputs.attach(rooms)
        if (phoneMusicOn) local.add(phoneLibrary)
        wifi.start()
        embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            ytmpModule(
                rooms,
                HostSources(
                    search = cached,
                    suggestions = cached,
                    similar = local.radio(cached),
                    catalog = local.catalog(cached),
                    lyrics = LyricsLookup({ song -> if (song.id.startsWith("ytm:")) ytm.lyrics(song.id) else null }),
                    audio = local.audio(OnDeviceAudioProxy { songId, fresh -> ytm.stream(songId, fresh) }),
                    local = local,
                    zones = zones,
                    personal = youtube,
                    // Find similar and Related: the song graph of the asker's server, with their login.
                    songGraph = { call, seed ->
                        call.bearerToken()?.let { authLink.auth.similar(it, seed) }.orEmpty().filter { local.playable(it.id) }
                    },
                ),
                HostOptions(
                    kind = HostKind.PHONE,
                    shareUrl = ::lanUrl,
                    shareAddresses = ::lanAddresses,
                    localOnlyRoomManagement = true,
                    supportsPrivateRooms = true,
                    auth = authLink.auth,
                    onAccountToken = sync::remember,
                    onLocalAccount = { authLink.usedBy(it.id) },
                    deviceOwner = { authLink.ownerAccount },
                ),
                webApp = webApp,
                extraApi = {
                    authLink.routes(this)
                    sync.routes(this)
                    sync.cloneRoute(this)
                },
            )
        }.start(wait = false)
        Log.i(TAG, "Hosting on port $PORT")
    }

    /** The address friends on the same network use, e.g. `http://192.168.1.23:8765`, or null when offline. */
    fun lanUrl(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val address = connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: return null
        return "http://${address.hostAddress}:$PORT"
    }

    /**
     * Every address friends may reach this phone at: its Wi-Fi, its own hotspot, a VPN, … (not
     * mobile data: nobody reaches a phone there). The one of the active network ([lanUrl]) first.
     */
    fun lanAddresses(): List<ShareAddress> {
        val active = lanUrl()
        val all = runCatching { java.net.NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filterNot { name -> MOBILE_DATA.any { name.name.startsWith(it) } }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { ShareAddress("http://${it.hostAddress}:$PORT", label(nif.name)) }
            }
            .distinctBy { it.url }
        return all.sortedByDescending { it.url == active }
    }

    private fun label(interfaceName: String): String = when {
        HOTSPOT.any { interfaceName.startsWith(it) } -> "Hotspot"
        interfaceName.startsWith("wlan") -> "Wi-Fi"
        interfaceName.startsWith("tun") || interfaceName.startsWith("ppp") || interfaceName.startsWith("wg") -> "VPN"
        interfaceName.startsWith("eth") -> "Cable"
        interfaceName.startsWith("rndis") || interfaceName.startsWith("usb") -> "USB"
        else -> interfaceName
    }

    /** Copies the bundled web app out of the APK (once per app version), so it can be served as files. */
    private fun installWebApp(): File {
        val dir = File(context.filesDir, "web")
        @Suppress("DEPRECATION") // The replacement needs API 33; this works everywhere.
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val marker = File(dir, ".version")
        if (marker.takeIf { it.exists() }?.readText() == version.toString()) return dir

        dir.deleteRecursively()
        copyAssets("web", dir)
        marker.writeText(version.toString())
        return dir
    }

    private fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            for (child in children) copyAssets("$path/$child", File(target, child))
        }
    }

    /** "Stop hosting": public rooms end, solo rooms pause (they stay until closed). */
    fun stopHosting() {
        for (room in rooms.all()) {
            if (room.visibility == RoomVisibility.PUBLIC) rooms.close(room.code) else scope.launch { room.pause() }
        }
    }

    companion object {
        const val PORT = 8765
        private const val PHONE_MUSIC = "phoneMusic"
        /** Reading the music on the phone: its own permission since Android 13. */
        val MUSIC_PERMISSION = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        /** Mobile data: friends can't reach the phone there. */
        private val MOBILE_DATA = listOf("rmnet", "ccmni", "v4-rmnet", "pdp", "seth")
        /** The phone's own hotspot (differs per maker). */
        private val HOTSPOT = listOf("swlan", "ap", "softap", "wlan1")
        const val LOCAL_URL = "http://127.0.0.1:$PORT"
        private const val TAG = "YtmpHost"

        /** A skipped song counts as played after this long, like on YouTube. */
        private const val HISTORY_MIN_MS = 30_000L
    }
}
