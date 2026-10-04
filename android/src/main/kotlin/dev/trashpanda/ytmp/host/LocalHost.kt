package dev.trashpanda.ytmp.host

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.util.Log
import dev.trashpanda.ytmp.OnDeviceYtm
import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.core.FinishedPlay
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.YoutubeHistory
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
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

    /** Sonos speakers on the network; they fetch the audio from this phone too. */
    val sonos = SonosOutputs(scope) { localAddress -> "http://${localAddress.hostAddress}:$PORT" }
    val sonosFinder = SonosFinder(context, sonos)
    private val outputs = combine(casts.devices, sonos.devices) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptyList())
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
    val rooms = RoomManager(
        streams = object : StreamResolver {
            override suspend fun resolveStream(songId: String) = ytm.resolveStream(songId)
            override suspend fun resolve(songId: String) = ytm.resolve(songId)
        },
        scope = scope, outputs = outputs, store = PhoneRoomStore(context),
        onPlayFinished = { play ->
            plays.report(play)
            addToYoutubeHistory(play)
        },
        radio = cached,
    )

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
            if (!wanted || youtube.account() == null) return@launch
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
        casts.attach(rooms)
        sonos.attach(rooms)
        embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            ytmpModule(
                rooms,
                search = cached,
                audio = OnDeviceAudioProxy { songId, fresh -> ytm.stream(songId, fresh) },
                webApp = webApp,
                options = HostOptions(
                    kind = HostKind.PHONE,
                    shareUrl = ::lanUrl,
                    localOnlyRoomManagement = true,
                    supportsPrivateRooms = true,
                    auth = authLink.auth,
                ),
                extraApi = { authLink.routes(this) },
                similar = cached,
                catalog = cached,
                personal = youtube,
                suggestions = cached,
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

    /** Copies the bundled web app out of the APK (once per app version), so it can be served as files. */
    private fun installWebApp(): File {
        val dir = File(context.filesDir, "web")
        val version = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime
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
        const val LOCAL_URL = "http://127.0.0.1:$PORT"
        private const val TAG = "YtmpHost"

        /** A skipped song counts as played after this long, like on YouTube. */
        private const val HISTORY_MIN_MS = 30_000L
    }
}
