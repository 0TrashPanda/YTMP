package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.BanInfo
import dev.trashpanda.ytmp.protocol.Migrant
import dev.trashpanda.ytmp.protocol.MigrationOffer
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Role
import dev.trashpanda.ytmp.protocol.RoomSettings
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.RepeatMode
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
    /** Where podcast episodes were left off (song ID -> ms). */
    val episodePositions: Map<String, Long> = emptyMap(),
    /** The same room on the owner's other hosts (see [Room.syncId]). */
    val syncId: String? = null,
    val epoch: Int = 0,
    /** When it was saved, and whether it was playing then: a room that moves continues from there. */
    val savedAt: Long = 0,
    val playing: Boolean = false,
    /** People from the room this is a clone of who haven't come yet. */
    val migrants: List<Migrant> = emptyList(),
    val migration: MigrationOffer? = null,
    /** Joining needs this password ([RoomPasswords]). */
    val passwordHash: String? = null,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** The first song of the run of queue songs that repeat queue plays again (an item id). */
    val runStart: String? = null,
    /** The speed episodes play at ([Command.SetSpeed]). */
    val episodeSpeed: Double = 1.0,
    /** The speed each podcast was last played at (podcast ID -> speed). */
    val podcastSpeeds: Map<String, Double> = emptyMap(),
    /** Codes the room had before ([Command.SetRoomCode]): they still lead to it. Newest first. */
    val formerCodes: List<String> = emptyList(),
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
    val joinedAt: Long = 0,
    /** A party screen ([dev.trashpanda.ytmp.protocol.Participant.screen]). */
    val screen: Boolean = false,
)

@Serializable
data class SavedBan(val info: BanInfo, val guestToken: String?)
