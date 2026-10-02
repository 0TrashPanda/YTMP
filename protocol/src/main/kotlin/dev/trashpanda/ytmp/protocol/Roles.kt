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
    @SerialName("skip") SKIP,
    @SerialName("seek") SEEK,

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
)

/** The roles a room starts with. Saved to an account, so every room you create gets yours. */
@Serializable
@SerialName("RoleTemplate")
data class RoleTemplate(
    val roles: List<Role>,
    val settings: RoomSettings,
    /** The permissions that existed when it was saved, so newer ones can be added to it (set by the server). */
    val knownPermissions: List<Permission>? = null,
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
