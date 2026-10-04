package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.SearchSuggestions
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.core.ResolvedStream
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.host.AudioProxy
import dev.trashpanda.ytmp.host.SourceException
import dev.trashpanda.ytmp.protocol.Song
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import dev.trashpanda.ytmp.protocol.CreatePlaylistRequest
import dev.trashpanda.ytmp.protocol.CreatePlaylistResponse
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.PlaylistSummary
import dev.trashpanda.ytmp.protocol.SaveSongsRequest
import dev.trashpanda.ytmp.protocol.YoutubeAccount
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.protocol.AlbumPage
import dev.trashpanda.ytmp.protocol.ArtistPage
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.PodcastPage
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.SuggestionsResponse
import dev.trashpanda.ytmp.protocol.wireName
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.Serializable

/**
 * Talks to a source module that runs as a separate program, over the module HTTP API
 * (docs/implementation/module-api.md).
 */
class RemoteSourceModule(
    private val client: HttpClient,
    private val baseUrl: String,
    private val key: String,
) : SongSearch, StreamResolver, AudioProxy, RadioSource, CatalogSource, SearchSuggestions {

    @Serializable
    private data class SearchResult(val items: List<Song>)

    @Serializable
    private data class StreamResult(val url: String, val durationMs: Long? = null)

    @Serializable
    private data class ErrorBody(val error: Detail) {
        @Serializable
        data class Detail(val code: String, val message: String)
    }

    class ModuleException(val code: String, message: String) : SourceException(message)

    override suspend fun search(query: String, type: SearchType): SearchPage =
        client.get("$baseUrl/search") {
            auth()
            parameter("q", query)
            parameter("type", type.wireName)
            parameter("limit", 20)
        }.orThrow().body()

    override suspend fun suggestions(query: String): List<String> =
        client.get("$baseUrl/search/suggestions") {
            auth()
            parameter("q", query)
        }.orThrow().body<SuggestionsResponse>().items

    override suspend fun radio(seedSongId: String): List<Song> =
        client.get("$baseUrl/radio") {
            auth()
            parameter("seed", seedSongId)
            parameter("limit", 25)
        }.orThrow().body<SearchResult>().items

    override suspend fun artist(id: String): ArtistPage =
        client.get("$baseUrl/artists/${id.encodeURLPathPart()}") { auth() }.orThrow().body()

    override suspend fun album(id: String): AlbumPage =
        client.get("$baseUrl/albums/${id.encodeURLPathPart()}") { auth() }.orThrow().body()

    override suspend fun playlist(id: String): PlaylistPage =
        client.get("$baseUrl/playlists/${id.encodeURLPathPart()}") { auth() }.orThrow().body()

    override suspend fun podcast(id: String): PodcastPage =
        client.get("$baseUrl/podcasts/${id.encodeURLPathPart()}") { auth() }.orThrow().body()

    override suspend fun home(): HomePage = client.get("$baseUrl/home") { auth() }.orThrow().body()

    override suspend fun resolveStream(songId: String): String = resolve(songId).url

    override suspend fun resolve(songId: String): ResolvedStream =
        client.get("$baseUrl/songs/${songId.encodeURLPathPart()}/stream") { auth() }
            .orThrow().body<StreamResult>().let { ResolvedStream(it.url, it.durationMs) }

    /** Streams the module's audio (status, range headers, body) straight through to the caller. */
    override suspend fun respond(call: ApplicationCall, songId: String, range: String?) {
        client.prepareGet("$baseUrl/songs/${songId.encodeURLPathPart()}/audio") {
            auth()
            if (range != null) header(HttpHeaders.Range, range)
        }.execute { response -> call.respond(ProxiedAudio(response, response.bodyAsChannel())) }
    }

    private class ProxiedAudio(response: HttpResponse, private val body: ByteReadChannel) : OutgoingContent.ReadChannelContent() {
        override val status = response.status
        override val contentType: ContentType? = response.contentType()
        override val contentLength: Long? = response.contentLength()
        override val headers: Headers = headersOf(
            *listOfNotNull(
                HttpHeaders.AcceptRanges to listOf("bytes"),
                response.headers[HttpHeaders.ContentRange]?.let { HttpHeaders.ContentRange to listOf(it) },
            ).toTypedArray(),
        )

        override fun readFrom(): ByteReadChannel = body
    }

    // --- one account's YouTube Music: the server keeps the sign-in and sends it each time ---

    suspend fun myAccount(cookie: String): YoutubeAccount? = client.get("$baseUrl/me/account") { me(cookie) }.orThrow().body()

    suspend fun myHome(cookie: String): HomePage = client.get("$baseUrl/me/home") { me(cookie) }.orThrow().body()

    suspend fun myLibrary(cookie: String): HomePage = client.get("$baseUrl/me/library") { me(cookie) }.orThrow().body()

    suspend fun myPlaylist(cookie: String, id: String): PlaylistPage =
        client.get("$baseUrl/me/playlists/${id.encodeURLPathPart()}") { me(cookie) }.orThrow().body()

    suspend fun myPlaylists(cookie: String): List<PlaylistSummary> = client.get("$baseUrl/me/playlists") { me(cookie) }.orThrow().body()

    suspend fun myNewPlaylist(cookie: String, title: String, songIds: List<String>): String =
        client.post("$baseUrl/me/playlists") { me(cookie, CreatePlaylistRequest(title, songIds)) }.orThrow().body<CreatePlaylistResponse>().id

    suspend fun addToMyPlaylist(cookie: String, id: String, songIds: List<String>) {
        client.post("$baseUrl/me/playlists/${id.encodeURLPathPart()}/songs") { me(cookie, SaveSongsRequest(songIds)) }.orThrow()
    }

    suspend fun myLike(cookie: String, songId: String): Boolean =
        client.get("$baseUrl/me/likes/${songId.encodeURLPathPart()}") { me(cookie) }.orThrow().body<LikeStatus>().liked

    suspend fun setMyLike(cookie: String, songId: String, liked: Boolean) {
        client.put("$baseUrl/me/likes/${songId.encodeURLPathPart()}") { me(cookie, LikeStatus(liked)) }.orThrow()
    }

    suspend fun addToMyHistory(cookie: String, songId: String) {
        client.post("$baseUrl/me/history/${songId.encodeURLPathPart()}") { me(cookie) }.orThrow()
    }

    private inline fun <reified T : Any> HttpRequestBuilder.me(cookie: String, body: T) {
        me(cookie)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.me(cookie: String) {
        auth()
        header("X-Ytm-Cookie", cookie)
    }

    private fun HttpRequestBuilder.auth() {
        if (key.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $key")
    }

    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (status.isSuccess()) return this
        val error = runCatching { body<ErrorBody>().error }.getOrNull()
        throw ModuleException(error?.code ?: "upstream_error", error?.message ?: "Module answered $status: ${bodyAsText().take(200)}")
    }
}
