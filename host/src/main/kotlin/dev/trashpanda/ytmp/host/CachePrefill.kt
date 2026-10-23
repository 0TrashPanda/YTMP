package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId

/**
 * Fills the song cache from the owner's YouTube Music account, not only from what plays here
 * (docs/implementation/song-cache.md#from-your-youtube-music): the [liked] most recently liked
 * songs, and songs its history says were played more than once (that history's plays count as
 * plays here: [SongCache.importPlays]). They're ordinary cached songs: they go like any other
 * when the cache is full, and only free room is filled (no song is pushed out for them).
 * Slow on purpose, one song every [gapMs]: YouTube blocks a network that fetches a lot.
 */
class CachePrefill(
    private val cache: SongCache,
    /** The [count] songs the account liked last, newest first. */
    private val likes: suspend (count: Int) -> List<Song>,
    /** The account's YouTube Music history: each song, and about how many days ago (null: not known). */
    private val history: suspend () -> List<Pair<Song, Int?>>,
    /** Signed in to YouTube Music. */
    private val signedIn: suspend () -> Boolean,
    private val scope: CoroutineScope,
    /** Why fetching is on hold right now ("Wi-Fi"), or null. */
    private val hold: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val gapMs: Long = GAP_MS,
    private val retryMs: Long = RETRY_MS,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private val log = LoggerFactory.getLogger(CachePrefill::class.java)
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** How many recent likes to keep cached (0: none). */
    @Volatile
    var liked = 0
        private set

    /** Songs in the YouTube Music history played more than once are cached. */
    @Volatile
    var fromHistory = false
        private set

    /** Of the [liked] songs that can be cached (no episodes): how many there are, and how many are here (cached or downloaded). */
    val likedOf: Int get() = lastLiked.count { cache.takes(it) }

    val likedCached: Int get() = lastLiked.count { cache.takes(it) && !cache.wanted(it) }

    @Volatile
    private var lastLiked: List<Song> = emptyList()

    /** Why it isn't filling right now ("Wi-Fi", "full", "signed out", "error"), or null. */
    @Volatile
    var waiting: String? = null
        private set

    /** Applies the settings and looks again at once (also when nothing changed: the owner asked). */
    fun set(liked: Int, fromHistory: Boolean) {
        this.liked = liked.coerceIn(0, MAX_LIKED)
        this.fromHistory = fromHistory
        if (this.liked == 0) lastLiked = emptyList()
        if (this.liked == 0 && !fromHistory) {
            job?.cancel()
            job = null
            waiting = null
            return
        }
        if (job == null) job = scope.launch { loop() } else wake.trySend(Unit)
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val wait = try {
                round()
                intervalMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bot check, no internet: later.
                log.info("Song cache from YouTube Music: {}; trying again later", e.message)
                waiting = "error"
                retryMs
            }
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** One look at the account: the history's plays counted, then what's worth keeping fetched. */
    internal suspend fun round() {
        if (!signedIn()) {
            waiting = "signed out"
            return
        }
        waiting = null
        val wanted = LinkedHashMap<String, Song>()
        if (fromHistory) {
            val heard = history()
            val added = cache.importPlays(heard.map { (song, days) -> song to playedAt(days) })
            log.info("Song cache: {} song(s) in the YouTube Music history, {} play(s) new", heard.size, added)
            // Played more than once: worth keeping, like a song played twice here.
            for ((song, _) in heard) if (cache.worth(song.id)) wanted.putIfAbsent(song.id, song)
        }
        val count = liked
        if (count > 0) {
            val songs = likes(count).take(count)
            lastLiked = songs
            for (song in songs) wanted.putIfAbsent(song.id, song)
        }
        var fetched = 0
        for (song in wanted.values) {
            if (!cache.wanted(song)) continue
            if (!cache.hasRoom()) {
                waiting = "full"
                break
            }
            hold()?.let { reason ->
                waiting = reason
                cache.fetchGate()
                waiting = null
            }
            if (cache.fetch(song)) fetched++
            delay(gapMs)
        }
        if (fetched > 0) log.info("Song cache: {} song(s) kept from YouTube Music", fetched)
    }

    /** About when a history song was played: the middle of that day (not later than now); unknown: a month ago. */
    private fun playedAt(daysAgo: Int?): Long {
        val now = clock()
        val days = (daysAgo ?: UNKNOWN_DAYS).toLong()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days)
        return minOf(now, day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
    }

    companion object {
        const val MAX_LIKED = 5000
        /** Between two songs it fetches. */
        private const val GAP_MS = 20_000L
        private const val RETRY_MS = 30L * 60 * 1000
        /** How often it looks at the account again. */
        private const val INTERVAL_MS = 6L * 3600 * 1000
        /** A history day it can't read (another language): counted as a month ago. */
        private const val UNKNOWN_DAYS = 31
    }
}
