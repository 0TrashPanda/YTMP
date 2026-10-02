package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

// Wire format, see docs/implementation/protocol-messages.md.
// Messages use "type" (lowercase names), commands and events use "kind".

/** Client → host. */
@Serializable
sealed interface ClientMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocolVersion: Int,
        val roomCode: String,
        val guestName: String,
        /** Returned in [ServerMessage.Welcome]; lets a guest reconnect as the same participant. */
        val guestToken: String?,
        /** Proves the sender created the room. */
        val ownerToken: String?,
        /** Joins with an account: a host token from the account's auth server. */
        val accountToken: String? = null,
        /** Don't name me in other people's listening history. */
        val hideFromHistory: Boolean = false,
    ) : ClientMessage

    @Serializable
    @SerialName("command")
    data class CommandMessage(
        val id: String,
        val command: Command,
    ) : ClientMessage

    @Serializable
    @SerialName("ping")
    data class Ping(val clientTime: Long) : ClientMessage

    @Serializable
    @SerialName("request_snapshot")
    data object RequestSnapshot : ClientMessage
}

/** The lists a room's items live in. */
@Serializable
@SerialName("ItemList")
enum class ItemList {
    @SerialName("history") HISTORY,
    @SerialName("queue") QUEUE,
    @SerialName("autoplay") AUTOPLAY,
}

@Serializable
@SerialName("QueuePosition")
enum class QueuePosition {
    @SerialName("next") NEXT,
    @SerialName("end") END,
}

@Serializable
@JsonClassDiscriminator("kind")
sealed interface Command {
    @Serializable
    @SerialName("AddSongs")
    data class AddSongs(val songs: List<Song>, val position: QueuePosition) : Command

    @Serializable
    @SerialName("PlayNow")
    data class PlayNow(val song: Song) : Command

    @Serializable
    @SerialName("RemoveQueueItem")
    data class RemoveQueueItem(val itemId: String) : Command

    @Serializable
    @SerialName("MoveQueueItem")
    data class MoveQueueItem(val itemId: String, val toIndex: Int) : Command

    /**
     * Moves any item (history, queue or autoplay) to a place in any of those lists. A played
     * song moved into the queue plays again; an autoplay song moved into the queue becomes
     * yours. History indexes count from the oldest song in the snapshot.
     */
    @Serializable
    @SerialName("MoveItem")
    data class MoveItem(val itemId: String, val list: ItemList, val toIndex: Int) : Command

    /** Plays [song] now and replaces the queue with a radio from it. */
    @Serializable
    @SerialName("StartRadio")
    data class StartRadio(val song: Song) : Command

    /** Replaces the autoplay queue with a radio from [song], without touching the queue. */
    @Serializable
    @SerialName("AutoplayFromHere")
    data class AutoplayFromHere(val song: Song) : Command

    /** Jump to an item in the queue, the autoplay queue or the history. */
    @Serializable
    @SerialName("JumpTo")
    data class JumpTo(val itemId: String) : Command

    @Serializable
    @SerialName("Play")
    data object Play : Command

    @Serializable
    @SerialName("Pause")
    data object Pause : Command

    @Serializable
    @SerialName("Skip")
    data object Skip : Command

    @Serializable
    @SerialName("Previous")
    data object Previous : Command

    @Serializable
    @SerialName("Seek")
    data class Seek(val positionMs: Long) : Command

    @Serializable
    @SerialName("SetListening")
    data class SetListening(val on: Boolean) : Command

    /** Plays the room on a speaker or TV, or stops playing on it. */
    @Serializable
    @SerialName("SetOutput")
    data class SetOutput(val outputId: String, val active: Boolean) : Command

    @Serializable
    @SerialName("SetOutputVolume")
    data class SetOutputVolume(val outputId: String, val volume: Double) : Command

    /** Owner only. Makes a solo room public, or a public room solo again. */
    @Serializable
    @SerialName("SetVisibility")
    data class SetVisibility(val visibility: RoomVisibility) : Command

    // --- people & roles (see Roles.kt) ---

    /** Removes someone; they can rejoin right away. */
    @Serializable
    @SerialName("Kick")
    data class Kick(val participantId: String) : Command

    /** Removes someone for good (by account, or a guest by their guest token). */
    @Serializable
    @SerialName("Ban")
    data class Ban(val participantId: String) : Command

    @Serializable
    @SerialName("Unban")
    data class Unban(val banId: String) : Command

    @Serializable
    @SerialName("AssignRole")
    data class AssignRole(val participantId: String, val roleId: String) : Command

    /** Per-person overrides on top of their role. A permission in neither list follows the role. */
    @Serializable
    @SerialName("SetParticipantPermissions")
    data class SetParticipantPermissions(val participantId: String, val allow: List<Permission>, val deny: List<Permission>) : Command

    /** Adds a role at the bottom of the list. */
    @Serializable
    @SerialName("CreateRole")
    data class CreateRole(val name: String, val color: String, val permissions: List<Permission>) : Command

    /** Changes a role's name, color and permissions (matched by id). */
    @Serializable
    @SerialName("UpdateRole")
    data class UpdateRole(val role: Role) : Command

    /** Deletes a role; its people get the default role for their kind (guest or account). */
    @Serializable
    @SerialName("DeleteRole")
    data class DeleteRole(val roleId: String) : Command

    /** Moves a role in the ranking (0 = top). */
    @Serializable
    @SerialName("MoveRole")
    data class MoveRole(val roleId: String, val toIndex: Int) : Command

    /** Room name and settings. Null fields stay as they are. */
    @Serializable
    @SerialName("UpdateSettings")
    data class UpdateSettings(
        val name: String? = null,
        val defaultGuestRole: String? = null,
        val defaultAccountRole: String? = null,
        val autoplay: Boolean? = null,
    ) : Command
}

/** Host → client. */
@Serializable
sealed interface ServerMessage {
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val participantId: String,
        val guestToken: String,
        val seq: Long,
        val state: RoomState,
        /** The account the participant joined with, or null if they joined as a guest (also when the token wasn't accepted). */
        val accountId: String? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: RejectReason) : ServerMessage

    @Serializable
    @SerialName("result")
    data class Result(
        val id: String,
        val error: ErrorInfo?,
    ) : ServerMessage

    @Serializable
    @SerialName("event")
    data class EventMessage(
        val seq: Long,
        val event: Event,
    ) : ServerMessage

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val seq: Long,
        val state: RoomState,
    ) : ServerMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val clientTime: Long, val hostTime: Long) : ServerMessage
}

@Serializable
@SerialName("RejectReason")
enum class RejectReason {
    @SerialName("room_not_found") ROOM_NOT_FOUND,
    @SerialName("version_mismatch") VERSION_MISMATCH,
    @SerialName("invalid_name") INVALID_NAME,

    /** The same participant connected again from somewhere else. */
    @SerialName("replaced") REPLACED,

    /** A solo room; only the hosting device can join it. */
    @SerialName("private_room") PRIVATE_ROOM,

    /** Removed by an admin; joining again is allowed. */
    @SerialName("kicked") KICKED,

    @SerialName("banned") BANNED,
}

@Serializable
@SerialName("ErrorCode")
enum class ErrorCode {
    @SerialName("not_found") NOT_FOUND,
    @SerialName("invalid") INVALID,
    @SerialName("permission_denied") PERMISSION_DENIED,
}

@Serializable
@SerialName("ErrorInfo")
data class ErrorInfo(val code: ErrorCode, val message: String)

/**
 * Something that changed in the room. Events are small, so a client applies them one by
 * one, in [ServerMessage.EventMessage.seq] order.
 */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Event {
    @Serializable
    @SerialName("ParticipantJoined")
    data class ParticipantJoined(val participant: Participant) : Event

    @Serializable
    @SerialName("ParticipantUpdated")
    data class ParticipantUpdated(val participant: Participant) : Event

    @Serializable
    @SerialName("ParticipantLeft")
    data class ParticipantLeft(val participantId: String) : Event

    @Serializable
    @SerialName("QueueItemsAdded")
    data class QueueItemsAdded(val items: List<QueueItem>, val index: Int) : Event

    @Serializable
    @SerialName("QueueItemRemoved")
    data class QueueItemRemoved(val itemId: String) : Event

    @Serializable
    @SerialName("QueueItemMoved")
    data class QueueItemMoved(val itemId: String, val toIndex: Int) : Event

    @Serializable
    @SerialName("HistoryAppended")
    data class HistoryAppended(val item: QueueItem) : Event

    @Serializable
    @SerialName("HistoryItemRemoved")
    data class HistoryItemRemoved(val itemId: String) : Event

    /** A new current song (or none). Its stream URL follows in [StreamReady]. */
    @Serializable
    @SerialName("NowPlayingChanged")
    data class NowPlayingChanged(val item: QueueItem?) : Event

    @Serializable
    @SerialName("StreamReady")
    data class StreamReady(val itemId: String, val streamUrl: String) : Event

    @Serializable
    @SerialName("PlaybackChanged")
    data class PlaybackChanged(val playback: PlaybackStatus) : Event

    /** Outputs appeared, disappeared, were switched on or off, or changed volume. */
    @Serializable
    @SerialName("OutputsChanged")
    data class OutputsChanged(val outputs: List<OutputInfo>) : Event

    /** The room's name or visibility changed. */
    @Serializable
    @SerialName("RoomUpdated")
    data class RoomUpdated(val room: RoomInfo) : Event

    /** Roles were created, changed, deleted or reordered: the whole list. */
    /** The (visible part of the) history changed at once, e.g. a song was moved in or out of it. */
    @Serializable
    @SerialName("HistoryReplaced")
    data class HistoryReplaced(val items: List<QueueItem>) : Event

    /** The whole upcoming queue changed at once (Start radio). */
    @Serializable
    @SerialName("QueueReplaced")
    data class QueueReplaced(val items: List<QueueItem>) : Event

    @Serializable
    @SerialName("AutoplayChanged")
    data class AutoplayChanged(val seed: Song?, val items: List<QueueItem>) : Event

    @Serializable
    @SerialName("RolesChanged")
    data class RolesChanged(val roles: List<Role>) : Event

    @Serializable
    @SerialName("SettingsChanged")
    data class SettingsChanged(val settings: RoomSettings) : Event

    @Serializable
    @SerialName("BansChanged")
    data class BansChanged(val bans: List<BanInfo>) : Event

    /** Something went wrong that everyone should know about, e.g. a song that can't be played. */
    @Serializable
    @SerialName("Notice")
    data class Notice(val message: String) : Event
}

/** JSON settings shared by host and tests. Defaults are always encoded, so TS fields are never missing. */
val ProtocolJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}
