package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.OutputRoom
import dev.trashpanda.ytmp.core.RoomView
import dev.trashpanda.ytmp.protocol.OutputKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.InetAddress

/** An MPD (Music Player Daemon) on the network. */
data class MpdAddress(val id: String, val name: String, val host: String, val port: Int = MpdPlayer.DEFAULT_PORT)

/**
 * MPD outputs: the MPDs the host knows about (say a Raspberry Pi with a Bluetooth speaker), and
 * one driver per room that keeps the MPDs the room plays on in step with it, like
 * [SonosOutputs]. MPD fetches the audio from the host's `/api/audio/…`, so [audioBaseUrl] must
 * be an address it can reach. See docs/implementation/mpd.md.
 */
class MpdOutputs(
    scope: CoroutineScope,
    /** Base URL of this host as seen from an MPD that reached us at [InetAddress]. */
    private val audioBaseUrl: (localAddress: InetAddress) -> String,
) : DeviceDriver(scope, "MPD") {
    private val known = MutableStateFlow<Map<String, MpdAddress>>(emptyMap())
    /** MPDs that feed amplifier zones: they're offered as those zones ([ZoneOutputs]), not by themselves. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    override val devices: StateFlow<List<OutputDevice>> = combine(known, hidden) { all, hide ->
        all.values.filter { it.id !in hide }.sortedBy { it.name }.map { OutputDevice(it.id, it.name, OutputKind.MPD) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(device: MpdAddress) {
        known.update { it + (device.id to device) }
        found(device.id)
    }

    fun remove(id: String) {
        known.update { it - id }
    }

    /** The network search lost it: removed, once no room plays on it any more ([lose]). */
    fun lost(id: String) = lose(id) { remove(id) }

    /** These MPDs (by id) aren't offered by themselves. */
    fun setHidden(ids: Set<String>) {
        hidden.value = ids
    }

    /**
     * Adds the MPD at [host]. [name]: from the config or its mDNS name; without one (or with
     * MPD's default "Music Player @ …") it's named after its audio output ("Bluetooth Speaker").
     * [retry]: an MPD from the config that's off now is tried again every minute.
     */
    fun addHost(host: String, port: Int = MpdPlayer.DEFAULT_PORT, name: String? = null, retry: Boolean = false) {
        val id = idFor(host, port)
        scope.launch {
            while (true) {
                try {
                    val outputs = MpdPlayer(host, port).outputs()
                    add(MpdAddress(id, displayName(name, outputs, host), host, port))
                    return@launch
                } catch (e: MpdException) {
                    log.warn("No MPD at {}:{}: {}", host, port, e.message)
                    if (!retry) return@launch
                }
                delay(RETRY_ADD_MS)
            }
        }
    }

    override fun has(id: String) = id in known.value && id !in hidden.value

    override fun name(id: String) = known.value[id]?.name ?: id

    override fun session(id: String, resuming: Boolean): DeviceSession {
        val device = known.value.getValue(id)
        val mpd = MpdSession(device.name, device.host, device.port, audioBaseUrl)
        return object : DeviceSession {
            override suspend fun sync(view: RoomView, wantedVolume: Double?, room: OutputRoom, outputId: String) =
                mpd.sync(view, wantedVolume, report = room::notice, ready = { room.outputReady(outputId, it) }) { actual -> room.reportOutputVolume(outputId, actual) }

            override suspend fun stop() = mpd.stop()

            /** It waits a little before trying again. */
            override fun failed(): Boolean {
                mpd.failedAt = System.currentTimeMillis()
                return false
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MpdOutputs::class.java)
        private const val RETRY_ADD_MS = 60_000L

        fun idFor(host: String, port: Int = MpdPlayer.DEFAULT_PORT) = if (port == MpdPlayer.DEFAULT_PORT) "mpd:$host" else "mpd:$host:$port"

        /** MPD's mDNS name unless it's the default one, else its one enabled output, else the machine. */
        internal fun displayName(name: String?, outputs: List<MpdOutput>, host: String): String {
            val default = name?.startsWith(DEFAULT_ZEROCONF_NAME) == true
            if (!name.isNullOrBlank() && !default) return name
            outputs.filter { it.enabled }.singleOrNull()?.let { return it.name }
            return name?.substringAfter("@", "")?.trim()?.ifEmpty { null } ?: host
        }

        /** MPD's `zeroconf_name` is "Music Player @ %h" unless set. */
        private const val DEFAULT_ZEROCONF_NAME = "Music Player"
    }
}
