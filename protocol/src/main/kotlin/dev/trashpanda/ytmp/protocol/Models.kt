package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bumped on every incompatible protocol change. Client and host must match. */
const val PROTOCOL_VERSION = 1

@Serializable
@SerialName("ArtistRef")
data class ArtistRef(
    val id: String?,
    val name: String,
)

@Serializable
@SerialName("AlbumRef")
data class AlbumRef(
    val id: String?,
    val name: String,
)

@Serializable
@SerialName("Thumbnail")
data class Thumbnail(
    val url: String,
    val width: Int,
    val height: Int,
)

/** A song as returned by a source module. [id] is namespaced, e.g. `ytm:dQw4w9WgXcQ`. */
@Serializable
@SerialName("Song")
data class Song(
    val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef?,
    val durationMs: Long,
    val thumbnails: List<Thumbnail>,
    val explicit: Boolean = false,
)

@Serializable
@SerialName("QueueItemResult")
enum class QueueItemResult {
    @SerialName("played") PLAYED,
    @SerialName("skipped") SKIPPED,
}

/** One entry in the queue, the history or "now playing". */
@Serializable
@SerialName("QueueItem")
data class QueueItem(
    val itemId: String,
    val song: Song,
    val addedBy: String,
    val addedByName: String,
    val addedAt: Long,
    val result: QueueItemResult? = null,
)

@Serializable
@SerialName("Participant")
data class Participant(
    val id: String,
    val name: String,
    val isOwner: Boolean,
    val online: Boolean,
    val listening: Boolean,
    /** `username@issuer`, or null for a guest. */
    val accountId: String? = null,
    val roleId: String = "",
    /** Per-person overrides on top of the role. */
    val allow: List<Permission> = emptyList(),
    val deny: List<Permission> = emptyList(),
)

@Serializable
@SerialName("PlaybackStatus")
data class PlaybackStatus(
    val playing: Boolean,
    /** Position at [hostTimeMs]. While playing, the position moves on from there. */
    val positionMs: Long,
    val hostTimeMs: Long,
)

@Serializable
@SerialName("NowPlaying")
data class NowPlaying(
    val item: QueueItem,
    /** Direct stream URL, or null while the host is still resolving it. */
    val streamUrl: String?,
)

@Serializable
@SerialName("RoomVisibility")
enum class RoomVisibility {
    /** Anyone who can reach the host and has the code can join. */
    @SerialName("public") PUBLIC,

    /** A solo room: only the device that hosts it can join. */
    @SerialName("private") PRIVATE,
}

@Serializable
@SerialName("RoomInfo")
data class RoomInfo(
    val code: String,
    val name: String,
    val visibility: RoomVisibility,
)

@Serializable
@SerialName("OutputKind")
enum class OutputKind {
    @SerialName("chromecast") CHROMECAST,
}

/** A speaker or TV the host can play the room on. */
@Serializable
@SerialName("OutputInfo")
data class OutputInfo(
    val id: String,
    val name: String,
    val kind: OutputKind,
    /** The room is playing on it. */
    val active: Boolean,
    /** 0.0–1.0, or null if unknown (not active). */
    val volume: Double?,
)

/** Full room state, sent on connect and on [ClientMessage.RequestSnapshot]. */
@Serializable
@SerialName("RoomState")
data class RoomState(
    val room: RoomInfo,
    val participants: List<Participant>,
    val queue: List<QueueItem>,
    val history: List<QueueItem>,
    val nowPlaying: NowPlaying?,
    val playback: PlaybackStatus,
    val outputs: List<OutputInfo>,
    /** Ranked, highest first. */
    val roles: List<Role> = emptyList(),
    val settings: RoomSettings = RoomSettings("", ""),
    val bans: List<BanInfo> = emptyList(),
)
