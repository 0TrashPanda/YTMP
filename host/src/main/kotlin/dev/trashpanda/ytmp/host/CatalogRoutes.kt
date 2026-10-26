package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.AlbumRef
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.LocalLibraries
import dev.trashpanda.ytmp.protocol.SearchResponse
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.SuggestionsResponse
import dev.trashpanda.ytmp.protocol.wireName
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ytmp")

/**
 * Finding music: search, Find similar, lyrics, artist, album, playlist and podcast pages, Home,
 * the Local tab, songs' audio through the host, and downloads.
 */
internal fun Route.catalogRoutes(host: HostContext) = with(host) {
    get("/search") {
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        if (query.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Missing q")
        val type = call.request.queryParameters["type"]?.let(::searchType) ?: SearchType.ALL
        call.respond(call.asYou({ search(query, type) }) { sources.search.search(query, type) })
    }
    get("/search/suggestions") {
        // Typing goes on without them, so no source (or an empty box) is just no suggestions.
        val query = call.request.queryParameters["q"].orEmpty()
        if (query.isBlank()) return@get call.respond(SuggestionsResponse(emptyList()))
        call.respond(SuggestionsResponse(call.asYou({ suggestions(query) }) { sources.suggestions?.suggestions(query).orEmpty() }))
    }
    get("/similar") {
        val songId = call.request.queryParameters["id"]?.trim().orEmpty()
        if (songId.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Missing id")
        val source = sources.similar ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No radio source")
        call.respond(SearchResponse(call.withGraph(songId) { call.asYou({ radio(songId) }) { source.radio(songId) } }.filter { it.id != songId }))
    }
    get("/lyrics") {
        // The song as the page knows it: LRCLIB looks lyrics up by title, artist, album and length.
        val query = call.request.queryParameters
        val id = query["id"]?.trim().orEmpty()
        val title = query["title"]?.trim().orEmpty()
        if (id.isEmpty() || title.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Missing id or title")
        val song = Song(
            id = id,
            title = title,
            artists = listOfNotNull(query["artist"]?.trim()?.takeIf { it.isNotEmpty() }?.let { ArtistRef(null, it) }),
            album = query["album"]?.trim()?.takeIf { it.isNotEmpty() }?.let { AlbumRef(null, it) },
            durationMs = query["durationMs"]?.toLongOrNull() ?: 0,
            thumbnails = emptyList(),
        )
        call.respond(sources.lyrics?.lyrics(song) ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No lyrics for this song"))
    }
    get("/artists/{id}") { call.respond(catalog("artist").artist(call.parameters["id"]!!)) }
    get("/albums/{id}") { call.respond(catalog("album").album(call.parameters["id"]!!)) }
    // ?more=: the next part of a long playlist ([PlaylistPage.more]).
    get("/playlists/{id}") { call.respond(catalog("playlist").playlist(call.parameters["id"]!!, call.request.queryParameters["more"])) }
    get("/podcasts/{id}") { call.respond(catalog("podcast").podcast(call.parameters["id"]!!)) }
    // A song by its ID: a link to it was pasted in the search box.
    get("/songs/{id}") {
        val source = catalog("song")
        val song = try {
            source.song(call.parameters["id"]!!)
        } catch (e: UnsupportedOperationException) {
            throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, e.message ?: "Songs can't be looked up here")
        }
        call.respond(song)
    }
    get("/home") {
        // The owner's own home page in their app; everyone else gets the general one.
        // ?fresh=1: pulled down to refresh, so not from the cache.
        val fresh = call.request.queryParameters["fresh"] == "1"
        val mine = call.personal()
        if (mine != null && mine.account() != null) {
            return@get call.respond(if (fresh) mine.freshHome() else mine.home())
        }
        val source = catalog("home")
        call.respond(if (fresh) source.freshHome() else source.home())
    }

    // The Local tab: the libraries here, then one whole (filtered in the page as you type).
    get("/local") {
        call.respond(sources.local?.summaries() ?: LocalLibraries(emptyList()))
    }
    // Covers of local music that the host serves itself (the music on a phone), and of cached songs.
    get("/local/art/{songId}") {
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 544
        val songId = call.parameters["songId"]!!
        if (sources.local?.respondArt(call, songId, size) != true && sources.cache?.respondArt(call, songId, size) != true) call.respond(HttpStatusCode.NotFound)
    }
    get("/local/{id}") {
        val page = sources.local?.page(call.parameters["id"]!!) ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "That music isn't on the network now")
        call.respond(page)
    }

    get("/audio/{songId}") {
        val proxy = sources.audio ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No audio source")
        val songId = call.parameters["songId"]!!
        val range = call.request.header(HttpHeaders.Range)
        if (!options.mayProxyAudio(call)) {
            // A cached song's link is this route: a device that may not stream through the host
            // gets YouTube's link instead, as it would without the cache.
            val cache = sources.cache
            if (cache != null && cache.has(songId)) {
                val link = runCatching { cache.directLink(songId) }.onFailure { log.info("audio {}: no link for a device outside the network: {}", songId, it.message) }.getOrNull()
                if (link != null) return@get call.respondRedirect(link)
            }
            // Behind a reverse proxy the peer is the proxy; the visitor is in X-Forwarded-For.
            val forwarded = call.request.headers[HttpHeaders.XForwardedFor]?.let { " (forwarded for $it)" }.orEmpty()
            log.info("audio {}: not streamed to {}{} (outside the network)", songId, call.request.origin.remoteAddress, forwarded)
            throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "This server doesn't stream audio to devices outside its network")
        }
        log.debug("audio {}: through the host for {} ({})", songId, call.request.origin.remoteAddress, range ?: "whole file")
        proxy.respond(call, songId, range)
    }

    // Download: the song (as the page knows it, for the tags) made into a file to keep.
    post("/download") {
        val files = sources.downloads ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Songs can't be downloaded here")
        val song = call.receive<Song>()
        if (LocalMusic.isLocal(song.id)) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Local music can't be downloaded")
        if (!options.mayProxyAudio(call)) {
            throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "This server only sends songs to devices on its own network")
        }
        val made = files.make(song)
        try {
            log.info("download {}: {} ({} KB) for {}", song.id, made.name, made.file.length() / 1024, call.request.origin.remoteAddress)
            call.response.header(HttpHeaders.ContentDisposition, attachment(made.name))
            call.respond(LocalFileContent(made.file, ContentType.parse(made.mimeType)))
        } finally {
            made.file.delete()
        }
    }
}

/** A Content-Disposition for [name], also when it isn't plain ASCII (RFC 6266). */
private fun attachment(name: String): String {
    val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"
}

/** The host's artist, album, playlist, podcast and home pages, or a 404 saying there are no [what] pages here. */
private fun HostContext.catalog(what: String) =
    sources.catalog ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, if (what == "home") "No home page here" else "No $what pages here")

/** "community_playlists" -> [SearchType.COMMUNITY_PLAYLISTS]. */
internal fun searchType(value: String): SearchType =
    SearchType.entries.firstOrNull { it.wireName == value }
        ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Unknown search type: $value")
