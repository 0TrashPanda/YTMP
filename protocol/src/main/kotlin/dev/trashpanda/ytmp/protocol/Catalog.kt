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
