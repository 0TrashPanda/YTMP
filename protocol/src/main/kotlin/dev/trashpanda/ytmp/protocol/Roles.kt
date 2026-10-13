package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Roles and permissions, see docs/features/room-management.md#permissions.
// Every participant has one role; per-person overrides allow or deny single permissions on top.

@Serializable
@SerialName("Permission")
enum class Permission {
    // Queue
    @SerialName("add_songs") ADD_SONGS,
    @SerialName("play_now") PLAY_NOW,
    @SerialName("remove_own") REMOVE_OWN,
    @SerialName("remove_others") REMOVE_OTHERS,
    @SerialName("reorder") REORDER,
    @SerialName("start_radio") START_RADIO,
    @SerialName("autoplay_from_here") AUTOPLAY_FROM_HERE,

    // Playback
    @SerialName("play_pause") PLAY_PAUSE,
    /** Skipping right away, also when the room votes to skip. */
    @SerialName("skip") SKIP,
    @SerialName("seek") SEEK,
    /** Repeat off / the queue / one song. */
    @SerialName("repeat") REPEAT,
    /** Skipping by voting when the room has [RoomSettings.voteSkip] on; right away when it's off. */
    @SerialName("vote_skip") VOTE_SKIP,

    // Listening
    @SerialName("listen_locally") LISTEN_LOCALLY,

    // Outputs
    @SerialName("change_outputs") CHANGE_OUTPUTS,
    @SerialName("output_volume") OUTPUT_VOLUME,

    // People
    @SerialName("kick") KICK,
    @SerialName("ban") BAN,
    @SerialName("assign_roles") ASSIGN_ROLES,
    @SerialName("edit_roles") EDIT_ROLES,

    // Room
    @SerialName("change_settings") CHANGE_SETTINGS,
    /** Making a copy of the room somewhere else (it's a hint for the page: everyone sees the queue anyway). */
    @SerialName("clone_room") CLONE_ROOM,
    /** Inviting (or moving) everyone in the room to another room, e.g. a clone. */
    @SerialName("send_migration") SEND_MIGRATION,
}

/** A named set of permissions. Roles are ranked: earlier in the list is higher. */
@Serializable
@SerialName("Role")
data class Role(
    val id: String,
    val name: String,
    /** `#rrggbb`, used for the names of people with this role. */
    val color: String,
    val permissions: List<Permission>,
)

@Serializable
@SerialName("RoomSettings")
data class RoomSettings(
    /** Role for guests when they join. */
    val defaultGuestRole: String,
    /** Role for account holders when they join. */
    val defaultAccountRole: String,
    /** When the queue runs out, keep playing a radio from the last song (like YTM). */
    val autoplay: Boolean = true,
    /** Opening this room on another of the owner's hosts; null: the owner's account setting. */
    val openElsewhere: RoomOpenMode? = null,
    /** Never deleted for being empty (a room on a server that's always there). Not in templates. */
    val alwaysOn: Boolean = false,
    /** Fair order: the queue goes round by round, one song per person per round (docs/features/queue.md). */
    val fairOrder: Boolean = false,
    /** People with [Permission.VOTE_SKIP] (and not [Permission.SKIP]) skip by voting. */
    val voteSkip: Boolean = false,
    /** Share of the people here (who can skip or vote) whose votes skip the song, 1–100. */
    val voteSkipPercent: Int = 50,
    /** At most this many songs per person in the queue at once; 0: no limit. */
    val maxSongsPerPerson: Int = 0,
    /** Songs longer than this many minutes can't be added; 0: no limit. */
    val maxSongMinutes: Int = 0,
    /** Songs played in the last this many minutes can't be added again; 0: no limit. */
    val noRepeatMinutes: Int = 0,
)

/** The roles a room starts with. Saved to an account, so every room you create gets yours. */
@Serializable
@SerialName("RoleTemplate")
data class RoleTemplate(
    val roles: List<Role>,
    val settings: RoomSettings,
    /** The permissions that existed when it was saved, so newer ones can be added to it (set by the server). */
    val knownPermissions: List<Permission>? = null,
    /**
     * The account's default room password, hashed (see core's RoomPasswords): rooms it creates
     * start with it. Only set by the auth server when it hands the template to a host.
     */
    val roomPassword: String? = null,
)

/** Someone who can't join the room again. */
@Serializable
@SerialName("BanInfo")
data class BanInfo(
    val id: String,
    val name: String,
    /** Banned by account; a guest is banned by their guest token (easy to get around). */
    val accountId: String?,
)
