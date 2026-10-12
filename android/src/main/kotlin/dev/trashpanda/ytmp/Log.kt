package dev.trashpanda.ytmp

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Like android.util.Log, but through slf4j (logback-android, see assets/logback.xml), so the
 * app's own lines also end up in its log files next to the shared code's.
 */
object Log {
    private val loggers = ConcurrentHashMap<String, Logger>()

    private fun logger(tag: String): Logger = loggers.getOrPut(tag) { LoggerFactory.getLogger("dev.trashpanda.ytmp.$tag") }

    fun d(tag: String, message: String) = logger(tag).debug(message)

    fun i(tag: String, message: String) = logger(tag).info(message)

    fun w(tag: String, message: String, error: Throwable? = null) = logger(tag).warn(message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = logger(tag).error(message, error)
}
