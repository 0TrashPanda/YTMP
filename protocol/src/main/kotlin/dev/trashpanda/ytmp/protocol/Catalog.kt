package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// Artist and album pages (`GET /api/artists/{id}`, `GET /api/albums/{id}`).

/** An album or single in a list. */
@Serializable
@SerialName("AlbumSummary")
data class AlbumSummary(
    val id: String,
    val title: String,
    /** "Album", "Single", "EP", … as the source names it. */
    val kind: String,
    val year: String?,
    val thumbnails: List<Thumbnail>,
)

@Serializable
@SerialName("ArtistPage")
data class ArtistPage(
    val id: String,
    val name: String,
    val thumbnails: List<Thumbnail>,
    val description: String?,
    /** Their most played songs. */
    val songs: List<Song>,
    val albums: List<AlbumSummary>,
    val singles: List<AlbumSummary>,
    /** All their songs, as a playlist (YTM's "Show all"). */
    val songsPlaylistId: String? = null,
)

@Serializable
@SerialName("AlbumPage")
data class AlbumPage(
    val id: String,
    val title: String,
    val kind: String,
    val year: String?,
    val artists: List<ArtistRef>,
    val thumbnails: List<Thumbnail>,
    val songs: List<Song>,
)

// Playlist pages (`GET /api/playlists/{id}`) and search (`GET /api/search?q=&type=`).

@Serializable
@SerialName("PlaylistPage")
data class PlaylistPage(
    val id: String,
    val title: String,
    /** Who made it, e.g. a person's name or "YouTube Music". */
    val author: String?,
    val description: String?,
    val thumbnails: List<Thumbnail>,
    val songs: List<Song>,
    /** One of your own playlists: you can remove, move and rename (`/api/me/playlists/{id}`). */
    val owned: Boolean = false,
    /** Your own playlists (and Liked music): each song's place in it, in the same order as [songs] ("" if unknown). */
    val itemIds: List<String> = emptyList(),
    /**
     * Your own playlists (and Liked music) come in parts: this asks for the next songs
     * (`/api/me/playlists/{id}?more=…`, which answers with only those); null when there are no more.
     */
    val more: String? = null,
    /** How many songs it has in all, when known (also the ones not loaded yet). */
    val songCount: Int? = null,
)

/** A song in one of your playlists ([PlaylistPage.itemIds]). */
@Serializable
@SerialName("PlaylistItemRef")
data class PlaylistItemRef(val songId: String, val itemId: String)

/** `POST /api/me/playlists/{id}/remove` */
@Serializable
@SerialName("RemovePlaylistItemsRequest")
data class RemovePlaylistItemsRequest(val items: List<PlaylistItemRef>)

/** `POST /api/me/playlists/{id}/move`: [itemId] goes right before [beforeItemId] (null: to the end). */
@Serializable
@SerialName("MovePlaylistItemRequest")
data class MovePlaylistItemRequest(val itemId: String, val beforeItemId: String?)

/** `PUT /api/me/playlists/{id}` */
@Serializable
@SerialName("RenamePlaylistRequest")
data class RenamePlaylistRequest(val title: String)

/** A podcast (`GET /api/podcasts/{id}`): its newest episodes first. */
@Serializable
@SerialName("PodcastPage")
data class PodcastPage(
    val id: String,
    val title: String,
    val author: String?,
    val description: String?,
    val thumbnails: List<Thumbnail>,
    val episodes: List<Episode>,
    /** In your YouTube Music library (subscribed); null: not known (`/api/podcasts`, without your account). */
    val saved: Boolean? = null,
)

/** `PUT /api/me/podcasts/{id}/saved`: subscribe to a podcast (add it to your YouTube Music library), or not. */
@Serializable
@SerialName("PodcastSavedRequest")
data class PodcastSavedRequest(val saved: Boolean)

/** An episode on a podcast page: playable like a song, plus what a list of episodes shows. */
@Serializable
@SerialName("Episode")
data class Episode(
    val song: Song,
    /** As the source says it, e.g. "Oct 18, 2020" or "3 days ago". */
    val date: String?,
    val description: String?,
)

/** What to search for; the chips above the results. */
@Serializable
@SerialName("SearchType")
enum class SearchType {
    /** A bit of everything, in sections (top result, songs, albums, …). */
    @SerialName("all") ALL,
    @SerialName("songs") SONGS,
    @SerialName("videos") VIDEOS,
    @SerialName("albums") ALBUMS,
    @SerialName("artists") ARTISTS,
    @SerialName("community_playlists") COMMUNITY_PLAYLISTS,
    @SerialName("featured_playlists") FEATURED_PLAYLISTS,
    @SerialName("podcasts") PODCASTS,
    @SerialName("episodes") EPISODES,
}

/** The name on the wire and in URLs, e.g. "community_playlists". */
val SearchType.wireName: String get() = SearchType.serializer().descriptor.getElementName(ordinal)

/** One search result: something to play, or a page to open. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface SearchItem {
    /** A song, or a (music) video, which plays like a song. */
    @Serializable
    @SerialName("song")
    data class SongResult(val song: Song, val video: Boolean = false) : SearchItem

    @Serializable
    @SerialName("album")
    data class AlbumResult(val album: AlbumSummary, val artists: List<ArtistRef>) : SearchItem

    @Serializable
    @SerialName("artist")
    data class ArtistResult(val id: String, val name: String, val thumbnails: List<Thumbnail>) : SearchItem

    @Serializable
    @SerialName("podcast")
    data class PodcastResult(val id: String, val title: String, val author: String?, val thumbnails: List<Thumbnail>) : SearchItem

    @Serializable
    @SerialName("playlist")
    data class PlaylistResult(
        val id: String,
        val title: String,
        val author: String?,
        /** Number of songs, when the source says. */
        val itemCount: Int?,
        val thumbnails: List<Thumbnail>,
    ) : SearchItem
}

@Serializable
@SerialName("SearchSection")
data class SearchSection(
    /** e.g. "Top result", "Songs", "Community playlists". */
    val title: String,
    /** The search type that shows more of this, if any. */
    val type: SearchType?,
    val items: List<SearchItem>,
)

/** Search results: one section for a single type, several for [SearchType.ALL]. */
@Serializable
@SerialName("SearchPage")
data class SearchPage(val sections: List<SearchSection>)

/** The home page (`GET /api/home`): suggestions in rows, like YTM's quick picks and mixes. */
@Serializable
@SerialName("HomePage")
data class HomePage(val sections: List<HomeSection>)

@Serializable
@SerialName("HomeSection")
data class HomeSection(
    /** e.g. "Quick picks", "New releases". */
    val title: String,
    val items: List<SearchItem>,
)

/** A signed-in YouTube Music account. */
@Serializable
@SerialName("YoutubeAccount")
data class YoutubeAccount(val name: String, val handle: String?, val photoUrl: String?)

/**
 * `GET /api/me/youtube`: the YouTube Music account of this host's owner. Only a phone host
 * has one, and only tells its own app; the library is `GET /api/me/library` (a [HomePage]
 * with "Playlists" and "Podcasts"), private playlists `GET /api/me/playlists/{id}`.
 */
@Serializable
@SerialName("YoutubeAccountStatus")
data class YoutubeAccountStatus(
    /** You can sign in here (the phone app on its own host). */
    val available: Boolean,
    val account: YoutubeAccount?,
    /** Which songs go into your YouTube Music history (so its suggestions learn from them). */
    val history: YoutubeHistory = YoutubeHistory.SOLO,
    /** Search and radio go through this account (see [YoutubePersonalizeSetting]). */
    val personalize: Boolean = true,
)

/** Which songs played on this phone go into its owner's YouTube Music history. */
@Serializable
@SerialName("YoutubeHistory")
enum class YoutubeHistory {
    @SerialName("off") OFF,

    /** Songs in solo rooms: only you hear those. */
    @SerialName("solo") SOLO,

    /** Also what plays in party rooms on this phone, whoever added it. */
    @SerialName("all") ALL,
}

/**
 * `PUT /api/me/youtube`: sign in to YouTube Music on a server with the cookies of a signed-in
 * music.youtube.com session, or text that contains them (copied request headers, cURL).
 */
@Serializable
@SerialName("YoutubeSignInRequest")
data class YoutubeSignInRequest(val cookie: String)

/** `PUT /api/me/youtube/history`. */
@Serializable
@SerialName("YoutubeHistorySetting")
data class YoutubeHistorySetting(val history: YoutubeHistory)

/**
 * `PUT /api/me/youtube/personalize`: whether search and radio go through your YouTube Music
 * account, so they fit your taste like in YouTube Music: your own searches (and the suggestions
 * while typing) and Related, and the radio and autoplay of rooms that are yours. Others' searches
 * in your rooms never do.
 */
@Serializable
@SerialName("YoutubePersonalizeSetting")
data class YoutubePersonalizeSetting(val on: Boolean)

/** `GET`/`PUT /api/me/likes/{songId}`: whether you like a song (thumbs up) in YouTube Music. */
@Serializable
@SerialName("LikeStatus")
data class LikeStatus(
    /** Thumbs up. */
    val liked: Boolean,
    /** Thumbs down (never both). */
    val disliked: Boolean = false,
)

/** One of your own playlists, to save songs to. */
@Serializable
@SerialName("PlaylistSummary")
data class PlaylistSummary(val id: String, val title: String, val thumbnails: List<Thumbnail>)

/** `GET /api/me/playlists`: your own playlists (not Liked music). */
@Serializable
@SerialName("MyPlaylists")
data class MyPlaylists(val playlists: List<PlaylistSummary>)

/** `POST /api/me/playlists/{id}/songs`: save songs to one of your playlists. */
@Serializable
@SerialName("SaveSongsRequest")
data class SaveSongsRequest(val songIds: List<String>)

/** `POST /api/me/playlists`: a new private playlist with these songs. */
@Serializable
@SerialName("CreatePlaylistRequest")
data class CreatePlaylistRequest(val title: String, val songIds: List<String>)

@Serializable
@SerialName("CreatePlaylistResponse")
data class CreatePlaylistResponse(val id: String)

// Local music (`GET /api/local`, `GET /api/local/{id}`): music the host reaches itself, like
// the music folder of a Raspberry Pi with MPD, or the music on the hosting phone.

@Serializable
@SerialName("LocalLibraryKind")
enum class LocalLibraryKind {
    /** An MPD's music (say a Raspberry Pi next to the stereo). */
    @SerialName("mpd") MPD,

    /** The music stored on the hosting phone. */
    @SerialName("phone") PHONE,

    /**
     * Songs the hosting phone has cached or downloaded ("Cached on this phone"): YouTube Music
     * songs that keep their own IDs, so they play without internet (docs/implementation/song-cache.md).
     */
    @SerialName("cache") CACHE,
}

@Serializable
@SerialName("LocalLibrarySummary")
data class LocalLibrarySummary(val id: String, val name: String, val kind: LocalLibraryKind, val songCount: Int)

@Serializable
@SerialName("LocalLibraries")
data class LocalLibraries(val libraries: List<LocalLibrarySummary>)

/**
 * One library, whole: the Local tab filters it as you type. [albums] and [artists] are search
 * items (album, artist). [genres]: by name. [folders]: every folder that has songs itself, by
 * its path in the library ("" is the top, "/" between folders).
 */
@Serializable
@SerialName("LocalLibraryPage")
data class LocalLibraryPage(
    val id: String,
    val name: String,
    val kind: LocalLibraryKind,
    val albums: List<SearchItem>,
    val artists: List<SearchItem>,
    val songs: List<Song>,
    val genres: List<LocalGroup> = emptyList(),
    val folders: List<LocalGroup> = emptyList(),
)

/** A genre or folder of a [LocalLibraryPage]: its [songs] as places in the page's songs, in album order. */
@Serializable
@SerialName("LocalGroup")
data class LocalGroup(val name: String, val songs: List<Int>)

// The song cache (`GET /api/cache`, `PUT /api/cache`, `POST /api/cache/clear`): songs kept
// so they start at once and, on a phone, play without internet (docs/features/sources.md#song-cache).

@Serializable
@SerialName("SongCacheStatus")
data class SongCacheStatus(
    val songs: Int,
    val bytes: Long,
    /** 0: the cache is off. */
    val maxBytes: Long,
    /** Downloaded songs it lists too (a phone), not counted in [bytes]. */
    val downloads: Int = 0,
    /** The phone's own music it lists too (matched to YouTube Music), not counted in [bytes]. */
    val local: Int = 0,
    /** The setting to list the phone's own music; null where that isn't a thing (a server). */
    val localSongs: Boolean? = null,
    /** Phone songs still to be looked up on YouTube Music. */
    val checking: Int = 0,
    /** Phone songs that are looked up in all (done and still to do). */
    val checkingOf: Int = 0,
    /** How many phone songs are looked up at once. */
    val localParallel: Int = 1,
    /** Songs kept in the background (from their 2nd play) only on Wi-Fi; null where that isn't a setting. */
    val fetchWifiOnly: Boolean? = null,
    /** Downloads (song menu) wait for Wi-Fi; null where that isn't a setting. */
    val downloadsWifiOnly: Boolean? = null,
    /** Phone songs are looked up only on Wi-Fi / only while charging. */
    val localWifiOnly: Boolean = false,
    val localCharging: Boolean = false,
    /** Why the lookups are waiting right now ("Wi-Fi", "charging"), or null. */
    val localWaiting: String? = null,
    /** How many of the songs you liked last on YouTube Music are kept (0: none); null where that isn't a setting (a server). */
    val keepLiked: Int? = null,
    /** Of those (the ones that can be kept): how many there are, and how many are here. */
    val likedOf: Int = 0,
    val likedCached: Int = 0,
    /** Songs your YouTube Music history says you played more than once are kept; null where that isn't a setting. */
    val fromHistory: Boolean? = null,
    /** Why songs from YouTube Music aren't coming in right now: "Wi-Fi", "full", "signed out", "error"; or null. */
    val fromYoutubeWaiting: String? = null,
    /** Looking at YouTube Music and fetching songs right now. */
    val fromYoutubeBusy: Boolean = false,
    /** After [fromYoutubeWaiting] "error": what went wrong. */
    val fromYoutubeError: String? = null,
    /** When it looks again by itself (ms): after an error, or once a round is done. Settings (or Retry) look at once. */
    val fromYoutubeNextAt: Long? = null,
    /** The imported Google Takeout history, or null (none yet, or not a setting here). */
    val takeout: TakeoutSummary? = null,
    /** Songs from it are kept: those played at least [takeoutMinPlays] times in the last [takeoutDays] days (0: all of it). */
    val fromTakeout: Boolean = false,
    val takeoutDays: Int = 0,
    val takeoutMinPlays: Int = 0,
    /** Of the songs those settings pick (that can be kept): how many, and how many are here. */
    val takeoutOf: Int = 0,
    val takeoutCached: Int = 0,
    /** Picked songs YouTube doesn't have any more ("Video unavailable"): skipped, not in [takeoutOf]. */
    val takeoutGone: Int = 0,
    /** Where it can be kept (a phone: its own storage, an SD card); empty where that isn't a setting. */
    val places: List<SongCachePlace> = emptyList(),
    val place: String? = null,
    /** The size and place can be changed here (a phone); a server has them in ytmp.toml. */
    val settable: Boolean = false,
    /** Being moved to another place right now. */
    val moving: Boolean = false,
)

@Serializable
@SerialName("SongCachePlace")
data class SongCachePlace(val id: String, val name: String, val freeBytes: Long? = null)

@Serializable
@SerialName("SongCacheSettings")
data class SongCacheSettings(
    val maxBytes: Long,
    val place: String? = null,
    val localSongs: Boolean? = null,
    val localParallel: Int? = null,
    val fetchWifiOnly: Boolean? = null,
    val downloadsWifiOnly: Boolean? = null,
    val localWifiOnly: Boolean? = null,
    val localCharging: Boolean? = null,
    val keepLiked: Int? = null,
    val fromHistory: Boolean? = null,
    val fromTakeout: Boolean? = null,
    val takeoutDays: Int? = null,
    val takeoutMinPlays: Int? = null,
)

/** A Google Takeout history imported on a phone: its YouTube Music [plays] of [songs] songs, from [first] to [last] (ms). */
@Serializable
@SerialName("TakeoutSummary")
data class TakeoutSummary(val songs: Int, val plays: Int, val first: Long, val last: Long)

/** `POST /api/cache/takeout/preview`: which songs these Takeout settings would pick. */
@Serializable
@SerialName("TakeoutPreviewRequest")
data class TakeoutPreviewRequest(val days: Int, val minPlays: Int)

/** The [songs] they pick, [cached] of them already here, and about how much room all of them take ([bytes]). */
@Serializable
@SerialName("TakeoutPreview")
data class TakeoutPreview(val songs: Int, val cached: Int, val bytes: Long)

/** A phone song that has no YouTube Music match (`GET /api/cache/local/unmatched`), and why. */
@Serializable
@SerialName("UnmatchedSong")
data class UnmatchedSong(val key: String, val title: String, val artist: String, val file: String, val why: String)

@Serializable
@SerialName("UnmatchedSongs")
data class UnmatchedSongs(val songs: List<UnmatchedSong>)

/** `POST /api/cache/local/link`: [link] (a YouTube Music link or video ID) is the song of the phone song [key]; empty: it isn't on YouTube Music. */
@Serializable
@SerialName("LocalLinkRequest")
data class LocalLinkRequest(val key: String, val link: String)

/** A download of the phone (Settings → Downloads): [state] is "waiting" (for Wi-Fi, say), "downloading" or "failed" with [error]. */
@Serializable
@SerialName("DownloadItem")
data class DownloadItem(val song: Song, val state: String, val error: String? = null)

@Serializable
@SerialName("DownloadItems")
data class DownloadItems(val items: List<DownloadItem>)

/** `POST /api/cache/downloads/{cancel,retry,now}`: which song. */
@Serializable
@SerialName("DownloadRequest")
data class DownloadRequest(val songId: String)
