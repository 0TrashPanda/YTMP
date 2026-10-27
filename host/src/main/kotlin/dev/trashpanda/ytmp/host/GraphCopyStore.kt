package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.GraphCopy
import dev.trashpanda.ytmp.protocol.GraphCopyRequest
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * A phone's copy of the song graph for the songs it can play offline (its music, its saved
 * songs): each one's best neighbours among them, from the account's server ([fetch]), kept in
 * [file]. Refreshed once a day when the server can be reached; radios use it when it can't.
 * See docs/features/history-and-recommendations.md#song-graph.
 */
class GraphCopyStore(
    private val file: File,
    private val scope: CoroutineScope,
    /** The songs that play here without internet. */
    private val songs: suspend () -> List<Song>,
    /** A part of the copy from the server; null when it can't be reached (or no account is linked). */
    private val fetch: suspend (GraphCopyRequest) -> GraphCopy?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(GraphCopyStore::class.java)

    @Serializable
    private data class Saved(val at: Long = 0, val neighbours: Map<String, List<String>> = emptyMap(), val songs: Map<String, Song> = emptyMap())

    @Volatile private var copy: Saved? = null
    private var job: Job? = null

    private fun loaded(): Saved = copy ?: runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrDefault(Saved()).also { copy = it }

    /** When the copy was made (0: never). */
    val madeAt: Long get() = loaded().at

    /** How many songs have neighbours in the copy. */
    val size: Int get() = loaded().neighbours.size

    /** Songs that go with [seedSongId], best first, from the copy (empty: none known). */
    fun similar(seedSongId: String, limit: Int = 50): List<Song> {
        val c = loaded()
        return c.neighbours[seedSongId].orEmpty().mapNotNull(c.songs::get).take(limit)
    }

    /** Refreshes the copy once a day from now on (and now, when it's older). */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                if (clock() - madeAt > REFRESH_MS) {
                    try {
                        refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.info("Couldn't refresh the copy of the song graph: {}", e.message)
                    }
                }
                delay(RECHECK_MS)
            }
        }
    }

    /** Makes the copy again. False when the server couldn't be asked (the old copy stays). */
    suspend fun refresh(): Boolean {
        val here = songs().distinctBy { it.id }.filter { it.podcast == null }
        if (here.isEmpty()) return false
        val among = here.map { it.id }
        val neighbours = HashMap<String, List<String>>()
        for (seeds in among.chunked(CHUNK)) {
            val part = fetch(GraphCopyRequest(seeds, among, LIMIT)) ?: return false
            neighbours.putAll(part.neighbours)
        }
        val used = neighbours.values.flatten().toSet() + neighbours.keys
        val made = Saved(clock(), neighbours, here.filter { it.id in used }.associateBy { it.id })
        val temp = File(file.path + ".part")
        temp.writeText(json.encodeToString(Saved.serializer(), made))
        if (!temp.renameTo(file)) throw IllegalStateException("Couldn't save the copy of the song graph")
        copy = made
        log.info("Copy of the song graph: {} of {} songs have neighbours", neighbours.size, here.size)
        return true
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val REFRESH_MS = 24 * 3_600_000L
        const val RECHECK_MS = 3_600_000L
        const val CHUNK = 400
        const val LIMIT = 50
    }
}
