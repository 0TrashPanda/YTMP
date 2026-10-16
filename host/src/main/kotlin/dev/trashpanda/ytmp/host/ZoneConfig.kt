package dev.trashpanda.ytmp.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder

/**
 * A house's amplifier zones, as a file on its network describes them (served by e.g. the Raspberry
 * Pi that plays into the amp): the player that feeds them and how to switch each zone to it,
 * from general building blocks (web requests, reading the amp's state, saving and restoring
 * what a zone had). No amp-specific code. See docs/implementation/zones.md.
 */
@Serializable
data class ZoneConfig(
    val version: Int = 1,
    val player: ZonePlayer = ZonePlayer(),
    /** The amp and its zones; a file can also only share the player's [music]. */
    val amp: ZoneAmp? = null,
    val zones: List<ZoneEntry> = emptyList(),
    val music: ZoneMusic? = null,
)

/**
 * The player's music for the Local tab: MPD's songs, with the files and covers from a web server
 * (deploy/zones/ytmp-share.py). [files] and [art] are relative to the file's own address (or
 * whole addresses); [name] is how the Local tab calls it.
 */
@Serializable
data class ZoneMusic(val name: String? = null, val files: String = "/music/", val art: String? = "/art/")

/** The player all zones listen to. [host]: the machine that serves the config when left out. */
@Serializable
data class ZonePlayer(val type: String = "mpd", val host: String? = null, val port: Int = MpdPlayer.DEFAULT_PORT)

@Serializable
data class ZoneAmp(
    /** Shared start of the requests, as `{base}`; it may use `{zone}`. */
    val base: String = "",
    /** Request that answers with the zone's state as a JSON object. */
    val state: String? = null,
    /** The state's JSON comes after this text in the answer (for amps that put something before it). */
    val stateAfter: String? = null,
    /** State fields that say the zone plays the player; another value means someone switched it elsewhere. */
    val ours: Map<String, JsonPrimitive> = emptyMap(),
    /** State fields saved when a zone is switched on, as `{field}` in [off] to put them back. */
    val save: List<String> = emptyList(),
    /** Requests that switch a zone to the player, in order. */
    val on: List<String> = emptyList(),
    /** Requests that switch it off again; one that needs a saved field we don't have is left out. */
    val off: List<String> = emptyList(),
    val volume: ZoneVolume? = null,
)

/** The zone's own volume: [set] with `{volume}` from [min] to [max], read from the state's [field]. */
@Serializable
data class ZoneVolume(val set: String, val field: String? = null, val min: Double = 0.0, val max: Double = 100.0)

/** [zone]: the amp's number or name for it, as `{zone}`. [hidden]: not offered (say the amp's headphones). */
@Serializable
data class ZoneEntry(val zone: JsonPrimitive, val name: String, val hidden: Boolean = false)

class ZoneConfigException(message: String) : Exception(message)

/** The building blocks put together: requests for one zone, and what its state means. */
class ZoneControl(val key: String, private val amp: ZoneAmp) {
    private val base = amp.base.replace("{zone}", encode(key))

    /** Whether the config says how to tell that the zone plays the player. */
    val checksOurs get() = amp.ours.isNotEmpty()

    fun stateUrl(): String? = amp.state?.let { expand(it, emptyMap()) }

    fun onUrls(): List<String> = amp.on.map { expand(it, emptyMap()) }

    /** The off requests with [saved] filled in; those that need a field that wasn't saved are left out. */
    fun offUrls(saved: Map<String, String>): List<String> = amp.off.mapNotNull { template ->
        if (placeholders(template).any { it !in BUILT_IN && it !in saved }) null else expand(template, saved)
    }

    /** [volume]: 0–1. */
    fun volumeUrl(volume: Double): String? = amp.volume?.let { v -> expand(v.set, mapOf("volume" to ampVolume(volume).toString())) }

    /** 0–1 in the amp's own steps. */
    fun ampVolume(volume: Double): Long = amp.volume?.let { v -> Math.round(v.min + volume.coerceIn(0.0, 1.0) * (v.max - v.min)) } ?: 0

    /** The amp's state from a [stateUrl] answer. */
    fun parseState(answer: String): JsonObject {
        val text = amp.stateAfter?.let { after ->
            val at = answer.indexOf(after)
            if (at < 0) throw ZoneConfigException("the amp's answer has no \"$after\"")
            answer.substring(at + after.length)
        } ?: answer
        return runCatching { Json.parseToJsonElement(text.trim()) as JsonObject }.getOrElse { throw ZoneConfigException("the amp's answer isn't a JSON object") }
    }

    /** Whether the zone plays the player now; true when the config doesn't say how to tell. */
    fun isOurs(state: JsonObject): Boolean = amp.ours.all { (field, value) -> state.field(field)?.content == value.content }

    fun saved(state: JsonObject): Map<String, String> = amp.save.mapNotNull { field -> state.field(field)?.let { field to it.content } }.toMap()

    /** 0–1, or null when the state doesn't have it. */
    fun volumeOf(state: JsonObject): Double? {
        val v = amp.volume ?: return null
        val actual = state.field(v.field ?: return null)?.content?.toDoubleOrNull() ?: return null
        if (v.max <= v.min) return null
        return ((actual - v.min) / (v.max - v.min)).coerceIn(0.0, 1.0)
    }

    private fun expand(template: String, values: Map<String, String>): String {
        val withBase = template.replace("{base}", base).replace("{zone}", encode(key))
        return PLACEHOLDER.replace(withBase) { match ->
            values[match.groupValues[1]]?.let(::encode) ?: throw ZoneConfigException("no value for ${match.value}")
        }
    }

    companion object {
        // Both braces escaped: Android's regex engine (ICU) rejects a lone "}".
        private val PLACEHOLDER = Regex("""\{([A-Za-z0-9_.-]+)\}""")
        private val BUILT_IN = setOf("base", "zone")

        internal fun placeholders(template: String) = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** A field of the state, `a.b` for one inside another. */
        private fun JsonObject.field(path: String): JsonPrimitive? {
            var at: JsonElement = this
            for (part in path.split('.')) at = (at as? JsonObject)?.get(part) ?: return null
            return (at as? JsonPrimitive)?.takeIf { it !is JsonNull }
        }
    }
}

/**
 * Checks [config] before anything is sent: every placeholder known, and every request (and the
 * player) on the local network, so a file found on someone's Wi-Fi can't make the host send
 * requests to the internet. [sourceHost]: the machine that served it.
 * @return the player's address.
 */
fun validateZones(config: ZoneConfig, sourceHost: String): String {
    if (config.version != 1) throw ZoneConfigException("version ${config.version} isn't supported")
    if (config.player.type != "mpd") throw ZoneConfigException("player type \"${config.player.type}\" isn't supported")
    if (config.amp == null && config.music == null) throw ZoneConfigException("no \"amp\" or \"music\"")
    if (config.amp == null && config.zones.isNotEmpty()) throw ZoneConfigException("zones need an \"amp\"")
    val player = config.player.host ?: sourceHost
    if (!isLocal(player)) throw ZoneConfigException("the player $player isn't on the local network")
    val amp = config.amp ?: return InetAddress.getByName(player).hostAddress
    if (config.zones.isEmpty()) throw ZoneConfigException("no zones")
    if (amp.on.isEmpty()) throw ZoneConfigException("no \"on\" requests")
    if (amp.state == null && (amp.ours.isNotEmpty() || amp.save.isNotEmpty() || amp.volume != null)) throw ZoneConfigException("\"ours\", \"save\" and \"volume\" need \"state\"")
    amp.volume?.let { if (it.field == null) throw ZoneConfigException("\"volume\" needs the \"field\" it's read from") }
    amp.volume?.let { if (it.max <= it.min) throw ZoneConfigException("\"volume\" needs a \"max\" above its \"min\"") }
    if (config.zones.map { it.zone.content }.toSet().size != config.zones.size) throw ZoneConfigException("two zones with the same \"zone\"")
    val plain = listOfNotNull(amp.state) + amp.on
    for (template in plain) checkPlaceholders(template, emptySet())
    for (template in amp.off) checkPlaceholders(template, amp.save.toSet())
    amp.volume?.let { checkPlaceholders(it.set, setOf("volume")) }
    if (amp.base.isNotEmpty()) checkPlaceholders(amp.base, emptySet())

    val control = ZoneControl(config.zones.first().zone.content, amp)
    val urls = listOfNotNull(control.stateUrl()) + control.onUrls() + control.offUrls(amp.save.associateWith { "0" }) + listOfNotNull(control.volumeUrl(0.0))
    for (url in urls) {
        val uri = runCatching { URI(url) }.getOrElse { throw ZoneConfigException("not a web address: $url") }
        if (uri.scheme != "http" && uri.scheme != "https") throw ZoneConfigException("not a web address: $url")
        val host = uri.host ?: throw ZoneConfigException("no host in $url")
        if (!isLocal(host)) throw ZoneConfigException("$host isn't on the local network")
    }
    return InetAddress.getByName(player).hostAddress
}

private fun checkPlaceholders(template: String, allowed: Set<String>) {
    val unknown = ZoneControl.placeholders(template) - setOf("base", "zone") - allowed
    if (unknown.isNotEmpty()) throw ZoneConfigException("unknown {${unknown.first()}} in $template")
}

/** A private, link-local or loopback address (resolving [host] if it's a name). */
internal fun isLocal(host: String): Boolean {
    val address = runCatching { InetAddress.getByName(host.removeSurrounding("[", "]")) }.getOrNull() ?: return false
    return address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
        (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)
}

internal val zoneJson = Json { ignoreUnknownKeys = true }
