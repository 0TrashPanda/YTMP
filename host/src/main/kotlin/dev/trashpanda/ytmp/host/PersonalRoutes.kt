package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.protocol.CreatePlaylistRequest
import dev.trashpanda.ytmp.protocol.CreatePlaylistResponse
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.MovePlaylistItemRequest
import dev.trashpanda.ytmp.protocol.MyPlaylists
import dev.trashpanda.ytmp.protocol.PodcastSavedRequest
import dev.trashpanda.ytmp.protocol.RemovePlaylistItemsRequest
import dev.trashpanda.ytmp.protocol.RenamePlaylistRequest
import dev.trashpanda.ytmp.protocol.SaveSongsRequest
import dev.trashpanda.ytmp.protocol.SearchResponse
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.SuggestionsResponse
import dev.trashpanda.ytmp.protocol.YoutubeAccountStatus
import dev.trashpanda.ytmp.protocol.YoutubeHistory
import dev.trashpanda.ytmp.protocol.YoutubeHistorySetting
import dev.trashpanda.ytmp.protocol.YoutubePersonalizeSetting
import dev.trashpanda.ytmp.protocol.YoutubeSignInRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * `/api/me/…`: your YouTube Music (docs/features/accounts.md#linked-youtube-account): your Home,
 * Library, playlists and likes, and search and radio as you. On a server also for the pages of
 * rooms on other hosts ([HostOptions.crossOriginPersonal]).
 */
internal fun Route.personalRoutes(host: HostContext) = with(host) {
    route("/me") {
        if (options.crossOriginPersonal) {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
            }
        }
        fun ApplicationCall.owner(): PersonalCatalog =
            personal() ?: throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Log in to use your YouTube Music here")
        // Your Home, search and radio, for pages of other hosts (theirs are /api/home, /api/search, …).
        get("/home") { call.respond(call.owner().home()) }
        get("/search") {
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Missing q")
            val type = call.request.queryParameters["type"]?.let(::searchType) ?: SearchType.ALL
            call.respond(call.owner().search(query, type) ?: throw notHere())
        }
        get("/search/suggestions") {
            val query = call.request.queryParameters["q"].orEmpty()
            if (query.isBlank()) return@get call.respond(SuggestionsResponse(emptyList()))
            call.respond(SuggestionsResponse(call.owner().suggestions(query) ?: throw notHere()))
        }
        get("/similar") {
            val songId = call.request.queryParameters["id"]?.trim().orEmpty()
            if (songId.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Missing id")
            val owner = call.owner()
            call.respond(SearchResponse(call.similarFrom(songId) { owner.radio(songId) ?: throw notHere() }.filter { it.id != songId }))
        }
        get("/youtube") {
            val mine = call.personal()
            call.respond(
                YoutubeAccountStatus(
                    available = mine != null,
                    account = mine?.account(),
                    history = mine?.historySetting() ?: YoutubeHistory.SOLO,
                    personalize = mine?.personalize() ?: true,
                ),
            )
        }
        put("/youtube/personalize") {
            val owner = call.owner()
            owner.setPersonalize(call.receive<YoutubePersonalizeSetting>().on)
            call.respond(HttpStatusCode.NoContent)
        }
        put("/youtube/history") {
            val owner = call.owner()
            owner.setHistorySetting(call.receive<YoutubeHistorySetting>().history)
            call.respond(HttpStatusCode.NoContent)
        }
        put("/youtube") {
            val owner = call.owner()
            val account = try {
                owner.signIn(call.receive<YoutubeSignInRequest>().cookie)
            } catch (e: UnsupportedOperationException) {
                throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, e.message ?: "Sign in in the app")
            }
            call.respond(account)
        }
        delete("/youtube") {
            call.owner().signOut()
            call.respond(HttpStatusCode.NoContent)
        }
        get("/library") { call.respond(call.owner().library()) }
        get("/playlists") { call.respond(MyPlaylists(call.owner().ownPlaylists())) }
        post("/playlists") {
            val owner = call.owner()
            val request = call.receive<CreatePlaylistRequest>()
            val title = request.title.trim()
            if (title.isEmpty() || title.length > 150) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Playlist name must be 1-150 characters")
            call.respond(CreatePlaylistResponse(owner.createPlaylist(title, request.songIds)))
        }
        get("/playlists/{id}") { call.respond(call.owner().playlist(call.parameters["id"]!!, call.request.queryParameters["more"])) }
        post("/playlists/{id}/songs") {
            val owner = call.owner()
            owner.addToPlaylist(call.parameters["id"]!!, call.receive<SaveSongsRequest>().songIds)
            call.respond(HttpStatusCode.NoContent)
        }
        // Your own playlists: remove songs, move one, rename, delete.
        post("/playlists/{id}/remove") {
            val owner = call.owner()
            owner.removeFromPlaylist(call.parameters["id"]!!, call.receive<RemovePlaylistItemsRequest>().items)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/playlists/{id}/move") {
            val owner = call.owner()
            val request = call.receive<MovePlaylistItemRequest>()
            owner.movePlaylistItem(call.parameters["id"]!!, request.itemId, request.beforeItemId)
            call.respond(HttpStatusCode.NoContent)
        }
        put("/playlists/{id}") {
            val owner = call.owner()
            val title = call.receive<RenamePlaylistRequest>().title.trim()
            if (title.isEmpty() || title.length > 150) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Playlist name must be 1-150 characters")
            owner.renamePlaylist(call.parameters["id"]!!, title)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/playlists/{id}") {
            call.owner().deletePlaylist(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
        // Podcasts as you: subscribed or not, and subscribing.
        get("/podcasts/{id}") { call.respond(call.owner().podcast(call.parameters["id"]!!) ?: throw notHere()) }
        put("/podcasts/{id}/saved") {
            val owner = call.owner()
            owner.setPodcastSaved(call.parameters["id"]!!, call.receive<PodcastSavedRequest>().saved)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/likes/{songId}") { call.respond(call.owner().rating(call.parameters["songId"]!!)) }
        put("/likes/{songId}") {
            val owner = call.owner()
            val rating = call.receive<LikeStatus>()
            owner.setRating(call.parameters["songId"]!!, rating.copy(disliked = rating.disliked && !rating.liked))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun notHere() = ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Not with this YouTube Music sign-in")
