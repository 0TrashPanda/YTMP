package dev.trashpanda.ytmp.cast

import dev.trashpanda.ytmp.cast.CastChannel.Companion.NS_RECEIVER
import dev.trashpanda.ytmp.cast.CastChannel.Companion.RECEIVER
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Shows a web page on a Cast device (a TV, a display) with DashCast: an existing receiver
 * app that opens any URL, so YTMP needs no Cast app of its own (Home Assistant's pychromecast
 * uses it the same way). A device runs one app at a time: this replaces its media player.
 */
class CastWebPage(private val channel: CastChannel) {
    private var transportId: String? = null

    /**
     * Starts DashCast and opens [url] in it, as the whole page (`force`) rather than in
     * DashCast's frame: a page in that frame may not play sound by itself (the frame has no
     * autoplay permission), and an `http` page (a phone's address) may not show there at all.
     * DashCast no longer hears us after that, so a running DashCast is stopped and started afresh.
     */
    suspend fun show(url: String) {
        val started = System.currentTimeMillis()
        val before = status()
        dashCast(before)?.let { running ->
            log.info("DashCast: stopping the one that runs, to start afresh")
            channel.request(NS_RECEIVER, RECEIVER, payload("STOP", "sessionId" to JsonPrimitive(running.sessionId)))
        }
        val launched = channel.request(NS_RECEIVER, RECEIVER, payload("LAUNCH", "appId" to JsonPrimitive(APP_ID)))
        if (launched.type != "RECEIVER_STATUS") log.info("DashCast: the device answered the launch with {}", launched)
        // A Nest Hub first answers LAUNCH_STATUS, and the app that ran there takes a moment to make way.
        var found = dashCast(status())
        var waits = 0
        while (found == null && ++waits <= READY_TRIES) {
            delay(READY_WAIT_MS)
            found = dashCast(status())
        }
        var app = found ?: error("The Cast device didn't start DashCast (running: ${appIds(status())})")
        // A just-started app only hears messages once it has set up its namespace.
        var tries = 0
        while (NAMESPACE !in app.namespaces) {
            if (++tries > READY_TRIES) error("DashCast didn't get ready on the Cast device")
            delay(READY_WAIT_MS)
            app = dashCast(status()) ?: error("DashCast stopped on the Cast device")
        }
        log.info("DashCast: ready after {} ms, opening {} as the whole page", System.currentTimeMillis() - started, url.substringBefore('#'))
        val transport = app.transportId
        transportId = transport
        channel.connect(transport)
        channel.send(
            NAMESPACE, transport,
            JsonObject(mapOf("url" to JsonPrimitive(url), "force" to JsonPrimitive(true), "reload" to JsonPrimitive(false), "reload_time" to JsonPrimitive(0))),
        )
    }

    /** DashCast still runs on the device (nobody cast something else, the TV wasn't switched off). */
    suspend fun isShowing(): Boolean {
        val status = status()
        return (dashCast(status) != null).also { if (!it) log.info("DashCast: no longer runs (running: {})", appIds(status)) }
    }

    private fun appIds(status: JsonObject): List<String> =
        (status["status"] as? JsonObject)?.get("applications")?.let { it as? JsonArray }
            ?.mapNotNull { ((it as? JsonObject)?.get("displayName") ?: (it as? JsonObject)?.get("appId"))?.jsonPrimitive?.content }.orEmpty()

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
        private val log = LoggerFactory.getLogger(CastWebPage::class.java)

        /** DashCast's app id and namespace (as in pychromecast's dashcast controller). */
        const val APP_ID = "84912283"
        const val NAMESPACE = "urn:x-cast:com.madmod.dashcast"
        private const val READY_TRIES = 20
        private const val READY_WAIT_MS = 500L
    }
}
