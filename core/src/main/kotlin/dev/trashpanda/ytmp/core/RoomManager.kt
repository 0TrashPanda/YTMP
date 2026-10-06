package dev.trashpanda.ytmp.core

import kotlinx.coroutines.CoroutineScope
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.Song
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
    /**
     * A solo room without an owner account (a guest's, on a server), with nobody connected, is
     * deleted after this long. Null: kept until closed (phones; account owners can always find theirs).
     */
    val guestSoloRoom: Duration? = null,
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
    /** Songs that finished playing, for the listening history. Must not block. */
    private val onPlayFinished: (FinishedPlay) -> Unit = {},
    /** YTM's radio, for Start radio and autoplay. */
    private val radio: RadioSource? = null,
    /**
     * The room owner's own radio (their YouTube Music account), by owner account (null: a room
     * without one), when they have one and want it ([PersonalCatalog.personalized]). Null: the shared [radio].
     */
    private val ownerRadio: suspend (ownerAccount: String?, seedSongId: String) -> List<Song>? = { _, _ -> null },
    /** A room with an owner account was saved: its copy elsewhere can be updated (see [Room.syncId]). Must not block. */
    private val onRoomSaved: (SavedRoom) -> Unit = {},
    /** A room with an owner account was closed or expired here (not moved away). Must not block. */
    private val onRoomClosed: (Room) -> Unit = {},
) {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val random = SecureRandom()
    private val _list = MutableStateFlow<List<RoomInfo>>(emptyList())
    private val _hosting = MutableStateFlow<List<RoomInfo>>(emptyList())
    private val _playing = MutableStateFlow<List<RoomInfo>>(emptyList())

    private val unsaved = ConcurrentHashMap.newKeySet<String>()
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)

    /** All rooms, updated when rooms are created, deleted or change visibility. */
    val list: StateFlow<List<RoomInfo>> = _list

    /** Rooms that need the host to stay reachable: public ones (friends may join) and ones playing music. */
    val hosting: StateFlow<List<RoomInfo>> = _hosting

    /** Rooms playing music right now. */
    val playing: StateFlow<List<RoomInfo>> = _playing

    init {
        val store = store
        if (store != null) {
            val saved = runCatching { store.loadAll() }.onFailure { log.error("Couldn't load saved rooms", it) }.getOrDefault(emptyList())
            for (s in saved) add(Room.restore(s, streams, scope, onInfoChanged = { roomChanged(s.code) }, clock = clock, radio = radioFor(s.ownerAccount)))
            if (saved.isNotEmpty()) log.info("Restored {} room(s)", saved.size)
            refreshList()
            scope.launch(storeContext) { saveLoop(store) }
        }
        scope.launch {
            outputs.collect { devices -> for (room in rooms.values) room.setAvailableOutputs(devices) }
        }
    }

    /**
     * Creates a room. [ownerAccount] (`username@issuer`) is the owner on any device it logs in
     * from; [template] is their role template (default roles if null).
     */
    fun create(name: String, visibility: RoomVisibility = RoomVisibility.PUBLIC, ownerAccount: String? = null, template: RoleTemplate? = null): Room {
        while (true) {
            val code = newCode()
            val room = Room(
                code, name, Ids.token(), visibility, streams, scope, ownerAccount, template ?: DefaultRoles.template, radioFor(ownerAccount),
                onInfoChanged = { roomChanged(code) }, clock = clock,
            )
            if (add(room)) {
                roomChanged(code)
                return room
            }
        }
    }

    private fun add(room: Room): Boolean {
        if (rooms.putIfAbsent(room.code, room) != null) return false
        room.addChangeListener { roomChanged(room.code) }
        room.onPlayFinished = onPlayFinished
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

    /** The room with this [Room.syncId], if it lives here. */
    fun bySyncId(syncId: String): Room? = rooms.values.firstOrNull { it.syncId == syncId }

    /**
     * A room that moved here from another host: it continues from [saved] (where a playing
     * room would be by now, but paused) with [epoch], under its old code if that's free here.
     */
    fun adopt(saved: SavedRoom, epoch: Int): Room {
        val position = if (saved.playing && saved.savedAt > 0) saved.positionMs + (clock() - saved.savedAt) else saved.positionMs
        val length = saved.current?.song?.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val state = saved.copy(positionMs = position.coerceIn(0, length), lastActive = clock())
        var code = normalize(saved.code)
        while (true) {
            val room = Room.restore(state, streams, scope, onInfoChanged = { roomChanged(code) }, radio = radioFor(state.ownerAccount), clock = clock, code = code, epoch = epoch)
            if (add(room)) {
                moved.remove(code)
                roomChanged(code)
                log.info("Room {} ({}) moved here", code, room.name)
                return room
            }
            code = newCode()
        }
    }

    /** The room moved to another host: everyone in it is sent to [url], and it's gone from here. */
    suspend fun movedAway(code: String, url: String?) {
        val room = rooms[normalize(code)] ?: return
        room.movedAway(url)
        if (!rooms.remove(room.code, room)) return
        moved[room.code] = url.orEmpty()
        while (moved.size > MAX_MOVED) moved.remove(moved.keys.first())
        remove(room, closedHere = false)
        log.info("Room {} ({}) moved to {}", room.code, room.name, url ?: "another host")
    }

    /**
     * Where a room that moved away went (for people who still have its code): its link, ""
     * when that isn't known, or null when no room with this code moved away.
     */
    fun movedTo(code: String): String? = moved[normalize(code)]

    /** Codes of rooms that moved away, and where to (oldest first). */
    private val moved = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())

    private fun remove(room: Room, closedHere: Boolean = true) {
        if (closedHere && room.syncId != null) onRoomClosed(room)
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
            if (saved.syncId != null) onRoomSaved(saved)
        }
    }

    /** A room's radio: its owner's own when there is one ([ownerRadio]), else (or when that fails) the shared one. */
    private fun radioFor(ownerAccount: String?): RadioSource? {
        val shared = radio ?: return null
        return RadioSource { seed ->
            try {
                ownerRadio(ownerAccount, seed)?.let { return@RadioSource it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("The owner's radio didn't work, using the shared one: {}", e.message)
            }
            shared.radio(seed)
        }
    }

    private fun refreshList() {
        val all = rooms.values.sortedBy { it.name }
        _list.value = all.map { it.info }
        _hosting.value = all.filter { it.visibility == RoomVisibility.PUBLIC || it.isPlaying }.map { it.info }
        _playing.value = all.filter { it.isPlaying }.map { it.info }
    }

    /** Periodically drops offline participants and deletes rooms that have been empty too long. */
    fun startCleanup() = scope.launch {
        while (isActive) {
            delay(timeouts.checkInterval)
            for (room in rooms.values) {
                val nobodyOnline = room.cleanup(timeouts.participantOffline.inWholeMilliseconds)
                val idle = clock() - room.lastActive
                val expired = when {
                    room.visibility == RoomVisibility.PUBLIC -> idle > timeouts.emptyRoom.inWholeMilliseconds
                    room.ownerAccount == null -> timeouts.guestSoloRoom?.let { idle > it.inWholeMilliseconds } ?: false
                    else -> false
                }
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
        const val MAX_MOVED = 200
    }
}
