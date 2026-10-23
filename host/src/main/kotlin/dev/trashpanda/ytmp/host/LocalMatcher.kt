package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.trashpanda.ytmp.protocol.UnmatchedSong
import org.slf4j.LoggerFactory
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
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
    /** The YouTube video ID written in a file's tags (a link, say), or null. */
    private val tagged: suspend (LocalTrack) -> String? = { null },
    /** A YouTube Music song by its ID, or null when there's none. */
    private val lookup: suspend (songId: String) -> Song? = { null },
    private val scope: CoroutineScope,
    /** Why looking up is on hold right now ("Wi-Fi", "charging"), or null. */
    private val hold: () -> String? = { null },
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

    private val left = AtomicInteger()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Why it isn't looking anything up right now, or null. */
    val waiting: String? get() = if (enabled && left.get() > 0) hold() else null

    /** Files still to look up. */
    val pending: Int get() = left.get()

    /** How many files are looked up at once (1..[MAX_PARALLEL]); changes take effect within a second. */
    @Volatile
    var parallel = 1
        set(value) {
            field = value.coerceIn(1, MAX_PARALLEL)
        }

    /** Files that are looked at in all (the ones with an answer already, and the ones to do). */
    @Volatile
    var total = 0
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
                // Until the next look, or until someone asks for one.
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /** Stops looking; what's found stays stored, but isn't used. */
    fun stop() {
        job?.cancel()
        job = null
        left.set(0)
        total = 0
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
        total = files.size
        publish(valid)
        val todo = files.filter { f -> cache.localMatch(f.key).let { it == null || (!it.manual && it.sig != signature(f)) } }
        left.set(todo.size)
        val next = AtomicInteger()
        // Workers 0 until [parallel] take files one by one; the others wait until they're wanted.
        coroutineScope {
            repeat(MAX_PARALLEL) { worker ->
                launch {
                    while (isActive) {
                        if (worker >= parallel) {
                            if (next.get() >= todo.size) break
                            delay(IDLE_MS)
                            continue
                        }
                        while (isActive && hold() != null) delay(HOLD_MS)
                        val track = todo.getOrNull(next.getAndIncrement()) ?: break
                        val match = check(track)
                        cache.setLocalMatch(track.key, match)
                        if (match.song != null) publish(valid)
                        left.decrementAndGet()
                        delay(gapMs)
                    }
                }
            }
        }
        left.set(0)
        // Files that went: forget what was found.
        for (key in cache.localMatchKeys() - valid.keys) cache.setLocalMatch(key, null)
        return rescanMs
    }

    /** Looks one file up: by an ID in its name or tags first, else by title and artist. */
    private suspend fun check(track: LocalTrack): LocalMatch {
        val sig = signature(track)
        val id = idInName(track.file) ?: tagged(track)
        var why: String? = null
        if (id != null) {
            val song = lookup("ytm:$id")
            when {
                song == null -> why = "Its ID ($id) isn't a YouTube Music song"
                sameLength(track, song) -> return LocalMatch(sig, song)
                else -> why = "The song its ID points to has another length"
            }
        }
        if (track.title == UNKNOWN_SONG || track.artist == UNKNOWN_ARTIST) {
            return LocalMatch(sig, null, why ?: "No title or artist tag, and no ID in its name or tags")
        }
        val results = search("${track.artist} ${track.title}")
        return LocalMatch(sig, match(track, results), why ?: if (results.isEmpty()) "YouTube Music found nothing" else "No result with the same title, artist and length")
    }

    /** The files without a match, with why. */
    fun unmatched(): List<UnmatchedSong> = current.values.mapNotNull { t ->
        val m = cache.localMatch(t.key) ?: return@mapNotNull null
        if (m.song != null) return@mapNotNull null
        UnmatchedSong(t.key, t.title, t.artist, t.file, if (m.manual) "Skipped by you" else m.why ?: "No match")
    }.sortedBy { it.file.ifEmpty { it.title }.lowercase() }

    /** Looks at the files without a match again (not the ones the owner decided). */
    fun recheck() {
        cache.forgetLocalMatches { it.song == null && !it.manual }
        wake.trySend(Unit)
    }

    /** Forgets what was found; what the owner chose stays. */
    fun forget() {
        cache.forgetLocalMatches { !it.manual }
        wake.trySend(Unit)
    }

    /** The owner says file [key] is the song at [link] (a link or a video ID); empty: it isn't on YouTube Music. Null when it worked, else why not. */
    suspend fun link(key: String, link: String): String? {
        val track = current[key] ?: return "That song isn't on this phone any more"
        val text = link.trim()
        if (text.isEmpty()) {
            cache.setLocalMatch(key, LocalMatch(signature(track), null, null, manual = true))
            return null
        }
        val id = LINK.find(text)?.groupValues?.get(1) ?: Regex("^$ID$").find(text)?.value ?: return "That isn't a YouTube link or video ID"
        val song = try {
            lookup("ytm:$id")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "Couldn't look it up"
        } ?: return "YouTube Music doesn't have that song"
        cache.setLocalMatch(key, LocalMatch(signature(track), song, null, manual = true))
        publish(current)
        return null
    }

    private fun publish(valid: Map<String, LocalTrack>) {
        byId = valid.values.mapNotNull { t -> cache.localMatch(t.key)?.song?.let { it.id to t } }.toMap()
    }

    companion object {
        const val GAP_MS = 4_000L
        const val MAX_PARALLEL = 8
        private const val IDLE_MS = 1_000L
        const val RETRY_MS = 10 * 60_000L
        const val RESCAN_MS = 5 * 60_000L
        private const val DURATION_SLACK_MS = 3_000L
        private const val MAX_MS = 60 * 60_000L

        const val UNKNOWN_SONG = "Unknown song"
        const val UNKNOWN_ARTIST = "Unknown artist"
        private const val HOLD_MS = 15_000L

        /** Music, not the app's own downloads (found by their tags), nothing over an hour. A file without title or artist can still be found by an ID. */
        internal fun eligible(t: LocalTrack) =
            !t.folder.startsWith("Music/YTMP") && t.durationMs in 30_000..MAX_MS

        // v2: IDs in names and tags count.
        internal fun signature(t: LocalTrack) = "v2|${t.title}|${t.artist}|${t.durationMs}"

        private val ID = "[A-Za-z0-9_-]{11}"
        private val IN_BRACKETS = Regex("\\[($ID)]\\s*$")
        private val AFTER_DASH = Regex("-($ID)$")
        private val LINK = Regex("(?:youtube\\.com/(?:watch\\?(?:[^\\s\"'&]*&)?v=|shorts/|embed/)|youtu\\.be/)($ID)")

        /** The video ID in a yt-dlp style file name: "Song [dQw4w9WgXcQ].mp3" or "Song-dQw4w9WgXcQ.mp3". */
        internal fun idInName(file: String): String? {
            val name = file.substringBeforeLast('.')
            return (IN_BRACKETS.find(name) ?: AFTER_DASH.find(name))?.groupValues?.get(1)
        }

        /** The video ID in a YouTube link somewhere in [bytes] (a tag's text, in any of its encodings). */
        fun idInBytes(bytes: ByteArray): String? {
            // UTF-16 text has a zero byte between letters: leave those out.
            val text = String(bytes.filter { it != 0.toByte() }.toByteArray(), Charsets.ISO_8859_1)
            return LINK.find(text)?.groupValues?.get(1)
        }

        private fun sameLength(t: LocalTrack, song: Song) = song.durationMs > 0 && abs(song.durationMs - t.durationMs) <= ID_SLACK_MS
        private const val ID_SLACK_MS = 10_000L

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
