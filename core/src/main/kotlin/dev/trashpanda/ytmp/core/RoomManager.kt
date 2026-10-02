package dev.trashpanda.ytmp.core

import kotlinx.coroutines.CoroutineScope
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How room codes look. See docs/features/joining.md. */
enum class RoomCodeStyle(val alphabet: String) {
    LETTERS("ACDEFGHJKMNPQRTUVWXY"),
    DIGITS("0123456789"),
    ALNUM("ACDEFGHJKMNPQRTUVWXY34679"),
}

data class RoomTimeouts(
    /** A disconnected guest keeps their name and songs this long. */
    val participantOffline: Duration = 15.minutes,
    /** A public room with nobody connected is deleted after this long. Solo rooms stay until closed. */
    val emptyRoom: Duration = 1.hours,
    val checkInterval: Duration = 1.minutes,
)

class RoomManager(
    private val streams: StreamResolver,
    private val scope: CoroutineScope,
    private val codeStyle: RoomCodeStyle = RoomCodeStyle.LETTERS,
    private val codeLength: Int = 4,
    private val timeouts: RoomTimeouts = RoomTimeouts(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Speakers and TVs the host found; every room can play on them. */
    private val outputs: StateFlow<List<OutputDevice>> = MutableStateFlow(emptyList()),
    /** Where rooms are kept across restarts, or null to keep them in memory only. */
    private val store: RoomStore? = null,
    /** Store calls run here, one at a time and in order. */
    private val storeContext: CoroutineContext = Dispatchers.IO.limitedParallelism(1),
) {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val random = SecureRandom()
    private val _list = MutableStateFlow<List<RoomInfo>>(emptyList())
    private val _hosting = MutableStateFlow<List<RoomInfo>>(emptyList())

    private val unsaved = ConcurrentHashMap.newKeySet<String>()
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)

    /** All rooms, updated when rooms are created, deleted or change visibility. */
    val list: StateFlow<List<RoomInfo>> = _list

    /** Rooms that need the host to stay reachable: public ones (friends may join) and ones playing music. */
    val hosting: StateFlow<List<RoomInfo>> = _hosting

    init {
        val store = store
        if (store != null) {
            val saved = runCatching { store.loadAll() }.onFailure { log.error("Couldn't load saved rooms", it) }.getOrDefault(emptyList())
            for (s in saved) add(Room.restore(s, streams, scope, onInfoChanged = { roomChanged(s.code) }, clock = clock))
            if (saved.isNotEmpty()) log.info("Restored {} room(s)", saved.size)
            refreshList()
            scope.launch(storeContext) { saveLoop(store) }
        }
        scope.launch {
            outputs.collect { devices -> for (room in rooms.values) room.setAvailableOutputs(devices) }
        }
    }

    /** Creates a room. [ownerAccount] (`username@issuer`) is the owner on any device it logs in from. */
    fun create(name: String, visibility: RoomVisibility = RoomVisibility.PUBLIC, ownerAccount: String? = null): Room {
        while (true) {
            val code = newCode()
            val room = Room(code, name, Ids.token(), visibility, streams, scope, onInfoChanged = { roomChanged(code) }, clock = clock, ownerAccount = ownerAccount)
            if (add(room)) {
                roomChanged(code)
                return room
            }
        }
    }

    private fun add(room: Room): Boolean {
        if (rooms.putIfAbsent(room.code, room) != null) return false
        room.addChangeListener { roomChanged(room.code) }
        scope.launch { room.setAvailableOutputs(outputs.value) }
        return true
    }

    private fun roomChanged(code: String) {
        refreshList()
        if (store != null) {
            unsaved += code
            saveSignal.trySend(Unit)
        }
    }

    operator fun get(code: String): Room? = rooms[normalize(code)]

    /** All rooms right now. */
    fun all(): List<Room> = rooms.values.toList()

    val count: Int get() = rooms.size

    /** Closes and removes a room right away. */
    fun close(code: String) {
        rooms.remove(normalize(code))?.let(::remove)
    }

    private fun remove(room: Room) {
        room.close()
        refreshList()
        if (store != null) scope.launch(storeContext) { runCatching { store.delete(room.code) }.onFailure { log.warn("Couldn't delete room {}", room.code, it) } }
    }

    /** Writes every room to the store now, e.g. before the host shuts down. */
    suspend fun saveAll() {
        if (store == null) return
        unsaved += rooms.keys
        withContext(storeContext) { saveUnsaved(store) }
    }

    /** Saves changed rooms shortly after they change, and playing rooms now and then (for the position). */
    private suspend fun saveLoop(store: RoomStore) {
        while (true) {
            val anyPlaying = rooms.values.any { it.isPlaying }
            val signalled = if (anyPlaying) withTimeoutOrNull(POSITION_SAVE_INTERVAL) { saveSignal.receive() } else saveSignal.receive()
            if (signalled == null) unsaved += rooms.values.filter { it.isPlaying }.map { it.code }
            // Changes come in bursts (skip = several events); write once.
            delay(SAVE_DELAY)
            saveUnsaved(store)
        }
    }

    private suspend fun saveUnsaved(store: RoomStore) {
        for (code in unsaved.toList()) {
            unsaved -= code
            val room = rooms[code] ?: continue
            val saved = room.save()
            // Closed while saving: don't bring it back. (Deletes run after this, on the same context.)
            if (rooms[code] !== room) continue
            try {
                store.save(saved)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("Couldn't save room {}", code, e)
            }
        }
    }

    private fun refreshList() {
        val all = rooms.values.sortedBy { it.name }
        _list.value = all.map { it.info }
        _hosting.value = all.filter { it.visibility == RoomVisibility.PUBLIC || it.isPlaying }.map { it.info }
    }

    /** Periodically drops offline participants and deletes public rooms that have been empty too long. */
    fun startCleanup() = scope.launch {
        while (isActive) {
            delay(timeouts.checkInterval)
            for (room in rooms.values) {
                val nobodyOnline = room.cleanup(timeouts.participantOffline.inWholeMilliseconds)
                val expired = room.visibility == RoomVisibility.PUBLIC && clock() - room.lastActive > timeouts.emptyRoom.inWholeMilliseconds
                if (nobodyOnline && expired && rooms.remove(room.code, room)) remove(room)
            }
        }
    }

    private fun newCode() = buildString {
        repeat(codeLength) { append(codeStyle.alphabet[random.nextInt(codeStyle.alphabet.length)]) }
    }

    private fun normalize(code: String) = code.trim().uppercase()

    private companion object {
        val log = LoggerFactory.getLogger(RoomManager::class.java)
        val SAVE_DELAY = 1.seconds
        val POSITION_SAVE_INTERVAL = 15.seconds
    }
}
