package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.RadioCrawlStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/** What the radio crawl is set to (kept by whoever runs it). */
@Serializable
data class RadioCrawlSettings(
    val enabled: Boolean = false,
    val paceSeconds: Int = 90,
    val refreshDays: Int = 90,
    /** Also in the background while charging on Wi-Fi (a phone; see [RadioCrawl.overnight]). */
    val overnight: Boolean = false,
)

/**
 * Learns YouTube Music's radios of the songs here, slowly ([songs]: local music with a YouTube
 * Music ID and saved songs): one radio without an account every [RadioCrawlSettings.paceSeconds],
 * each song again after [RadioCrawlSettings.refreshDays], for the song graph ([learned]). Stops
 * for hours when YouTube Music doesn't answer: many lookups get a home network blocked.
 * See docs/features/history-and-recommendations.md#learning-from-youtube-music.
 */
class RadioCrawl(
    private val scope: CoroutineScope,
    /** The YouTube Music song IDs to learn the radio of. */
    private val songs: suspend () -> List<String>,
    /** When each of these seeds last had its radio learned (a seed not in it: never). */
    private val lastFetched: suspend (seeds: Collection<String>) -> Map<String, Long>,
    /** YouTube Music's radio of a song, without an account (its 50 songs). */
    private val fetch: suspend (seed: String) -> List<Song>,
    /** Keeps a fetched radio (the song graph). */
    private val learned: suspend (seed: String, songs: List<Song>) -> Unit,
    private val load: () -> RadioCrawlSettings,
    private val save: (RadioCrawlSettings) -> Unit,
    /** Why it can't fetch right now ("Waiting for Wi-Fi", "Link a YTMP account…"), or null. */
    private val hold: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Whether this host can also crawl in the background (a phone, while it charges on Wi-Fi:
     * [RadioCrawlSettings.overnight]). Without it, the setting isn't offered.
     */
    private val overnight: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(RadioCrawl::class.java)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    @Volatile private var settings = load()
    @Volatile private var waiting: String? = null
    @Volatile private var until: Long? = null
    @Volatile private var lastAt: Long? = null
    @Volatile private var counts = 0 to 0

    fun start() {
        if (job != null) return
        job = scope.launch { run() }
    }

    fun status(): RadioCrawlStatus {
        val s = settings
        return RadioCrawlStatus(s.enabled, s.paceSeconds, s.refreshDays, counts.first, counts.second, hold() ?: waiting, until, lastAt, s.overnight.takeIf { overnight })
    }

    /** What it's set to now. */
    val current: RadioCrawlSettings get() = settings

    fun set(enabled: Boolean, paceSeconds: Int, refreshDays: Int, overnight: Boolean = settings.overnight): RadioCrawlStatus {
        settings = RadioCrawlSettings(enabled, paceSeconds.coerceIn(MIN_PACE_S, MAX_PACE_S), refreshDays.coerceIn(7, 3650), overnight && this.overnight)
        save(settings)
        // A change (switched on, slower or faster) is looked at at once; a block from YouTube stays.
        if (until == null) wake.trySend(Unit)
        return status()
    }

    /**
     * Looks again now: the app just woke up (a background run of the phone). Its waits went by
     * the wall clock, so one that's over while the phone slept doesn't hold it up.
     */
    fun nudge() {
        wake.trySend(Unit)
    }

    private suspend fun run() {
        while (true) {
            val s = settings
            if (!s.enabled) {
                waiting = null
                sleep(null)
                continue
            }
            if (hold() != null) {
                sleep(HOLD_RECHECK_MS)
                continue
            }
            val all = runCatching { songs() }.getOrDefault(emptyList())
            val last = runCatching { lastFetched(all) }.getOrDefault(emptyMap())
            val now = clock()
            val fresh = now - s.refreshDays * DAY_MS
            val todo = all.filter { (last[it] ?: 0) < fresh }.sortedBy { last[it] ?: 0 }
            counts = (all.size - todo.size) to all.size
            val seed = todo.firstOrNull()
            if (seed == null) {
                waiting = null
                sleep(IDLE_RECHECK_MS)
                continue
            }
            try {
                val radio = fetch(seed)
                try {
                    learned(seed, radio)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not YouTube's doing: keeping it didn't work (the server), try again in a while.
                    log.info("The radio crawl couldn't keep a radio: {}", e.message)
                    waiting = e.message ?: "Couldn't keep it"
                    until = clock() + FAILED_PAUSE_MS
                    sleepUntil(until!!)
                    until = null
                    continue
                }
                lastAt = clock()
                waiting = null
                counts = (counts.first + 1) to counts.second
                sleepUntil(lastAt!! + s.paceSeconds * 1000L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val blocked = e.message.orEmpty().let { m -> listOf("bot", "confirm", "429", "Too Many").any { it in m } }
                log.info("The radio crawl stops a while: {}", e.message)
                waiting = if (blocked) "YouTube asked to confirm this isn't a bot" else "YouTube Music didn't answer: ${e.message}"
                val pause = if (blocked) BLOCKED_PAUSE_MS else FAILED_PAUSE_MS
                until = clock() + pause
                sleepUntil(until!!)
                until = null
            }
        }
    }

    /** Waits [ms] (null: until [set]), or less when the settings change. */
    private suspend fun sleep(ms: Long?) {
        if (ms == null) wake.receive() else withTimeoutOrNull(ms) { wake.receive() }
    }

    /**
     * Waits until [at] by the wall clock: a phone's timers stand still while it sleeps, so after
     * waking ([nudge]) only what's left of the wait counts. A change of the settings still ends
     * it early, except a pause after YouTube said no.
     */
    private suspend fun sleepUntil(at: Long) {
        val paused = until != null
        val from = settings
        while (true) {
            val left = at - clock()
            if (left <= 0) return
            sleep(left)
            if (!paused && settings != from) return
        }
    }

    /** When seeds had their radio learned, kept in a file (a phone, whose song graph is on its server). */
    class FileLog(private val file: File) {
        private val lock = Any()
        private var times: MutableMap<String, Long>? = null

        private fun loaded(): MutableMap<String, Long> = times ?: runCatching { json.decodeFromString<Map<String, Long>>(file.readText()) }
            .getOrDefault(emptyMap()).toMutableMap().also { times = it }

        fun get(seeds: Collection<String>): Map<String, Long> = synchronized(lock) { loaded().filterKeys { it in seeds } }

        fun put(seed: String, at: Long) = synchronized(lock) {
            loaded()[seed] = at
            val temp = File(file.path + ".part")
            temp.writeText(json.encodeToString(loaded()))
            temp.renameTo(file)
        }

        private companion object {
            val json = Json { ignoreUnknownKeys = true }
        }
    }

    companion object {
        /** Faster than this, YouTube soon wants to know if it's a bot. */
        const val MIN_PACE_S = 30
        const val MAX_PACE_S = 3600
        private const val DAY_MS = 24L * 3600 * 1000
        private const val HOLD_RECHECK_MS = 60_000L
        private const val IDLE_RECHECK_MS = 3_600_000L
        private const val BLOCKED_PAUSE_MS = 6 * 3_600_000L
        private const val FAILED_PAUSE_MS = 30 * 60_000L

        fun loadSettings(text: String?): RadioCrawlSettings =
            text?.let { runCatching { Json.decodeFromString(RadioCrawlSettings.serializer(), it) }.getOrNull() } ?: RadioCrawlSettings()

        fun saveSettings(settings: RadioCrawlSettings): String = Json.encodeToString(RadioCrawlSettings.serializer(), settings)
    }
}

/** The radio crawl in the settings, at [path] (`GET`, `PUT`), for whoever [allowed] says (a phone's owner on the phone). */
fun Route.radioCrawlRoutes(path: String, crawl: RadioCrawl?, allowed: suspend (ApplicationCall) -> Boolean) =
    route(path) {
        suspend fun ApplicationCall.crawl(): RadioCrawl {
            if (!allowed(this)) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only the owner can change this")
            return crawl ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Not on this host")
        }
        get { call.respond(call.crawl().status()) }
        put {
            val crawl = call.crawl()
            val wanted = call.receive<RadioCrawlStatus>()
            call.respond(crawl.set(wanted.enabled, wanted.paceSeconds, wanted.refreshDays, wanted.overnight ?: crawl.current.overnight))
        }
    }
