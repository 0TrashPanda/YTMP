package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ConvertItemState
import dev.trashpanda.ytmp.protocol.ConvertJobList
import dev.trashpanda.ytmp.protocol.ConvertPick
import dev.trashpanda.ytmp.protocol.ConvertStartRequest
import dev.trashpanda.ytmp.protocol.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * `/api/me/convert/…`: importing playlists into your YouTube Music
 * (docs/implementation/playlist-converter.md#api-host). Under `/me`, so a page of a room on
 * another host reaches the host with your YouTube Music sign-in, like the rest of it.
 */
internal fun Route.convertRoutes(host: HostContext) {
    route("/convert") {
        // The file as the body: what's in it.
        post("/read") {
            val reader = call.request.queryParameters["reader"] ?: SpotifyExport.READER
            converting(host) { owner -> read(owner, reader, call.receiveStream()) }
        }
        get("/jobs") { converting(host) { owner -> ConvertJobList(jobs(owner)) } }
        post("/jobs") {
            val request = call.receive<ConvertStartRequest>()
            converting(host) { owner -> start(owner, request) }
        }
        get("/jobs/{id}") { converting(host) { owner -> job(owner, call.parameters["id"]!!) } }
        get("/jobs/{id}/items") {
            val state = call.request.queryParameters["state"]?.let { s -> ConvertItemState.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
            converting(host) { owner -> items(owner, call.parameters["id"]!!, state, call.request.queryParameters["cursor"]) }
        }
        put("/jobs/{id}/items/{key}") {
            val pick = call.receive<ConvertPick>()
            converting(host) { owner -> pick(owner, call.parameters["id"]!!, call.parameters["key"]!!, pick) }
        }
        post("/jobs/{id}/accept-unsure") { converting(host) { owner -> acceptUnsure(owner, call.parameters["id"]!!) } }
        post("/jobs/{id}/pause") { converting(host) { owner -> pause(owner, call.parameters["id"]!!, paused = true) } }
        post("/jobs/{id}/resume") { converting(host) { owner -> pause(owner, call.parameters["id"]!!, paused = false) } }
        post("/jobs/{id}/cancel") { converting(host) { owner -> cancel(owner, call.parameters["id"]!!) } }
        delete("/jobs/{id}") { converting(host) { owner -> remove(owner, call.parameters["id"]!!) } }
    }
}

/** [block] with the converter and whose imports these are, answered as JSON (204 for nothing); its refusals as 400 or 404. */
private suspend inline fun <reified T : Any> RoutingContext.converting(host: HostContext, crossinline block: suspend PlaylistConverter.(owner: String) -> T) {
    val converter = host.sources.converter ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Importing playlists isn't on this host")
    val owner = with(host) { call.personalOwner() } ?: throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Log in to use your YouTube Music here")
    val answer = try {
        converter.block(owner)
    } catch (e: ConvertReadException) {
        throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, e.message ?: "Can't read that")
    } catch (e: ConvertException) {
        throw ApiException(if (e.notFound) HttpStatusCode.NotFound else HttpStatusCode.BadRequest, if (e.notFound) ErrorCode.NOT_FOUND else ErrorCode.INVALID, e.message ?: "Can't do that")
    }
    if (answer is Unit) call.respond(HttpStatusCode.NoContent) else call.respond(answer)
}
