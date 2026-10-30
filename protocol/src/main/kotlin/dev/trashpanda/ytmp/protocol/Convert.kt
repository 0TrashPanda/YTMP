package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Importing playlists (`/api/me/convert/…`), see docs/features/importing-playlists.md and
// docs/implementation/playlist-converter.md.

@Serializable
@SerialName("ConvertListKind")
enum class ConvertListKind {
    @SerialName("playlist") PLAYLIST,
    @SerialName("liked_songs") LIKED_SONGS,
    @SerialName("saved_episodes") SAVED_EPISODES,
    @SerialName("followed_shows") FOLLOWED_SHOWS,
}

@Serializable
@SerialName("ConvertItemType")
enum class ConvertItemType {
    @SerialName("song") SONG,
    @SerialName("episode") EPISODE,

    /** A file the user added to a playlist from their own disk: still a song, often one YouTube Music has. */
    @SerialName("local_file") LOCAL_FILE,

    /** A podcast (followed shows). */
    @SerialName("show") SHOW,
}

/** One song (or episode, show) of an imported list, as the source has it: each field only when it does. */
@Serializable
@SerialName("ConvertItem")
data class ConvertItem(
    val type: ConvertItemType,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    /** The source's own ID, e.g. "spotify:track:…". */
    val sourceId: String? = null,
    /** An episode's podcast. */
    val show: String? = null,
)

/** One list found in what was read, for *What to import*. */
@Serializable
@SerialName("ConvertListPreview")
data class ConvertListPreview(
    val index: Int,
    val name: String,
    val kind: ConvertListKind,
    val count: Int,
    /** Only its first [count] songs could be read (a link to a long Spotify playlist). */
    val truncated: Boolean = false,
)

/** `POST /api/me/convert/read`: what's in the file, kept for an hour as [readId]. */
@Serializable
@SerialName("ConvertPreview")
data class ConvertPreview(
    val readId: String,
    /** e.g. "spotify-export", "spotify-link". */
    val reader: String,
    val lists: List<ConvertListPreview>,
    /** Rows that weren't anything to import (an audiobook, a blank row). */
    val skipped: Int,
)

@Serializable
@SerialName("ConvertTargetKind")
enum class ConvertTargetKind {
    /** A new private YouTube Music playlist named [ConvertTarget.title]. */
    @SerialName("new_playlist") NEW_PLAYLIST,

    /** Added to one of your YouTube Music playlists ([ConvertTarget.playlistId]): only what isn't in it. */
    @SerialName("existing_playlist") EXISTING_PLAYLIST,

    /** Liked on YouTube Music, slowly. */
    @SerialName("likes") LIKES,

    /** Shows followed (saved to the library) on YouTube Music. */
    @SerialName("follow") FOLLOW,
}

@Serializable
@SerialName("ConvertTarget")
data class ConvertTarget(val kind: ConvertTargetKind, val title: String? = null, val playlistId: String? = null)

@Serializable
@SerialName("ConvertSettings")
data class ConvertSettings(
    /** Songs YTMP isn't sure about are added without asking. */
    val addUnsure: Boolean = false,
    /** A music video when there's no song version. */
    val videos: Boolean = true,
)

@Serializable
@SerialName("ConvertListChoice")
data class ConvertListChoice(val index: Int, val target: ConvertTarget)

/** `POST /api/me/convert/jobs`. */
@Serializable
@SerialName("ConvertStartRequest")
data class ConvertStartRequest(val readId: String, val lists: List<ConvertListChoice>, val settings: ConvertSettings = ConvertSettings())

@Serializable
@SerialName("ConvertItemState")
enum class ConvertItemState {
    /** Not looked up yet. */
    @SerialName("pending") PENDING,
    @SerialName("matched") MATCHED,

    /** Something close was found: waits for you (unless [ConvertSettings.addUnsure]). */
    @SerialName("unsure") UNSURE,
    @SerialName("not_found") NOT_FOUND,

    /** You left it out. */
    @SerialName("left_out") LEFT_OUT,
}

@Serializable
@SerialName("ConvertJobStatus")
enum class ConvertJobStatus {
    /** Looking songs up (or writing them). */
    @SerialName("matching") MATCHING,

    /** Paused by you. */
    @SerialName("paused") PAUSED,

    /** Everything is looked up and written, but songs it wasn't sure about wait for you. */
    @SerialName("needs_you") NEEDS_YOU,
    @SerialName("done") DONE,
    @SerialName("cancelled") CANCELLED,
}

/** How many songs are in each state (each song once, also when it's in several lists). */
@Serializable
@SerialName("ConvertCounts")
data class ConvertCounts(
    val total: Int,
    val pending: Int,
    val matched: Int,
    val unsure: Int,
    val notFound: Int,
    val leftOut: Int,
)

@Serializable
@SerialName("ConvertListStatus")
data class ConvertListStatus(
    val name: String,
    val kind: ConvertListKind,
    val target: ConvertTarget,
    /** The YouTube Music playlist once made (or the existing one). */
    val playlistId: String?,
    /** Rows of the list. */
    val count: Int,
    /** Rows put in the target so far. */
    val written: Int,
    /** Why writing it didn't work, the last time; it's tried again. */
    val error: String?,
)

/** An import (`GET /api/me/convert/jobs/{id}`). */
@Serializable
@SerialName("ConvertJob")
data class ConvertJob(
    val id: String,
    val title: String,
    val reader: String,
    val createdAt: Long,
    val status: ConvertJobStatus,
    /** Why it's waiting right now (YouTube's bot check, no YouTube Music sign-in), or null. */
    val waiting: String?,
    /** Until when it waits, ms since the epoch, or null. */
    val waitingUntil: Long?,
    /** About how long until everything is looked up, in ms, or null. */
    val leftMs: Long?,
    val counts: ConvertCounts,
    val lists: List<ConvertListStatus>,
    val settings: ConvertSettings,
)

@Serializable
@SerialName("ConvertJobList")
data class ConvertJobList(val jobs: List<ConvertJob>)

/** One song of an import, each once (also when it's in several lists), for *Checking songs*. */
@Serializable
@SerialName("ConvertItemRow")
data class ConvertItemRow(
    val key: String,
    val item: ConvertItem,
    val state: ConvertItemState,
    /** What it's matched to (also for [ConvertItemState.UNSURE]: the best guess). */
    val song: Song?,
    /** Other songs it might be, best first. */
    val candidates: List<Song>,
    /** How sure, 0..1, or null. */
    val score: Double?,
    /** Picked (or left out) by you. */
    val manual: Boolean,
    /** The lists it's in. */
    val lists: List<String>,
)

/** `GET /api/me/convert/jobs/{id}/items?state=&cursor=`. */
@Serializable
@SerialName("ConvertItemPage")
data class ConvertItemPage(val items: List<ConvertItemRow>, val next: String?)

/** `PUT /api/me/convert/jobs/{id}/items/{key}`: this song, leave it out, or put a song left out back as it was ([back]). */
@Serializable
@SerialName("ConvertPick")
data class ConvertPick(val song: Song? = null, val leftOut: Boolean = false, val back: Boolean = false)
