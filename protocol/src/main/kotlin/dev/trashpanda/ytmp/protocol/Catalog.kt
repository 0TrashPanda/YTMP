package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
