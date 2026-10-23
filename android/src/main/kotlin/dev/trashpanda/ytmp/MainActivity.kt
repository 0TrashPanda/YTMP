package dev.trashpanda.ytmp

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import dev.trashpanda.ytmp.host.LocalHost
import dev.trashpanda.ytmp.host.MusicFolder
import dev.trashpanda.ytmp.host.createFor
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.host.NearbyRoom
import dev.trashpanda.ytmp.host.ShownScreen
import dev.trashpanda.ytmp.host.NearbyPlaying
import dev.trashpanda.ytmp.protocol.OutputKind
import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.host.SourceException
import dev.trashpanda.ytmp.protocol.YoutubeAccount
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Shows the YTMP web app from the configured server in a WebView. The web app talks to
 * the room; this activity only adds native playback through the `YtmpNative` JS bridge.
 */
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var controller: ListenableFuture<MediaController>? = null
    private var askedForNotifications = false
    private var nearbyUpdates: Job? = null
    private var castUpdates: Job? = null
    private val app get() = application as YtmpApp

    private val prefs by lazy { getSharedPreferences("ytmp", MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Reading the music on the phone, for the Local tab: the answer goes to the page as onPhoneMusic. */
    private val musicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.host.setPhoneMusic(granted)
        phoneMusicAnswer(granted, if (granted) null else "YTMP may not read the music on this phone. You can allow Music and audio in the app's settings (Android settings → Apps → YTMP).")
    }

    /** Picking a folder of music (Settings → Local music); the page hears the new list as onMusicFolders. */
    private val musicFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            app.host.musicFolders.add(tree)
            app.host.setPhoneMusic(true)
        }
        musicFoldersAnswer()
    }

    private fun musicFoldersAnswer() =
        js("window.__ytmpNative && window.__ytmpNative.onMusicFolders && window.__ytmpNative.onMusicFolders(${app.host.musicFolders.all().let { Json.encodeToString(ListSerializer(MusicFolder.serializer()), it) }})")

    private fun phoneMusicAnswer(on: Boolean, error: String?) =
        js("window.__ytmpNative && window.__ytmpNative.onPhoneMusic && window.__ytmpNative.onPhoneMusic($on, ${error?.let(JSONObject::quote) ?: "null"})")

    /** Back from [YoutubeLoginActivity]: sign the host in, then tell the page (null = signed in, else why not). */
    private val youtubeLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cookie = result.data?.getStringExtra(YoutubeLoginActivity.EXTRA_COOKIE)
        lifecycleScope.launch {
            val error = when {
                result.resultCode != RESULT_OK || cookie == null -> "Not signed in"
                else -> try {
                    app.host.youtube.signIn(cookie)
                    null
                } catch (e: SourceException) {
                    e.message ?: "Signing in didn't work"
                }
            }
            js("window.__ytmpNative && window.__ytmpNative.onYoutubeSignIn && window.__ytmpNative.onYoutubeSignIn(${error?.let(JSONObject::quote) ?: "null"})")
        }
    }

    /**
     * Back from [YoutubeLoginActivity] for a server's page (bridge `youtubeCookie`): the page
     * gets the cookies and stores them in your account there.
     */
    private val youtubeCookieLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cookie = result.data?.getStringExtra(YoutubeLoginActivity.EXTRA_COOKIE)?.takeIf { result.resultCode == RESULT_OK }
        val answer = if (cookie != null) "${JSONObject.quote(cookie)}, null" else "null, ${JSONObject.quote("Not signed in")}"
        js("window.__ytmpNative && window.__ytmpNative.onYoutubeCookie && window.__ytmpNative.onYoutubeCookie($answer)")
    }

    /** Serves the bundled setup page from assets on a fake https origin. */
    private val assets by lazy {
        WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
    }

    /** The page shown now (the bridge runs off the main thread, where the WebView can't be asked). */
    @Volatile
    private var currentUrl: Uri? = null

    private var server: String?
        get() = prefs.getString("server", null)
        set(value) = prefs.edit().putString("server", value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Debug builds can be inspected from chrome://inspect.
        WebView.setWebContentsDebuggingEnabled(debuggable)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Keep the page (and its room connection) alive while the app is in the background.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            addJavascriptInterface(Bridge(), "YtmpNative")
            webViewClient = Client()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    val line = "${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                    // The page's warnings and errors go to the log files too.
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) Log.i("YtmpWeb", line)
                    else Log.d("YtmpWeb", line)
                    return true
                }
            }
        }
        // A WebView ignores its own padding, so keep it clear of the system bars with a container.
        val root = FrameLayout(this).apply { addView(webView) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        PlaybackHub.commandSink = commandSink
        PlaybackHub.statusSink = statusSink
        // The sleep timer changed (also when it went off in the player): the page shows it.
        lifecycleScope.launch {
            PlaybackHub.sleep.collect { setting ->
                val value = setting?.let { JSONObject.quote(json.encodeToString(SleepSetting.serializer(), it)) } ?: "null"
                js("window.__ytmpNative && window.__ytmpNative.onSleepTimer && window.__ytmpNative.onSleepTimer($value)")
            }
        }

        // For development: `adb shell am start -n dev.trashpanda.ytmp/.MainActivity --es server http://10.0.2.2:8080`
        intent.getStringExtra("server")?.let { server = normalize(it) }
        val link = roomLink(intent)
        when {
            savedInstanceState != null -> webView.restoreState(savedInstanceState)
            intent.hasExtra("server") -> openServer()
            link != null && isAppPage(link) -> webView.loadUrl(link.toString())
            else -> {
                openHome()
                link?.let(::openLink)
            }
        }

        // A new version: asked once per app start (not when the screen is only recreated).
        if (!app.checkedForUpdate) {
            app.checkedForUpdate = true
            Updates.checkOnStart(this, lifecycleScope)
        }

        // The page decides first (closing a sheet, going back a page in a room, see
        // `onBack` in frontend/src/lib/native.ts); a page without an answer gets the WebView's history.
        onBackPressedDispatcher.addCallback(this) {
            webView.evaluateJavascript("window.__ytmpNative && window.__ytmpNative.onBack ? window.__ytmpNative.onBack() : null") { answer ->
                when (answer) {
                    "\"handled\"" -> Unit
                    // Like YTM at its home page: the app goes to the background and keeps playing.
                    "\"exit\"" -> moveTaskToBack(true)
                    else -> if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        roomLink(intent)?.let(::openLink)
    }

    /** A room link the browser handed over (`ytmp://open?url=…`, see frontend/src/lib/openInApp.ts), or null. */
    private fun roomLink(intent: Intent?): Uri? {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
        if (data.scheme != "ytmp" || data.host != "open") return null
        val url = data.getQueryParameter("url")?.let(Uri::parse) ?: return null
        return url.takeIf { it.scheme == "http" || it.scheme == "https" }
    }

    /**
     * Shows a room link: in the app when it's one of the app's pages (a phone on this network,
     * your server); else back in the browser, which then stays with it (`web=1`).
     */
    private fun openLink(url: Uri) {
        if (isAppPage(url)) webView.loadUrl(url.toString())
        else startActivity(Intent(Intent.ACTION_VIEW, url.buildUpon().appendQueryParameter("web", "1").build()))
    }

    override fun onStart() {
        super.onStart()
        app.visible.value = true
        app.nearby.startDiscovery()
        nearbyUpdates = lifecycleScope.launch {
            app.nearby.rooms.collect { rooms ->
                val list = json.encodeToString(ListSerializer(NearbyRoom.serializer()), rooms)
                js("window.__ytmpNative && window.__ytmpNative.onNearbyRooms && window.__ytmpNative.onNearbyRooms($list)")
            }
        }
        castUpdates = lifecycleScope.launch {
            launch {
                castState().collect { state ->
                    js("window.__ytmpNative && window.__ytmpNative.onCastScreens && window.__ytmpNative.onCastScreens(${json.encodeToString(CastScreenState.serializer(), state)})")
                }
            }
            launch {
                nearbyState().collect { state ->
                    js("window.__ytmpNative && window.__ytmpNative.onNearby && window.__ytmpNative.onNearby(${json.encodeToString(NearbyState.serializer(), state)})")
                }
            }
            app.host.nearby.notices.collect { text ->
                js("window.__ytmpNative && window.__ytmpNative.onNearbyNotice && window.__ytmpNative.onNearbyNotice(${JSONObject.quote(text)})")
            }
        }
        // Binding a controller starts the playback service, so it can go to the foreground when playing.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        app.visible.value = false
        nearbyUpdates?.cancel()
        castUpdates?.cancel()
        app.nearby.stopDiscovery()
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // This screen's own hooks: when Android replaces the screen, the old one's onDestroy can
    // come after the new one's onCreate, and must not unhook the new one.
    private val commandSink: (String) -> Unit = { command -> js("window.__ytmpNative && window.__ytmpNative.onCommand && window.__ytmpNative.onCommand($command)") }
    private val statusSink: (String) -> Unit = { status -> js("window.__ytmpNative && window.__ytmpNative.onStatus && window.__ytmpNative.onStatus(${JSONObject.quote(status)})") }

    override fun onDestroy() {
        if (PlaybackHub.commandSink === commandSink) PlaybackHub.commandSink = null
        if (PlaybackHub.statusSink === statusSink) PlaybackHub.statusSink = null
        if (isFinishing) PlaybackHub.target.value = null
        webView.destroy()
        super.onDestroy()
    }

    /** The app's home: the web app served by this phone's own host. */
    private fun openHome() = webView.loadUrl(LocalHost.LOCAL_URL)

    private fun openServer(error: String? = null) {
        val url = server
        if (url == null || error != null) {
            val query = buildString {
                if (url != null) append("url=").append(Uri.encode(url))
                if (error != null) append("&error=").append(Uri.encode(error))
            }
            webView.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/setup.html?$query")
        } else {
            webView.loadUrl(url)
        }
    }

    private fun js(code: String) = webView.post { webView.evaluateJavascript(code, null) }

    /** The TVs this phone found, and the party screens it shows on them (bridge `castScreens`). */
    private fun castState() = combine(app.host.casts.devices, app.host.castScreens.shown, ::castState)

    private fun castState(devices: List<OutputDevice>, shown: List<ShownScreen>) =
        CastScreenState(devices.map { CastScreenDevice(it.id, it.name) }, shown)

    /** The speakers and TVs this phone found, the party screens it shows, and what plays on them (bridge `nearby`). */
    private fun nearbyState() = combine(app.host.outputs.devices, app.host.castScreens.shown, app.host.nearby.playing, NearbyState::of)

    private fun nearbyDevices(): List<OutputDevice> = app.host.outputs.devices.value

    /** [url] is on the site of the page shown now (one of the app's pages): only that page may use it. */
    private fun fromThisPage(url: String): Boolean {
        val from = currentUrl ?: return false
        val page = Uri.parse(url)
        return isAppPage(from) && page.scheme == from.scheme && page.host == from.host && page.port == from.port
    }

    private fun isServerUrl(uri: Uri): Boolean {
        val server = server?.let(Uri::parse) ?: return false
        return uri.host == server.host && uri.port == server.port
    }

    private fun isLocalHost(uri: Uri) = uri.host == "127.0.0.1" && uri.port == LocalHost.PORT

    /** Pages the app shows itself: its own host, the configured server, the account server, and hosts on the local network. */
    private fun isAppPage(uri: Uri): Boolean =
        isLocalHost(uri) || isServerUrl(uri) || isAuthServer(uri) || uri.host == WebViewAssetLoader.DEFAULT_DOMAIN || isPrivateAddress(uri.host)

    /** Logging in happens on the account server's own pages (see docs/implementation/auth.md). */
    private fun isAuthServer(uri: Uri): Boolean {
        val auth = app.host.authLink.url?.let(Uri::parse) ?: return false
        return uri.host == auth.host && uri.port == auth.port
    }

    /** A YTMP server you use: the account server the phone is linked to, or the configured server. */
    private fun isYourServer(uri: Uri): Boolean = isAuthServer(uri) || isServerUrl(uri)

    private fun isPrivateAddress(host: String?): Boolean {
        val parts = host?.split('.')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 4 } ?: return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168)
    }

    private fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if ("://" in trimmed) trimmed else "http://$trimmed"
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            currentUrl = url?.let(Uri::parse)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assets.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isAppPage(uri)) return false
            // Links to other sites open in the browser.
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            when {
                // The phone's own host may still be starting.
                isLocalHost(request.url) -> view.postDelayed({ view.reload() }, 500)
                isServerUrl(request.url) -> openServer(error = "Can't reach ${request.url.host}: ${error.description}")
            }
        }
    }

    /** Methods the web app can call as `window.YtmpNative.*`. They run on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun bridgeVersion(): Int = BRIDGE_VERSION

        /** The music on this phone shows in the Local tab (bridge version 10). */
        @JavascriptInterface
        fun phoneMusic(): Boolean = app.host.phoneMusicOn

        /** Switches it on (asking Android once) or off; the answer comes as onPhoneMusic (bridge version 10). */
        @JavascriptInterface
        fun setPhoneMusic(on: Boolean) = runOnUiThread {
            when {
                !on -> app.host.setPhoneMusic(false).also { phoneMusicAnswer(false, null) }
                app.host.canReadMusic() -> app.host.setPhoneMusic(true).also { phoneMusicAnswer(true, null) }
                else -> musicPermission.launch(LocalHost.MUSIC_PERMISSION)
            }
        }

        /** The folders picked as music, JSON [{id, name}] (bridge version 13). */
        @JavascriptInterface
        fun musicFolders(): String = Json.encodeToString(ListSerializer(MusicFolder.serializer()), app.host.musicFolders.all())

        /** Opens Android's folder picker; the answer comes as onMusicFolders (bridge version 13). */
        @JavascriptInterface
        fun addMusicFolder() = runOnUiThread { musicFolderPicker.launch(null) }

        @JavascriptInterface
        fun removeMusicFolder(id: String) = runOnUiThread {
            app.host.musicFolders.remove(id)
            musicFoldersAnswer()
        }

        @JavascriptInterface
        fun serverUrl(): String = server.orEmpty()

        @JavascriptInterface
        fun setServer(url: String) {
            server = normalize(url)
            runOnUiThread { openServer() }
        }

        @JavascriptInterface
        fun changeServer() = runOnUiThread { openServer(error = "") }

        /** Opens the configured server, or asks for one. */
        @JavascriptInterface
        fun openServer() = runOnUiThread { openServer(error = if (server == null) "" else null) }

        /** Back to the rooms on this phone. */
        @JavascriptInterface
        fun openHome() = runOnUiThread { this@MainActivity.openHome() }

        /** JSON list of rooms found on the local network (see [Nearby]). */
        @JavascriptInterface
        fun nearbyRooms(): String = json.encodeToString(ListSerializer(NearbyRoom.serializer()), app.nearby.rooms.value)

        /**
         * For a server's page: after you confirm, gives the page the YouTube Music sign-in this
         * phone has, or opens YouTube Music's sign-in for another account; the page gets the
         * cookies (`onYoutubeCookie`), to keep in your account on that server. Only for your
         * server's pages.
         */
        @JavascriptInterface
        fun youtubeCookie() = runOnUiThread {
            val noAnswer = "window.__ytmpNative && window.__ytmpNative.onYoutubeCookie && window.__ytmpNative.onYoutubeCookie(null, null)"
            if (currentUrl?.let(::isYourServer) != true) {
                js(noAnswer)
                return@runOnUiThread
            }
            val host = currentUrl?.host ?: "this server"
            val phone = app.host.youtube.shareable()
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(if (phone != null) "Use YouTube Music as ${phone.first.name} on $host?" else "Sign in to YouTube Music for $host?")
                .setMessage("Your YouTube Music sign-in will be kept in your YTMP account on $host, so your Home, Library and likes work there in any browser. Only do this on servers you trust.")
                .setNegativeButton("Cancel") { _, _ -> js(noAnswer) }
                .setOnCancelListener { js(noAnswer) }
            if (phone != null) {
                dialog.setPositiveButton("Use ${phone.first.name}") { _, _ ->
                    js("window.__ytmpNative && window.__ytmpNative.onYoutubeCookie && window.__ytmpNative.onYoutubeCookie(${JSONObject.quote(phone.second)}, null)")
                }
                dialog.setNeutralButton("Another account") { _, _ -> youtubeCookieLogin.launch(Intent(this@MainActivity, YoutubeLoginActivity::class.java)) }
            } else {
                dialog.setPositiveButton("Sign in") { _, _ -> youtubeCookieLogin.launch(Intent(this@MainActivity, YoutubeLoginActivity::class.java)) }
            }
            dialog.show()
        }

        /** For your server's pages: the YouTube Music account this phone is signed in to (JSON), to offer it there; null if none. */
        @JavascriptInterface
        fun phoneYoutubeAccount(): String? {
            if (currentUrl?.let(::isYourServer) != true) return null
            val account = app.host.youtube.shareable()?.first ?: return null
            return ProtocolJson.encodeToString(YoutubeAccount.serializer(), account)
        }

        /**
         * From your account server's page (`/account/youtube-app`): the YouTube Music sign-in
         * kept in your account there, for this phone too. Signs in after you confirm, then goes
         * back to the phone's own pages.
         */
        @JavascriptInterface
        fun useYoutubeCookie(cookie: String) = runOnUiThread {
            val from = currentUrl?.takeIf(::isAuthServer) ?: return@runOnUiThread
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Use YouTube Music on this phone?")
                .setMessage("This phone signs in to YouTube Music with the sign-in from your account on ${from.host}.")
                .setPositiveButton("Use it") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            app.host.youtube.signIn(cookie)
                            openHome()
                        } catch (e: SourceException) {
                            AlertDialog.Builder(this@MainActivity).setMessage("That didn't work: ${e.message}").setPositiveButton("OK", null).show()
                        }
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> openHome() }
                .show()
        }

        /** Opens YouTube Music's sign-in; the page hears back through `onYoutubeSignIn`. */
        @JavascriptInterface
        fun youtubeSignIn() = runOnUiThread { youtubeLogin.launch(Intent(this@MainActivity, YoutubeLoginActivity::class.java)) }

        /**
         * Log in once: your server's pages hand over a login (host token JSON) for the phone's own
         * pages. Only taken from the server the phone is linked to, else any page could log you
         * in as someone else.
         */
        @JavascriptInterface
        fun shareIdentity(identityJson: String) {
            if (currentUrl?.let(::isAuthServer) == true) app.sharedIdentity = identityJson
        }

        /** On the phone's own pages: the login from [shareIdentity], once. */
        @JavascriptInterface
        fun takeIdentity(): String? {
            if (currentUrl?.let(::isLocalHost) != true) return null
            return app.sharedIdentity.also { app.sharedIdentity = null }
        }

        /** This app's version, e.g. "0.5.2" (bridge version 5). */
        @JavascriptInterface
        fun appVersion(): String = Updates.installedVersion(this@MainActivity)

        /** Looks for a new version on GitHub; the app shows what it found (bridge version 5). */
        @JavascriptInterface
        fun checkForUpdates() = runOnUiThread {
            if (currentUrl?.let(::isAppPage) == true) Updates.checkNow(this@MainActivity, lifecycleScope)
        }

        /** Shares the app's log files, to report a problem (bridge version 5). */
        @JavascriptInterface
        fun shareLogs() = runOnUiThread {
            if (currentUrl?.let(::isAppPage) != true) return@runOnUiThread
            lifecycleScope.launch {
                val chooser = withContext(Dispatchers.IO) { runCatching { LogFiles.share(this@MainActivity) } }
                chooser.onSuccess(::startActivity).onFailure {
                    Log.w("YtmpLogs", "Couldn't share the logs", it)
                    AlertDialog.Builder(this@MainActivity).setMessage("Couldn't collect the logs: ${it.message}").setPositiveButton("OK", null).show()
                }
            }
        }

        /**
         * Clones a room onto this phone (bridge version 6): [requestJson] is a CreateRoomRequest
         * with the copy. Answers a CreateRoomResponse (its `url` is the room's link on the local
         * network), or `{"error": …}`. The page then opens the room on the phone's own host.
         */
        @JavascriptInterface
        fun cloneRoom(requestJson: String): String {
            if (currentUrl?.let(::isAppPage) != true) return ERROR_NOT_HERE
            return try {
                val request = json.decodeFromString(CreateRoomRequest.serializer(), requestJson)
                val host = app.host
                val room = host.rooms.createFor(request, ownerAccount = null, template = null, supportsPrivateRooms = true)
                val url = (host.lanUrl() ?: "http://127.0.0.1:${LocalHost.PORT}") + "/room/${room.code}"
                json.encodeToString(CreateRoomResponse.serializer(), CreateRoomResponse(room.code, room.ownerToken, url))
            } catch (e: Exception) {
                Log.w("YtmpClone", "Couldn't clone the room here", e)
                JSONObject().put("error", e.message ?: "Couldn't make the room").toString()
            }
        }

        /**
         * The Chromecasts on this phone's network, and the party screens it shows there (bridge
         * version 7), as JSON [CastScreenState]. Changes come as `onCastScreens`.
         */
        @JavascriptInterface
        fun castScreens(): String =
            json.encodeToString(CastScreenState.serializer(), castState(app.host.casts.devices.value, app.host.castScreens.shown.value))

        /**
         * Shows a room's party screen on a TV on this phone's network (bridge version 7): [url]
         * is the page's own `/tv#cast=CODE.TOKEN` (the room made that screen participant). The
         * answer comes as `onCastScreenShown(deviceId, error)`, error null when it shows.
         */
        @JavascriptInterface
        fun showCastScreen(deviceId: String, url: String) {
            if (!fromThisPage(url) || !ShownScreen.CAST_PAGE.matches(url)) {
                js("window.__ytmpNative && window.__ytmpNative.onCastScreenShown && window.__ytmpNative.onCastScreenShown(${JSONObject.quote(deviceId)}, \"Not from this page\")")
                return
            }
            lifecycleScope.launch {
                val error = try {
                    app.host.castScreens.show(deviceId, url)
                    null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("YtmpCast", "Couldn't show the party screen on $deviceId", e)
                    e.message ?: "The TV didn't answer"
                }
                js("window.__ytmpNative && window.__ytmpNative.onCastScreenShown && window.__ytmpNative.onCastScreenShown(${JSONObject.quote(deviceId)}, ${error?.let(JSONObject::quote) ?: "null"})")
            }
        }

        /** Stops the party screen this phone shows on [deviceId] (bridge version 7); the room hears so. */
        @JavascriptInterface
        fun stopCastScreen(deviceId: String) {
            if (currentUrl?.let(::isAppPage) != true) return
            lifecycleScope.launch { app.host.castScreens.stop(deviceId) }
        }

        /**
         * The Chromecasts, Sonos speakers and MPDs on this phone's network, the party screens shown
         * there, and the room playing on them (bridge version 8), as JSON [NearbyState].
         * Changes come as `onNearby`, problems as `onNearbyNotice(text)`.
         */
        @JavascriptInterface
        fun nearby(): String = json.encodeToString(
            NearbyState.serializer(),
            NearbyState.of(nearbyDevices(), app.host.castScreens.shown.value, app.host.nearby.playing.value),
        )

        /**
         * Plays the page's room on the speaker [deviceId] near this phone (bridge version 8):
         * [roomJson] is a [RoomLink] to the page's own host, with the page's guest token. The
         * app follows the room itself and serves the speaker the audio.
         */
        @JavascriptInterface
        fun playNearby(deviceId: String, roomJson: String) {
            val link = runCatching { json.decodeFromString(RoomLink.serializer(), roomJson) }.getOrNull() ?: return
            if (!fromThisPage(link.hostUrl)) return
            val device = nearbyDevices().firstOrNull { it.id == deviceId } ?: return
            app.host.nearby.turnOn(link.code, deviceId, device.name) { FollowerSource(link) }
        }

        /** Stops playing on the speaker [deviceId] (bridge version 8). */
        @JavascriptInterface
        fun stopNearby(deviceId: String) {
            if (currentUrl?.let(::isAppPage) == true) app.host.nearby.turnOff(deviceId)
        }

        /** The volume (0–1) of the speaker [deviceId] that plays the room (bridge version 8). */
        @JavascriptInterface
        fun setNearbyVolume(deviceId: String, volume: Double) {
            if (currentUrl?.let(::isAppPage) == true) app.host.nearby.setVolume(deviceId, volume)
        }

        /**
         * Download (bridge version 11): [songJson] is the Song, saved with its tags in the phone's
         * Music/YTMP folder. The phone gets it from YouTube itself, whichever host the page is on.
         * The answer comes as `onDownloaded(songId, error)`, error null when it's saved.
         */
        @JavascriptInterface
        fun downloadSong(songJson: String) {
            if (currentUrl?.let(::isAppPage) != true) return
            val song = runCatching { ProtocolJson.decodeFromString(Song.serializer(), songJson) }.getOrNull() ?: return
            app.host.downloads.save(song) { error ->
                js("window.__ytmpNative && window.__ytmpNative.onDownloaded && window.__ytmpNative.onDownloaded(${JSONObject.quote(song.id)}, ${error?.let(JSONObject::quote) ?: "null"})")
            }
        }

        /** The sleep timer (bridge version 12): JSON SleepSetting, or null when it's off. */
        @JavascriptInterface
        fun sleepTimer(): String? = PlaybackHub.sleep.value?.let { json.encodeToString(SleepSetting.serializer(), it) }

        /** Sets the sleep timer (JSON SleepSetting), or turns it off ("null"). Every page hears as onSleepTimer. */
        @JavascriptInterface
        fun setSleepTimer(settingJson: String) {
            PlaybackHub.sleep.value = runCatching { json.decodeFromString(SleepSetting.serializer(), settingJson) }.getOrNull()
                ?.takeIf { it.endsAt != null || it.itemId != null }
        }

        @JavascriptInterface
        fun playback(targetJson: String) {
            val target = json.decodeFromString(PlaybackTarget.serializer(), targetJson)
            PlaybackHub.target.value = target
            if (target.enabled) runOnUiThread { askForNotifications() }
        }
    }

    /** The media notification (with the playback controls) needs this permission, from Android 13 on. */
    private fun askForNotifications() {
        if (askedForNotifications || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        askedForNotifications = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val BRIDGE_VERSION = 13
        private const val ERROR_NOT_HERE = "{\"error\": \"Not from this page\"}"
    }
}

/** For the page (bridge `castScreens`): the TVs this phone found, and what it shows there. */
@Serializable
private data class CastScreenState(val devices: List<CastScreenDevice>, val shown: List<ShownScreen>)

@Serializable
private data class CastScreenDevice(val id: String, val name: String)

/** For the page (bridge `nearby`): the speakers and TVs this phone found, and what it does with them. */
@Serializable
private data class NearbyState(val devices: List<NearbyDevice>, val shown: List<ShownScreen>, val playing: NearbyPlaying?) {
    companion object {
        fun of(devices: List<OutputDevice>, shown: List<ShownScreen>, playing: NearbyPlaying?) =
            NearbyState(devices.map { NearbyDevice(it.id, it.name, it.kind) }, shown, playing)
    }
}

@Serializable
private data class NearbyDevice(val id: String, val name: String, val kind: OutputKind)
