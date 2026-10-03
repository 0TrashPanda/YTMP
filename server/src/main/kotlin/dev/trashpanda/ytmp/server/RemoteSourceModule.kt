package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.host.AudioProxy
import dev.trashpanda.ytmp.host.SourceException
import dev.trashpanda.ytmp.protocol.Song
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.protocol.AlbumPage
import dev.trashpanda.ytmp.protocol.ArtistPage
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchType
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
) : SongSearch, StreamResolver, AudioProxy, RadioSource, CatalogSource {

    @Serializable
    private data class SearchResult(val items: List<Song>)

    @Serializable
    private data class StreamResult(val url: String)

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

    override suspend fun resolveStream(songId: String): String =
        client.get("$baseUrl/songs/${songId.encodeURLPathPart()}/stream") { auth() }
            .orThrow().body<StreamResult>().url

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

    private fun HttpRequestBuilder.auth() {
        if (key.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $key")
    }

    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (status.isSuccess()) return this
        val error = runCatching { body<ErrorBody>().error }.getOrNull()
        throw ModuleException(error?.code ?: "upstream_error", error?.message ?: "Module answered $status: ${bodyAsText().take(200)}")
    }
}
