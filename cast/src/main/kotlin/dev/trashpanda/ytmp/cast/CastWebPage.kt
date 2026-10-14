package dev.trashpanda.ytmp.cast

import dev.trashpanda.ytmp.cast.CastChannel.Companion.NS_RECEIVER
import dev.trashpanda.ytmp.cast.CastChannel.Companion.RECEIVER
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shows a web page on a Cast device (a TV, a display) with DashCast: an existing receiver
 * app that opens any URL, so YTMP needs no Cast app of its own (Home Assistant's pychromecast
 * uses it the same way). A device runs one app at a time: this replaces its media player.
 */
class CastWebPage(private val channel: CastChannel) {
    private var transportId: String? = null

    /**
     * Starts DashCast (or reuses it) and opens [url] in it. DashCast is an `https` page, which
     * can only frame `https` pages: an `http` page (a phone's address) replaces DashCast's page
     * instead (`force`), after which DashCast no longer hears us, so that starts it afresh.
     */
    suspend fun show(url: String) {
        val force = url.startsWith("http://")
        var app = dashCast(status())
        if (app != null && force) {
            channel.request(NS_RECEIVER, RECEIVER, payload("STOP", "sessionId" to JsonPrimitive(app.sessionId)))
            app = null
        }
        if (app == null) {
            channel.request(NS_RECEIVER, RECEIVER, payload("LAUNCH", "appId" to JsonPrimitive(APP_ID)))
            app = dashCast(status()) ?: error("The Cast device didn't start DashCast")
        }
        // A just-started app only hears messages once it has set up its namespace.
        var tries = 0
        while (NAMESPACE !in app!!.namespaces) {
            if (++tries > READY_TRIES) error("DashCast didn't get ready on the Cast device")
            delay(READY_WAIT_MS)
            app = dashCast(status()) ?: error("DashCast stopped on the Cast device")
        }
        val transport = app.transportId
        transportId = transport
        channel.connect(transport)
        // In its own frame where possible: then DashCast keeps its Cast connection.
        channel.send(
            NAMESPACE, transport,
            JsonObject(mapOf("url" to JsonPrimitive(url), "force" to JsonPrimitive(force), "reload" to JsonPrimitive(false), "reload_time" to JsonPrimitive(0))),
        )
    }

    /** DashCast still runs on the device (nobody cast something else, the TV wasn't switched off). */
    suspend fun isShowing(): Boolean = dashCast(status()) != null

    /** Closes DashCast on the device. */
    suspend fun stop() {
        val app = dashCast(status()) ?: return
        channel.request(NS_RECEIVER, RECEIVER, payload("STOP", "sessionId" to JsonPrimitive(app.sessionId)))
        transportId = null
    }

    private class App(val transportId: String, val sessionId: String, val namespaces: Set<String>)

    private suspend fun status(): JsonObject = channel.request(NS_RECEIVER, RECEIVER, payload("GET_STATUS"))

    private fun dashCast(status: JsonObject): App? {
        val app = status["status"]?.jsonObject?.get("applications")?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["appId"]?.jsonPrimitive?.content == APP_ID } ?: return null
        return App(
            transportId = app["transportId"]?.jsonPrimitive?.content ?: return null,
            sessionId = app["sessionId"]?.jsonPrimitive?.content ?: return null,
            namespaces = app["namespaces"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.orEmpty().toSet(),
        )
    }

    private fun payload(type: String, vararg fields: Pair<String, JsonElement>) =
        JsonObject(mapOf("type" to JsonPrimitive(type)) + fields)

    companion object {
        /** DashCast's app id and namespace (as in pychromecast's dashcast controller). */
        const val APP_ID = "84912283"
        const val NAMESPACE = "urn:x-cast:com.madmod.dashcast"
        private const val READY_TRIES = 20
        private const val READY_WAIT_MS = 500L
    }
}
