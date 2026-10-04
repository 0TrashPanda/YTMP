package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Your rooms on all your hosts (docs/features/room-sync.md). A room lives on one host at a
// time (its home); the account's YTMP server keeps track of where, and keeps a copy of rooms
// that live elsewhere (a phone), so they can move: opened on another host, the room continues
// there and its old home closes it.

/** What happens when you open one of your rooms on a host where it doesn't live. */
@Serializable
@SerialName("RoomOpenMode")
enum class RoomOpenMode {
    /** Ask: open it where it lives, or move it here. */
    @SerialName("ask") ASK,

    /** Always move it here. */
    @SerialName("move") MOVE,

    /** Open it where it lives if that host was seen recently; otherwise move it here. */
    @SerialName("move_if_away") MOVE_IF_AWAY,
}

/** Where a room lives. */
@Serializable
@SerialName("RoomHome")
data class RoomHome(
    val kind: HostKind,
    /** "server", or the phone's own ID. */
    val hostId: String,
    /** e.g. "Pixel 7", or the server's name. */
    val hostName: String,
    /** Where to open it, e.g. `http://192.168.1.23:8765`; null when unknown (offline). */
    val url: String?,
)

/** One of your rooms, on any of your hosts. */
@Serializable
@SerialName("SyncedRoom")
data class SyncedRoom(
    val syncId: String,
    /** Its code on its home host. */
    val code: String,
    val name: String,
    val visibility: RoomVisibility,
    val home: RoomHome,
    /** Goes up every time the room moves; the highest one is the real room. */
    val epoch: Int,
    /** When its home last checked in (ms since epoch). */
    val lastSeen: Long,
    val playing: Boolean,
    /** The song it's on, if any. */
    val nowPlaying: String?,
    /** The room's own setting for opening it elsewhere; null: your account's. */
    val openElsewhere: RoomOpenMode?,
)

/** `GET /api/rooms/elsewhere` (and `/api/auth/rooms` for hosts): your rooms on other hosts. */
@Serializable
@SerialName("SyncedRoomsResponse")
data class SyncedRoomsResponse(
    /** Your account's setting. */
    val openElsewhere: RoomOpenMode,
    val rooms: List<SyncedRoom>,
)

/** `POST /api/rooms/elsewhere/{syncId}/move`: the room's code here, after it moved here. */
@Serializable
@SerialName("MovedRoomResponse")
data class MovedRoomResponse(val code: String)

// --- between a host and the account's server (with a host token of the room's owner) ---

/** `PUT /api/auth/rooms/{syncId}`: the latest state of a room that lives on this host. */
@Serializable
@SerialName("RoomCopyRequest")
data class RoomCopyRequest(
    val home: RoomHome,
    val epoch: Int,
    /** The room, as the host saves it (JSON). */
    val room: String,
)

/** A host's rooms that moved away; it closes them (`409` to [RoomCopyRequest], or in [RoomHeartbeatResponse]). */
@Serializable
@SerialName("RoomMovedAway")
data class RoomMovedAway(
    val syncId: String,
    /** Where it lives now, to send people there, e.g. `https://ytmp.example.com/room/ABCD`. */
    val url: String?,
)

/** `POST /api/auth/rooms/heartbeat`: this host is up, with these rooms. */
@Serializable
@SerialName("RoomHeartbeatRequest")
data class RoomHeartbeatRequest(val home: RoomHome, val rooms: Map<String, Int>)

@Serializable
@SerialName("RoomHeartbeatResponse")
data class RoomHeartbeatResponse(val moved: List<RoomMovedAway>)

/** `POST /api/auth/rooms/{syncId}/take`: move a room to [home]. Answers with a [RoomSnapshot]. */
@Serializable
@SerialName("TakeRoomRequest")
data class TakeRoomRequest(val home: RoomHome)

/** A room's state, to continue it on another host, with the epoch it gets there. */
@Serializable
@SerialName("RoomSnapshot")
data class RoomSnapshot(val epoch: Int, val room: String)
