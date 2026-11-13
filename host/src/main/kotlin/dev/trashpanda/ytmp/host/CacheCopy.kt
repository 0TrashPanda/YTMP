package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.Song
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** A song in the song cache, for another of the owner's devices to copy. */
@Serializable
data class CachedSongFile(val song: Song, val size: Long, val type: String)

@Serializable
data class CachedSongFiles(val songs: List<CachedSongFile>)

/**
 * The song cache for the owner's other devices (the desktop app copies a phone's songs over
 * Wi-Fi, docs/implementation/desktop-app.md#songs-from-your-phone): the list
 * (`GET /api/cache/songs`) and a song's file (`GET /api/cache/songs/<id>`). Only for the
 * device's owner: its own page, or their account logged in on another device.
 */
internal fun Route.cacheCopyRoutes(host: HostContext) = with(host) {
    fun ApplicationCall.cache(): SongCache {
        if (!options.isLocal(this) && !isDeviceOwner()) {
            throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only this phone's owner can copy its songs: log in with that account")
        }
        return sources.cache ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No song cache here")
    }
    route("/cache/songs") {
        get { call.respond(CachedSongFiles(call.cache().copyable())) }
        get("/{songId}") {
            val songId = call.parameters["songId"]!!
            val (file, type) = call.cache().fileOf(songId) ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "That song isn't in the cache now")
            SongCache.respondFile(call, file, type, call.request.header(HttpHeaders.Range))
        }
    }
}
