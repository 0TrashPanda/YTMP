package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.AccountIdentity
import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.core.Room
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.mixedRadio
import dev.trashpanda.ytmp.core.personalized
import dev.trashpanda.ytmp.protocol.Song
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * What the host's routes share: its rooms, what it offers ([HostSources]), how it behaves
 * ([HostOptions]), and who a request is (its account, its YouTube Music). The routes are in
 * files by area: [roomRoutes], [catalogRoutes], [personalRoutes], [screenRoutes].
 */
internal class HostContext(val rooms: RoomManager, val sources: HostSources, val options: HostOptions) {
    val screens = ScreenPairings()

    fun ApplicationCall.mayManageRooms() = !options.localOnlyRoomManagement || options.isLocal(this)

    /** The account this request proves (bearer host token), if any. */
    fun ApplicationCall.account(): AccountIdentity? {
        val token = bearerToken() ?: return null
        return options.auth.verify(token)?.also {
            options.onAccountToken(token, it)
            if (options.isLocal(this)) options.onLocalAccount(it)
        }
    }

    /** Logged in with the account of whoever owns this device (a phone), on another device. */
    fun ApplicationCall.isDeviceOwner(): Boolean {
        val owner = options.deviceOwner() ?: return false
        return account()?.id == owner
    }

    /**
     * Your YouTube Music: on a phone, its owner's, in their own app or logged in with their
     * account on another device; on a server, your account's.
     */
    fun ApplicationCall.personal(): PersonalCatalog? {
        sources.personalFor?.invoke(this)?.let { return it }
        if (options.isLocal(this)) {
            // The device's own page: notes who's logged in there (its owner).
            account()
            return sources.personal
        }
        return sources.personal?.takeIf { isDeviceOwner() }
    }

    /**
     * Find similar from where the request's `from` says (Find similar's hold): YouTube Music's
     * radio and the song graph mixed (the usual), only one of them, or only what plays here
     * without internet.
     */
    suspend fun ApplicationCall.similarFrom(songId: String, radio: suspend () -> List<Song>): List<Song> = when (request.queryParameters["from"]) {
        "ytm" -> radio()
        "graph" -> sources.songGraph?.invoke(this, songId).orEmpty()
        "device" -> withGraph(songId) { runCatching { radio() }.getOrDefault(emptyList()) }
            .filter { LocalMusic.isLocal(it.id) || sources.cache?.has(it.id) == true }
        else -> withGraph(songId, radio)
    }

    /** [radio] (of [songId]) with the song graph's songs for whoever asks mixed in ([mixedRadio]). */
    suspend fun ApplicationCall.withGraph(songId: String, radio: suspend () -> List<Song>): List<Song> {
        val graph = sources.songGraph ?: return radio()
        return mixedRadio(radio) { graph(this, songId) }
    }

    /**
     * Your own searches (and Related) go through your YouTube Music account, when you're signed
     * in and want that; everyone else's (and yours, if that fails) through the shared, anonymous one.
     */
    suspend fun <T : Any> ApplicationCall.asYou(mine: suspend PersonalCatalog.() -> T?, anonymous: suspend () -> T): T {
        val you = personal()?.personalized()
        if (you != null) {
            try {
                you.mine()?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Searching with the YouTube Music account didn't work, searching without: {}", e.message)
            }
        }
        return anonymous()
    }

    /** The room's owner: its owner token (header), or its owner account. */
    fun ApplicationCall.owns(room: Room) =
        request.header(OWNER_TOKEN_HEADER) == room.ownerToken || (room.ownerAccount != null && account()?.id == room.ownerAccount)

    private companion object {
        val log = LoggerFactory.getLogger("ytmp")
    }
}
