package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.core.SongMatch
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.core.SourceBlocked
import dev.trashpanda.ytmp.protocol.ConvertCounts
import dev.trashpanda.ytmp.protocol.ConvertItem
import dev.trashpanda.ytmp.protocol.ConvertItemPage
import dev.trashpanda.ytmp.protocol.ConvertItemRow
import dev.trashpanda.ytmp.protocol.ConvertItemState
import dev.trashpanda.ytmp.protocol.ConvertJob
import dev.trashpanda.ytmp.protocol.ConvertJobStatus
import dev.trashpanda.ytmp.protocol.ConvertListKind
import dev.trashpanda.ytmp.protocol.ConvertListPreview
import dev.trashpanda.ytmp.protocol.ConvertListStatus
import dev.trashpanda.ytmp.protocol.ConvertPick
import dev.trashpanda.ytmp.protocol.ConvertPreview
import dev.trashpanda.ytmp.protocol.ConvertSettings
import dev.trashpanda.ytmp.protocol.ConvertStartRequest
import dev.trashpanda.ytmp.protocol.ConvertTarget
import dev.trashpanda.ytmp.protocol.ConvertTargetKind
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.PlaylistItemRef
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream

/** A pick, a cancel, … that can't be done: the message says why (for the API's 400 or 404). */
class ConvertException(message: String, val notFound: Boolean = false) : Exception(message)

/**
 * Imports playlists (docs/implementation/playlist-converter.md): what a reader read becomes a
 * stored job, whose songs are looked up one at a time in the background ([ConvertMatcher], with
 * the match memory first) and written to YouTube Music as each list is looked up whole. Kept in
 * [dir] as JSON (a job per file, and `matches.json`), so it goes on after a restart.
 */
class PlaylistConverter(
    private val dir: File,
    search: SongSearch,
    /** The YouTube Music of a job's owner (an account here; a phone: its owner's), or null when not signed in. */
    private val personalOf: (owner: String) -> PersonalCatalog?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val gapMs: Long = GAP_MS,
    private val writeGapMs: Long = WRITE_GAP_MS,
    private val likeGapMs: Long = LIKE_GAP_MS,
    private val blockedPauseMs: Long = BLOCKED_PAUSE_MS,
    private val failedPauseMs: Long = FAILED_PAUSE_MS,
) {
    private val log = LoggerFactory.getLogger(PlaylistConverter::class.java)
    private val matcher = ConvertMatcher(search)
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    private val jobs = LinkedHashMap<String, StoredJob>()
    private val memory = HashMap<String, Remembered>()
    private val reads = LinkedHashMap<String, Pending>()
    private val dirty = HashSet<String>()
    private var memoryDirty = false
    private var savedAt = 0L

    /** Why nothing is looked up right now (YouTube's bot check, no answer), and until when. */
    @Volatile private var waiting: String? = null
    @Volatile private var until: Long? = null
    /** The last step wrote: matching goes next, so a long list of likes doesn't hold up the rest. */
    private var wrote = false

    init {
        dir.mkdirs()
        runCatching { memory.putAll(json.decodeFromString<Map<String, Remembered>>(File(dir, MEMORY_FILE).readText())) }
        for (file in dir.listFiles { f -> f.name.startsWith("job-") && f.name.endsWith(".json") }.orEmpty()) {
            runCatching { json.decodeFromString<StoredJob>(file.readText()) }
                .onSuccess { jobs[it.id] = it }
                .onFailure { log.warn("Can't read import {}: {}", file.name, it.message) }
        }
    }

    fun start() {
        if (worker != null) return
        worker = scope.launch { run() }
    }

    /** Looks again now (the app woke up; a phone's waits went by the wall clock). */
    fun nudge() {
        wake.trySend(Unit)
    }

    // --- the API's side ---

    /** Reads an import with [reader] for [owner]; what's in it is kept for an hour as the answer's `readId`. */
    suspend fun read(owner: String, reader: String, input: InputStream): ConvertPreview {
        val read = withContext(Dispatchers.IO) {
            when (reader) {
                SpotifyExport.READER -> SpotifyExport.read(input)
                else -> throw ConvertException("Can't import from $reader")
            }
        }
        val id = newId()
        lock.withLock {
            val now = clock()
            reads.values.removeAll { now - it.at > READ_KEPT_MS }
            while (reads.size >= MAX_READS) reads.remove(reads.keys.first())
            reads[id] = Pending(owner, read, now)
        }
        return ConvertPreview(id, read.reader, read.lists.mapIndexed { i, l -> ConvertListPreview(i, l.name, l.kind, l.items.size) }, read.skipped)
    }

    /** Starts an import of the lists [request] picks from a read; songs found before are matched at once. */
    suspend fun start(owner: String, request: ConvertStartRequest): ConvertJob = lock.withLock {
        val pending = reads[request.readId]?.takeIf { it.owner == owner }
            ?: throw ConvertException("That export isn't here any more: pick the file again")
        if (request.lists.isEmpty()) throw ConvertException("Pick at least one list")
        val entries = LinkedHashMap<String, StoredEntry>()
        val lists = mutableListOf<StoredList>()
        for (choice in request.lists.distinctBy { it.index }) {
            val list = pending.read.lists.getOrNull(choice.index) ?: throw ConvertException("There's no list ${choice.index}")
            val target = checked(list, choice.target)
            // A YouTube Music playlist holds 5,000 songs: a longer list goes in parts.
            val parts = if (target.kind.isPlaylist()) list.items.chunked(PLAYLIST_MAX).ifEmpty { listOf(emptyList()) } else listOf(list.items)
            parts.forEachIndexed { part, items ->
                val name = if (part == 0) list.name else "${list.name} (${part + 1})"
                val partTarget = if (part == 0 || target.kind != ConvertTargetKind.NEW_PLAYLIST) target else target.copy(title = "${target.title} (${part + 1})")
                val keys = items.map { item ->
                    val key = keyOf(item)
                    entries.getOrPut(key) { StoredEntry(item) }
                    key
                }
                lists += StoredList(name, list.kind, partTarget, keys)
            }
        }
        for ((key, entry) in entries) remembered(key, entry.item)?.let { entry.apply(it) }
        val names = lists.map { it.name }.distinct()
        val title = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> "${names[0]} and ${names.size - 1} more"
        }
        val job = StoredJob(newId(), owner, title, pending.read.reader, clock(), request.settings, lists, entries)
        if (request.settings.addUnsure) job.entries.values.forEach { if (it.state == ConvertItemState.UNSURE) it.state = ConvertItemState.MATCHED }
        jobs[job.id] = job
        reads.remove(request.readId)
        save(job)
        wake.trySend(Unit)
        status(job)
    }

    suspend fun jobs(owner: String): List<ConvertJob> = lock.withLock { jobs.values.filter { it.owner == owner }.sortedByDescending { it.createdAt }.map(::status) }

    suspend fun job(owner: String, id: String): ConvertJob = lock.withLock { status(owned(owner, id)) }

    /** The songs of an import, each once, in the order of its lists; [state]: only those (null: all). */
    suspend fun items(owner: String, id: String, state: ConvertItemState?, cursor: String?, limit: Int = PAGE): ConvertItemPage = lock.withLock {
        val job = owned(owner, id)
        val all = job.entries.entries.filter { state == null || it.value.state == state }
        val from = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = all.drop(from).take(limit)
        val listsOf = HashMap<String, MutableList<String>>()
        for (l in job.lists) for (k in l.keys.toSet()) listsOf.getOrPut(k) { mutableListOf() } += l.name
        ConvertItemPage(page.map { (key, e) -> row(key, e, listsOf[key].orEmpty()) }, (from + limit).takeIf { it < all.size }?.toString())
    }

    /** [pick] for the song [key] of an import: that song, or left out. Remembered for later imports. */
    suspend fun pick(owner: String, id: String, key: String, pick: ConvertPick): ConvertItemRow = lock.withLock {
        val job = owned(owner, id)
        val entry = job.entries[key] ?: throw ConvertException("That song isn't in this import", notFound = true)
        val song = pick.song
        when {
            pick.back -> {
                if (entry.state != ConvertItemState.LEFT_OUT) throw ConvertException("That song isn't left out")
                putBack(entry)
                if (entry.writable()) rewant(job, entry.song!!.id)
                forget(key, entry.item)
                save(job)
                wake.trySend(Unit)
                return@withLock row(key, entry, job.lists.filter { key in it.keys }.map { it.name })
            }
            pick.leftOut -> {
                if (entry.state != ConvertItemState.LEFT_OUT) entry.leftFrom = entry.state
                entry.song?.let { unwrite(job, key, it.id) }
                // What it was matched to stays one of the options, to pick after all.
                entry.candidates = (listOfNotNull(entry.song) + entry.candidates).distinctBy { it.id }.take(CANDIDATES)
                entry.song = null
                entry.state = ConvertItemState.LEFT_OUT
            }
            song != null -> {
                if (!song.id.startsWith("ytm:")) throw ConvertException("Only YouTube Music songs can go there")
                // Another song instead of one already put there: that one comes out again.
                entry.song?.takeIf { it.id != song.id && entry.state == ConvertItemState.MATCHED }?.let { unwrite(job, key, it.id) }
                rewant(job, song.id)
                entry.candidates = (listOfNotNull(entry.song) + entry.candidates).filter { it.id != song.id }.take(CANDIDATES)
                entry.song = song
                entry.state = ConvertItemState.MATCHED
            }
            else -> throw ConvertException("Pick a song, or leave it out")
        }
        entry.manual = true
        remember(key, entry.item, Remembered(entry.song, manual = true))
        save(job)
        wake.trySend(Unit)
        row(key, entry, job.lists.filter { key in it.keys }.map { it.name })
    }

    /**
     * A song you left out, as it was before: found, not sure (with what it was matched to, the
     * first of the options now) or not found. Left out before that was kept: by how sure YTMP was.
     */
    private fun putBack(entry: StoredEntry) {
        // Left out before its match moved to the options.
        entry.song?.let { old -> entry.candidates = (listOf(old) + entry.candidates).distinctBy { it.id } }
        val score = entry.score
        val was = entry.leftFrom ?: when {
            entry.candidates.isEmpty() || score == null -> ConvertItemState.NOT_FOUND
            score >= ConvertMatcher.MATCH -> ConvertItemState.MATCHED
            score >= ConvertMatcher.UNSURE -> ConvertItemState.UNSURE
            else -> ConvertItemState.NOT_FOUND
        }
        val matched = was == ConvertItemState.MATCHED || was == ConvertItemState.UNSURE
        entry.song = if (matched) entry.candidates.firstOrNull() else null
        if (matched) entry.candidates = entry.candidates.drop(1)
        entry.state = if (matched && entry.song == null) ConvertItemState.NOT_FOUND else was
        entry.leftFrom = null
        entry.manual = false
    }

    /**
     * The rows of [key] are no longer [songId] (left out, or another song picked): not written
     * any more, and the song comes out of the target again when nothing else in the list is it
     * and this import put it there (a song that was in your playlist before stays).
     */
    private fun unwrite(job: StoredJob, key: String, songId: String) {
        for (list in job.lists) {
            val rows = list.keys.indices.filter { list.keys[it] == key }.toSet()
            if (rows.none { it in list.written }) continue
            list.written -= rows
            list.late -= rows
            val stillThere = list.written.any { i -> job.entries[list.keys[i]]?.song?.id == songId }
            if (stillThere) continue
            list.songIds -= songId
            val ours = list.target.kind == ConvertTargetKind.NEW_PLAYLIST || songId in list.added
            if (ours) list.toRemove += songId
            list.added -= songId
        }
    }

    /** [songId] is wanted again: if it was still to come out of a target, it stays there instead. */
    private fun rewant(job: StoredJob, songId: String) {
        for (list in job.lists) {
            if (list.toRemove.remove(songId)) {
                list.songIds += songId
                list.added += songId
            }
        }
    }

    /** Forgets that [item] was left out by hand, so the next import looks it up again. */
    private fun forget(key: String, item: ConvertItem) {
        for (k in setOf(key, textKey(item))) if (memory[k]?.let { it.manual && it.song == null } == true) memory.remove(k)
        memoryDirty = true
    }

    /** Accepts every song it wasn't sure about as it is. */
    suspend fun acceptUnsure(owner: String, id: String): ConvertJob = lock.withLock {
        val job = owned(owner, id)
        for ((key, e) in job.entries) {
            if (e.state == ConvertItemState.UNSURE && e.song != null) {
                e.state = ConvertItemState.MATCHED
                rewant(job, e.song!!.id)
                remember(key, e.item, Remembered(e.song, manual = true))
            }
        }
        save(job)
        wake.trySend(Unit)
        status(job)
    }

    suspend fun pause(owner: String, id: String, paused: Boolean): ConvertJob = lock.withLock {
        val job = owned(owner, id)
        if (job.cancelled) throw ConvertException("This import was cancelled")
        job.paused = paused
        save(job)
        wake.trySend(Unit)
        status(job)
    }

    /** Stops an import for good; the playlists made so far stay. */
    suspend fun cancel(owner: String, id: String): ConvertJob = lock.withLock {
        val job = owned(owner, id)
        job.cancelled = true
        save(job)
        status(job)
    }

    /** Takes an import off the list (cancelled first, if it still goes); the playlists stay. */
    suspend fun remove(owner: String, id: String) = lock.withLock {
        val job = owned(owner, id)
        jobs.remove(job.id)
        dirty.remove(job.id)
        withContext(Dispatchers.IO) { fileOf(job.id).delete() }
    }

    private fun owned(owner: String, id: String): StoredJob = jobs[id]?.takeIf { it.owner == owner } ?: throw ConvertException("There's no such import", notFound = true)

    private fun checked(list: ConvertList, target: ConvertTarget): ConvertTarget {
        val shows = list.kind == ConvertListKind.FOLLOWED_SHOWS
        if (shows != (target.kind == ConvertTargetKind.FOLLOW)) {
            throw ConvertException(if (shows) "Podcasts are followed, not put in a playlist" else "Only podcasts can be followed")
        }
        return when (target.kind) {
            ConvertTargetKind.FOLLOW, ConvertTargetKind.LIKES -> target
            ConvertTargetKind.NEW_PLAYLIST -> {
                val title = (target.title ?: list.name).trim()
                if (title.isEmpty() || title.length > 150) throw ConvertException("A playlist name is 1-150 characters")
                target.copy(title = title)
            }
            ConvertTargetKind.EXISTING_PLAYLIST -> if (target.playlistId.isNullOrBlank()) throw ConvertException("Pick the playlist to add to") else target
        }
    }

    // --- the match memory ---

    /** What [item] was matched to before (by its source ID, else its title, artist and album), if anything. */
    private fun remembered(key: String, item: ConvertItem): Remembered? = memory[key] ?: memory[textKey(item)]

    private fun remember(key: String, item: ConvertItem, what: Remembered) {
        // An automatic match never replaces a pick made by hand.
        for (k in setOf(key, textKey(item))) if (what.manual || memory[k]?.manual != true) memory[k] = what
        memoryDirty = true
    }

    private fun StoredEntry.apply(r: Remembered) {
        song = r.song
        manual = r.manual
        state = if (r.song != null) ConvertItemState.MATCHED else ConvertItemState.LEFT_OUT
        score = null
    }

    // --- the background work ---

    private sealed interface Work {
        data class Match(val job: StoredJob, val key: String, val item: ConvertItem, val videos: Boolean) : Work
        data class Write(val job: StoredJob, val list: StoredList) : Work
    }

    private suspend fun run() {
        while (true) {
            val wait = until?.let { it - clock() }
            if (wait != null && wait > 0) {
                withTimeoutOrNull(wait) { wake.receive() }
                continue
            }
            if (until != null) {
                until = null
                waiting = null
            }
            val work = lock.withLock { next() }
            try {
                when (work) {
                    null -> {
                        lock.withLock { flush(force = true) }
                        withTimeoutOrNull(IDLE_MS) { wake.receive() }
                    }
                    is Work.Match -> {
                        wrote = false
                        match(work)
                        delay(gapMs)
                    }
                    is Work.Write -> {
                        wrote = true
                        write(work.job, work.list)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val blocked = e is SourceBlocked || e.message.orEmpty().let { m -> listOf("bot", "confirm", "429", "Too Many").any { it in m } }
                log.info("Importing playlists stops a while: {}", e.message)
                waiting = if (blocked) "YouTube asked to confirm this isn't a bot" else "YouTube Music didn't answer: ${e.message}"
                until = clock() + if (blocked) blockedPauseMs else failedPauseMs
            }
        }
    }

    /** The next thing to do: a list ready to write (not twice in a row while songs wait), else the next song to look up. */
    private fun next(): Work? {
        val now = clock()
        val active = jobs.values.filter { !it.paused && !it.cancelled }.sortedBy { it.createdAt }
        val write = active.firstNotNullOfOrNull { job -> job.lists.firstOrNull { (it.retryAt ?: 0) <= now && needsWrite(job, it) }?.let { Work.Write(job, it) } }
        val match = active.firstNotNullOfOrNull { job ->
            job.lists.firstNotNullOfOrNull { l -> l.keys.firstOrNull { job.entries[it]?.state == ConvertItemState.PENDING } }
                ?.let { Work.Match(job, it, job.entries.getValue(it).item, job.settings.videos) }
        }
        return if (wrote && match != null) match else write ?: match
    }

    private suspend fun match(work: Work.Match) {
        val found = matcher.match(work.item, work.videos)
        lock.withLock {
            val entry = work.job.entries[work.key] ?: return
            if (entry.state != ConvertItemState.PENDING) return
            entry.song = found.song
            entry.candidates = found.candidates
            entry.score = found.score
            entry.state = if (found.state == ConvertItemState.UNSURE && work.job.settings.addUnsure) ConvertItemState.MATCHED else found.state
            if (found.state == ConvertItemState.MATCHED) remember(work.key, entry.item, Remembered(found.song, manual = false))
            // The same song in another import waiting: matched at once.
            for (other in jobs.values) {
                if (other === work.job) continue
                val same = other.entries[work.key]?.takeIf { it.state == ConvertItemState.PENDING } ?: continue
                same.song = entry.song
                same.candidates = entry.candidates
                same.score = entry.score
                same.state = if (found.state == ConvertItemState.UNSURE && other.settings.addUnsure) ConvertItemState.MATCHED else found.state
                dirty += other.id
            }
            dirty += work.job.id
            flush(force = false)
        }
    }

    /** Whether [list] is looked up whole and has something to put in its target (or its new playlist isn't made yet). */
    private fun needsWrite(job: StoredJob, list: StoredList): Boolean {
        if (list.toRemove.isNotEmpty()) return true
        if (list.keys.any { job.entries[it]?.state == ConvertItemState.PENDING }) return false
        if (list.target.kind == ConvertTargetKind.NEW_PLAYLIST && list.playlistId == null) return true
        if (list.late.isNotEmpty()) return true
        return list.keys.indices.any { it !in list.written && job.entries[list.keys[it]]?.writable() == true }
    }

    /** One step of writing [list]: a part of the songs (a few likes or follows), or putting late songs in their place. */
    private suspend fun write(job: StoredJob, list: StoredList) {
        val catalog = personalOf(job.owner)?.takeIf { it.account() != null }
        if (catalog == null) {
            lock.withLock {
                list.error = "Sign in to YouTube Music to put the songs there"
                list.retryAt = clock() + RETRY_WRITE_MS
                save(job)
            }
            return
        }
        try {
            if (lock.withLock { list.toRemove.isNotEmpty() }) {
                takeOut(job, list, catalog)
            } else when (list.target.kind) {
                ConvertTargetKind.NEW_PLAYLIST, ConvertTargetKind.EXISTING_PLAYLIST -> writePlaylist(job, list, catalog)
                ConvertTargetKind.LIKES -> writeEach(job, list, PER_STEP_LIKES) { catalog.setRating(it.id, LikeStatus(liked = true)) }
                ConvertTargetKind.FOLLOW -> writeEach(job, list, PER_STEP_LIKES) { catalog.setPodcastSaved(it.id, true) }
            }
            lock.withLock {
                list.error = null
                save(job)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is SourceBlocked) throw e
            log.info("Writing {} of import {}: {}", list.name, job.id, e.message)
            lock.withLock {
                list.error = e.message ?: "Couldn't put the songs there"
                list.retryAt = clock() + RETRY_WRITE_MS
                save(job)
            }
        }
    }

    /** Takes the songs left out (or replaced) out of [list]'s target again: from the playlist at once, likes and follows a few at a time. */
    private suspend fun takeOut(job: StoredJob, list: StoredList, catalog: PersonalCatalog) {
        val gone = lock.withLock { list.toRemove.toList() }
        when (list.target.kind) {
            ConvertTargetKind.NEW_PLAYLIST, ConvertTargetKind.EXISTING_PLAYLIST -> {
                val id = list.playlistId
                if (id != null) {
                    val refs = allSongs(catalog, id).filter { it.first in gone && it.second.isNotEmpty() }.distinctBy { it.first }
                    if (refs.isNotEmpty()) catalog.removeFromPlaylist(id, refs.map { PlaylistItemRef(it.first, it.second) })
                }
                lock.withLock {
                    list.toRemove -= gone.toSet()
                    save(job)
                }
                delay(writeGapMs)
            }
            ConvertTargetKind.LIKES, ConvertTargetKind.FOLLOW -> for (songId in gone.take(PER_STEP_LIKES)) {
                if (list.target.kind == ConvertTargetKind.LIKES) catalog.setRating(songId, LikeStatus(liked = false)) else catalog.setPodcastSaved(songId, false)
                lock.withLock {
                    list.toRemove -= songId
                    save(job)
                }
                delay(likeGapMs)
            }
        }
    }

    /** The songs of [list] to write now, in its order: positions and songs, each song once in a list. */
    private suspend fun todo(job: StoredJob, list: StoredList): List<Pair<Int, Song>> = lock.withLock {
        val out = mutableListOf<Pair<Int, Song>>()
        val ids = HashSet(list.songIds)
        for ((i, key) in list.keys.withIndex()) {
            if (i in list.written) continue
            val e = job.entries[key]?.takeIf { it.writable() } ?: continue
            val song = e.song!!
            if (!ids.add(song.id)) {
                // Already in it (twice in the list, or two items that are one song): nothing to add.
                list.written += i
                continue
            }
            out += i to song
        }
        out
    }

    private suspend fun writePlaylist(job: StoredJob, list: StoredList, catalog: PersonalCatalog) {
        if (list.target.kind == ConvertTargetKind.EXISTING_PLAYLIST && !list.started) {
            val there = allSongs(catalog, list.target.playlistId!!).map { it.first }
            lock.withLock {
                list.playlistId = list.target.playlistId
                list.songIds += there
                list.started = true
                save(job)
            }
        }
        val todo = todo(job, list)
        val part = todo.take(PART)
        val after = lock.withLock { list.addedUpTo }
        if (list.playlistId == null) {
            val id = catalog.createPlaylist(list.target.title ?: list.name, part.map { it.second.id })
            lock.withLock {
                list.playlistId = id
                list.started = true
                written(list, part)
                save(job)
            }
        } else if (part.isNotEmpty()) {
            try {
                catalog.addToPlaylist(list.playlistId!!, part.map { it.second.id })
                lock.withLock { written(list, part) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SourceBlocked) throw e
                // One of them is in it already (YouTube refuses the whole part then): one by one.
                for (one in part) {
                    runCatching { catalog.addToPlaylist(list.playlistId!!, listOf(one.second.id)) }
                        .onFailure { if (it is SourceBlocked) throw it }
                    lock.withLock { written(list, listOf(one)) }
                    delay(writeGapMs)
                }
            }
            lock.withLock {
                // Songs that belong before ones already there (picked later) are put in their place next.
                list.late += part.filter { it.first < after }.map { it.first }
                save(job)
            }
        }
        if (todo.size <= PART && lock.withLock { list.late.isNotEmpty() }) place(job, list, catalog)
        delay(writeGapMs)
    }

    private fun written(list: StoredList, part: List<Pair<Int, Song>>) {
        for ((i, song) in part) {
            list.written += i
            list.songIds += song.id
            list.addedUpTo = maxOf(list.addedUpTo, i)
            list.added += song.id
        }
    }

    /** Puts the songs that were added late where they belong in the list's order (a move each). */
    private suspend fun place(job: StoredJob, list: StoredList, catalog: PersonalCatalog) {
        val id = list.playlistId ?: return
        val there = allSongs(catalog, id)
        val itemOf = HashMap<String, String>()
        for ((song, item) in there) if (item.isNotEmpty()) itemOf.putIfAbsent(song, item)
        val (order, late) = lock.withLock {
            val order = list.keys.indices.filter { it in list.written }.mapNotNull { i -> job.entries[list.keys[i]]?.song?.id?.let { i to it } }.distinctBy { it.second }
            order to list.late.toSet()
        }
        for ((n, entry) in order.withIndex()) {
            val (i, song) = entry
            if (i !in late) continue
            val item = itemOf[song] ?: continue
            // Before the next song that was already in its place.
            val before = order.drop(n + 1).firstOrNull { it.first !in late }?.second?.let { itemOf[it] }
            catalog.movePlaylistItem(id, item, before)
            delay(writeGapMs)
        }
        lock.withLock {
            list.late.clear()
            save(job)
        }
    }

    /** Every song of one of your playlists with its place (`itemId`), all its parts. */
    private suspend fun allSongs(catalog: PersonalCatalog, id: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var page = catalog.playlist(id)
        while (true) {
            page.songs.forEachIndexed { i, s -> out += s.id to page.itemIds.getOrElse(i) { "" } }
            val more = page.more ?: break
            page = catalog.playlist(id, more)
        }
        return out
    }

    /** A few of [list]'s songs (likes, follows) one at a time, slowly. */
    private suspend fun writeEach(job: StoredJob, list: StoredList, count: Int, put: suspend (Song) -> Unit) {
        for (one in todo(job, list).take(count)) {
            put(one.second)
            lock.withLock {
                written(list, listOf(one))
                save(job)
            }
            delay(likeGapMs)
        }
    }

    // --- status ---

    private fun status(job: StoredJob): ConvertJob {
        val counts = counts(job)
        val now = clock()
        val writing = job.lists.any { needsWrite(job, it) }
        val status = when {
            job.cancelled -> ConvertJobStatus.CANCELLED
            job.paused -> ConvertJobStatus.PAUSED
            counts.pending > 0 || writing -> ConvertJobStatus.MATCHING
            counts.unsure > 0 -> ConvertJobStatus.NEEDS_YOU
            else -> ConvertJobStatus.DONE
        }
        val held = status == ConvertJobStatus.MATCHING && until?.let { it > now } == true
        val listError = job.lists.firstNotNullOfOrNull { it.error }
        return ConvertJob(
            job.id, job.title, job.reader, job.createdAt, status,
            waiting = if (held) waiting else listError.takeIf { status == ConvertJobStatus.MATCHING },
            waitingUntil = if (held) until else null,
            leftMs = if (status == ConvertJobStatus.MATCHING) counts.pending * (gapMs + SEARCH_MS) else null,
            counts = counts,
            lists = job.lists.map { l ->
                ConvertListStatus(l.name, l.kind, l.target, l.playlistId, l.keys.size, l.written.size, l.error)
            },
            settings = job.settings,
        )
    }

    private fun counts(job: StoredJob): ConvertCounts {
        val by = job.entries.values.groupingBy { it.state }.eachCount()
        return ConvertCounts(
            job.entries.size, by[ConvertItemState.PENDING] ?: 0, by[ConvertItemState.MATCHED] ?: 0, by[ConvertItemState.UNSURE] ?: 0,
            by[ConvertItemState.NOT_FOUND] ?: 0, by[ConvertItemState.LEFT_OUT] ?: 0,
        )
    }

    private fun row(key: String, e: StoredEntry, lists: List<String>) =
        ConvertItemRow(key, e.item, e.state, e.song, e.candidates, e.score, e.manual, lists)

    // --- keeping it ---

    private fun fileOf(id: String) = File(dir, "job-$id.json")

    /** Keeps [job] now (a pick, a write: things that shouldn't be done twice). */
    private suspend fun save(job: StoredJob) {
        dirty += job.id
        flush(force = true)
    }

    /** Keeps what changed: now when [force], else at most every [SAVE_EVERY_MS] (each song looked up changes a job). */
    private suspend fun flush(force: Boolean) {
        if (!force && clock() - savedAt < SAVE_EVERY_MS) return
        savedAt = clock()
        val toSave = dirty.mapNotNull { jobs[it] }.map { it.id to json.encodeToString(StoredJob.serializer(), it) }
        dirty.clear()
        val mem = if (memoryDirty) json.encodeToString(memorySerializer, memory) else null
        memoryDirty = false
        withContext(Dispatchers.IO) {
            for ((id, text) in toSave) writeAtomically(fileOf(id), text)
            if (mem != null) writeAtomically(File(dir, MEMORY_FILE), mem)
        }
    }

    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.path + ".part")
        temp.writeText(text)
        if (!temp.renameTo(file)) log.warn("Couldn't keep {}", file.name)
    }

    @Serializable
    private data class Remembered(val song: Song?, val manual: Boolean)

    private data class Pending(val owner: String, val read: ConvertRead, val at: Long)

    @Serializable
    private class StoredEntry(
        val item: ConvertItem,
        var state: ConvertItemState = ConvertItemState.PENDING,
        var song: Song? = null,
        var candidates: List<Song> = emptyList(),
        var score: Double? = null,
        var manual: Boolean = false,
        /** What it was before you left it out, to put it back as that. */
        var leftFrom: ConvertItemState? = null,
    ) {
        fun writable() = state == ConvertItemState.MATCHED && song != null
    }

    @Serializable
    private class StoredList(
        val name: String,
        val kind: ConvertListKind,
        val target: ConvertTarget,
        /** Each row's song, by its key in [StoredJob.entries]. */
        val keys: List<String>,
        var playlistId: String? = null,
        /** The new playlist is made, or the existing one read. */
        var started: Boolean = false,
        /** Rows put in the target (or that needn't be: the song was in it already). */
        val written: MutableSet<Int> = mutableSetOf(),
        /** The songs in the target, so none goes in twice. */
        val songIds: MutableSet<String> = mutableSetOf(),
        /** The last row added by the import (songs that were there already don't count). */
        var addedUpTo: Int = -1,
        /** The songs this import put in the target (not the ones that were there already). */
        val added: MutableSet<String> = mutableSetOf(),
        /** Songs to take out of the target again (left out, or replaced by another pick). */
        val toRemove: MutableSet<String> = mutableSetOf(),
        /** Rows added after later ones, still to put in their place. */
        val late: MutableSet<Int> = mutableSetOf(),
        var error: String? = null,
        var retryAt: Long? = null,
    )

    @Serializable
    private class StoredJob(
        val id: String,
        val owner: String,
        val title: String,
        val reader: String,
        val createdAt: Long,
        val settings: ConvertSettings,
        val lists: List<StoredList>,
        val entries: LinkedHashMap<String, StoredEntry>,
        var paused: Boolean = false,
        var cancelled: Boolean = false,
    )

    companion object {
        /** Between two searches: YouTube blocks a network that searches a lot (LocalMatcher's pace). */
        const val GAP_MS = 4_000L
        /** About how long a search takes, for the time left. */
        private const val SEARCH_MS = 1_500L
        const val WRITE_GAP_MS = 2_000L
        /** Between two likes or follows: thousands at once look like spam to YouTube. */
        const val LIKE_GAP_MS = 15_000L
        const val BLOCKED_PAUSE_MS = 30 * 60_000L
        const val FAILED_PAUSE_MS = 5 * 60_000L
        private const val RETRY_WRITE_MS = 10 * 60_000L
        private const val IDLE_MS = 10 * 60_000L
        private const val SAVE_EVERY_MS = 30_000L
        private const val READ_KEPT_MS = 3_600_000L
        private const val MAX_READS = 20
        /** Songs added to a playlist at once. */
        const val PART = 50
        private const val PER_STEP_LIKES = 4
        /** A YouTube Music playlist holds this many. */
        const val PLAYLIST_MAX = 5_000
        private const val CANDIDATES = 5
        private const val PAGE = 50
        private const val MEMORY_FILE = "matches.json"

        private val json = Json { ignoreUnknownKeys = true }
        private val memorySerializer = MapSerializer(String.serializer(), Remembered.serializer())

        private val random = java.security.SecureRandom()

        private fun newId(): String = buildString { repeat(12) { append("abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)]) } }

        private fun ConvertTargetKind.isPlaylist() = this == ConvertTargetKind.NEW_PLAYLIST || this == ConvertTargetKind.EXISTING_PLAYLIST

        /** An item's key in a job: its source ID, else its type, title, artist and album. */
        fun keyOf(item: ConvertItem): String = item.sourceId?.takeIf { it.isNotBlank() } ?: textKey(item)

        private fun textKey(item: ConvertItem): String = listOf(
            item.type.name.lowercase(),
            SongMatch.normalize(ConvertMatcher.cleanTitle(item.title)),
            SongMatch.normalize(item.artists.firstOrNull().orEmpty()),
            SongMatch.normalize(item.album ?: item.show.orEmpty()),
        ).joinToString("|")
    }
}
