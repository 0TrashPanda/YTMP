package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.OutputRoom
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.RoomView
import dev.trashpanda.ytmp.protocol.OutputKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder

/**
 * Amplifier zones: rooms of a multi-room amp that one player (an MPD) feeds, each offered as a
 * speaker. The house describes them in a file on its network ([ZoneConfig], found with mDNS or
 * set in the server's config): switching a zone on in a room switches it to the player on the
 * amp, and the player plays the room once, however many of its zones are on. Switching it off
 * puts back what the zone had. See docs/implementation/zones.md.
 */
class ZoneOutputs(
    private val scope: CoroutineScope,
    /** Base URL of this host as seen from a player that reached us at [InetAddress]. */
    private val audioBaseUrl: (localAddress: InetAddress) -> String,
    /** Its MPDs that feed zones aren't offered by themselves too. */
    private val mpd: MpdOutputs? = null,
) : OutputDriver {
    /** One zone: [control] makes its requests. */
    class Zone(val id: String, val name: String, val player: Player, val control: ZoneControl)

    data class Player(val host: String, val port: Int) {
        val mpdId get() = MpdOutputs.idFor(host, port)
    }

    /** Zone files by source (an mDNS service, or a URL from the config). */
    private val loaded = MutableStateFlow<Map<String, List<Zone>>>(emptyMap())
    private val sources = HashMap<String, Pair<String, Job>>()
    private val drivers = HashMap<String, Job>()

    // Shared by all rooms on this host, under [lock]: who has each player and zone, and what
    // each zone had before (put back when it's switched off).
    private val lock = Any()
    private val playerOwners = HashMap<Player, Any>()
    private val zoneOwners = HashMap<String, Any>()
    private val savedStates = HashMap<String, Map<String, String>>()

    override val devices: StateFlow<List<OutputDevice>> = loaded
        .map { all -> all.values.flatten().distinctBy { it.id }.map { OutputDevice(it.id, it.name, OutputKind.ZONE) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        if (mpd != null) scope.launch { loaded.collect { all -> mpd.setHidden(all.values.flatten().map { it.player.mpdId }.toSet()) } }
    }

    /** Follows the zones file at [url] (fetched again every minute, so changes to it show up). */
    fun addSource(id: String, url: String) {
        synchronized(sources) {
            if (sources[id]?.first == url) return
            sources.remove(id)?.second?.cancel()
            sources[id] = url to scope.launch { follow(id, url) }
        }
    }

    fun removeSource(id: String) {
        synchronized(sources) { sources.remove(id)?.second?.cancel() }
        loaded.update { it - id }
    }

    private suspend fun follow(id: String, url: String) {
        var last: Pair<ZoneConfig, String>? = null
        var problem: String? = null
        var misses = 0
        while (true) {
            try {
                val (config, playerHost) = fetch(url)
                misses = 0
                problem = null
                if (last != config to playerHost) {
                    last = config to playerHost
                    val player = Player(playerHost, config.player.port)
                    val zones = config.zones.filterNot { it.hidden }.map { Zone(idFor(player, it.zone.content), it.name, player, ZoneControl(it.zone.content, config.amp)) }
                    currentCoroutineContext().ensureActive()
                    loaded.update { it + (id to zones) }
                    log.info("Zones from {}: {}", url, zones.joinToString { it.name })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                // Anything wrong with a file from the network only costs its zones, never the app.
                if (e.message != problem) log.warn("Zones from {}: {}", url, e.message)
                problem = e.message
                // A broken file has no zones; one that's out of reach keeps them a little while.
                if (e is ZoneConfigException || ++misses >= MISSES) {
                    last = null
                    currentCoroutineContext().ensureActive()
                    loaded.update { it - id }
                }
            }
            delay(REFRESH_MS)
        }
    }

    private suspend fun fetch(url: String): Pair<ZoneConfig, String> = withContext(Dispatchers.IO) {
        val host = runCatching { URI(url).host }.getOrNull() ?: throw ZoneConfigException("not a web address: $url")
        if (!isLocal(host)) throw ZoneConfigException("$host isn't on the local network")
        val config = try {
            zoneJson.decodeFromString<ZoneConfig>(httpGet(url))
        } catch (e: IllegalArgumentException) {
            throw ZoneConfigException("not a zones file: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        config to validateZones(config, host)
    }

    /** Starts a driver for every room, now and as rooms are created. */
    fun attach(rooms: RoomManager) {
        scope.launch {
            rooms.list.collect { list ->
                val codes = list.map { it.code }.toSet()
                synchronized(drivers) {
                    for (code in drivers.keys - codes) drivers.remove(code)?.cancel()
                    for (code in codes - drivers.keys) rooms[code]?.let { drivers[code] = scope.launch { drive(it) } }
                }
            }
        }
    }

    override fun play(room: OutputRoom): Job = scope.launch { drive(room) }

    /** A zone switched on in one room. */
    private class ZoneRun(var zone: Zone) {
        var settledAt = 0L
        /** In the amp's steps, as last set or seen. */
        var appliedVolume: Long? = null
        var checkedVolume = false
        var failedAt = 0L
        var told = false
        /** Switched to the player on the amp (by us, and nobody switched it elsewhere since). */
        var switched = false
    }

    private suspend fun drive(room: OutputRoom) {
        val me = Any()
        val changed = Channel<Unit>(Channel.CONFLATED)
        room.addChangeListener { changed.trySend(Unit) }
        val zones = HashMap<String, ZoneRun>()
        val players = HashMap<Player, MpdSession>()
        /** Since when the room plays nothing (paused, or nothing left); 0 while it plays. */
        var quietSince = 0L
        try {
            while (true) {
                withTimeoutOrNull(SYNC_INTERVAL_MS) { changed.receive() }
                val view = room.view()
                val now = System.currentTimeMillis()
                if (view.playing) quietSince = 0 else if (quietSince == 0L) quietSince = now
                // Paused for a while: the zones get back what they had (the TV), until it plays again.
                val parked = quietSince > 0 && now - quietSince >= PARK_AFTER_MS
                val known = loaded.value.values.flatten().associateBy { it.id }
                // Switched off in the room, or gone from the network: put back what the zone had.
                for (id in zones.keys - view.activeOutputs.keys) letGo(me, zones.remove(id)!!, restore = true)
                for (id in view.activeOutputs.keys) known[id]?.let { zone -> zones.getOrPut(id) { ZoneRun(zone) }.zone = zone }
                // A player none of the room's zones listen to any more stops (unless another room has it now).
                for (player in players.keys - zones.values.map { it.zone.player }.toSet()) {
                    val session = players.remove(player)!!
                    if (release(me, player)) runCatching { session.stop() }
                }
                for ((player, runs) in zones.values.groupBy { it.zone.player }) syncPlayer(room, me, view, parked, player, runs, zones, players)
            }
        } finally {
            withContext(NonCancellable) {
                for (run in zones.values) letGo(me, run, restore = true)
                for ((player, session) in players) if (release(me, player)) runCatching { session.stop() }
            }
        }
    }

    private suspend fun syncPlayer(
        room: OutputRoom,
        me: Any,
        view: RoomView,
        parked: Boolean,
        player: Player,
        runs: List<ZoneRun>,
        zones: HashMap<String, ZoneRun>,
        players: HashMap<Player, MpdSession>,
    ) {
        suspend fun takenOver(restore: Boolean) {
            players.remove(player)
            release(me, player)
            for (run in runs) {
                letGo(me, run, restore)
                zones.remove(run.zone.id)
                room.outputTakenOver(run.zone.id)
            }
        }
        // Another room on this host switched on a zone of this player since: it plays that room now.
        if (player in players && synchronized(lock) { playerOwners[player] } !== me) {
            log.info("Zones of {}: another room plays there now", player.host)
            return takenOver(restore = true)
        }
        var session = players[player]
        if (session == null) {
            inheritFrom(player, runs.map { it.zone.id }.toSet())
            synchronized(lock) { playerOwners[player] = me }
            session = MpdSession(runs.joinToString(" and ") { it.zone.name }, player.host, player.port, audioBaseUrl)
            players[player] = session
        }
        // The zones first, so the song's start is heard.
        for (run in runs) {
            if (!syncZone(room, me, run, view.activeOutputs[run.zone.id], parked)) {
                zones.remove(run.zone.id)
                room.outputTakenOver(run.zone.id)
            }
        }
        val ids = runs.map { it.zone.id }.filter { it in zones }
        if (ids.isEmpty()) return
        var failed = false
        try {
            // The zones have the volume: the player plays at full volume.
            session.sync(view, 1.0, report = room::notice, ready = { item -> ids.forEach { room.outputReady(it, item) } }) { }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutputTakenOver) {
            // Another app or another host plays there now: leave its zones be.
            log.info("Zones of {}: {}", player.host, e.message)
            return takenOver(restore = false)
        } catch (e: Exception) {
            log.warn("Player {} for zones failed: {}", player.host, e.message)
            session.failedAt = System.currentTimeMillis()
            failed = true
        }
        // A new song waits until the player has it (or failed), also for a zone switched on since.
        val item = view.current
        if (view.starting && item != null && (failed || session.loaded == item.itemId)) ids.forEach { room.outputReady(it, item.itemId) }
    }

    /** Switches [run]'s zone on, or checks it's still ours; false when someone else has it now. */
    private suspend fun syncZone(room: OutputRoom, me: Any, run: ZoneRun, wantedVolume: Double?, parked: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (now - run.failedAt < MpdSession.RETRY_AFTER_MS) return true
        val zone = run.zone
        val control = zone.control
        try {
            if (parked) {
                // The zone stays on in the room; on the amp it has what it had until the room plays.
                if (run.switched) {
                    log.info("Zone {}: the room is paused, giving it back", zone.name)
                    letGo(me, run, restore = true)
                    run.switched = false
                }
                // Its volume slider shows the zone's volume all the same.
                if (!run.checkedVolume) readState(control)?.let { state -> control.volumeOf(state)?.let { volume ->
                    run.checkedVolume = true
                    run.appliedVolume = control.ampVolume(volume)
                    room.reportOutputVolume(zone.id, volume)
                } }
                return true
            }
            if (run.switched && synchronized(lock) { zoneOwners[zone.id] } !== me) {
                log.info("Zone {}: another room switched it on", zone.name)
                return false
            }
            if (!run.switched) {
                switchOn(me, zone)
                run.switched = true
                run.settledAt = now + SETTLE_MS
            }
            val state = readState(control)
            if (state != null && now >= run.settledAt && !control.isOurs(state)) {
                // Switched to something else on the amp itself: that's what it plays now.
                log.info("Zone {} was switched to something else on the amp", zone.name)
                letGo(me, run, restore = false)
                return false
            }
            if (state != null) syncVolume(room, run, control, state, wantedVolume)
            run.told = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Zone {} failed: {}", zone.name, e.message)
            run.failedAt = now
            if (!run.told) room.notice("Couldn't control ${zone.name} on the amplifier: ${e.message}")
            run.told = true
        }
        return true
    }

    /** Claims the zone, saves what it has (unless it plays the player already) and switches it to the player. */
    private suspend fun switchOn(me: Any, zone: Zone) {
        val control = zone.control
        val before = readState(control)
        synchronized(lock) {
            zoneOwners[zone.id] = me
            // Taken over from another room: what it had before that room is what comes back.
            if (before != null && zone.id !in savedStates && !(control.checksOurs && control.isOurs(before))) savedStates[zone.id] = control.saved(before)
        }
        log.info("Zone {}: switching it on", zone.name)
        for (url in control.onUrls()) httpGet(url)
    }

    /** Lets go of [run]'s zone if it's still ours; [restore] puts back what it had before. */
    private suspend fun letGo(me: Any, run: ZoneRun, restore: Boolean) {
        val zone = run.zone
        val saved = synchronized(lock) {
            if (zoneOwners[zone.id] !== me) return
            zoneOwners.remove(zone.id)
            savedStates.remove(zone.id).orEmpty()
        }
        if (!restore) return
        log.info("Zone {}: switching it off", zone.name)
        for (url in zone.control.offUrls(saved)) {
            try {
                httpGet(url)
            } catch (e: IOException) {
                log.warn("Zone {} couldn't be switched off: {}", zone.name, e.message)
                return
            }
        }
    }

    /**
     * What this host's rooms have of [player]'s zones, for another YTMP host that takes the player
     * over: each zone's state from before it was switched on (empty when nothing was saved).
     */
    fun handover(player: Player): ZoneHandover {
        val ids = loaded.value.values.flatten().filter { it.player == player }.map { it.id }.toSet()
        return synchronized(lock) { ZoneHandover(zoneOwners.keys.filter { it in ids }.associateWith { savedStates[it].orEmpty() }) }
    }

    /**
     * Before a room takes [player] over from another YTMP host (its music plays there now): asks
     * that host which zones it has on and what they had before. The zones this room switches on
     * too go back to that when it's done; the others go back right away. If the other host
     * can't be asked, its zones are left as they are.
     */
    private suspend fun inheritFrom(player: Player, wanted: Set<String>) {
        val handover = try {
            askHandover(player) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("Zones of {}: couldn't ask the host that played there: {}", player.host, e.message)
            return
        }
        val known = loaded.value.values.flatten().associateBy { it.id }
        for ((id, saved) in handover.zones) {
            val zone = known[id]?.takeIf { it.player == player } ?: continue
            if (synchronized(lock) { zoneOwners[id] != null }) continue
            if (id in wanted) {
                synchronized(lock) { savedStates.putIfAbsent(id, saved) }
            } else {
                log.info("Zone {}: switching it off (the host that played there had it on)", zone.name)
                try {
                    for (url in zone.control.offUrls(saved)) httpGet(url)
                } catch (e: IOException) {
                    log.warn("Zone {} couldn't be switched off: {}", zone.name, e.message)
                }
            }
        }
    }

    /** The other host's handover, or null when the player plays nothing of another YTMP host's. */
    private suspend fun askHandover(player: Player): ZoneHandover? {
        val mpd = MpdPlayer(player.host, player.port)
        val file = mpd.status().file ?: return null
        val at = file.indexOf("/api/audio/").takeIf { it > 0 } ?: return null
        val base = file.substring(0, at)
        if (base == audioBaseUrl(mpd.localAddress).trimEnd('/')) return null
        val uri = runCatching { URI(base) }.getOrNull() ?: return null
        if ((uri.scheme != "http" && uri.scheme != "https") || uri.host == null || !isLocal(uri.host)) return null
        val query = "host=" + URLEncoder.encode(player.host, Charsets.UTF_8) + "&port=" + player.port
        val text = httpGet("$base/api/zones/handover?$query", connectTimeout = 1_500, readTimeout = 2_000)
        return zoneJson.decodeFromString<ZoneHandover>(text)
    }

    private fun release(me: Any, player: Player): Boolean = synchronized(lock) {
        if (playerOwners[player] !== me) return false
        playerOwners.remove(player)
        true
    }

    private suspend fun readState(control: ZoneControl): JsonObject? = control.stateUrl()?.let { control.parseState(httpGet(it)) }

    /** The room's slider starts at the zone's volume on the amp and follows changes made there. */
    private suspend fun syncVolume(room: OutputRoom, run: ZoneRun, control: ZoneControl, state: JsonObject, wanted: Double?) {
        val actual = control.volumeOf(state) ?: return
        val actualSteps = control.ampVolume(actual)
        val wantedSteps = wanted?.let(control::ampVolume)
        when {
            !run.checkedVolume || (actualSteps != run.appliedVolume && wantedSteps == run.appliedVolume) -> {
                run.checkedVolume = true
                run.appliedVolume = actualSteps
                room.reportOutputVolume(run.zone.id, actual)
            }
            wanted != null && wantedSteps != actualSteps -> {
                control.volumeUrl(wanted)?.let { httpGet(it) }
                run.appliedVolume = wantedSteps
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ZoneOutputs::class.java)
        private const val SYNC_INTERVAL_MS = 2_000L
        /** After switching a zone on, the amp's state may still show what it had. */
        private const val SETTLE_MS = 4_000L
        /** Paused this long, the room's zones get back what they had on the amp. */
        private const val PARK_AFTER_MS = 10_000L
        private const val REFRESH_MS = 60_000L
        /** Fetches in a row that fail before the zones go (the file's machine is off or gone). */
        private const val MISSES = 3

        /** The zones file an mDNS announcement points at ([path]: its `path` TXT record). */
        fun fileUrl(host: String, port: Int, path: String?): String {
            val address = if (':' in host) "[$host]" else host
            return "http://$address:$port/" + (path?.trim()?.trimStart('/')?.ifEmpty { null } ?: DEFAULT_PATH)
        }

        private const val DEFAULT_PATH = "ytmp-zones.json"

        fun idFor(player: Player, zone: String) =
            if (player.port == MpdPlayer.DEFAULT_PORT) "zone:${player.host}:$zone" else "zone:${player.host}:${player.port}:$zone"

        /** The answer to a GET, as text. */
        internal suspend fun httpGet(url: String, connectTimeout: Int = 3_000, readTimeout: Int = 5_000): String = withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeout
                connection.readTimeout = readTimeout
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code from ${URI(url).host}")
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** A host's zones of one player, by id, each with the state it had before (`GET /api/zones/handover`). */
@Serializable
data class ZoneHandover(val zones: Map<String, Map<String, String>>)
