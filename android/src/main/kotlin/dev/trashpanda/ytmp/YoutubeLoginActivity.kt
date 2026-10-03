package dev.trashpanda.ytmp

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Google's own sign-in page for YouTube Music. Once it lands on music.youtube.com signed in,
 * it returns that session's cookies (see [PhoneYoutubeAccount]); YTMP never sees the password.
 */
class YoutubeLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CookieManager.getInstance().setAcceptCookie(true)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Google refuses sign-ins from pages that say they are an embedded WebView ("; wv").
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = check(url)
                override fun onPageFinished(view: WebView, url: String) = check(url)
            }
        }
        val root = FrameLayout(this).apply { addView(webView) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        webView.loadUrl(SIGN_IN_URL)
    }

    /** Signed in once music.youtube.com has the session cookies. */
    private fun check(url: String) {
        if (done || !url.startsWith(YTM_URL)) return
        val cookie = CookieManager.getInstance().getCookie(YTM_URL) ?: return
        if ("__Secure-3PAPISID=" !in cookie) return
        done = true
        CookieManager.getInstance().flush()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_COOKIE, cookie))
        finish()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COOKIE = "cookie"
        private const val YTM_URL = "https://music.youtube.com"
        private const val SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
                "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
    }
}
