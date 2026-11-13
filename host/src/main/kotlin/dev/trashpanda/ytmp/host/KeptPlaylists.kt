package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.OfflinePlaylist
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.SearchItem
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.Thumbnail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Playlists on the phone (docs/implementation/sources/song-cache.md#playlists-offline): the playlists
 * the owner keeps offline ([keep]) and, as set, their [mostPlayed] most played ones are kept in
 * the [cache] and kept up to date; and every playlist whose songs are all on the phone (cached or
 * downloaded, however they got there) is listed in "Cached on this phone" ([complete]).
 *
 * It knows a playlist's songs by reading it: the account's playlists (its library) once a day,
 * or when their number of songs changes, and at once when the app changes one ([changed]) or
 * shows one whole ([seen]). What it read is kept in [file], so it works offline too.
 */
class KeptPlaylists(
    private val cache: SongCache,
    private val file: File,
    /** The account's playlists (its library); null: not signed in. */
    private val library: suspend () -> List<SearchItem.PlaylistResult>?,
    /** A part of playlist [id]: the first, or the one after a page's `more`. */
    private val part: suspend (id: String, more: String?) -> PlaylistPage,
    private val scope: CoroutineScope,
    /** Why fetching is on hold right now ("Wi-Fi"), or null. */
    private val hold: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val gapMs: Long = GAP_MS,
    private val readGapMs: Long = READ_GAP_MS,
    private val retryMs: Long = RETRY_MS,
    private val intervalMs: Long = INTERVAL_MS,
    private val changeDelayMs: Long = CHANGE_DELAY_MS,
) {
    private val log = LoggerFactory.getLogger(KeptPlaylists::class.java)
    private val lock = Any()
    private val known = LinkedHashMap<String, Known>()
    private val kept = LinkedHashSet<String>()
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** How many of the most played playlists are kept (0: none). */
    @Volatile
    var mostPlayed = 0
        private set

    /** The most played playlists picked in the last round (that fit). */
    @Volatile
    private var picked: List<String> = emptyList()

    /** Why songs aren't coming in now ("Wi-Fi", "full", "error"), or null. */
    @Volatile
    var waiting: String? = null
        private set

    @Volatile
    var busy = false
        private set

    @Volatile
    var error: String? = null
        private set

    /** With [waiting] "full": the playlist there's no room for. */
    @Volatile
    var full: String? = null
        private set

    init {
        load()
    }

    /** Starts looking (now, and every few hours). */
    fun start() {
        if (job == null) job = scope.launch { loop() } else wake.trySend(Unit)
    }

    /** Keeps [mostPlayed] most played playlists too (0: none), and looks again at once. */
    fun set(mostPlayed: Int) {
        this.mostPlayed = mostPlayed.coerceIn(0, MAX_MOST_PLAYED)
        if (this.mostPlayed == 0) picked = emptyList()
        wake.trySend(Unit)
    }

    /** Keeps playlist [id] offline, or stops ([keep] false: its songs stay cached, like other songs, until room is needed). */
    fun keep(id: String, keep: Boolean, title: String = "", thumbnails: List<Thumbnail> = emptyList()) {
        synchronized(lock) {
            if (keep) {
                kept += id
                if (id !in known) known[id] = Known(id, title, thumbnails = thumbnails)
            } else {
                kept -= id
            }
        }
        save()
        wake.trySend(Unit)
    }

    fun isKept(id: String): Boolean = synchronized(lock) { id in kept }

    /** The app shows [page] (the first part): when it's all of the playlist, its songs are known now. */
    fun seen(page: PlaylistPage) {
        if (page.more != null || page.title.isBlank()) return
        val changed = synchronized(lock) {
            val old = known[page.id] ?: return
            if (old.songs.map { it.id } == page.songs.map { it.id } && old.readAt > 0) return
            known[page.id] = old.copy(title = page.title, author = page.author, thumbnails = page.thumbnails.ifEmpty { old.thumbnails }, songs = page.songs.distinctBy { it.id }, readAt = clock(), tooLong = false)
            page.id in kept || page.id in picked
        }
        save()
        if (changed) wake.trySend(Unit)
    }

    /** The app changed playlist [id] (songs added, removed, moved; Liked music: a like): read it again soon. */
    fun changed(id: String) {
        val soon = synchronized(lock) {
            val old = known[id] ?: return
            known[id] = old.copy(readAt = 0)
            // Liked music changes with every like: at the next round, unless it's kept.
            id != LIKED_MUSIC || id in kept
        }
        if (soon) scope.launch { delay(changeDelayMs); wake.trySend(Unit) }
    }

    /** The songs of the playlists kept (by hand and the most played): they go last when room is needed. */
    fun keptSongs(): Set<String> = synchronized(lock) {
        (kept + picked).flatMap { known[it]?.songs.orEmpty().map { s -> s.id } }.toSet()
    }

    /** Every playlist whose songs are all on the phone, for "Cached on this phone". */
    fun complete(): List<LibraryPlaylist> = synchronized(lock) { known.values.toList() }
        .filter { it.isComplete() }
        .map { LibraryPlaylist(it.id, it.title, it.author, it.songs.map { s -> s.id }) }

    private fun Known.isComplete() = readAt > 0 && !tooLong && songs.isNotEmpty() && songs.all { cache.here(it.id) }

    /** The playlists kept, the most played ones picked and the ones that are complete, with how far along they are. */
    fun status(): List<OfflinePlaylist> {
        val (all, keptNow, pickedNow) = synchronized(lock) { Triple(known.values.toList(), kept.toList(), picked) }
        val byId = all.associateBy { it.id }
        val listed = (keptNow + pickedNow + all.filter { it.isComplete() }.map { it.id }).distinct()
        return listed.mapNotNull { id ->
            val p = byId[id] ?: return@mapNotNull null
            val here = p.songs.count { cache.here(it.id) }
            OfflinePlaylist(
                id = p.id,
                title = p.title,
                thumbnails = p.thumbnails,
                kept = id in keptNow,
                mostPlayed = id in pickedNow && id !in keptNow,
                songs = p.songs.size,
                here = here,
                notKept = p.songs.count { !cache.here(it.id) && !cache.takes(it) },
                tooLong = p.tooLong,
                read = p.readAt > 0 || p.songs.isNotEmpty(),
            )
        }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            busy = true
            val wait = try {
                round()
                error = null
                intervalMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("Playlists offline: {}; trying again later", e.message)
                waiting = "error"
                error = e.message ?: e.javaClass.simpleName
                retryMs
            } finally {
                busy = false
            }
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** One look: the playlists read again where they changed, the most played picked, and the missing songs fetched. */
    internal suspend fun round() {
        waiting = null
        full = null
        readPlaylists()
        picked = pickMostPlayed()
        val wanted = synchronized(lock) { (kept + picked).distinct().mapNotNull { known[it] } }
        var failed = 0
        var fetched = 0
        for (playlist in wanted) {
            if (playlist.tooLong) continue
            for (song in playlist.songs) {
                if (!cache.wanted(song)) continue
                if (!roomFor(song)) {
                    waiting = "full"
                    full = playlist.title
                    log.info("Playlists offline: no room for \"{}\"", playlist.title)
                    return
                }
                hold()?.let { reason ->
                    waiting = reason
                    cache.fetchGate()
                    waiting = null
                }
                if (cache.fetch(song)) {
                    fetched++
                    failed = 0
                } else if (cache.wanted(song) && ++failed >= MAX_FAILED) {
                    // Several in a row: no internet, or YouTube's bot check. Later.
                    throw IllegalStateException("Couldn't download songs from YouTube")
                }
                delay(gapMs)
            }
        }
        if (fetched > 0) log.info("Playlists offline: {} song(s) kept", fetched)
    }

    /** The account's playlists that are new or changed, and kept ones of others that are old, read (again). */
    private suspend fun readPlaylists() {
        val now = clock()
        val playlists = library()
        if (playlists != null) {
            val ids = playlists.map { it.id }.toSet()
            synchronized(lock) {
                // Gone from the library (deleted, removed): forgotten, unless kept.
                known.keys.removeAll { it !in ids && it !in kept }
                for (p in playlists) known[p.id] = (known[p.id] ?: Known(p.id, p.title)).copy(inLibrary = true)
            }
            save()
        }
        val stale = synchronized(lock) {
            val fromLibrary = playlists.orEmpty().filter { item ->
                val k = known[item.id]
                k == null || k.readAt == 0L || now - k.readAt > READ_AGAIN_MS || (item.itemCount != null && item.itemCount != k.count)
            }.map { it.id to it }
            val others = kept.filter { id -> fromLibrary.none { it.first == id } && known[id].let { it == null || it.readAt == 0L || now - it.readAt > READ_AGAIN_MS } }.map { it to null }
            fromLibrary + others
        }
        var failed = 0
        for ((id, item) in stale) {
            try {
                read(id, item)
                failed = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("Playlists offline: couldn't read {}: {}", id, e.message)
                if (++failed >= MAX_FAILED) throw e
            }
            delay(readGapMs)
        }
    }

    /** Reads all of playlist [id] (up to [MAX_SONGS] songs). */
    private suspend fun read(id: String, item: SearchItem.PlaylistResult?) {
        val first = part(id, null)
        val songs = first.songs.toMutableList()
        var more = first.more
        var tooLong = false
        while (more != null) {
            if (songs.size >= MAX_SONGS) {
                tooLong = true
                break
            }
            delay(readGapMs)
            val next = part(id, more)
            songs += next.songs
            more = next.more
        }
        synchronized(lock) {
            val old = known[id] ?: Known(id, "")
            known[id] = old.copy(
                title = first.title.ifBlank { item?.title ?: old.title },
                author = first.author ?: item?.author ?: old.author,
                thumbnails = first.thumbnails.ifEmpty { item?.thumbnails ?: old.thumbnails },
                songs = songs.distinctBy { it.id }.take(MAX_SONGS),
                readAt = clock(),
                count = item?.itemCount ?: first.songCount,
                tooLong = tooLong,
            )
        }
        save()
    }

    /**
     * The most played playlists of the library (not Liked music, which *Keep your recent likes*
     * is for): the plays of their songs in the last 30 days, most first, only ones that fit
     * whole, next to the playlists kept by hand.
     */
    internal fun pickMostPlayed(): List<String> {
        val count = mostPlayed
        if (count <= 0) return emptyList()
        val (playlists, keptNow) = synchronized(lock) { known.values.toList() to kept.toList() }
        val budget = cache.maxBytes / 10 * 9
        val usual = cache.usualSongBytes()
        val counted = HashSet<String>()
        var bytes = 0L
        fun cost(songs: List<Song>): Long = songs.filter { it.id !in counted }.sumOf {
            when {
                cache.has(it.id) -> cache.sizeOf(it.id)
                cache.here(it.id) || !cache.takes(it) -> 0L
                else -> usual
            }
        }
        for (id in keptNow) {
            val songs = playlists.firstOrNull { it.id == id }?.songs.orEmpty()
            bytes += cost(songs)
            counted += songs.map { it.id }
        }
        val ranked = playlists.filter { it.inLibrary && it.id !in keptNow && it.id != LIKED_MUSIC && !it.tooLong && it.songs.isNotEmpty() }
            .map { it to it.songs.sumOf { s -> cache.recentPlays(s.id, MOST_PLAYED_WINDOW_MS) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        val picks = ArrayList<String>()
        for ((playlist, _) in ranked) {
            if (picks.size >= count) break
            val more = cost(playlist.songs)
            if (bytes + more > budget) continue
            bytes += more
            counted += playlist.songs.map { it.id }
            picks += playlist.id
        }
        return picks
    }

    /** The songs kept for playlists, with [song], stay under 90 % of the cache (other songs make room). */
    private fun roomFor(song: Song): Boolean {
        if (!cache.takes(song)) return false
        val keptBytes = keptSongs().sumOf { cache.sizeOf(it) }
        return keptBytes + cache.usualSongBytes() <= cache.maxBytes / 10 * 9
    }

    // --- kept in a file ---

    @Serializable
    internal data class Known(
        val id: String,
        val title: String,
        val author: String? = null,
        val thumbnails: List<Thumbnail> = emptyList(),
        val songs: List<Song> = emptyList(),
        /** When its songs were read (0: not yet, or changed since). */
        val readAt: Long = 0,
        /** How many songs the library said it had then. */
        val count: Int? = null,
        val tooLong: Boolean = false,
        /** In the account's library. */
        val inLibrary: Boolean = false,
    )

    @Serializable
    private data class Saved(val playlists: List<Known> = emptyList(), val kept: List<String> = emptyList())

    private fun load() {
        val saved = file.takeIf { it.isFile }?.let {
            runCatching { json.decodeFromString(Saved.serializer(), it.readText()) }
                .onFailure { e -> log.warn("Playlists offline: couldn't read {}: {}", file, e.message) }
                .getOrNull()
        } ?: return
        synchronized(lock) {
            saved.playlists.forEach { known[it.id] = it }
            kept += saved.kept
        }
    }

    private fun save() {
        val saved = synchronized(lock) { Saved(known.values.toList(), kept.toList()) }
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".part")
            temp.writeText(json.encodeToString(Saved.serializer(), saved))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { log.warn("Playlists offline: couldn't save: {}", it.message) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        const val LIKED_MUSIC = "LM"
        const val MAX_MOST_PLAYED = 20
        const val DEFAULT_MOST_PLAYED = 3
        /** Longer playlists aren't kept (or listed): a phone's cache holds a few hundred songs. */
        const val MAX_SONGS = 1000
        /** Plays in this long count for "most played". */
        const val MOST_PLAYED_WINDOW_MS = 30L * 24 * 3600 * 1000
        /** Failures in a row before it stops the round (no internet, a bot check). */
        private const val MAX_FAILED = 3
        /** Between two songs it fetches. */
        private const val GAP_MS = 10_000L
        /** Between two playlists (or parts) it reads. */
        private const val READ_GAP_MS = 3_000L
        private const val RETRY_MS = 30L * 60 * 1000
        private const val INTERVAL_MS = 6L * 3600 * 1000
        /** A playlist is read again after this long, also when its number of songs stays the same. */
        private const val READ_AGAIN_MS = 24L * 3600 * 1000
        /** After the app changed a playlist: YouTube Music has it by then. */
        private const val CHANGE_DELAY_MS = 5_000L
    }
}
