package dev.trashpanda.ytmp.core

import kotlinx.coroutines.CoroutineScope
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomVisibility
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** How room codes look. See docs/features/joining.md. */
enum class RoomCodeStyle(val alphabet: String) {
    LETTERS("ACDEFGHJKMNPQRTUVWXY"),
    DIGITS("0123456789"),
    ALNUM("ACDEFGHJKMNPQRTUVWXY34679"),
}

data class RoomTimeouts(
    /** A disconnected guest keeps their name and songs this long. */
    val participantOffline: Duration = 15.minutes,
    /** A room with nobody connected is deleted after this long. */
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
) {
    private val rooms = ConcurrentHashMap<String, Room>()

    init {
        scope.launch {
            outputs.collect { devices -> for (room in rooms.values) room.setAvailableOutputs(devices) }
        }
    }
    private val random = SecureRandom()
    private val _list = MutableStateFlow<List<RoomInfo>>(emptyList())

    /** All rooms, updated when rooms are created, deleted or change visibility. */
    val list: StateFlow<List<RoomInfo>> = _list

    fun create(name: String, visibility: RoomVisibility = RoomVisibility.PUBLIC): Room {
        while (true) {
            val code = newCode()
            val room = Room(code, name, Ids.token(), visibility, streams, scope, onInfoChanged = ::refreshList, clock = clock)
            if (rooms.putIfAbsent(code, room) == null) {
                scope.launch { room.setAvailableOutputs(outputs.value) }
                refreshList()
                return room
            }
        }
    }

    operator fun get(code: String): Room? = rooms[normalize(code)]

    /** All rooms right now. */
    fun all(): List<Room> = rooms.values.toList()

    val count: Int get() = rooms.size

    /** Closes and removes a room right away. */
    fun close(code: String) {
        rooms.remove(normalize(code))?.close()
        refreshList()
    }

    private fun refreshList() {
        _list.value = rooms.values.map { it.info }.sortedBy { it.name }
    }

    /** Periodically drops offline participants and deletes rooms that have been empty too long. */
    fun startCleanup() = scope.launch {
        while (isActive) {
            delay(timeouts.checkInterval)
            for (room in rooms.values) {
                val nobodyOnline = room.cleanup(timeouts.participantOffline.inWholeMilliseconds)
                if (nobodyOnline && clock() - room.lastActive > timeouts.emptyRoom.inWholeMilliseconds) {
                    rooms.remove(room.code, room)
                    room.close()
                    refreshList()
                }
            }
        }
    }

    private fun newCode() = buildString {
        repeat(codeLength) { append(codeStyle.alphabet[random.nextInt(codeStyle.alphabet.length)]) }
    }

    private fun normalize(code: String) = code.trim().uppercase()
}
