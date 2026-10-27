package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.GraphRank
import dev.trashpanda.ytmp.core.SongMatch
import dev.trashpanda.ytmp.protocol.EdgeKind
import dev.trashpanda.ytmp.protocol.GraphEdge
import dev.trashpanda.ytmp.protocol.GraphFull
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.trashpanda.ytmp.protocol.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File

/** The phone's copy of the song graph in the settings (`GET`/`PUT /api/graph-copy`). */
@Serializable
data class GraphCopyStatus(
    val enabled: Boolean,
    /** When it was made (0: not yet). */
    val madeAt: Long = 0,
    val songs: Int = 0,
    val links: Int = 0,
    /** Its size on the phone. */
    val bytes: Long = 0,
)

/**
 * A phone's copy of the whole song graph as its owner sees it, from their server ([fetch]),
 * kept in [file]: radios use it when the server can't be reached. The phone's own songs that
 * play offline ([songs]) are matched to it by their ID, or else by title, artist and length,
 * so a music file never played still finds its song. Ranked like the server ([GraphRank]).
 * Refreshed once a day; [enabled] off deletes it. See docs/features/history-and-recommendations.md.
 */
class GraphCopyStore(
    private val file: File,
    private val scope: CoroutineScope,
    /** The songs that play here without internet. */
    private val songs: suspend () -> List<Song>,
    /** The graph from the server; null when it can't be reached (or no account is linked). */
    private val fetch: suspend () -> GraphFull?,
    /** Whether to keep it, as the owner set it, and saving that. */
    private val loadEnabled: () -> Boolean = { true },
    private val saveEnabled: (Boolean) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(GraphCopyStore::class.java)

    /** The copy, ready to rank: links by song (both ways round), songs by version and by key. */
    private class Index(val graph: GraphFull, val madeAt: Long) {
        val edges: List<GraphEdge> = graph.edges.mapNotNull { r ->
            if (r.size < 11) return@mapNotNull null
            GraphEdge(
                r[0].toString(), r[1].toString(), EdgeKind.entries.getOrNull(r[2].toInt()) ?: return@mapNotNull null,
                r[3].toInt(), r[4].toInt(), r[5].toInt(), r[6].toInt(), r[7].toInt().takeIf { it >= 0 }, r[8].toInt(), r[9], r[10] == 1L,
            )
        }
        val around: Map<String, List<GraphEdge>> = HashMap<String, MutableList<GraphEdge>>().also { m ->
            for (e in edges) {
                m.getOrPut(e.from) { mutableListOf() } += e
                if (e.to != e.from) m.getOrPut(e.to) { mutableListOf() } += e
            }
        }
        val bySource: Map<String, String> = graph.nodes.withIndex().flatMap { (i, n) -> n.sources.map { it to i.toString() } }.toMap()
        val byKey: Map<String, List<Int>> = graph.nodes.withIndex().groupBy({ it.value.key }, { it.index })

        /** The song (node) [song] is: one of its versions, or the same title, artist and length. */
        fun nodeOf(song: Song): String? = bySource[song.id]
            ?: byKey[SongMatch.key(song)]?.firstOrNull { SongMatch.sameLength(graph.nodes[it].durationMs, song.durationMs) }?.toString()
    }

    @Volatile private var index: Index? = null
    @Volatile private var loaded = false
    @Volatile var enabled: Boolean = loadEnabled()
        private set
    private var job: Job? = null

    /** The phone's songs by node, made again when they change. */
    @Volatile private var mine: Pair<Int, Map<String, List<Song>>> = 0 to emptyMap()

    private fun index(): Index? {
        if (!loaded) {
            loaded = true
            index = runCatching {
                val saved = ProtocolJson.decodeFromString(Saved.serializer(), file.readText())
                Index(saved.graph, saved.madeAt)
            }.getOrNull()
        }
        return index
    }

    @Serializable
    private class Saved(val madeAt: Long, val graph: GraphFull)

    fun status(): GraphCopyStatus {
        val i = if (enabled) index() else null
        return GraphCopyStatus(enabled, i?.madeAt ?: 0, i?.graph?.nodes?.size ?: 0, i?.graph?.edges?.size ?: 0, if (file.isFile) file.length() else 0)
    }

    /** Keep a copy, or not (off: the copy is deleted). */
    fun setEnabled(on: Boolean) {
        enabled = on
        saveEnabled(on)
        if (!on) {
            file.delete()
            index = null
            loaded = true
        } else {
            loaded = false
            scope.launch { runCatching { refresh() } }
        }
    }

    /**
     * Songs on the phone that go with [seed] (a song on the phone, or its ID), best first, from
     * the copy. Empty when it's off, not made yet, or knows nothing about it.
     */
    suspend fun similar(seedSongId: String, limit: Int = 50): List<Song> {
        if (!enabled) return emptyList()
        val i = index() ?: return emptyList()
        val here = songs()
        if (here.size != mine.first || mine.second.isEmpty()) {
            mine = here.size to here.groupBy { i.nodeOf(it) ?: "" }.filterKeys { it.isNotEmpty() }
        }
        val seedSong = here.firstOrNull { it.id == seedSongId }
        val seed = seedSong?.let(i::nodeOf) ?: i.bySource[seedSongId] ?: return emptyList()
        val ranked = GraphRank.rank(seed, { nodes -> nodes.flatMap { i.around[it].orEmpty() } }, i.graph.use, i.graph.tuning, clock())
        return ranked.asSequence().mapNotNull { mine.second[it]?.firstOrNull() }.filter { it.id != seedSongId }.take(limit).toList()
    }

    /** Refreshes the copy once a day from now on (and now, when it's older). */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                if (enabled && clock() - (index()?.madeAt ?: 0) > REFRESH_MS) {
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

    /** Makes the copy again. False when it's off or the server couldn't be asked (the old copy stays). */
    suspend fun refresh(): Boolean {
        if (!enabled) return false
        val graph = fetch() ?: return false
        val saved = Saved(clock(), graph)
        val temp = File(file.path + ".part")
        temp.writeText(ProtocolJson.encodeToString(Saved.serializer(), saved))
        if (!enabled || !temp.renameTo(file)) {
            temp.delete()
            return false
        }
        index = Index(graph, saved.madeAt)
        loaded = true
        mine = 0 to emptyMap()
        log.info("Copy of the song graph: {} songs, {} links, {} KB", graph.nodes.size, graph.edges.size, file.length() / 1024)
        return true
    }

    private companion object {
        const val REFRESH_MS = 24 * 3_600_000L
        const val RECHECK_MS = 3_600_000L
    }
}

/** The phone's copy of the song graph in its settings, at [path]: `GET`, `PUT` (on or off), `POST …/refresh`. For whoever [allowed] says. */
fun Route.graphCopyRoutes(path: String, store: GraphCopyStore?, allowed: suspend (ApplicationCall) -> Boolean) =
    route(path) {
        suspend fun ApplicationCall.store(): GraphCopyStore {
            if (!allowed(this)) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only the owner can change this")
            return store ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Not on this host")
        }
        get { call.respond(call.store().status()) }
        put {
            val store = call.store()
            store.setEnabled(call.receive<GraphCopyStatus>().enabled)
            call.respond(store.status())
        }
        post("/refresh") {
            val store = call.store()
            store.refresh()
            call.respond(store.status())
        }
    }
