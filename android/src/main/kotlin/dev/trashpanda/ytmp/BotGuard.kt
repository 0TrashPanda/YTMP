package dev.trashpanda.ytmp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs YouTube's BotGuard in a hidden WebView, for PO tokens (see ytm-module/ytmp_ytm/pot.py,
 * which does the network part and calls these from Python, on its thread). The page
 * (assets/botguard.html) has YouTube's address, like BotGuard expects, but loads nothing
 * itself. After a while without tokens it's closed to free its memory; the next token then
 * starts over.
 */
class BotGuard(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val answers = ConcurrentHashMap<Int, CompletableFuture<String>>()
    private val ids = AtomicInteger()
    // Main thread only.
    private var page: WebView? = null
    private val closeIdle = Runnable { close() }

    /** Runs BotGuard on a fresh page; returns its answer, for Google's integrity token. */
    fun snapshot(script: String, program: String, globalName: String, ytcfg: String): String {
        val started = System.nanoTime()
        val loaded = CompletableFuture<Unit>()
        main.post { close(); open(loaded) }
        loaded.get(TIMEOUT_S, TimeUnit.SECONDS)
        return call("snapshot", script, program, globalName, ytcfg)
            .also { Log.d(TAG, "BotGuard ran in ${(System.nanoTime() - started) / 1_000_000} ms") }
    }

    /** Starts the minter with Google's integrity token. */
    fun start(integrityToken: String) {
        call("start", integrityToken)
    }

    /** A PO token for a video or visitor. Throws when the page was closed (then: [snapshot] again). */
    fun mint(binding: String): String = call("mint", binding)

    private fun call(function: String, vararg args: String): String {
        val id = ids.incrementAndGet()
        val answer = CompletableFuture<String>().also { answers[id] = it }
        val script = "ytmpBg.$function($id, ${args.joinToString(", ") { JSONObject.quote(it) }})"
        main.post {
            main.removeCallbacks(closeIdle)
            main.postDelayed(closeIdle, IDLE_MS)
            page?.evaluateJavascript(script, null) ?: answer.completeExceptionally(IllegalStateException("No BotGuard page"))
        }
        return try {
            answer.get(TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            answers.remove(id)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(loaded: CompletableFuture<Unit>) {
        val view = try {
            WebView(context)
        } catch (e: Exception) {
            loaded.completeExceptionally(e)
            return
        }
        view.settings.javaScriptEnabled = true
        // Python fetches all BotGuard needs; the page itself doesn't go online.
        view.settings.blockNetworkLoads = true
        view.addJavascriptInterface(Answers(), "YtmpBotGuard")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                loaded.complete(Unit)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Without this the whole app would go down with it.
                Log.w(TAG, "BotGuard's page crashed")
                if (view == page) close()
                return true
            }
        }
        val html = context.assets.open("botguard.html").bufferedReader().use { it.readText() }
        view.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null)
        page = view
    }

    private fun close() {
        main.removeCallbacks(closeIdle)
        page?.destroy()
        page = null
        answers.values.forEach { it.completeExceptionally(IllegalStateException("BotGuard's page was closed")) }
    }

    private inner class Answers {
        @JavascriptInterface
        fun done(id: Int, value: String) {
            answers[id]?.complete(value)
        }

        @JavascriptInterface
        fun fail(id: Int, message: String) {
            answers[id]?.completeExceptionally(Exception(message))
        }
    }

    private companion object {
        const val TAG = "BotGuard"
        const val TIMEOUT_S = 30L
        const val IDLE_MS = 10 * 60_000L
    }
}
