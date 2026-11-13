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
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** A song here, for another of the owner's devices to copy; [size] and [type] are null for a download or music file (not known before it's sent). */
@Serializable
data class CachedSongFile(val song: Song, val size: Long?, val type: String?)

@Serializable
data class CachedSongFiles(val songs: List<CachedSongFile>)

/** A key for copying without your server (`POST /api/cache/songs/key`): this device keeps it, the computer too. */
@Serializable
data class CopyKey(val key: String, val deviceId: String, val name: String)

/**
 * Keys that let one of the owner's computers copy this phone's songs without asking the
 * server for a login (when it's down): made once with a login, kept on both sides.
 */
interface CopyKeys {
    /** This device's own id and name. */
    val deviceId: String
    val name: String

    fun make(): String

    fun valid(key: String): Boolean

    /** Shows this device to the owner's computer now (*Send songs to my computer*); false when it can't. */
    fun showNow(): Boolean = false
}

/** The header a computer sends its [CopyKey] in. */
const val COPY_KEY_HEADER = "X-Copy-Key"

/**
 * This device's songs for the owner's other devices (the desktop app copies a phone's songs over
 * Wi-Fi, docs/implementation/apps/desktop-app.md#songs-from-your-phone-): the list
 * (`GET /api/cache/songs`) and a song's file (`GET /api/cache/songs/<id>`). Only for the
 * device's owner: its own page, their account logged in on another device, or a computer with a
 * [CopyKey] it got that way.
 */
internal fun Route.cacheCopyRoutes(host: HostContext) = with(host) {
    fun ApplicationCall.byOwner(): Boolean = options.isLocal(this) || isDeviceOwner()

    fun ApplicationCall.cache(): SongCache {
        val key = request.header(COPY_KEY_HEADER)
        if (!byOwner() && (key == null || options.copyKeys?.valid(key) != true)) {
            throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only this phone's owner can copy its songs: log in with that account")
        }
        return sources.cache ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No song cache here")
    }
    route("/cache/songs") {
        get { call.respond(CachedSongFiles(call.cache().copyable())) }
        // A key for next time, only with a login (not with another key).
        post("/key") {
            if (!call.byOwner()) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only this phone's owner can copy its songs: log in with that account")
            val keys = options.copyKeys ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Not on this device")
            call.respond(CopyKey(keys.make(), keys.deviceId, keys.name))
        }
        // The phone's own page: *Send songs to my computer*.
        post("/show") {
            if (!options.isLocal(call)) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only on the phone itself")
            if (options.copyKeys?.showNow() != true) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Log in with your account in YTMP on this phone first")
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{songId}") {
            val songId = call.parameters["songId"]!!
            if (!call.cache().respondCopy(call, songId, call.request.header(HttpHeaders.Range))) {
                throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "That song isn't here now")
            }
        }
    }
}
