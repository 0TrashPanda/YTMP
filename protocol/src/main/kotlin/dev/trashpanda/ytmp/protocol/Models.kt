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

/**
 * A song as returned by a source module. [id] is namespaced, e.g. `ytm:dQw4w9WgXcQ`.
 * Podcast episodes are songs too, with [podcast] set.
 */
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
    /** The podcast this is an episode of; null for music. */
    val podcast: PodcastRef? = null,
)

@Serializable
@SerialName("PodcastRef")
data class PodcastRef(val id: String, val name: String)

@Serializable
@SerialName("QueueItemResult")
enum class QueueItemResult {
    @SerialName("played") PLAYED,
    @SerialName("skipped") SKIPPED,
}

/** Where a queue item came from. */
@Serializable
@SerialName("QueueItemOrigin")
enum class QueueItemOrigin {
    @SerialName("manual") MANUAL,

    /** Start radio */
    @SerialName("radio") RADIO,

    /** The autoplay queue */
    @SerialName("autoplay") AUTOPLAY,
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
    val origin: QueueItemOrigin = QueueItemOrigin.MANUAL,
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
    /**
     * Recognises this person in a clone of the room ([Migrant.tag]): a hash of their own
     * migration key ([ServerMessage.Welcome.migrationKey]), which only they get.
     */
    val tag: String = "",
    /** A party screen (a TV showing the room): it only watches, and doesn't count as a person here. */
    val screen: Boolean = false,
    /** Speakers near their phone that they play the room on ([Command.SetSpeakers]). */
    val speakers: List<String> = emptyList(),
)

@Serializable
@SerialName("PlaybackStatus")
data class PlaybackStatus(
    val playing: Boolean,
    /** Position at [hostTimeMs]. While playing, the position moves on from there. */
    val positionMs: Long,
    val hostTimeMs: Long,
    /**
     * A new song is loading on the players: [playing] is false until they all have it (or a
     * few seconds passed), then everyone starts it together from [positionMs]. Show it as playing.
     */
    val starting: Boolean = false,
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
    /** Joining needs a password ([ClientMessage.Hello.password]). */
    val hasPassword: Boolean = false,
)

@Serializable
@SerialName("OutputKind")
enum class OutputKind {
    @SerialName("chromecast") CHROMECAST,
    @SerialName("sonos") SONOS,
    /** MPD (Music Player Daemon), e.g. on a Raspberry Pi with a speaker. */
    @SerialName("mpd") MPD,
    /** A room of a multi-room amplifier, fed by a player (an MPD) that other zones may share. */
    @SerialName("zone") ZONE,
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
    /** It shows the party screen (and plays the music with it). */
    val screen: Boolean = false,
)

@Serializable
@SerialName("RepeatMode")
enum class RepeatMode {
    /** When the queue ends, the autoplay queue takes over. */
    @SerialName("off") OFF,

    /** When the queue ends, the songs it played are queued again. */
    @SerialName("queue") QUEUE,

    /** The current song plays again and again (skipping still moves on). */
    @SerialName("one") ONE,
}

/** Votes to skip the current song ([RoomSettings.voteSkip]). */
@Serializable
@SerialName("SkipVotes")
data class SkipVotes(
    /** Who voted (participant ids). */
    val voters: List<String>,
    /** Votes it takes to skip, from the people here now. */
    val needed: Int,
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
    /** Plays when the queue runs out; refilled endlessly. */
    val autoplay: List<QueueItem> = emptyList(),
    /** The song the autoplay queue is a radio of, or null. */
    val autoplaySeed: Song? = null,
    /** Someone invited everyone to another room (a clone), or null. */
    val migration: MigrationOffer? = null,
    /** Nobody who runs the room (the owner, or someone with the top role) has been here for a while. */
    val adminless: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Null when the room doesn't vote to skip (or nothing plays). */
    val skipVotes: SkipVotes? = null,
)

/** A room people can be moved to, e.g. a clone of this one. */
@Serializable
@SerialName("MigrationTarget")
data class MigrationTarget(
    /** Its link, e.g. `https://ytmp.example.com/room/ABCD`. */
    val url: String,
    val code: String,
    val name: String,
)

@Serializable
@SerialName("MigrationMode")
enum class MigrationMode {
    /** Everyone sees a message and can follow. */
    @SerialName("invite") INVITE,

    /** Everyone is moved; this room empties. */
    @SerialName("forced") FORCED,
}

@Serializable
@SerialName("MigrationOffer")
data class MigrationOffer(
    val target: MigrationTarget,
    val mode: MigrationMode,
    /** Who sent it. */
    val fromName: String,
)

/**
 * Someone from the room a clone was made of: whoever comes with the key behind [tag] gets
 * this name, role and id back (so their songs are still theirs).
 */
@Serializable
@SerialName("Migrant")
data class Migrant(
    val tag: String,
    /** Their participant id in the old room. */
    val id: String,
    val name: String,
    val roleId: String,
    val accountId: String? = null,
    val allow: List<Permission> = emptyList(),
    val deny: List<Permission> = emptyList(),
)

/** What a clone starts with: a copy of another room, as its page saw it. */
@Serializable
@SerialName("RoomClone")
data class RoomClone(
    val queue: List<QueueItem>,
    val history: List<QueueItem>,
    val current: QueueItem? = null,
    val positionMs: Long = 0,
    val autoplay: List<QueueItem> = emptyList(),
    val autoplaySeed: Song? = null,
    val roles: List<Role> = emptyList(),
    val settings: RoomSettings? = null,
    val migrants: List<Migrant> = emptyList(),
)

/** A song's lyrics (`GET /api/lyrics`). */
@Serializable
@SerialName("Lyrics")
data class Lyrics(
    /** The whole text, a line per line. */
    val plain: String,
    /** Each line with when it starts, when the lyrics are timed; null: only [plain]. */
    val synced: List<LyricLine>?,
    /** Where they're from, to credit it: "LRCLIB", "YouTube Music (LyricFind)". */
    val source: String,
)

@Serializable
@SerialName("LyricLine")
data class LyricLine(val timeMs: Long, val text: String)
