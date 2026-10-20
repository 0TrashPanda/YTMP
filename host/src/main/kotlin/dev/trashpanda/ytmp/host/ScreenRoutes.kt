package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.ScreenRefused
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ScreenCastRequest
import dev.trashpanda.ytmp.protocol.ScreenLinkRequest
import dev.trashpanda.ytmp.protocol.ScreenLinked
import dev.trashpanda.ytmp.protocol.ScreenUnlinkRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** A TV waiting to be linked asks again after this long. */
private const val SCREEN_WAIT_MS = 25_000L

private val screenCodeLimit = PerMinuteLimit(perMinute = 10)

/**
 * Party screens on a TV (docs/features/ui.md#party-screen): it shows a code, someone in a room
 * enters it ([ScreenPairings]); or the app shows one on a TV on its own network.
 */
internal fun Route.screenRoutes(host: HostContext) = with(host) {
    post("/screens") {
        if (!screenCodeLimit.allow(call.request.origin.remoteAddress)) {
            throw ApiException(HttpStatusCode.TooManyRequests, ErrorCode.INVALID, "Too many codes, try again in a minute")
        }
        call.respond(screens.start())
    }
    get("/screens/{secret}") {
        val linked = try {
            screens.await(call.parameters["secret"]!!, SCREEN_WAIT_MS)
        } catch (e: NoSuchElementException) {
            throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, e.message ?: "This code expired")
        }
        if (linked == null) call.respond(HttpStatusCode.NoContent) else call.respond(linked)
    }
    post("/screens/link") {
        val request = call.receive<ScreenLinkRequest>()
        val room = rooms[request.roomCode] ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Room not found")
        var full = false
        val linked = screens.link(request.code) {
            room.addScreen(request.guestToken)?.let { ScreenLinked(room.code, it) }.also { full = it == null }
        }
        if (!linked) {
            throw ApiException(
                HttpStatusCode.NotFound, ErrorCode.NOT_FOUND,
                if (full) "This room can't show on another screen" else "No TV shows that code. Check it, or get a new one on the TV.",
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
    // A TV on your own network shows the party screen, started by the app (CastScreens there).
    post("/screens/cast") {
        val request = call.receive<ScreenCastRequest>()
        val room = rooms[request.roomCode] ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Room not found")
        val token = room.addPlayingScreen(request.guestToken, request.name).getOrElse { e ->
            val error = (e as ScreenRefused).error
            val status = when (error.code) {
                ErrorCode.PERMISSION_DENIED -> HttpStatusCode.Forbidden
                ErrorCode.NOT_FOUND -> HttpStatusCode.NotFound
                else -> HttpStatusCode.Conflict
            }
            throw ApiException(status, error.code, error.message)
        }
        call.respond(ScreenLinked(room.code, token))
    }
    post("/screens/unlink") {
        val request = call.receive<ScreenUnlinkRequest>()
        val room = rooms[request.roomCode] ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Room not found")
        if (!room.removeScreen(request.guestToken)) throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No such screen in this room")
        call.respond(HttpStatusCode.NoContent)
    }
}
