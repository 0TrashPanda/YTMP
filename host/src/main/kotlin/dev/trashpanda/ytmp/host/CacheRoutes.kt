package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.DownloadItems
import dev.trashpanda.ytmp.protocol.DownloadRequest
import dev.trashpanda.ytmp.protocol.LocalLinkRequest
import dev.trashpanda.ytmp.protocol.SongCacheSettings
import dev.trashpanda.ytmp.protocol.UnmatchedSongs
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
        return if (settings == null) status else status.copy(places = settings.places(), place = settings.place(), settable = true, localSongs = settings.localSongs(), checking = settings.checking(), checkingOf = settings.checkingOf(), localParallel = settings.localParallel(),
            fetchWifiOnly = settings.fetchWifiOnly(), downloadsWifiOnly = settings.downloadsWifiOnly(),
            localWifiOnly = settings.localWifiOnly(), localCharging = settings.localCharging(), localWaiting = settings.localWaiting()).let { s ->
                val youtube = settings.fromYoutube() ?: return@let s
                s.copy(keepLiked = youtube.liked, likedOf = youtube.likedOf, likedCached = youtube.likedCached, fromHistory = youtube.fromHistory, fromYoutubeWaiting = youtube.waiting)
            }
    }
    get { call.respond(call.status()) }
    put {
        call.status()
        val wanted = call.receive<SongCacheSettings>()
        settings ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "The song cache is set in ytmp.toml here")
        settings.change(wanted)
        call.respond(call.status())
    }
    route("/downloads") {
        suspend fun ApplicationCall.phone(): CacheSettings {
            status()
            return settings ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Only on a phone")
        }
        get { call.respond(DownloadItems(call.phone().downloadQueue())) }
        post("/cancel") { val phone = call.phone(); phone.cancelDownload(call.receive<DownloadRequest>().songId); call.respond(DownloadItems(phone.downloadQueue())) }
        post("/retry") { val phone = call.phone(); phone.retryDownload(call.receive<DownloadRequest>().songId); call.respond(DownloadItems(phone.downloadQueue())) }
        post("/now") { val phone = call.phone(); phone.downloadNow(call.receive<DownloadRequest>().songId); call.respond(DownloadItems(phone.downloadQueue())) }
    }
    route("/local") {
        suspend fun ApplicationCall.phone(): CacheSettings {
            status()
            return settings ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Only on a phone")
        }
        get("/unmatched") { call.respond(UnmatchedSongs(call.phone().unmatched())) }
        post("/recheck") { call.phone().recheck(); call.respond(call.status()) }
        post("/forget") { call.phone().forget(); call.respond(call.status()) }
        post("/link") {
            val phone = call.phone()
            val wanted = call.receive<LocalLinkRequest>()
            phone.link(wanted.key, wanted.link)?.let { throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, it) }
            call.respond(call.status())
        }
    }
    post("/clear") {
        call.status()
        cache?.clear()
        call.respond(call.status())
    }
}
