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
)

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
)

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
    /** Your searches and suggestions go through this account (see [YoutubeSearchSetting]). */
    val searchWithAccount: Boolean = true,
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
 * `PUT /api/me/youtube/search`: whether your own searches (and the suggestions while typing)
 * go through your YouTube Music account, so they fit your taste like in YouTube Music. Others'
 * searches in your rooms never do.
 */
@Serializable
@SerialName("YoutubeSearchSetting")
data class YoutubeSearchSetting(val withAccount: Boolean)

/** `GET`/`PUT /api/me/likes/{songId}`: whether you like a song (thumbs up) in YouTube Music. */
@Serializable
@SerialName("LikeStatus")
data class LikeStatus(val liked: Boolean)

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
