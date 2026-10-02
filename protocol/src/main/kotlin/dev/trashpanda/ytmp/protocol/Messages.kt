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

    /** Jump to an item in the queue or the history. */
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
