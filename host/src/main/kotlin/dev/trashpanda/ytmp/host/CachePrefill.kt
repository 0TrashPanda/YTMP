package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.TakeoutPreview
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
 * songs, the songs in its history (about the last 200; their plays count as plays here too:
 * [SongCache.importPlays]), and the songs a Google Takeout export ([takeout]) says were played
 * most. They're ordinary cached songs: they go like any other
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
    /** The owner's imported Google Takeout history, where there can be one. */
    private val takeout: TakeoutHistory? = null,
    /** A song as YouTube Music has it (title, length, cover), for the ones from [takeout]. */
    private val songInfo: suspend (id: String) -> Song = { throw UnsupportedOperationException("Songs can't be looked up here") },
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

    /** The songs in the YouTube Music history are cached. */
    @Volatile
    var fromHistory = false
        private set

    /** Songs from [takeout] are cached: the ones played at least [takeoutMinPlays] times in the last [takeoutDays] days (0: all of it). */
    @Volatile
    var fromTakeout = false
        private set

    @Volatile
    var takeoutDays = DEFAULT_TAKEOUT_DAYS
        private set

    @Volatile
    var takeoutMinPlays = DEFAULT_TAKEOUT_MIN_PLAYS
        private set

    /** Takeout songs that turned out not to be kept (an episode, too long, gone from YouTube): not tried again until the app restarts. */
    private val unkeepable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Of the songs the Takeout settings pick (that can be cached): how many, and how many are here. */
    val takeoutOf: Int get() = takeoutPicks().size

    val takeoutCached: Int get() = takeoutPicks().count { !cache.wanted(it) }

    private fun takeoutPicks(days: Int = takeoutDays, minPlays: Int = takeoutMinPlays): List<Song> =
        if (takeout == null) emptyList()
        else takeout.pick(days, minPlays, clock()).map { it.song() }.filter { cache.takes(it) && it.id !in unkeepable }

    /** The imported Takeout history, or null. */
    fun takeoutSummary() = takeout?.summary()

    /** A new Takeout export ([TakeoutHistory.import]), looked at at once when its songs are kept. */
    fun importTakeout(input: java.io.InputStream) {
        val history = takeout ?: throw IllegalStateException("Not on this host")
        history.import(input)
        unkeepable.clear()
        if (fromTakeout) wake.trySend(Unit)
    }

    fun forgetTakeout() {
        takeout?.forget()
    }

    /** What [days] and [minPlays] would pick: the live count next to the settings. */
    fun preview(days: Int, minPlays: Int): TakeoutPreview {
        val songs = takeoutPicks(days.coerceIn(0, MAX_TAKEOUT_DAYS), minPlays.coerceIn(1, MAX_TAKEOUT_MIN_PLAYS))
        val cached = songs.count { !cache.wanted(it) }
        return TakeoutPreview(songs.size, cached, songs.size * cache.usualSongBytes())
    }

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
    fun set(liked: Int, fromHistory: Boolean, fromTakeout: Boolean = false, takeoutDays: Int = DEFAULT_TAKEOUT_DAYS, takeoutMinPlays: Int = DEFAULT_TAKEOUT_MIN_PLAYS) {
        this.liked = liked.coerceIn(0, MAX_LIKED)
        this.fromHistory = fromHistory
        this.fromTakeout = fromTakeout && takeout != null
        this.takeoutDays = takeoutDays.coerceIn(0, MAX_TAKEOUT_DAYS)
        this.takeoutMinPlays = takeoutMinPlays.coerceIn(1, MAX_TAKEOUT_MIN_PLAYS)
        if (this.liked == 0) lastLiked = emptyList()
        if (this.liked == 0 && !fromHistory && !this.fromTakeout) {
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

    /** One look at the account and the Takeout history: their plays counted, then what's worth keeping fetched. */
    internal suspend fun round() {
        val account = liked > 0 || fromHistory
        if (account && !signedIn()) {
            waiting = "signed out"
            if (!fromTakeout) return
        } else {
            waiting = null
        }
        val wanted = LinkedHashMap<String, Song>()
        if (account && waiting == null && fromHistory) {
            val heard = history()
            val added = cache.importPlays(heard.map { (song, days) -> song to playedAt(days) })
            log.info("Song cache: {} song(s) in the YouTube Music history, {} play(s) new", heard.size, added)
            // All of it (about the last 200 songs), newest first: it only goes back that far and
            // lists each song once, so it can't tell what was played often (a Takeout import could).
            for ((song, _) in heard) wanted.putIfAbsent(song.id, song)
        }
        val count = liked
        if (account && waiting == null && count > 0) {
            val songs = likes(count).take(count)
            lastLiked = songs
            for (song in songs) wanted.putIfAbsent(song.id, song)
        }
        // Songs from Takeout are only an ID and a title: looked up just before they're fetched.
        val lookUp = HashSet<String>()
        if (fromTakeout && takeout != null) {
            val added = cache.importPlays(takeout.songs.flatMap { song -> song.song().let { s -> song.plays.map { s to it } } })
            val picks = takeoutPicks()
            log.info("Song cache: {} song(s) picked from the Takeout history, {} play(s) new", picks.size, added)
            for (song in picks) if (wanted.putIfAbsent(song.id, song) == null) lookUp += song.id
        }
        val blocked = waiting
        var fetched = 0
        var failed = 0
        for (picked in wanted.values) {
            if (!cache.wanted(picked) || picked.id in unkeepable) continue
            if (!cache.hasRoom()) {
                waiting = "full"
                break
            }
            hold()?.let { reason ->
                waiting = reason
                cache.fetchGate()
                waiting = blocked
            }
            val song = if (picked.id in lookUp) {
                try {
                    songInfo(picked.id.removePrefix("ytm:")).also { failed = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Gone from YouTube, say; several in a row: YouTube itself (a bot check).
                    if (++failed >= MAX_FAILED_LOOKUPS) throw e
                    log.info("Song cache: couldn't look up {}: {}", picked.id, e.message)
                    unkeepable += picked.id
                    delay(gapMs)
                    continue
                }
            } else {
                picked
            }
            if (!cache.wanted(song)) {
                unkeepable += picked.id
                continue
            }
            if (cache.fetch(song)) fetched++
            delay(gapMs)
        }
        if (fetched > 0) log.info("Song cache: {} song(s) kept from YouTube Music and Takeout", fetched)
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
        const val MAX_TAKEOUT_DAYS = 3650
        const val MAX_TAKEOUT_MIN_PLAYS = 1000
        const val DEFAULT_TAKEOUT_DAYS = 365
        const val DEFAULT_TAKEOUT_MIN_PLAYS = 5
        /** Takeout songs it couldn't look up in a row before it stops the round (YouTube blocks it, likely). */
        private const val MAX_FAILED_LOOKUPS = 3
        /** Between two songs it fetches. */
        private const val GAP_MS = 20_000L
        private const val RETRY_MS = 30L * 60 * 1000
        /** How often it looks at the account again. */
        private const val INTERVAL_MS = 6L * 3600 * 1000
        /** A history day it can't read (another language): counted as a month ago. */
        private const val UNKNOWN_DAYS = 31
    }
}
