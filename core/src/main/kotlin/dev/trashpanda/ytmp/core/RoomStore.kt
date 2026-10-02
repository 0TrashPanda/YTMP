package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.BanInfo
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Role
import dev.trashpanda.ytmp.protocol.RoomSettings
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.RoomVisibility
import kotlinx.serialization.Serializable

/**
 * Keeps rooms across restarts: the phone's own SQLite database, or the server's database.
 * Calls block; [RoomManager] makes them one at a time, off the main thread.
 */
interface RoomStore {
    fun loadAll(): List<SavedRoom>

    /** Inserts or replaces the room with this code. */
    fun save(room: SavedRoom)

    fun delete(code: String)
}

/** Everything needed to bring a room back. Stored as JSON, so fields can be added with a default. */
@Serializable
data class SavedRoom(
    val code: String,
    val name: String,
    val ownerToken: String,
    val visibility: RoomVisibility,
    val members: List<SavedMember>,
    val queue: List<QueueItem>,
    val history: List<QueueItem>,
    val current: QueueItem?,
    val positionMs: Long,
    val lastActive: Long,
    val ownerAccount: String? = null,
    /** Null in rooms saved before roles existed: they get the default roles. */
    val roles: List<Role>? = null,
    val settings: RoomSettings? = null,
    val bans: List<SavedBan> = emptyList(),
    val autoplay: List<QueueItem> = emptyList(),
    val autoplaySeed: Song? = null,
    /** The permissions that existed when it was saved (null: before radio), to give roles the newer ones. */
    val knownPermissions: List<Permission>? = null,
) {
    fun toJson(): String = ProtocolJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): SavedRoom = ProtocolJson.decodeFromString(serializer(), json)
    }
}

/** A participant, so guests come back as themselves (same name, same songs) after a restart. */
@Serializable
data class SavedMember(
    val id: String,
    val token: String,
    val name: String,
    val isOwner: Boolean,
    val accountId: String? = null,
    val roleId: String? = null,
    val allow: List<Permission> = emptyList(),
    val deny: List<Permission> = emptyList(),
)

@Serializable
data class SavedBan(val info: BanInfo, val guestToken: String?)
