package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.FinishedPlay
import dev.trashpanda.ytmp.core.ResolvedStream
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.protocol.LocalLibraryKind
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.SongCacheStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Songs downloaded to keep (the phone's Music folder) that carry their song ID
 * ([M4aTags.Tags.songId]): they play from there, and show with the cached songs.
 */
interface DownloadedSongs {
    /** The download of [songId], as last seen (no waiting). */
    fun find(songId: String): DownloadedSong?

    /** All of them, looked at again when they changed: the same list until then. */
    suspend fun all(): List<DownloadedSong>

    /** Sends a download's audio (`/api/audio/…`), with [range]. */
    suspend fun respond(call: ApplicationCall, download: DownloadedSong, range: String?)

    /** Sends its cover, [size] px; false when it has none. */
    suspend fun respondArt(call: ApplicationCall, download: DownloadedSong, size: Int): Boolean = false
}

/** A download: [song] as its tags say, [uri] where the phone's own player can play it. */
data class DownloadedSong(val song: Song, val uri: String, val local: Boolean = false)

/**
 * A music file on the phone that [LocalMatcher] found on YouTube Music: [sig] says which file it
 * was (title, artist, length), so a changed file is checked again; [song] null: checked, no match.
 */
@Serializable
data class LocalMatch(val sig: String, val song: Song? = null, val why: String? = null, val manual: Boolean = false)

/**
 * The song cache (docs/implementation/song-cache.md): YouTube Music songs kept as files in
 * [dir], so they start at once, need no YouTube lookup, and on a phone play without internet.
 * A cached song stays the same song (its ID): only its audio comes from here, through
 * [streams] (rooms get a link to the host's `/api/audio/…` instead of YouTube's) and [audio]
 * (that route answers from the file).
 *
 * It fills itself: with what the host streams anyway ([audio], for speakers and players that
 * stream through it), with what a phone's own player plays ([copy]), and from a song's 2nd play
 * in 30 days by fetching it ([started]). Podcast episodes aren't kept. When it gets over
 * [maxBytes], the songs with the lowest keep score go (see [trim]).
 */
class SongCache(
    dir: File,
    maxBytes: Long,
    /** Looks songs up on YouTube: the ones that aren't here, and links for devices that may not stream through the host. */
    private val lookup: StreamResolver,
    /** Fetches whole songs to keep. Null: only what passes through [copy] is kept. */
    private val audio: AudioFiles?,
    private val scope: CoroutineScope,
    /** Downloaded songs (a phone's Music folder): they play from there, checked before the cache. */
    private val downloads: DownloadedSongs? = null,
    /** A song's cover to keep with it (for offline), or null. */
    private val coverOf: suspend (Song) -> ByteArray? = { withContext(Dispatchers.IO) { SongFiles.cover(it, COVER_SIZE) } },
    private val clock: () -> Long = System::currentTimeMillis,
    /** A song worth fetching waits this long first (ms): it may come in for free, or be skipped. */
    private val fetchDelayMs: Long = FETCH_DELAY_MS,
    /** Where plays are counted per day ([importPlays]). */
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    @Volatile
    var dir: File = dir
        private set

    /** 0: off (nothing new is kept). */
    @Volatile
    var maxBytes: Long = maxBytes
        private set

    /** Being moved to another folder ([moveTo]): nothing new is kept meanwhile. */
    @Volatile
    var moving = false
        private set

    // Under [lock]: quick work only, never waiting.
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    /** When songs were played (the last [PLAYS_KEPT_MS]), also songs that aren't here. */
    private val plays = HashMap<String, MutableList<Long>>()
    /** What songs are (title, artists, …), from the rooms that started them: needed to keep one. */
    private val known = object : LinkedHashMap<String, Song>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>) = size > KNOWN_SONGS
    }
    /** What the app knew of songs it downloaded (richer than their tags), by song ID. */
    private val downloadedSongs = HashMap<String, Song>()
    /** Phone music checked against YouTube Music, by the track's key (see [LocalMatcher]). */
    private val localMatches = HashMap<String, LocalMatch>()
    /** Changes to [entries] and [downloadedSongs], for [library]. */
    @Volatile
    private var version = 0

    private val fills = ConcurrentHashMap<String, Fill>()
    private val copies = ConcurrentHashMap<String, Copy>()
    private val fetches = Channel<Song>(Channel.UNLIMITED)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)
    private val trimming = Mutex()
    private val fetching = Mutex()
    /** Links looked up for devices that may not stream through the host ([directLink]). */
    private val links = ConcurrentHashMap<String, Pair<String, Long>>()

    /** Songs that mustn't go now: playing or next in a room (see [songsInUse]). */
    @Volatile
    var inUse: suspend () -> Set<String> = { emptySet() }

    /** The song the phone's own player plays: it mustn't go either. */
    @Volatile
    var playingHere: String? = null

    init {
        load()
        scope.launch(Dispatchers.IO) { saveLoop() }
        scope.launch(Dispatchers.IO) { fetchLoop() }
    }

    /** Whether [song] may be kept: YouTube Music music (no episodes, nothing very long), while the cache is on. */
    fun takes(song: Song): Boolean =
        maxBytes > 0 && !moving && song.id.startsWith("ytm:") && song.podcast == null && song.durationMs <= MAX_LENGTH_MS

    fun has(songId: String): Boolean = synchronized(lock) { songId in entries }

    /** The IDs of the songs saved here. */
    fun songIds(): List<String> = synchronized(lock) { entries.keys.toList() }

    /** Where the phone's own player can play [songId] from: its download, or its file here; null when neither. */
    fun localUri(songId: String): String? =
        downloads?.find(songId)?.uri ?: synchronized(lock) { entries[songId] }?.let { File(dir, it.file).toURI().toString() }

    // --- what rooms play ---

    /**
     * A room (or a phone's player in a room elsewhere) started [song]: it counts as a play,
     * and from the 2nd in [WORTH_WINDOW_MS] a song that isn't here yet is fetched, after a
     * moment (it may come in for free meanwhile, or be skipped: see [finished]).
     */
    fun started(song: Song) {
        if (!takes(song)) return
        val now = clock()
        val fetch = synchronized(lock) {
            known[song.id] = song
            entries[song.id]?.let { entries[song.id] = it.copy(lastPlayed = now) }
            val times = plays.getOrPut(song.id) { mutableListOf() }
            times += now
            times.removeAll { it < now - PLAYS_KEPT_MS }
            song.id !in entries && worthLocked(song.id, now)
        }
        saveSoon()
        if (fetch && audio != null && downloads?.find(song.id) == null) {
            scope.launch {
                delay(fetchDelayMs)
                if (worth(song.id) && !has(song.id) && !fills.containsKey(song.id) && !copies.containsKey(song.id)) fetchLater(song)
            }
        }
    }

    /** A song a room played ended: skipped within 30 s, it doesn't count as a play (like YouTube's). */
    fun finished(play: FinishedPlay) = finished(play.item.song, play.heardMs >= COUNTS_AFTER_MS || !play.skipped)

    /** [song] ended; [counts] false: skipped too soon to count as a play (its play from [started] is taken back). */
    fun finished(song: Song, counts: Boolean) {
        if (counts) return
        synchronized(lock) { plays[song.id]?.let { if (it.isNotEmpty()) it.removeAt(it.lastIndex) } }
        saveSoon()
    }

    fun localMatch(key: String): LocalMatch? = synchronized(lock) { localMatches[key] }

    fun setLocalMatch(key: String, match: LocalMatch?) {
        synchronized(lock) {
            if (match == null) localMatches.remove(key) else localMatches[key] = match
            version++
        }
        saveSoon()
    }

    /** Forgets the checks [drop] says yes to. */
    fun forgetLocalMatches(drop: (LocalMatch) -> Boolean) {
        synchronized(lock) {
            if (localMatches.values.removeAll(drop)) version++
        }
        saveSoon()
    }

    /** The keys that have a check stored. */
    fun localMatchKeys(): Set<String> = synchronized(lock) { localMatches.keys.toSet() }

    /** Played at least [WORTH_PLAYS] times in [WORTH_WINDOW_MS]: worth an extra download. */
    fun worth(songId: String): Boolean = synchronized(lock) { worthLocked(songId, clock()) }

    private fun worthLocked(songId: String, now: Long) =
        (plays[songId]?.count { it >= now - WORTH_WINDOW_MS } ?: 0) >= WORTH_PLAYS

    /** The app downloaded [song]: it plays from the download now, so its copy here goes; its cover stays for the list. */
    fun downloaded(song: Song) {
        val removed = synchronized(lock) {
            downloadedSongs[song.id] = song
            version++
            entries.remove(song.id)
        }
        removed?.let { File(dir, it.file).delete() }
        saveSoon()
        scope.launch { saveCover(song) }
    }

    // --- in front of YouTube Music ---

    /** Cached and downloaded songs get the host's `/api/audio/…` (and their length) without a YouTube lookup. */
    val streams: StreamResolver = object : StreamResolver {
        override suspend fun resolveStream(songId: String) = resolve(songId).url

        override suspend fun resolve(songId: String): ResolvedStream {
            val length = savedLength(songId) ?: return lookup.resolve(songId)
            return ResolvedStream(audioPath(songId), length.takeIf { it > 0 })
        }
    }

    private fun savedLength(songId: String): Long? {
        downloads?.find(songId)?.let { download -> return synchronized(lock) { downloadedSongs[songId] }?.durationMs ?: download.song.durationMs }
        return synchronized(lock) { entries[songId] }?.takeIf { File(dir, it.file).isFile }?.song?.durationMs
    }

    /**
     * The host's audio route: a download or cached file; else a song that may be kept is fetched
     * once, served while it comes in and kept; anything else goes to [inner].
     */
    fun audio(inner: AudioProxy?): AudioProxy = AudioProxy { call, songId, range ->
        downloads?.find(songId)?.let { return@AudioProxy downloads.respond(call, it, range) }
        val entry = synchronized(lock) { entries[songId] }
        val file = entry?.let { File(dir, it.file) }
        if (entry != null && file != null && file.isFile) {
            log.debug("Song cache: {} from the cache", songId)
            return@AudioProxy respondFile(call, file, entry.type, range)
        }
        val song = synchronized(lock) { known[songId] }?.takeIf { takes(it) && audio != null }
        if (song == null) return@AudioProxy inner?.respond(call, songId, range) ?: call.respond(HttpStatusCode.NotFound)
        val fill = fills.computeIfAbsent(songId) { Fill(song) }.also { it.start() }
        fill.respond(call, range, inner)
    }

    /** Downloads (Download in the song menu) of a cached song are made from its file. */
    fun files(inner: AudioFiles): AudioFiles = object : AudioFiles {
        override suspend fun download(songId: String, to: File, started: (type: String, size: Long?) -> Unit): String {
            val entry = synchronized(lock) { entries[songId] } ?: return inner.download(songId, to, started)
            val file = File(dir, entry.file)
            if (!file.isFile) return inner.download(songId, to, started)
            started(entry.type, file.length())
            withContext(Dispatchers.IO) { file.copyTo(to, overwrite = true) }
            return entry.type
        }
    }

    /**
     * A YouTube link for a cached song, for a device that may not stream through the host
     * (`[audio] proxy = "lan"`): it gets sent there. Looked up once in a while, not on every request.
     */
    suspend fun directLink(songId: String): String {
        val now = clock()
        links[songId]?.let { (url, at) -> if (now - at < LINK_KEPT_MS) return url }
        val url = lookup.resolveStream(songId)
        links.entries.removeIf { now - it.value.second >= LINK_KEPT_MS }
        links[songId] = url to now
        return url
    }

    /** Sends the cover kept with [songId]; false when there's none. */
    suspend fun respondArt(call: ApplicationCall, songId: String, size: Int): Boolean {
        val cover = coverFile(songId)
        if (cover.isFile) {
            call.respondBytes(withContext(Dispatchers.IO) { cover.readBytes() }, ContentType.Image.JPEG)
            return true
        }
        val download = downloads?.find(songId) ?: return false
        return downloads.respondArt(call, download, size)
    }

    // --- what a phone's own player plays ---

    /**
     * Keeps what the phone's player reads of [song] anyway (Media3 tees it here), in pieces at
     * any place; once they make the whole song it's kept. Null when it isn't taken, or is here
     * (or coming in) already.
     */
    fun copy(song: Song): Copy? {
        if (!takes(song) || has(song.id) || fills.containsKey(song.id) || downloads?.find(song.id) != null) return null
        synchronized(lock) { known[song.id] = song }
        return copies.computeIfAbsent(song.id) { Copy(song) }
    }

    /** The phone's player moved on from [songId]: what isn't whole is dropped, or fetched whole when it's worth it. */
    fun leave(songId: String) {
        val copy = copies.remove(songId) ?: return
        if (!copy.drop()) return
        val song = synchronized(lock) { known[songId] } ?: return
        if (audio != null && worth(songId) && !has(songId)) fetchLater(song)
    }

    /** One song as the phone's player reads it: pieces written where they belong; whole when they cover it all. */
    inner class Copy internal constructor(val song: Song) {
        private val temp = File(dir, key(song.id) + ".copy")
        private var file: RandomAccessFile? = null
        private val have = Ranges()
        private var total: Long? = null
        private var position = 0L
        private var finished = false

        /** The player reads from [at] on; [length] bytes follow (null: not known). */
        @Synchronized
        fun open(at: Long, length: Long?) {
            if (finished) return
            position = at
            if (length != null && length > 0) total = maxOf(total ?: 0, at + length)
        }

        @Synchronized
        fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (finished || length <= 0) return
            try {
                val out = file ?: RandomAccessFile(temp, "rw").also { file = it }
                out.seek(position)
                out.write(buffer, offset, length)
                have.add(position, position + length)
                position += length
            } catch (e: IOException) {
                log.info("Song cache: couldn't keep {} as it plays: {}", song.id, e.message)
                dropLocked()
            }
        }

        /** The player closed its source: the song is kept when it's all there. */
        @Synchronized
        fun close() {
            val size = total ?: return
            if (finished || !have.covers(0, size)) return
            finished = true
            val out = file ?: return
            file = null
            runCatching { out.setLength(size) }
            out.close()
            copies.remove(song.id, this)
            val type = typeOf(temp)
            if (type == null) {
                log.info("Song cache: {} played here isn't audio we know, not kept", song.id)
                temp.delete()
            } else {
                log.info("Song cache: {} \"{}\" kept as it played ({} KB)", song.id, song.title, size / 1024)
                commit(song, temp, type)
            }
        }

        /** Drops it unless it was kept; true when it dropped something unfinished. */
        @Synchronized
        internal fun drop(): Boolean {
            if (finished) return false
            dropLocked()
            return true
        }

        private fun dropLocked() {
            finished = true
            runCatching { file?.close() }
            file = null
            temp.delete()
            copies.remove(song.id, this)
        }
    }

    // --- fetching whole songs ---

    private fun fetchLater(song: Song) {
        if (queued.add(song.id)) fetches.trySend(song)
    }

    /** Waits until fetching in the background is allowed (a phone: on Wi-Fi, if the owner wants that). */
    @Volatile
    var fetchGate: suspend () -> Unit = {}

    /** One song at a time, in the background. */
    private suspend fun fetchLoop() {
        if (audio == null) return
        for (song in fetches) {
            try {
                if (!wanted(song)) continue
                fetchGate()
                fetch(song)
            } finally {
                queued.remove(song.id)
            }
        }
    }

    /** [song] may be kept and isn't here, coming in, or downloaded. */
    fun wanted(song: Song): Boolean =
        takes(song) && !has(song.id) && !fills.containsKey(song.id) && !copies.containsKey(song.id) && downloads?.find(song.id) == null

    /**
     * Fetches [song] whole and keeps it, now (the caller waited for [fetchGate]); false when it
     * isn't wanted any more or the fetch failed. One at a time with the background fetches.
     */
    suspend fun fetch(song: Song): Boolean = fetching.withLock {
        val audio = audio ?: return@withLock false
        // It may have come in for free meanwhile.
        if (!wanted(song)) return@withLock false
        synchronized(lock) { known[song.id] = song }
        val temp = File(dir, key(song.id) + ".part")
        val started = System.nanoTime()
        try {
            val type = audio.download(song.id, temp)
            log.info("Song cache: fetched {} \"{}\" ({} KB in {} ms)", song.id, song.title, temp.length() / 1024, (System.nanoTime() - started) / 1_000_000)
            commit(song, temp, type)
            true
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            log.info("Song cache: couldn't fetch {}: {}", song.id, e.message)
            false
        }
    }

    /**
     * There's room for another song nobody played here yet (liked songs, say): with a song of
     * the usual size it stays under 90 % of its size, so it doesn't push others out.
     */
    fun hasRoom(): Boolean {
        if (maxBytes <= 0 || moving) return false
        val total = synchronized(lock) { entries.values.sumOf { it.size } }
        return total + usualSongBytes() <= maxBytes / 10 * 9
    }

    /** How big a song here usually is: the cached songs' average, 4 MB when there are none. */
    fun usualSongBytes(): Long {
        val (total, count) = synchronized(lock) { entries.values.sumOf { it.size } to entries.size }
        return if (count > 0) total / count else USUAL_SONG_BYTES
    }

    /**
     * Plays heard elsewhere (the account's YouTube Music history): [song] at about [at]. A day
     * that already has a play of the song isn't counted again (plays here go into that history
     * too, and the history is read again later). They count like plays here: from the 2nd in
     * [WORTH_WINDOW_MS] a song is [worth] keeping, and they keep songs longer.
     */
    fun importPlays(heard: List<Pair<Song, Long>>): Int {
        val now = clock()
        var added = 0
        synchronized(lock) {
            for ((song, at) in heard) {
                if (!takes(song) || at < now - PLAYS_KEPT_MS) continue
                val times = plays.getOrPut(song.id) { mutableListOf() }
                val day = dayOf(at)
                if (times.any { dayOf(it) == day }) continue
                times += minOf(at, now)
                times.sort()
                known[song.id] = song
                added++
            }
        }
        if (added > 0) saveSoon()
        return added
    }

    private fun dayOf(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    /**
     * A song fetched once for the host's audio route and kept: every request for it (any
     * range, a speaker and a browser at once) reads the file as it comes in.
     */
    private inner class Fill(val song: Song) {
        val temp = File(dir, key(song.id) + ".part")
        /** Its type and size, once the fetch has them. */
        val head = CompletableDeferred<Pair<String, Long?>>()
        /** True when it's all in. */
        val done = CompletableDeferred<Boolean>()
        val readers = AtomicInteger()
        private var job: Job? = null

        @Synchronized
        fun start() {
            if (job != null) return
            job = scope.launch(Dispatchers.IO) {
                val started = System.nanoTime()
                try {
                    val type = audio!!.download(song.id, temp) { type, size -> head.complete(type to size) }
                    head.complete(type to temp.length())
                    log.info("Song cache: {} \"{}\" in while streaming it ({} KB in {} ms)", song.id, song.title, temp.length() / 1024, (System.nanoTime() - started) / 1_000_000)
                    commit(song, temp, type)
                    done.complete(true)
                } catch (e: Throwable) {
                    head.completeExceptionally(if (e is CancellationException) IOException("Stopped") else e)
                    done.complete(false)
                    temp.delete()
                    if (e is CancellationException) throw e
                    log.info("Song cache: fetching {} for the host's stream stopped: {}", song.id, e.message)
                } finally {
                    fills.remove(song.id, this@Fill)
                }
            }
        }

        fun cancel() = job?.cancel()

        suspend fun respond(call: ApplicationCall, range: String?, inner: AudioProxy?) {
            readers.incrementAndGet()
            try {
                val (type, size) = try {
                    head.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The fetch didn't start: stream it the usual way.
                    return inner?.respond(call, song.id, range) ?: throw e
                }
                // Opened now (it may be kept and moved meanwhile), read once the answer is sent.
                val reader = withContext(Dispatchers.IO) { open(type) } ?: return inner?.respond(call, song.id, range) ?: call.respond(HttpStatusCode.NotFound)
                try {
                    serve(call, reader, type, size, range)
                } catch (e: Throwable) {
                    reader.close()
                    throw e
                }
            } finally {
                if (readers.decrementAndGet() == 0) scope.launch {
                    // Nobody listens any more (a skip): keep it coming only when it's worth it.
                    delay(ABANDON_MS)
                    if (readers.get() == 0 && !done.isCompleted && !worth(song.id)) {
                        log.info("Song cache: {} not listened to any more, not kept", song.id)
                        cancel()
                    }
                }
            }
        }

        /** The file as it comes in, or where it went once it's kept. */
        private fun open(type: String): RandomAccessFile? =
            runCatching { RandomAccessFile(temp, "r") }.getOrNull()
                ?: runCatching { RandomAccessFile(File(dir, key(song.id) + "." + extension(type)), "r") }.getOrNull()

        private suspend fun serve(call: ApplicationCall, file: RandomAccessFile, type: String, size: Long?, range: String?) {
            val contentType = runCatching { ContentType.parse(type) }.getOrNull()
            val (start, requestedEnd) = if (size == null) 0L to ByteRange.OPEN_END else ByteRange.parse(range)
            val end = if (size == null) ByteRange.OPEN_END else minOf(requestedEnd, size - 1)
            if (start > end) {
                file.close()
                call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                call.respondBytes(ByteArray(0), status = HttpStatusCode.RequestedRangeNotSatisfiable)
                return
            }
            if (size != null) call.response.header(HttpHeaders.AcceptRanges, "bytes")
            val partial = range != null && size != null
            if (partial) call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$size")
            call.respondOutputStream(contentType, if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK, size?.let { end - start + 1 }) {
                file.use { withContext(Dispatchers.IO) { copyGrowing(it, this@respondOutputStream, start, end) } }
            }
        }

        private suspend fun copyGrowing(file: RandomAccessFile, out: OutputStream, start: Long, end: Long) {
            val buffer = ByteArray(BUFFER)
            var at = start
            try {
                while (at <= end) {
                    val have = file.length()
                    if (have > at) {
                        file.seek(at)
                        val n = file.read(buffer, 0, minOf(buffer.size.toLong(), end - at + 1, have - at).toInt())
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        at += n
                    } else if (done.isCompleted) {
                        // All in (and nothing more to read), or the fetch broke off.
                        if (file.length() <= at) break
                    } else {
                        delay(POLL_MS)
                    }
                }
            } catch (e: IOException) {
                // Usually the listener stopped or skipped.
            }
        }
    }

    // --- keeping and removing ---

    /** Keeps [temp] (whole) as [song]'s file, unless it's here already. */
    private fun commit(song: Song, temp: File, type: String) {
        val name = key(song.id) + "." + extension(type)
        val size = temp.length()
        val now = clock()
        val kept = synchronized(lock) {
            if (song.id in entries || maxBytes <= 0 || moving || size <= 0) return@synchronized false
            val target = File(dir, name)
            if (!temp.renameTo(target)) return@synchronized false
            val lastPlayed = plays[song.id]?.maxOrNull() ?: now
            entries[song.id] = Entry(song, name, type, size, now, lastPlayed)
            version++
            true
        }
        if (!kept) {
            temp.delete()
            return
        }
        saveSoon()
        scope.launch {
            saveCover(song)
            trim()
        }
    }

    private suspend fun saveCover(song: Song) {
        val file = coverFile(song.id)
        if (file.isFile) return
        val bytes = try {
            coverOf(song)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val temp = File(dir, key(song.id) + ".jpg.part")
                temp.writeBytes(bytes)
                temp.renameTo(file)
            }
        }
    }

    val totalBytes: Long get() = synchronized(lock) { entries.values.sumOf { it.size } }

    val songCount: Int get() = synchronized(lock) { entries.size }

    /**
     * Over [limit]: songs go until it's under 90 % of it (so it doesn't clean up on every
     * song), lowest keep score first: last played, plus a week for each other play in 90 days.
     * Songs in use stay.
     */
    suspend fun trim(limit: Long = maxBytes) = trimming.withLock {
        if (totalBytes <= limit) return@withLock
        val keep = protectedSongs()
        val now = clock()
        val order = synchronized(lock) { entries.values.filter { it.song.id !in keep }.sortedBy { keepScore(it, now) } }
        var size = totalBytes
        val target = limit / 10 * 9
        var removed = 0
        for (entry in order) {
            if (size <= target) break
            remove(entry.song.id)
            size -= entry.size
            removed++
        }
        log.info("Song cache: {} song(s) removed to make room ({} MB of {} MB)", removed, size / MB, limit / MB)
    }

    private fun keepScore(entry: Entry, now: Long): Long {
        val recent = synchronized(lock) { plays[entry.song.id]?.count { it >= now - PLAYS_KEPT_MS } ?: 0 }
        return entry.lastPlayed + PLAY_WORTH_MS * (recent - 1)
    }

    private suspend fun protectedSongs(): Set<String> =
        runCatching { inUse() }.getOrDefault(emptySet()) + setOfNotNull(playingHere) + fills.keys

    private fun remove(songId: String) {
        val entry = synchronized(lock) {
            entries.remove(songId)?.also { version++ }
        } ?: return
        File(dir, entry.file).delete()
        if (synchronized(lock) { songId !in downloadedSongs }) coverFile(songId).delete()
        saveSoon()
    }

    /** Clear cache: everything that isn't playing. */
    suspend fun clear() {
        val keep = protectedSongs()
        val all = synchronized(lock) { entries.keys.toList() }
        all.filter { it !in keep }.forEach(::remove)
        log.info("Song cache cleared ({} kept: playing)", all.count { it in keep })
    }

    /** A new size: smaller removes songs right away; 0 turns the cache off and empties it. */
    suspend fun setMaxBytes(bytes: Long) {
        maxBytes = bytes.coerceAtLeast(0)
        if (maxBytes == 0L) {
            fills.values.forEach { it.cancel() }
            copies.values.toList().forEach { it.drop() }
            clear()
        } else {
            trim()
        }
    }

    /**
     * Moves what's cached to [to] (a phone: its own storage or the SD card), in the background
     * ([moving] meanwhile). Songs that can't be moved are dropped.
     */
    fun moveTo(to: File): Job? {
        val from = dir
        if (from.canonicalPath == to.canonicalPath) return null
        moving = true
        return scope.launch(Dispatchers.IO) { moveAll(from, to) }
    }

    private fun moveAll(from: File, to: File) {
        try {
            fills.values.forEach { it.cancel() }
            copies.values.toList().forEach { it.drop() }
            to.mkdirs()
            val all = synchronized(lock) { entries.values.toList() }
            var moved = 0
            for (entry in all) {
                val ok = moveFile(File(from, entry.file), File(to, entry.file))
                if (ok) moved++ else synchronized(lock) { entries.remove(entry.song.id) }
            }
            for (cover in from.listFiles().orEmpty().filter { isOurs(it.name) && it.name.endsWith(".jpg") }) moveFile(cover, File(to, cover.name))
            synchronized(lock) {
                version++
                dir = to
            }
            writeIndex()
            File(from, INDEX).delete()
            from.listFiles().orEmpty().filter { isOurs(it.name) }.forEach { it.delete() }
            log.info("Song cache moved to {} ({} of {} songs)", to, moved, all.size)
        } catch (e: Exception) {
            log.warn("Song cache: moving to {} failed: {}", to, e.message)
        } finally {
            moving = false
        }
    }

    private fun moveFile(from: File, to: File): Boolean {
        if (!from.isFile) return false
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = true)
            from.delete()
            true
        } catch (e: IOException) {
            to.delete()
            false
        }
    }

    suspend fun status(): SongCacheStatus = SongCacheStatus(
        songs = songCount,
        bytes = totalBytes,
        maxBytes = maxBytes,
        downloads = downloads?.let { runCatching { it.all().count { d -> !d.local } }.getOrDefault(0) } ?: 0,
        local = downloads?.let { runCatching { it.all().count { d -> d.local } }.getOrDefault(0) } ?: 0,
        moving = moving,
    )

    // --- "Cached on this phone" ---

    /**
     * The cached and downloaded songs as a library of the Local tab, to pick them without
     * search (and without internet). Its songs keep their own IDs ([LocalTrack.song]); the
     * folders *Downloaded* and *Cached* say which are which. [baseUrl]: the host's address.
     */
    fun library(baseUrl: () -> String): LocalLibrary = object : LocalLibrary {
        override val id = LIBRARY_ID
        override val name = "Cached on this phone"
        override val kind = LocalLibraryKind.CACHE

        private var built: Triple<Int, List<DownloadedSong>, List<LocalTrack>>? = null

        override suspend fun tracks(): List<LocalTrack> {
            val downloaded = downloads?.all().orEmpty()
            val now = version
            built?.let { (v, d, tracks) -> if (v == now && d === downloaded) return tracks }
            val (cached, richer) = synchronized(lock) { entries.values.map { it.song } to HashMap(downloadedSongs) }
            val downloadIds = downloaded.map { it.song.id }.toSet()
            // What the app knew when it downloaded a song, with the file's own length.
            val tracks = downloaded.map { d ->
                val song = richer[d.song.id]?.let { if (d.song.durationMs > 0) it.copy(durationMs = d.song.durationMs) else it } ?: d.song
                track(song, if (d.local) ON_PHONE else DOWNLOADED)
            } + cached.filter { it.id !in downloadIds }.map { track(it, CACHED) }
            built = Triple(now, downloaded, tracks)
            return tracks
        }

        private fun track(song: Song, folder: String) = LocalTrack(
            key = song.id,
            title = song.title,
            artist = song.artists.joinToString(", ") { it.name },
            albumArtist = song.artists.firstOrNull()?.name.orEmpty(),
            album = song.album?.name.orEmpty(),
            disc = null,
            track = null,
            year = null,
            durationMs = song.durationMs,
            folder = folder,
            song = song,
        )

        override fun fileUrl(key: String) = baseUrl() + audioPath(key)

        override fun artUrl(key: String, size: Int) = "${baseUrl()}/api/local/art/${URLEncoder.encode(key, "UTF-8")}?size=$size"

        override suspend fun respond(call: ApplicationCall, key: String, range: String?) = audio(null).respond(call, key, range)

        override suspend fun respondArt(call: ApplicationCall, key: String, size: Int) = this@SongCache.respondArt(call, key, size)
    }

    // --- the index ---

    @Serializable
    internal data class Entry(
        val song: Song,
        val file: String,
        val type: String,
        val size: Long,
        val savedAt: Long,
        val lastPlayed: Long,
    )

    @Serializable
    private data class Index(
        val songs: List<Entry> = emptyList(),
        val plays: Map<String, List<Long>> = emptyMap(),
        val downloads: List<Song> = emptyList(),
        val local: Map<String, LocalMatch> = emptyMap(),
    )

    /** Reads the index; files without a row (a fetch cut short, say) go. */
    private fun load() {
        dir.mkdirs()
        val index = File(dir, INDEX).takeIf { it.isFile }?.let { file ->
            runCatching { json.decodeFromString(Index.serializer(), file.readText()) }
                .onFailure { log.warn("Song cache: couldn't read {}: {}", file, it.message) }
                .getOrNull()
        } ?: Index()
        val now = clock()
        synchronized(lock) {
            for (entry in index.songs) {
                val file = File(dir, entry.file)
                if (file.isFile && file.length() == entry.size) entries[entry.song.id] = entry
            }
            for ((id, times) in index.plays) times.filter { it >= now - PLAYS_KEPT_MS }.takeIf { it.isNotEmpty() }?.let { plays[id] = it.toMutableList() }
            for (song in index.downloads) downloadedSongs[song.id] = song
            localMatches.putAll(index.local)
        }
        val files = synchronized(lock) { entries.values.map { it.file }.toSet() }
        val covers = synchronized(lock) { (entries.keys + downloadedSongs.keys).map { key(it) + ".jpg" }.toSet() }
        // Only its own files, whatever else is in the folder: songs without a row (a fetch cut short, say).
        for (file in dir.listFiles().orEmpty()) {
            if (!isOurs(file.name) || file.name in files || file.name in covers) continue
            file.delete()
        }
        if (songCount > 0) log.info("Song cache: {} songs, {} MB of {} MB in {}", songCount, totalBytes / MB, maxBytes / MB, dir)
    }

    private fun saveSoon() {
        saveSignal.trySend(Unit)
    }

    private suspend fun saveLoop() {
        for (signal in saveSignal) {
            delay(SAVE_DELAY_MS)
            if (!moving) runCatching { writeIndex() }.onFailure { log.warn("Song cache: couldn't save its index: {}", it.message) }
        }
    }

    /** Saves the index now (else it's saved a moment after each change). */
    internal fun saveNow() = writeIndex()

    private fun writeIndex() {
        val index = synchronized(lock) {
            Index(entries.values.toList(), plays.mapValues { it.value.toList() }, downloadedSongs.values.toList(), HashMap(localMatches))
        }
        val file = File(dir, INDEX)
        val temp = File(dir, "$INDEX.part")
        temp.writeText(json.encodeToString(Index.serializer(), index))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun coverFile(songId: String) = File(dir, key(songId) + ".jpg")

    companion object {
        private val log = LoggerFactory.getLogger(SongCache::class.java)
        private val json = Json { ignoreUnknownKeys = true }

        const val LIBRARY_ID = "cache"
        const val DOWNLOADED = "Downloaded"
        const val CACHED = "Cached"
        const val ON_PHONE = "On this phone"
        private const val INDEX = "index.json"
        private const val MB = 1024L * 1024

        /** Plays are counted this long (the keep score's "recent plays"). */
        const val PLAYS_KEPT_MS = 90L * 24 * 3600 * 1000
        /** A song is worth an extra download from [WORTH_PLAYS] plays in this long. */
        const val WORTH_WINDOW_MS = 30L * 24 * 3600 * 1000
        const val WORTH_PLAYS = 2
        /** Each other recent play counts as this much more recent in the keep score. */
        private const val PLAY_WORTH_MS = 7L * 24 * 3600 * 1000
        /** Longer "songs" (hour-long mixes, whole concerts) aren't kept. */
        private const val MAX_LENGTH_MS = 60L * 60 * 1000
        /** A song worth fetching waits this long: it may come in for free, or be skipped. */
        private const val FETCH_DELAY_MS = 30_000L
        /** A song coming in for the host's stream goes on this long after its last listener. */
        private const val ABANDON_MS = 10_000L
        private const val SAVE_DELAY_MS = 2_000L
        private const val POLL_MS = 50L
        private const val BUFFER = 64 * 1024
        /** YouTube's links last hours; one is looked up again after this. */
        private const val LINK_KEPT_MS = 30L * 60 * 1000
        private const val KNOWN_SONGS = 500
        /** About the size of a song (4 minutes as YouTube sends it), until there are songs to tell. */
        private const val USUAL_SONG_BYTES = 4L * MB
        /** A skipped song counts as played after this long. */
        const val COUNTS_AFTER_MS = 30_000L
        private const val COVER_SIZE = 544

        /** The host's audio route for a song, as rooms hand it out for cached songs. */
        fun audioPath(songId: String) = "/api/audio/" + URLEncoder.encode(songId, "UTF-8")

        /** A song ID as a file name ("ytm:abc" -> "ytm_abc"): no ":" (SD cards don't take it). */
        fun key(songId: String) = songId.replace(Regex("[^A-Za-z0-9_-]"), "_")

        /** A file the cache made (songs are only YouTube Music's), or the index's temporary file. */
        private fun isOurs(name: String) = name.startsWith("ytm_") || name == "$INDEX.part"

        private fun extension(type: String) = when (type.substringBefore(';').trim()) {
            "audio/webm" -> "webm"
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            else -> "m4a"
        }

        /** What a file is from its first bytes: MP4 (.m4a) or WebM, else null. */
        internal fun typeOf(file: File): String? {
            val head = ByteArray(12)
            val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
            return when {
                read >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> SongFiles.MP4
                read >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> "audio/webm"
                else -> null
            }
        }

        /** Sends [file] with [range], like a web server. */
        internal suspend fun respondFile(call: ApplicationCall, file: File, type: String, range: String?) {
            val total = file.length()
            val (start, requestedEnd) = ByteRange.parse(range)
            val end = minOf(requestedEnd, total - 1)
            if (start > end) {
                call.response.header(HttpHeaders.ContentRange, "bytes */$total")
                call.respondBytes(ByteArray(0), status = HttpStatusCode.RequestedRangeNotSatisfiable)
                return
            }
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            if (range != null) call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$total")
            call.respondOutputStream(runCatching { ContentType.parse(type) }.getOrNull(), if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK, end - start + 1) {
                withContext(Dispatchers.IO) {
                    try {
                        RandomAccessFile(file, "r").use { input ->
                            input.seek(start)
                            val buffer = ByteArray(BUFFER)
                            var left = end - start + 1
                            while (left > 0) {
                                val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                                if (n < 0) break
                                write(buffer, 0, n)
                                left -= n
                            }
                        }
                    } catch (e: IOException) {
                        // Usually the listener stopped or skipped.
                    }
                }
            }
        }
    }
}

/** Byte ranges `[start, end)` that are there, merged. */
internal class Ranges {
    private val list = mutableListOf<LongArray>()

    fun add(start: Long, end: Long) {
        if (end <= start) return
        var s = start
        var e = end
        val kept = mutableListOf<LongArray>()
        for (r in list) {
            if (r[1] < s || r[0] > e) kept += r else {
                s = minOf(s, r[0])
                e = maxOf(e, r[1])
            }
        }
        kept += longArrayOf(s, e)
        kept.sortBy { it[0] }
        list.clear()
        list.addAll(kept)
    }

    fun covers(start: Long, end: Long): Boolean = list.any { it[0] <= start && it[1] >= end }
}

/** The songs playing or next in this host's rooms: the song cache keeps them. */
suspend fun RoomManager.songsInUse(): Set<String> =
    all().flatMap { room -> room.view().let { listOfNotNull(it.current?.song?.id, it.next?.song?.id) } }.toSet()
