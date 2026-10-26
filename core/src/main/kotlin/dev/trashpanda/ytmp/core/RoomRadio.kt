package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.RadioFrom
import dev.trashpanda.ytmp.protocol.RadioHints
import dev.trashpanda.ytmp.protocol.RadioOptions
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * Where the rooms' radios come from (Start radio, Autoplay this, autoplay), made the way the
 * room's [RadioOptions] say: YouTube Music's ([shared], or the room owner's own account's,
 * [owner]), the song graph ([hints]), or both mixed; only what plays here without internet
 * ([onDevice]). A local file with a YouTube Music ID ([ytmIdOf]) gets that song's radio.
 * See docs/features/history-and-recommendations.md#radio--mix.
 */
class RoomRadio(
    /** YTM's radio, without an account. */
    private val shared: RadioSource,
    /**
     * The room owner's own radio (their YouTube Music account), by owner account (null: a room
     * without one), when they have one and want it ([PersonalCatalog.personalized]). Null: the [shared] one.
     * [withAccount] is the room's choice: true or false overrides the owner's setting.
     */
    private val owner: suspend (ownerAccount: String?, seedSongId: String, withAccount: Boolean?) -> List<Song>? = { _, _, _ -> null },
    /**
     * What the song graph says (the owner's listening and the server's): its songs for the seed,
     * and which of the radio's songs the owner heard or would rather not. Empty: nothing known.
     */
    private val hints: suspend (ownerAccount: String?, seedSongId: String, candidates: List<String>, options: RadioOptions) -> RadioHints =
        { _, _, _, _ -> RadioHints(emptyList()) },
    /** The YouTube Music song a local file is (from its tags, or matched), or null. */
    private val ytmIdOf: suspend (songId: String) -> String? = { null },
    /** Songs from the library like a local [seedSongId] (same artist, album, genre); null when it isn't local. */
    private val library: suspend (seedSongId: String) -> List<Song>? = { null },
    /** Plays here without internet (local music, saved songs). */
    private val onDevice: (Song) -> Boolean = { false },
) {
    /** A room's radio. [ownerAccount] is asked each time: the owner can change. */
    fun forRoom(ownerAccount: () -> String?): OptionsRadio = OptionsRadio { seed, options -> radio(ownerAccount(), seed, options) }

    suspend fun radio(ownerAccount: String?, seedSongId: String, options: RadioOptions): List<Song> {
        // Local music asks YouTube Music for the song it is, when it is one.
        val ytmSeed = if (seedSongId.startsWith(LOCAL_PREFIX)) ytmIdOf(seedSongId) else seedSongId
        var from = options.from
        var failure: Exception? = null
        var base = emptyList<Song>()
        if (from == RadioFrom.MIX || from == RadioFrom.YTM || from == RadioFrom.DEVICE) {
            if (ytmSeed != null) {
                try {
                    base = ytmRadio(ownerAccount, ytmSeed, options.withAccount)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.info("No YouTube Music radio for {}: {}", ytmSeed, e.message)
                    failure = e
                    if (from != RadioFrom.DEVICE) from = options.offline.takeIf { it == RadioFrom.DEVICE } ?: RadioFrom.GRAPH
                }
            } else if (from == RadioFrom.YTM) {
                // Music YouTube Music doesn't know: the graph is all there is.
                from = RadioFrom.GRAPH
            }
        }
        base = base.filter { it.id != seedSongId && it.id != ytmSeed }
        val hints = try {
            hints(ownerAccount, seedSongId, base.map { it.id }, options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("No songs from the song graph: {}", e.message)
            RadioHints(emptyList())
        }
        val graph = hints.similar.filter { it.id != seedSongId && it.id != ytmSeed }
        val alike = if (from == RadioFrom.GRAPH || from == RadioFrom.DEVICE) library(seedSongId).orEmpty() else emptyList()
        val songs = when (from) {
            RadioFrom.MIX -> mixRadio(base, graph, every = options.mixEvery)
            RadioFrom.YTM -> base
            RadioFrom.GRAPH -> graph + alike
            RadioFrom.DEVICE -> (mixRadio(graph, base, limit = Int.MAX_VALUE, every = 1) + alike).filter(onDevice)
        }
        val made = shape(songs.filter { it.id != seedSongId }.distinctBy { it.id }, hints, options)
        if (made.isEmpty() && failure != null) throw failure
        return made
    }

    private suspend fun ytmRadio(ownerAccount: String?, seed: String, withAccount: Boolean?): List<Song> {
        if (withAccount != false) {
            try {
                owner(ownerAccount, seed, withAccount)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("The owner's radio didn't work, using the shared one: {}", e.message)
            }
        }
        return shared.radio(seed)
    }

    private companion object {
        val log = LoggerFactory.getLogger(RoomRadio::class.java)
        const val LOCAL_PREFIX = "local:"
    }
}

/**
 * [songs] the way [options] want them: without blocked songs and artists, without what [hints]
 * says to leave out, at most [RadioOptions.perArtist] per artist, songs you know first or last
 * ([RadioOptions.discover]; all the way: only those).
 */
fun shape(songs: List<Song>, hints: RadioHints, options: RadioOptions): List<Song> {
    val blockedArtists = options.blockedArtists.map { it.lowercase() }.toSet()
    val avoid = hints.avoid.toSet() + options.blockedSongs
    var kept = songs.filter { s -> s.id !in avoid && s.artists.none { it.name.lowercase() in blockedArtists } && s.podcast == null }
    if (options.perArtist > 0) {
        val count = HashMap<String, Int>()
        kept = kept.filter { s ->
            val artist = s.artists.firstOrNull()?.name?.lowercase().orEmpty()
            artist.isEmpty() || count.merge(artist, 1, Int::plus)!! <= options.perArtist
        }
    }
    val d = options.discover.coerceIn(-1.0, 1.0)
    if (d == 0.0 || hints.heard.isEmpty()) return kept
    val heard = hints.heard.toSet()
    // Discover pushes songs you heard back (familiar: the ones you didn't); all the way leaves them out.
    fun pushed(s: Song) = if (d > 0) s.id in heard else s.id !in heard
    if (abs(d) >= 1.0) return kept.filterNot(::pushed)
    val by = abs(d) * kept.size
    return kept.withIndex().sortedBy { (i, s) -> i + if (pushed(s)) by else 0.0 }.map { it.value }
}
/** What the host hears about its rooms ([RoomManager]). None of them may block. */
data class RoomHooks(
    /** Songs that finished playing, for the listening history. */
    val onPlayFinished: (FinishedPlay) -> Unit = {},
    /** A room started a song (also again, with repeat one), for the song cache. */
    val onSongStarted: (Song) -> Unit = {},
    /** A room with an owner account was saved: its copy elsewhere can be updated (see [Room.syncId]). */
    val onRoomSaved: (SavedRoom) -> Unit = {},
    /** A room with an owner account was closed or expired here (not moved away). */
    val onRoomClosed: (Room) -> Unit = {},
    /** A room is no longer [account]'s (it got another owner): its copy under [syncId] can go. */
    val onRoomDisowned: (account: String, syncId: String) -> Unit = { _, _ -> },
)
