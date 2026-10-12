package dev.trashpanda.ytmp

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import java.util.concurrent.TimeUnit

/**
 * Runs JavaScript for yt-dlp (YouTube's challenges, see ytm-module/ytmp_ytm/jsc.py) in the
 * WebView's JavaScript engine, in its own sandboxed process. Without one, yt-dlp only gets a
 * reduced set of YouTube's formats. Called from Python, on its thread.
 */
class JsEngine private constructor(private val context: Context) {
    private var sandbox: JavaScriptSandbox? = null

    /** Runs [code] and returns the value of its last line, as text. */
    @Synchronized
    fun run(code: String): String {
        val started = System.nanoTime()
        val result = try {
            evaluate(code)
        } catch (e: Exception) {
            // The sandbox's process may have died (memory, WebView update): once more with a new one.
            Log.w(TAG, "JavaScript failed, starting a new sandbox: $e")
            close()
            evaluate(code)
        }
        Log.d(TAG, "Ran ${code.length / 1024} KB of JavaScript in ${(System.nanoTime() - started) / 1_000_000} ms")
        return result
    }

    private fun evaluate(code: String): String {
        val sandbox = sandbox ?: JavaScriptSandbox.createConnectedInstanceAsync(context).get(TIMEOUT_S, TimeUnit.SECONDS).also { sandbox = it }
        // A fresh isolate each time: nothing carries over from one song to the next.
        return sandbox.createIsolate().use { it.evaluateJavaScriptAsync(code).get(TIMEOUT_S, TimeUnit.SECONDS) }
    }

    private fun close() {
        runCatching { sandbox?.close() }
        sandbox = null
    }

    companion object {
        private const val TAG = "JsEngine"
        private const val TIMEOUT_S = 30L

        /** Null when this phone's WebView can't run JavaScript on its own (too old). */
        fun create(context: Context): JsEngine? {
            if (!JavaScriptSandbox.isSupported()) {
                Log.w(TAG, "This WebView has no JavaScript sandbox: yt-dlp gets fewer formats")
                return null
            }
            val engine = JsEngine(context.applicationContext)
            // The player's script is a few MB: too big to send without this.
            val bigScripts = runCatching {
                synchronized(engine) {
                    JavaScriptSandbox.createConnectedInstanceAsync(engine.context).get(TIMEOUT_S, TimeUnit.SECONDS)
                        .also { engine.sandbox = it }
                        .isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)
                }
            }.onFailure { Log.w(TAG, "Couldn't start the JavaScript sandbox: $it") }.getOrDefault(false)
            if (!bigScripts) {
                Log.w(TAG, "This WebView's JavaScript sandbox can't take big scripts: yt-dlp gets fewer formats")
                engine.close()
                return null
            }
            return engine
        }
    }
}
