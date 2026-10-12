package dev.trashpanda.ytmp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Playback problems this phone hears (a link that stopped working, long buffering), sent to
 * the room's host for its log (`POST /api/log`). Best effort: a problem sending is ignored.
 */
object HostReport {
    fun send(hostUrl: String, roomCode: String, message: String) {
        runCatching {
            val connection = URL("${hostUrl.trimEnd('/')}/api/log").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(JSONObject().put("message", message).put("room", roomCode).toString().toByteArray()) }
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }
}
