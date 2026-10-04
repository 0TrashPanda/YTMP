package dev.trashpanda.ytmp

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
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
import dev.trashpanda.ytmp.host.NearbyRoom
import dev.trashpanda.ytmp.host.SourceException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    private val app get() = application as YtmpApp

    private val prefs by lazy { getSharedPreferences("ytmp", MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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

    /** Serves the bundled setup page from assets on a fake https origin. */
    private val assets by lazy {
        WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
    }

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
                    Log.d("YtmpWeb", "${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
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

        // For development: `adb shell am start -n dev.trashpanda.ytmp/.MainActivity --es server http://10.0.2.2:8080`
        intent.getStringExtra("server")?.let { server = normalize(it) }
        when {
            savedInstanceState != null -> webView.restoreState(savedInstanceState)
            intent.hasExtra("server") -> openServer()
            else -> openHome()
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
        // Binding a controller starts the playback service, so it can go to the foreground when playing.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        app.visible.value = false
        nearbyUpdates?.cancel()
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

    private fun isPrivateAddress(host: String?): Boolean {
        val parts = host?.split('.')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 4 } ?: return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168)
    }

    private fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if ("://" in trimmed) trimmed else "http://$trimmed"
    }

    private inner class Client : WebViewClient() {
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

        /** Opens YouTube Music's sign-in; the page hears back through `onYoutubeSignIn`. */
        @JavascriptInterface
        fun youtubeSignIn() = runOnUiThread { youtubeLogin.launch(Intent(this@MainActivity, YoutubeLoginActivity::class.java)) }

        @JavascriptInterface
        fun playback(targetJson: String) {
            val target = json.decodeFromString(PlaybackTarget.serializer(), targetJson)
            PlaybackHub.target.value = target
            if (target.enabled) runOnUiThread { askForNotifications() }
        }
    }

    /** The media notification (with the playback controls) needs this permission. */
    private fun askForNotifications() {
        if (askedForNotifications) return
        askedForNotifications = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val BRIDGE_VERSION = 2
    }
}
