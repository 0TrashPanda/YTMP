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
import com.google.common.util.concurrent.ListenableFuture
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

    private val prefs by lazy { getSharedPreferences("ytmp", MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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

        PlaybackHub.commandSink = { command -> js("window.__ytmpNative && window.__ytmpNative.onCommand($command)") }
        PlaybackHub.statusSink = { status -> js("window.__ytmpNative && window.__ytmpNative.onStatus(${JSONObject.quote(status)})") }

        // For development: `adb shell am start -n dev.trashpanda.ytmp/.MainActivity --es server http://10.0.2.2:8080`
        intent.getStringExtra("server")?.let { server = normalize(it) }
        if (savedInstanceState == null) openServer() else webView.restoreState(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller starts the playback service, so it can go to the foreground when playing.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        PlaybackHub.commandSink = null
        PlaybackHub.statusSink = null
        if (isFinishing) PlaybackHub.target.value = null
        webView.destroy()
        super.onDestroy()
    }

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

    private fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if ("://" in trimmed) trimmed else "http://$trimmed"
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assets.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isServerUrl(uri) || uri.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
            // Links to other sites open in the browser.
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && isServerUrl(request.url)) {
                openServer(error = "Can't reach ${request.url.host}: ${error.description}")
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
        const val BRIDGE_VERSION = 1
    }
}
