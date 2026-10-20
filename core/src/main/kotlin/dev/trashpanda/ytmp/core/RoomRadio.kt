package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Where the rooms' radios come from (Start radio, Autoplay this, autoplay): YouTube Music's
 * ([shared]), or the room owner's own account's ([owner]), with the song graph mixed in
 * ([graph]). See docs/features/history-and-recommendations.md#radio--mix.
 */
class RoomRadio(
    /** YTM's radio. */
    private val shared: RadioSource,
    /**
     * The room owner's own radio (their YouTube Music account), by owner account (null: a room
     * without one), when they have one and want it ([PersonalCatalog.personalized]). Null: the [shared] one.
     */
    private val owner: suspend (ownerAccount: String?, seedSongId: String) -> List<Song>? = { _, _ -> null },
    /**
     * Songs that go with the seed in the song graph (the owner's listening and the server's),
     * mixed into every radio ([mixRadio]). Empty: none known.
     */
    private val graph: suspend (ownerAccount: String?, seedSongId: String) -> List<Song> = { _, _ -> emptyList() },
) {
    /**
     * A room's radio: its owner's own when there is one, else (or when that fails) the shared
     * one, with the song graph mixed in (the whole radio when there's no other, or that failed).
     * [ownerAccount] is asked each time: the owner can change.
     */
    fun forRoom(ownerAccount: () -> String?): RadioSource {
        val base = RadioSource { seed ->
            try {
                owner(ownerAccount(), seed)?.let { return@RadioSource it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("The owner's radio didn't work, using the shared one: {}", e.message)
            }
            shared.radio(seed)
        }
        return RadioSource { seed -> mixedRadio({ base.radio(seed) }, { graph(ownerAccount(), seed) }) }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RoomRadio::class.java)
    }
}

/** What the host hears about its rooms ([RoomManager]). None of them may block. */
data class RoomHooks(
    /** Songs that finished playing, for the listening history. */
    val onPlayFinished: (FinishedPlay) -> Unit = {},
    /** A room with an owner account was saved: its copy elsewhere can be updated (see [Room.syncId]). */
    val onRoomSaved: (SavedRoom) -> Unit = {},
    /** A room with an owner account was closed or expired here (not moved away). */
    val onRoomClosed: (Room) -> Unit = {},
    /** A room is no longer [account]'s (it got another owner): its copy under [syncId] can go. */
    val onRoomDisowned: (account: String, syncId: String) -> Unit = { _, _ -> },
)
