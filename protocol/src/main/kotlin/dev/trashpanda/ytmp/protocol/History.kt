package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Listening history, see docs/features/history-and-recommendations.md. Stored on the
// account's auth server; hosts report plays there.

/** Someone you listened with. Guests by their name only; people who opted out are left out. */
@Serializable
@SerialName("ListenedWith")
data class ListenedWith(val accountId: String?, val name: String)

/** `POST /api/auth/plays` (with the account's host token): a song that finished playing in a room. */
@Serializable
@SerialName("PlayReport")
data class PlayReport(
    val song: Song,
    /** When it started, ms since the epoch. */
    val playedAt: Long,
    /** How much of it was heard, in ms. */
    val heardMs: Long,
    val skipped: Boolean,
    val roomCode: String,
    val roomName: String,
    val addedByMe: Boolean,
    val addedByName: String,
    val listenedWith: List<ListenedWith>,
    /** Others were in the room (also those who opted out of being named). */
    val shared: Boolean,
)

/** One entry of your history. */
@Serializable
@SerialName("HistoryEntry")
data class HistoryEntry(
    val id: String,
    val song: Song,
    val playedAt: Long,
    val heardMs: Long,
    val skipped: Boolean,
    val roomName: String,
    val addedByMe: Boolean,
    val addedByName: String,
    val listenedWith: List<ListenedWith>,
    val shared: Boolean,
)

/**
 * `GET /api/account/history?before=&limit=&q=`, newest first. Also for the pages of your
 * phone's app and of rooms on other hosts (see docs/implementation/auth.md#your-history-on-other-pages).
 */
@Serializable
@SerialName("HistoryPage")
data class HistoryPage(val plays: List<HistoryEntry>, val more: Boolean)

/** `GET`/`PUT /api/account/settings` */
@Serializable
@SerialName("AccountSettings")
data class AccountSettings(
    /** Keep a listening history. */
    val tracking: Boolean,
    /** Don't name me in other people's history. Applies to rooms you join after changing it. */
    val hideFromOthers: Boolean,
    /** Opening one of your rooms on a host where it doesn't live (each room can override it). */
    val openElsewhere: RoomOpenMode = RoomOpenMode.ASK,
    /** Rooms you create start with your default password (set with `PUT /api/account/room-password`). */
    val hasRoomPassword: Boolean = false,
    /** Which of your listening radio and Find similar learn from (the song graph). */
    val historyRadio: HistoryRadio = HistoryRadio.ALL,
)

/** Which of your listening the song graph uses for your radio. */
@Serializable
@SerialName("HistoryRadio")
enum class HistoryRadio {
    /** Not your own listening (what everyone on the server listens to still counts). */
    @SerialName("off") OFF,

    /** Only your solo rooms. */
    @SerialName("solo") SOLO,

    /** Solo rooms and parties (parties count a bit less). */
    @SerialName("all") ALL,
}

/** `PUT /api/account/room-password`: your default room password, or none (null). */
@Serializable
@SerialName("RoomPasswordRequest")
data class RoomPasswordRequest(val password: String?)
