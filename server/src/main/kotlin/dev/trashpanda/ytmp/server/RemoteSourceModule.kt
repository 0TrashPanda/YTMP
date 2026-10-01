package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.protocol.Song
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * Talks to a source module that runs as a separate program, over the module HTTP API
 * (docs/implementation/module-api.md).
 */
class RemoteSourceModule(
    private val client: HttpClient,
    private val baseUrl: String,
    private val key: String,
) : SongSearch, StreamResolver {

    @Serializable
    private data class SearchResult(val items: List<Song>)

    @Serializable
    private data class StreamResult(val url: String)

    @Serializable
    private data class ErrorBody(val error: Detail) {
        @Serializable
        data class Detail(val code: String, val message: String)
    }

    class ModuleException(val code: String, message: String) : Exception(message)

    override suspend fun search(query: String): List<Song> =
        client.get("$baseUrl/search") {
            auth()
            parameter("q", query)
            parameter("limit", 20)
        }.orThrow().body<SearchResult>().items

    override suspend fun resolveStream(songId: String): String =
        client.get("$baseUrl/songs/${songId.encodeURLPathPart()}/stream") { auth() }
            .orThrow().body<StreamResult>().url

    /** Streams the audio through the host. [block] gets the module's response while it is open. */
    suspend fun <T> audio(songId: String, range: String?, block: suspend (HttpResponse) -> T): T =
        client.prepareGet("$baseUrl/songs/${songId.encodeURLPathPart()}/audio") {
            auth()
            if (range != null) header(HttpHeaders.Range, range)
        }.execute { block(it) }

    private fun HttpRequestBuilder.auth() {
        if (key.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $key")
    }

    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (status.isSuccess()) return this
        val error = runCatching { body<ErrorBody>().error }.getOrNull()
        throw ModuleException(error?.code ?: "upstream_error", error?.message ?: "Module answered $status: ${bodyAsText().take(200)}")
    }
}
