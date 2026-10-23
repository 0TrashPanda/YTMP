package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.text.Normalizer
import kotlin.math.abs

/**
 * Finds out which of the music on the phone is a YouTube Music song: a search for "artist
 * title" with a result whose title, artist and length agree. Such a file can play as that song
 * (likes, radio and history work, offline too) and is listed with the cached songs. Every file
 * is looked up once; the answer, also "no match", is kept in the song cache's index (a changed
 * file is looked up again). Slow on purpose: YouTube blocks a network that searches a lot.
 */
class LocalMatcher(
    private val cache: SongCache,
    private val tracks: suspend () -> List<LocalTrack>,
    private val search: suspend (query: String) -> List<Song>,
    private val scope: CoroutineScope,
    private val gapMs: Long = GAP_MS,
    private val retryMs: Long = RETRY_MS,
    private val rescanMs: Long = RESCAN_MS,
) {
    private val log = LoggerFactory.getLogger(LocalMatcher::class.java)
    private var job: Job? = null

    @Volatile
    private var current: Map<String, LocalTrack> = emptyMap()
    @Volatile
    private var byId: Map<String, LocalTrack> = emptyMap()

    /** Files still to look up. */
    @Volatile
    var pending = 0
        private set

    val enabled: Boolean get() = job != null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val wait = try {
                    round()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A bot check, no internet: later.
                    log.info("Matching phone music: {}; trying again later", e.message)
                    retryMs
                }
                delay(wait)
            }
        }
    }

    /** Stops looking; what's found stays stored, but isn't used. */
    fun stop() {
        job?.cancel()
        job = null
        pending = 0
        current = emptyMap()
        byId = emptyMap()
    }

    /** The file that is YouTube Music song [songId], and the song as YouTube knows it. */
    fun find(songId: String): Pair<LocalTrack, Song>? {
        val track = byId[songId] ?: return null
        val song = cache.localMatch(track.key)?.song ?: return null
        return track to song
    }

    /** All the files that are a YouTube Music song, as far as found. */
    fun all(): List<Pair<LocalTrack, Song>> = current.values.mapNotNull { t -> cache.localMatch(t.key)?.song?.let { t to it } }

    /** One look at the files; the time to the next one. */
    private suspend fun round(): Long {
        val files = tracks().filter(::eligible)
        val valid = files.associateBy { it.key }
        current = valid
        publish(valid)
        val todo = files.filter { cache.localMatch(it.key)?.sig != signature(it) }
        pending = todo.size
        for (track in todo) {
            val match = LocalMatch(signature(track), match(track, search("${track.artist} ${track.title}")))
            cache.setLocalMatch(track.key, match)
            if (match.song != null) publish(valid)
            pending--
            delay(gapMs)
        }
        pending = 0
        // Files that went: forget what was found.
        for (key in cache.localMatchKeys() - valid.keys) cache.setLocalMatch(key, null)
        return rescanMs
    }

    private fun publish(valid: Map<String, LocalTrack>) {
        byId = valid.values.mapNotNull { t -> cache.localMatch(t.key)?.song?.let { it.id to t } }.toMap()
    }

    companion object {
        const val GAP_MS = 4_000L
        const val RETRY_MS = 10 * 60_000L
        const val RESCAN_MS = 5 * 60_000L
        private const val DURATION_SLACK_MS = 3_000L
        private const val MAX_MS = 60 * 60_000L

        /** Music, not the app's own downloads (found by their tags), nothing unknown or over an hour. */
        internal fun eligible(t: LocalTrack) =
            !t.folder.startsWith("Music/YTMP") && t.durationMs in 30_000..MAX_MS &&
                t.title != "Unknown song" && t.artist != "Unknown artist"

        internal fun signature(t: LocalTrack) = "${t.title}|${t.artist}|${t.durationMs}"

        /** The first result with the same title, an artist in common and (about) the same length. */
        internal fun match(track: LocalTrack, results: List<Song>): Song? {
            val title = plain(track.title)
            val artists = plain(track.artist) + " " + plain(track.albumArtist)
            return results.firstOrNull { song ->
                song.id.startsWith("ytm:") && song.podcast == null && song.durationMs > 0 &&
                    plain(song.title) == title &&
                    abs(song.durationMs - track.durationMs) <= DURATION_SLACK_MS &&
                    song.artists.any { a -> plain(a.name).let { it.isNotEmpty() && artists.contains(it) } }
            }
        }

        /** "Beyoncé (Remastered)" -> "beyonce": no accents, brackets, case or punctuation. */
        internal fun plain(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}
