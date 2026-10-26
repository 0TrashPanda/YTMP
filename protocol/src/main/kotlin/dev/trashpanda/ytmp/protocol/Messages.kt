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
        /** Moved here from another room: the key from there, to get your role back ([Migrant]). */
        val migrationKey: String? = null,
        /** The room's password, when it has one ([RoomInfo.hasPassword]). */
        val password: String? = null,
        /** Which device this is ([Participant.device]), so you can tell your own devices apart. */
        val device: String? = null,
    ) : ClientMessage

    /**
     * Instead of [Hello]: a second connection for a participant who is already in the room
     * (by their [guestToken]). It gets the same welcome and events and may send commands and
     * pings as that participant, but doesn't replace their connection or change whether
     * they're online. The Android app's player uses it, because Android freezes the app's
     * web page after a few minutes in the background.
     */
    @Serializable
    @SerialName("attach")
    data class Attach(
        val protocolVersion: Int,
        val roomCode: String,
        val guestToken: String,
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

    /** Puts [songs] back in the queue at [index] (Undo after removing songs or clearing the queue). */
    @Serializable
    @SerialName("RestoreSongs")
    data class RestoreSongs(val songs: List<Song>, val index: Int) : Command

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

    /**
     * Clears the queue, and plays [song] and then its radio (the autoplay queue becomes the
     * radio). The song starts right away; the radio follows while it plays.
     */
    @Serializable
    @SerialName("StartRadio")
    data class StartRadio(
        val song: Song,
        /** The radio, when the one who starts it brings their own (their YouTube Music account); null: the room's. */
        val songs: List<Song>? = null,
        /**
         * The sender brings their own radio, but it isn't there yet: [song] starts now, and the
         * radio follows in another StartRadio for the same song (with [songs], or null when theirs
         * failed and the room's will do).
         */
        val follows: Boolean = false,
        /**
         * Also clear the queue's history (the songs above the current one), like YouTube Music.
         * Only when the sender may remove those songs (their own, or others'); otherwise it stays.
         */
        val clearHistory: Boolean = false,
        /** How the radio is made; null: the room's last. */
        val options: RadioOptions? = null,
    ) : Command

    /** Empties the upcoming queue. */
    @Serializable
    @SerialName("ClearQueue")
    data object ClearQueue : Command

    /** Empties the autoplay queue; it stays empty until something new is played or started. */
    @Serializable
    @SerialName("ClearAutoplay")
    data object ClearAutoplay : Command

    /** "Autoplay this": replaces the autoplay queue with a radio from [song], without touching the queue. */
    @Serializable
    @SerialName("AutoplayFromHere")
    data class AutoplayFromHere(
        val song: Song,
        /** The radio, when the one who starts it brings their own (their YouTube Music account); null: the room's. */
        val songs: List<Song>? = null,
        /** How the radio is made; null: the room's last. */
        val options: RadioOptions? = null,
    ) : Command

    /**
     * The *Radio from* chips in Up next: the autoplay queue's radio is made again with
     * [options] (the songs people added stay), and later radios use them too.
     */
    @Serializable
    @SerialName("SetRadioOptions")
    data class SetRadioOptions(val options: RadioOptions) : Command

    /**
     * "Add to autoplay" for a playlist: [songs] become the autoplay queue (they play when the
     * queue is empty, after what people added), and the radio goes on after the last one.
     */
    @Serializable
    @SerialName("AutoplaySongs")
    data class AutoplaySongs(val songs: List<Song>) : Command

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

    /** Next. [play]: start playing it, also when the room was paused (like YouTube Music's Next). */
    @Serializable
    @SerialName("Skip")
    data class Skip(val play: Boolean = false) : Command

    @Serializable
    @SerialName("Previous")
    data object Previous : Command

    @Serializable
    @SerialName("Seek")
    data class Seek(val positionMs: Long) : Command

    /**
     * The speed podcast episodes play at, for everyone in the room ([isEpisodeSpeed]: 0.5–2 in steps of 0.05);
     * music always plays at 1. Kept for the next episodes. Play/pause.
     */
    @Serializable
    @SerialName("SetSpeed")
    data class SetSpeed(val speed: Double) : Command

    /** Puts the upcoming songs in a random order (with fair order: keeps the rounds). Reorder. */
    @Serializable
    @SerialName("ShuffleQueue")
    data object ShuffleQueue : Command

    @Serializable
    @SerialName("SetRepeat")
    data class SetRepeat(val mode: RepeatMode) : Command

    /**
     * Votes to skip the current song, or takes the vote back. [Skip] from someone who may only
     * vote is a vote too.
     */
    @Serializable
    @SerialName("VoteSkip")
    data class VoteSkip(val on: Boolean) : Command

    /**
     * [startsTogether]: this player tells the host when it has loaded a new song
     * ([PlayerReady]), so the room waits for it before the song starts (see [PlaybackStatus.starting]).
     */
    @Serializable
    @SerialName("SetListening")
    data class SetListening(val on: Boolean, val startsTogether: Boolean = false) : Command

    /**
     * Plays the room on speakers near this participant's phone ([names], empty: none), which
     * the app drives itself: a room on another host can't reach them. Like [SetListening], it
     * keeps the music going and needs *play on your own device*.
     */
    @Serializable
    @SerialName("SetSpeakers")
    data class SetSpeakers(val names: List<String>, val startsTogether: Boolean = false) : Command

    /**
     * This participant's player ([speakers]: its speakers, all of them) has loaded the song
     * [itemId] and can start it. Only sent while the song is [PlaybackStatus.starting].
     */
    @Serializable
    @SerialName("PlayerReady")
    data class PlayerReady(val itemId: String, val speakers: Boolean = false) : Command

    /**
     * This participant's player ran out of data in the middle of [itemId], at [positionMs] of the
     * song. When it is the only player, the room waits there ([PlaybackStatus.starting]) until it
     * says [PlayerReady] again, so nothing of the song is skipped. With other players, nothing
     * happens: they go on and this one catches up.
     */
    @Serializable
    @SerialName("PlayerStalled")
    data class PlayerStalled(val itemId: String, val positionMs: Long) : Command

    /**
     * Plays the room on a speaker or TV, or stops playing on it. [screen]: a TV or display
     * with Chromecast shows the party screen too, which plays the music itself.
     */
    @Serializable
    @SerialName("SetOutput")
    data class SetOutput(val outputId: String, val active: Boolean, val screen: Boolean = false) : Command

    @Serializable
    @SerialName("SetOutputVolume")
    data class SetOutputVolume(val outputId: String, val volume: Double) : Command

    /**
     * The volume (0–1) of a device: your own ([participantId] null, or yours), which a device
     * says whenever its volume changes, or another device of yours (someone here with the same
     * account), which then turns its volume to it ([Participant.volumeChanges]).
     */
    @Serializable
    @SerialName("SetDeviceVolume")
    data class SetDeviceVolume(val volume: Double, val participantId: String? = null) : Command

    /**
     * This device says whether your other devices (the same account) may switch its "Play here"
     * on and off ([allowed], its own setting), and whether it can start sound now without a tap
     * ([canStart]: a browser only after a tap on its page). Shown as [Participant.remotePlay],
     * [Participant.canStartSound].
     */
    @Serializable
    @SerialName("SetRemotePlay")
    data class SetRemotePlay(val allowed: Boolean, val canStart: Boolean) : Command

    /**
     * Switches "Play here" on or off on another device of yours (the same account) that allows
     * it ([Participant.remotePlay]); on only when it can start sound ([Participant.canStartSound]).
     * The device does it itself when [Participant.listenRequests] goes up.
     */
    @Serializable
    @SerialName("SetDeviceListening")
    data class SetDeviceListening(val participantId: String, val on: Boolean) : Command

    /** Owner only. Makes a solo room public, or a public room solo again. */
    @Serializable
    @SerialName("SetVisibility")
    data class SetVisibility(val visibility: RoomVisibility) : Command

    /** Owner only. Opening this room on another of your hosts; null: your account's setting. */
    @Serializable
    @SerialName("SetOpenElsewhere")
    data class SetOpenElsewhere(val mode: RoomOpenMode?) : Command

    /** Owner only. Hands the room to someone else; you keep the top role. */
    @Serializable
    @SerialName("ChangeOwner")
    data class ChangeOwner(val participantId: String) : Command

    /**
     * You leave the room for good (your songs stay). The owner of a party room hands it to
     * [newOwnerId], or, when null, to whoever has the top role and has been here the longest;
     * with [stayOwner] to nobody (they stay the owner and can come back).
     */
    @Serializable
    @SerialName("LeaveRoom")
    data class LeaveRoom(val newOwnerId: String? = null, val stayOwner: Boolean = false) : Command

    /** Invites everyone to [target] (a clone of this room), or moves them all there ([MigrationMode.FORCED]). */
    @Serializable
    @SerialName("SendMigration")
    data class SendMigration(val target: MigrationTarget, val mode: MigrationMode) : Command

    /**
     * Gives the room another code ([ROOM_CODE]). Its earlier codes keep leading to it, so links
     * and QR codes that are out there still work. Change settings.
     */
    @Serializable
    @SerialName("SetRoomCode")
    data class SetRoomCode(val code: String) : Command

    /** Sets the room's password (needed to join), or removes it (null). Manage room. */
    @Serializable
    @SerialName("SetPassword")
    data class SetPassword(val password: String?) : Command

    /** Takes back an invite from [SendMigration]. */
    @Serializable
    @SerialName("CancelMigration")
    data object CancelMigration : Command

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
        val alwaysOn: Boolean? = null,
        val fairOrder: Boolean? = null,
        val voteSkip: Boolean? = null,
        val voteSkipPercent: Int? = null,
        /** Limits: 0 turns one off. */
        val maxSongsPerPerson: Int? = null,
        val maxSongMinutes: Int? = null,
        val noRepeatMinutes: Int? = null,
        val noDuplicates: Boolean? = null,
        /** 0 to [MAX_START_WAIT_SECONDS]. */
        val startWaitSeconds: Int? = null,
        val karaoke: Boolean? = null,
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
        /** Only for you: brings your role along to a clone of this room ([ClientMessage.Hello.migrationKey]). */
        val migrationKey: String? = null,
    ) : ServerMessage

    /** You're the room's owner now: keep this, it proves it (like the one from creating a room). */
    @Serializable
    @SerialName("owner_token")
    data class OwnerToken(val ownerToken: String) : ServerMessage

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val reason: RejectReason,
        /** [RejectReason.ROOM_MOVED], [RejectReason.MIGRATED]: where to go now, e.g. `https://ytmp.example.com/room/ABCD`. */
        val movedTo: String? = null,
    ) : ServerMessage

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

    /** The room moved to another of its owner's hosts ([ServerMessage.Rejected.movedTo]). */
    @SerialName("room_moved") ROOM_MOVED,

    /** A solo room; only the hosting device can join it. */
    @SerialName("private_room") PRIVATE_ROOM,

    /** Removed by an admin; joining again is allowed. */
    @SerialName("kicked") KICKED,

    @SerialName("banned") BANNED,

    /** This host is for accounts only: log in first. */
    @SerialName("account_required") ACCOUNT_REQUIRED,

    /** You left the room (another of your connections did). */
    @SerialName("left") LEFT,

    /** Everyone was moved to another room ([ServerMessage.Rejected.movedTo]). */
    @SerialName("migrated") MIGRATED,

    /** The room has a password: join again with it. */
    @SerialName("password_required") PASSWORD_REQUIRED,

    @SerialName("wrong_password") WRONG_PASSWORD,

    /** Too many wrong passwords lately: nobody new gets in with one for a few minutes. */
    @SerialName("too_many_attempts") TOO_MANY_ATTEMPTS,
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

    /** How the room's radio is made changed (Start radio with other choices, the *Radio from* chips). */
    @Serializable
    @SerialName("RadioOptionsChanged")
    data class RadioOptionsChanged(val options: RadioOptions) : Event

    @Serializable
    @SerialName("RolesChanged")
    data class RolesChanged(val roles: List<Role>) : Event

    @Serializable
    @SerialName("SettingsChanged")
    data class SettingsChanged(val settings: RoomSettings) : Event

    @Serializable
    @SerialName("BansChanged")
    data class BansChanged(val bans: List<BanInfo>) : Event

    /**
     * Something everyone should know about, e.g. a song that can't be played, or who paused.
     * [notFor]: a participant who doesn't need it (they did it themselves).
     */
    @Serializable
    @SerialName("Notice")
    data class Notice(val message: String, val notFor: String? = null) : Event

    /** Someone invited everyone to another room, or took it back (null). */
    @Serializable
    @SerialName("MigrationChanged")
    data class MigrationChanged(val migration: MigrationOffer?) : Event

    /** [RoomState.adminless] changed. */
    @Serializable
    @SerialName("AdminlessChanged")
    data class AdminlessChanged(val adminless: Boolean) : Event

    @Serializable
    @SerialName("RepeatChanged")
    data class RepeatChanged(val mode: RepeatMode) : Event

    /** Someone voted, took a vote back, or the number of votes needed changed; null: no vote going on. */
    @Serializable
    @SerialName("SkipVotesChanged")
    data class SkipVotesChanged(val skipVotes: SkipVotes?) : Event
}

/** JSON settings shared by host and tests. Defaults are always encoded, so TS fields are never missing. */
val ProtocolJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}
