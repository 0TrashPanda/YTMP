package dev.trashpanda.ytmp.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
) {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val random = SecureRandom()

    fun create(name: String): Room {
        while (true) {
            val code = newCode()
            val room = Room(code, name, Ids.token(), streams, scope, clock)
            if (rooms.putIfAbsent(code, room) == null) return room
        }
    }

    operator fun get(code: String): Room? = rooms[normalize(code)]

    val count: Int get() = rooms.size

    /** Periodically drops offline participants and deletes rooms that have been empty too long. */
    fun startCleanup() = scope.launch {
        while (isActive) {
            delay(timeouts.checkInterval)
            for (room in rooms.values) {
                val nobodyOnline = room.cleanup(timeouts.participantOffline.inWholeMilliseconds)
                if (nobodyOnline && clock() - room.lastActive > timeouts.emptyRoom.inWholeMilliseconds) {
                    rooms.remove(room.code, room)
                    room.close()
                }
            }
        }
    }

    private fun newCode() = buildString {
        repeat(codeLength) { append(codeStyle.alphabet[random.nextInt(codeStyle.alphabet.length)]) }
    }

    private fun normalize(code: String) = code.trim().uppercase()
}
