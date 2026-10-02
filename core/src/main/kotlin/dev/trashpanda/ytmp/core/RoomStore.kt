package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ProtocolJson
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
) {
    fun toJson(): String = ProtocolJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): SavedRoom = ProtocolJson.decodeFromString(serializer(), json)
    }
}

/** A participant, so guests come back as themselves (same name, same songs) after a restart. */
@Serializable
data class SavedMember(val id: String, val token: String, val name: String, val isOwner: Boolean, val accountId: String? = null)
