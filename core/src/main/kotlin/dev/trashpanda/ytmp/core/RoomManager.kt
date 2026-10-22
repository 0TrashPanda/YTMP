package dev.trashpanda.ytmp.core

import kotlinx.coroutines.CoroutineScope
import dev.trashpanda.ytmp.protocol.ROOM_CODE
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.RoomClone
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
/** Another room has the code someone chose. */
class RoomCodeTaken(val code: String) : Exception("The code $code is taken")

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
    /**
     * A party room where nobody who runs it (owner, top role) has been connected this long
     * tells everyone they can clone it. Null: never (phones: the room goes when the phone does).
     */
    val adminless: Duration? = null,
    val checkInterval: Duration = 1.minutes,
)

class RoomManager(
    private val streams: StreamResolver,
    private val scope: CoroutineScope,
    codeStyle: RoomCodeStyle = RoomCodeStyle.LETTERS,
    codeLength: Int = 4,
    private val timeouts: RoomTimeouts = RoomTimeouts(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Speakers and TVs the host found; every room can play on them. */
    private val outputs: StateFlow<List<OutputDevice>> = MutableStateFlow(emptyList()),
    /** Where rooms are kept across restarts, or null to keep them in memory only. */
    private val store: RoomStore? = null,
    /** Store calls run here, one at a time and in order. */
    private val storeContext: CoroutineContext = Dispatchers.IO.limitedParallelism(1),
    /** Where rooms' radios come from (Start radio, autoplay). Null: no radio. */
    private val radio: RoomRadio? = null,
    /** What the host hears about its rooms. */
    private val hooks: RoomHooks = RoomHooks(),
    /** Where people are in podcast episodes, on their accounts (resume in any room). */
    private val bookmarks: EpisodeBookmarks? = null,
) {
    /** What new rooms' codes are made of; can change while running (a server's admin page). */
    @Volatile
    var codeStyle: RoomCodeStyle = codeStyle

    /** How long new rooms' codes are. */
    @Volatile
    var codeLength: Int = codeLength

    private val rooms = ConcurrentHashMap<String, Room>()
    /** Rooms by the codes they had before ([Room.formerCodes]): links and QR codes with them still work. */
    private val former = ConcurrentHashMap<String, Room>()
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
            for (s in saved) {
                lateinit var room: Room
                room = Room.restore(
                    s, streams, scope, onInfoChanged = { roomChanged(room.code) }, onOwnerChanged = ::disowned, clock = clock,
                    radio = radio?.forRoom { room.ownerAccount }, bookmarks = bookmarks,
                )
                add(room)
            }
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
    fun create(
        name: String,
        visibility: RoomVisibility = RoomVisibility.PUBLIC,
        ownerAccount: String? = null,
        template: RoleTemplate? = null,
        /** Start as a copy of another room. */
        clone: RoomClone? = null,
        /** Joining needs this password (hashed: [RoomPasswords.hash]). */
        passwordHash: String? = null,
        /** A code of the owner's own choosing ([ROOM_CODE]); null: a random one. Throws [RoomCodeTaken] when it's in use. */
        chosenCode: String? = null,
    ): Room {
        val chosen = chosenCode?.let(::normalize)
        require(chosen == null || ROOM_CODE.matches(chosen)) { "Room codes are 3 to 12 letters and digits" }
        // A room that moved away keeps sending people after it by its code, and a room still
        // takes people in by its earlier codes.
        if (chosen != null && (chosen in moved || former.containsKey(chosen))) throw RoomCodeTaken(chosen)
        while (true) {
            val code = chosen ?: newCode().takeIf { !former.containsKey(it) && it !in moved } ?: continue
            lateinit var room: Room
            room = Room(
                code, name, Ids.token(), visibility, streams, scope, ownerAccount, template ?: DefaultRoles.template, radio?.forRoom { room.ownerAccount },
                onInfoChanged = { roomChanged(room.code) }, onOwnerChanged = ::disowned, bookmarks = bookmarks, clock = clock,
            )
            if (clone != null) room.startFrom(clone)
            if (passwordHash != null) room.startWithPassword(passwordHash)
            if (add(room)) {
                roomChanged(code)
                return room
            }
            if (chosen != null) throw RoomCodeTaken(chosen)
        }
    }

    private fun add(room: Room): Boolean {
        if (rooms.putIfAbsent(room.code, room) != null) return false
        for (old in room.formerCodes) if (!rooms.containsKey(old)) former.putIfAbsent(old, room)
        room.claimCode = { code -> claim(room, code) }
        room.addChangeListener { roomChanged(room.code) }
        room.onPlayFinished = hooks.onPlayFinished
        room.onSongStarted = hooks.onSongStarted
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

    /** The room with this code, or that had it before. */
    operator fun get(code: String): Room? = normalize(code).let { rooms[it] ?: former[it] }

    /**
     * [room] gets the code [code] ([Command.SetRoomCode]); its current one keeps leading to it.
     * Throws [RoomCodeTaken] when another room has it, had it, or moved away from it.
     */
    @Synchronized
    private fun claim(room: Room, code: String) {
        if (code in moved || former[code].let { it != null && it !== room }) throw RoomCodeTaken(code)
        if (rooms.putIfAbsent(code, room) != null) throw RoomCodeTaken(code)
        val old = room.code
        rooms.remove(old, room)
        former.remove(code)
        former[old] = room
        // Saved under its new code once it says it changed (onInfoChanged).
        if (store != null) scope.launch(storeContext) { runCatching { store.delete(old) }.onFailure { log.warn("Couldn't delete room {}", old, it) } }
    }

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
            lateinit var room: Room
            room = Room.restore(
                state, streams, scope, onInfoChanged = { roomChanged(room.code) }, onOwnerChanged = ::disowned, radio = radio?.forRoom { room.ownerAccount },
                bookmarks = bookmarks, clock = clock, code = code, epoch = epoch,
            )
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
        former.values.removeIf { it === room }
        if (closedHere && room.syncId != null) hooks.onRoomClosed(room)
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
            if (saved.syncId != null) hooks.onRoomSaved(saved)
        }
    }

    private fun disowned(oldAccount: String?, oldSyncId: String?) {
        if (oldAccount != null && oldSyncId != null) hooks.onRoomDisowned(oldAccount, oldSyncId)
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
                timeouts.adminless?.let { room.checkAdminless(it.inWholeMilliseconds) }
                val expired = when {
                    room.alwaysOn -> false
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
