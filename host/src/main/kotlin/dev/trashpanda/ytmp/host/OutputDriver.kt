package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.OutputRoom
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.RoomView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * One kind of speaker or TV the host can play a room on: [CastOutputs], [SonosOutputs],
 * [MpdOutputs], [ZoneOutputs]. A new kind is a [DeviceDriver] (devices that each play the room
 * by themselves), or a [RoomFollowingDriver] with its own way of following a room, added to the
 * host's [OutputDrivers].
 */
interface OutputDriver {
    /** The devices found or configured, as room outputs. */
    val devices: StateFlow<List<OutputDevice>>

    /** Plays every room of [rooms] on these devices (the ones each room picks), now and as rooms are created. */
    fun attach(rooms: RoomManager)

    /**
     * Plays a room on these devices (the ones in its [RoomView.activeOutputs]) until the job is
     * cancelled: a room on another host that this phone plays on speakers near it.
     */
    fun play(room: OutputRoom): Job
}

/** An [OutputDriver] that follows each room in a loop of its own ([drive]), one per room. */
abstract class RoomFollowingDriver(protected val scope: CoroutineScope) : OutputDriver {
    private val drivers = HashMap<String, Job>()

    final override fun attach(rooms: RoomManager) {
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

    final override fun play(room: OutputRoom): Job = scope.launch { drive(room) }

    /** Keeps this kind of device in step with [room] until cancelled. */
    protected abstract suspend fun drive(room: OutputRoom)
}

/** One room playing on one device of a [DeviceDriver]. */
interface DeviceSession {
    /**
     * Brings the device in line with the room ([view], at [wantedVolume]; null: as it is), and
     * tells [room] what the device did (its volume, a skip on the device, that it has a starting
     * song). Throws [OutputTakenOver] when another app took the device, anything else when it failed.
     */
    suspend fun sync(view: RoomView, wantedVolume: Double?, room: OutputRoom, outputId: String)

    /** A song was loaded on the device in this session (another app playing there later took it over). */
    val loadedSomething: Boolean get() = false

    /** It can play faster or slower (a podcast episode at the room's speed); otherwise it sits such an episode out. */
    val supportsSpeed: Boolean get() = false

    /** The room stopped playing here. */
    suspend fun stop()

    /** Another app took the device: let go of it, without stopping that app. */
    fun takenOver() {}

    /** [sync] failed. True: drop this session and start a new one next round (reconnect); false: keep it (it waits before trying again). */
    fun failed(): Boolean

    /** The room's driver ends (the room closed, or the host stops). */
    suspend fun end() {
        runCatching { stop() }
    }
}

/**
 * A driver for devices that each play a room by themselves (a Chromecast, a Sonos speaker, an
 * MPD): one loop per room keeps a [DeviceSession] for every device the room plays on in step
 * with it, checking right away when the room changes and every [SYNC_INTERVAL_MS] anyway.
 */
abstract class DeviceDriver(scope: CoroutineScope, private val kind: String) : RoomFollowingDriver(scope) {
    /** Whether device [id] is there (found, configured, offered) now. */
    protected abstract fun has(id: String): Boolean

    protected abstract fun name(id: String): String

    /** A session for device [id]. [resuming]: this room played there before (the connection dropped), so it doesn't take it from another app. */
    protected abstract fun session(id: String, resuming: Boolean): DeviceSession

    /** Device id → how many rooms' sessions there synced fine last time. */
    private val reached = HashMap<String, Int>()
    /** Devices the search lost while a room still reached them: how to forget each once none does. */
    private val lost = HashMap<String, () -> Unit>()
    /**
     * Devices a room played on until they stopped answering: until when (nanoTime) they're kept
     * anyway. The phone's own Wi-Fi drops for a minute now and then; the device is still there.
     */
    private val keptUntil = HashMap<String, Long>()
    /** How long a device a room played on is kept after it stopped answering. */
    internal var keepAfterFailingMs = KEEP_AFTER_FAILING_MS

    /**
     * The network search lost device [id]: [forget] it, but only once no room reaches it any more
     * and none played on it in the last [keepAfterFailingMs].
     * A phone's search sometimes loses every device at once for a minute while they play on fine,
     * and when the phone itself drops off Wi-Fi for a while, the search and every connection fail
     * together; a device that's really gone keeps failing, and then it's forgotten.
     */
    protected fun lose(id: String, forget: () -> Unit) {
        val (now, wait) = synchronized(reached) {
            val wait = keptFor(id)
            if ((reached[id] ?: 0) > 0 || wait > 0) lost[id] = forget else lost.remove(id)
            (id !in lost) to wait
        }
        if (now) return forget()
        log.info("{} {}: the network search lost it, but a room still reaches it (or did just now), so it stays", kind, name(id))
        if (wait > 0) forgetLater(id, wait)
    }

    /** The search found device [id] (again): it isn't lost. */
    protected fun found(id: String) {
        synchronized(reached) { lost.remove(id) }
    }

    /** How many ms device [id] is still kept after it stopped answering; 0: not kept. Locked on [reached]. */
    private fun keptFor(id: String): Long {
        val until = keptUntil[id] ?: return 0
        val left = (until - System.nanoTime()) / 1_000_000
        if (left <= 0) keptUntil.remove(id)
        return left.coerceAtLeast(0)
    }

    /** [reaches]: a room's session there synced fine ([yes]), or stopped doing so, because it [failed] or the room let go. */
    private fun reaches(id: String, yes: Boolean, failed: Boolean = false) {
        var wait = 0L
        val forget = synchronized(reached) {
            val count = (reached[id] ?: 0) + if (yes) 1 else -1
            if (count > 0) reached[id] = count else reached.remove(id)
            if (yes) keptUntil.remove(id)
            else if (failed && count <= 0) keptUntil[id] = System.nanoTime() + keepAfterFailingMs * 1_000_000
            wait = keptFor(id)
            if (count > 0 || (wait > 0 && id in lost)) null else lost.remove(id)
        }
        if (forget != null) {
            log.info("{} {}: lost by the network search, and no room reaches it any more: removed", kind, name(id))
            forget()
        } else if (wait > 0 && synchronized(reached) { id in lost }) {
            forgetLater(id, wait)
        }
    }

    /** Forgets lost device [id] in [ms], unless a room reaches it again or the search finds it by then. */
    private fun forgetLater(id: String, ms: Long) {
        scope.launch {
            var wait = ms
            var forget: (() -> Unit)? = null
            while (forget == null) {
                delay(wait.coerceAtLeast(1))
                forget = synchronized(reached) {
                    if (id !in lost || (reached[id] ?: 0) > 0) return@launch
                    wait = keptFor(id)
                    if (wait > 0) null else lost.remove(id)
                }
            }
            log.info("{} {}: lost by the network search, and it didn't answer again in time: removed", kind, name(id))
            forget()
        }
    }

    final override suspend fun drive(room: OutputRoom) {
        val changed = Channel<Unit>(Channel.CONFLATED)
        room.addChangeListener { changed.trySend(Unit) }
        val sessions = HashMap<String, DeviceSession>()
        // Devices this room has played on: when another app runs there later, it took them over.
        val playedOn = HashSet<String>()
        // Devices that can't play an episode at the room's speed: they sit it out (paused).
        val sittingOut = HashSet<String>()
        // Devices whose session here synced fine last time.
        val reachedHere = HashSet<String>()
        fun unreached(id: String, failed: Boolean = false) {
            if (reachedHere.remove(id)) reaches(id, false, failed)
        }
        try {
            while (true) {
                // React to changes right away, and check the devices regularly anyway.
                withTimeoutOrNull(SYNC_INTERVAL_MS) { changed.receive() }
                val view = room.view()
                for (id in sessions.keys - view.activeOutputs.keys) {
                    sessions.remove(id)?.stop()
                    unreached(id)
                }
                // Switched off here: turning it on again takes it, also from another app.
                playedOn.retainAll(view.activeOutputs.keys)
                for ((id, volume) in view.activeOutputs) {
                    if (!has(id)) continue
                    val session = sessions.getOrPut(id) { session(id, resuming = id in playedOn) }
                    if (view.speed != 1.0 && !session.supportsSpeed) {
                        if (sittingOut.add(id)) {
                            runCatching { session.stop() }
                            room.notice("${name(id)} can't play faster or slower, so it sits out this episode")
                        }
                        // A new song doesn't wait for it.
                        if (view.starting) view.current?.let { room.outputReady(id, it.itemId) }
                        continue
                    }
                    sittingOut -= id
                    try {
                        session.sync(view, volume, room, id)
                        if (session.loadedSomething) playedOn += id
                        if (reachedHere.add(id)) reaches(id, true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutputTakenOver) {
                        // Leave it be: the other app is playing.
                        log.info("{} {}: {}", kind, name(id), e.message)
                        session.takenOver()
                        sessions.remove(id)
                        unreached(id)
                        playedOn -= id
                        room.outputTakenOver(id)
                    } catch (e: Exception) {
                        log.warn("{} {} failed: {}", kind, name(id), e.message)
                        if (session.failed()) sessions.remove(id)
                        unreached(id, failed = true)
                        // A new song doesn't wait for it.
                        if (view.starting) view.current?.let { room.outputReady(id, it.itemId) }
                    }
                }
            }
        } finally {
            sessions.values.forEach { it.end() }
            reachedHere.toList().forEach(::unreached)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DeviceDriver::class.java)
        const val SYNC_INTERVAL_MS = 2_000L
        /** A phone's Wi-Fi drop: about a minute in the logs, sometimes a bit more. */
        const val KEEP_AFTER_FAILING_MS = 150_000L
    }
}

/** All of a host's [OutputDriver]s as one: their devices together, and playing every room. */
class OutputDrivers(scope: CoroutineScope, val all: List<OutputDriver>) {
    val devices: StateFlow<List<OutputDevice>> =
        combine(all.map { it.devices }) { lists -> lists.flatMap { it } }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun attach(rooms: RoomManager) = all.forEach { it.attach(rooms) }
}
