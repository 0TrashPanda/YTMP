package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.SongCacheSettings
import dev.trashpanda.ytmp.protocol.SongCacheStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * The song cache in the settings, at [path]: how full it is (`GET`), *Clear cache*
 * (`POST …/clear`), and where there are [settings] its size and place (`PUT`). Only for whoever
 * [allowed] says: a phone's owner on the phone (`/api/cache`), a server's admin (`/api/admin/cache`).
 */
fun Route.songCacheRoutes(path: String, cache: SongCache?, settings: CacheSettings?, allowed: suspend (ApplicationCall) -> Boolean) = route(path) {
    suspend fun ApplicationCall.status(): SongCacheStatus {
        if (!allowed(this)) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only the owner can see the song cache")
        val status = cache?.status() ?: SongCacheStatus(songs = 0, bytes = 0, maxBytes = 0)
        return if (settings == null) status else status.copy(places = settings.places(), place = settings.place(), settable = true, localSongs = settings.localSongs(), checking = settings.checking(), checkingOf = settings.checkingOf(), localParallel = settings.localParallel())
    }
    get { call.respond(call.status()) }
    put {
        call.status()
        val wanted = call.receive<SongCacheSettings>()
        settings ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "The song cache is set in ytmp.toml here")
        settings.change(wanted)
        call.respond(call.status())
    }
    post("/clear") {
        call.status()
        cache?.clear()
        call.respond(call.status())
    }
}
