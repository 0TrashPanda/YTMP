package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.EdgeKind
import dev.trashpanda.ytmp.protocol.GraphEdge
import dev.trashpanda.ytmp.protocol.GraphTuning
import dev.trashpanda.ytmp.protocol.HistoryRadio
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How much a link of the song graph counts, and which songs go with a song: the same on the
 * server (its database) and on a phone (its copy of the graph). The graph view does the same
 * in frontend/src/lib/graph.ts. See docs/implementation/storage.md#song-graph.
 */
object GraphRank {
    /** A YouTube Music radio's first this many songs are kept. */
    const val MAX_RADIO_SONGS = 50

    /** In a radio, each song goes with this many after it. */
    const val NEAR_WINDOW = 3

    /** How far the walk goes, in steps. */
    private const val WALK_STEPS = 6

    /** After the seed's links, the links of this many of the best songs so far are looked at, round after round. */
    private val EXPAND = listOf(8, 24)

    /** Songs that count less than this share of the best one are left out. */
    private const val MIN_SHARE = 0.01

    /** What [e] counts, the way [t] says; [use]: what of your own listening counts. */
    fun weight(e: GraphEdge, use: HistoryRadio, t: GraphTuning, now: Long): Double {
        val ageDays = max(0L, now - e.updatedAt) / 86_400_000.0
        val decay = if (t.halfLifeDays > 0) 0.5.pow(ageDays / t.halfLifeDays) else 1.0
        val party = if (use == HistoryRadio.ALL) t.party else 0.0
        return when (e.kind) {
            EdgeKind.PERSONAL -> t.personal * (e.solo + e.shared * party - e.skips * t.skip) * decay
            EdgeKind.IMPORTED -> t.personal * t.imported * oneOff(e, t) * (e.solo - e.skips * t.skip) * decay
            EdgeKind.SERVER -> t.server * (e.solo + e.shared * t.party - e.skips * t.skip) * decay
            EdgeKind.SERVER_IMPORTED -> t.server * t.imported * oneOff(e, t) * (e.solo - e.skips * t.skip) * decay
            EdgeKind.PERSONAL_NEAR, EdgeKind.IMPORTED_NEAR -> {
                // 2 songs later counts fully, 3 later half.
                val distance = if (e.seen > 0) e.rankSum.toDouble() / e.seen else 2.0
                val heard = e.solo + e.shared * party
                t.personal * (if (e.kind == EdgeKind.IMPORTED_NEAR) t.imported else 1.0) * t.listenNear * (1 - (distance - 2) / 2) * heard * decay
            }
            EdgeKind.RADIO_NEAR -> {
                if ((e.bestRank ?: 0) >= t.radioDepth) return 0.0
                val distance = if (e.seen > 0) e.rankSum.toDouble() / e.seen else 1.0
                val often = min(2.0, sqrt(max(1, e.seen).toDouble()))
                t.radioNear * (1 - (distance - 1) / NEAR_WINDOW) * often * decay
            }
            EdgeKind.YTM, EdgeKind.YTM_ACCOUNT -> {
                if ((e.bestRank ?: 0) >= t.radioDepth) return 0.0
                val rank = if (e.seen > 0) e.rankSum.toDouble() / e.seen else (e.bestRank ?: MAX_RADIO_SONGS).toDouble()
                val often = min(2.0, sqrt(max(1, e.seen).toDouble()))
                (if (e.kind == EdgeKind.YTM) t.ytm else t.ytmAccount) * max(0.2, 1 - rank / MAX_RADIO_SONGS) * often * decay
            }
        }
    }

    /** A Takeout link that happened once and no radio confirms: [GraphTuning.oneOff] of it. */
    private fun oneOff(e: GraphEdge, t: GraphTuning) = if (e.solo <= 1 && !e.confirmed) t.oneOff else 1.0

    /**
     * The songs (nodes) that go with [seed], best first: a walk from it along the links. Each
     * step goes on to a song in proportion to how much that link counts against the song's other
     * links (a song played before counts [GraphTuning.reverse]; songs close together in a radio
     * either way round), and each step after the first counts [GraphTuning.secondHop] of the one
     * before. So a song reached along many ways counts more than one behind a single stray link,
     * and a song with many links doesn't drown out the rest. Songs in the seed's [group] then
     * count [GraphTuning.sameGroup] extra. [around] gives the links of some nodes, both ways round.
     */
    fun rank(
        seed: String,
        around: (nodes: List<String>) -> List<GraphEdge>,
        use: HistoryRadio,
        t: GraphTuning,
        now: Long,
        group: ((String) -> Int?)? = null,
    ): List<String> {
        // Where a step from a song can go, and how much each way counts (only songs looked at so far).
        val out = HashMap<String, HashMap<String, Double>>()
        val expanded = HashSet<String>()
        // Links already counted onwards from their first song, and back from their second (a link comes again with its other song).
        val onwards = HashSet<GraphEdge>()
        val backwards = HashSet<GraphEdge>()
        fun learn(nodes: List<String>) {
            val fresh = nodes.filter { expanded.add(it) }
            if (fresh.isEmpty()) return
            for (edge in around(fresh)) {
                if (edge.from == edge.to) continue
                val w = weight(edge, use, t, now)
                if (edge.from in expanded && onwards.add(edge)) out.getOrPut(edge.from) { HashMap() }.merge(edge.to, w, Double::plus)
                if (edge.to in expanded && backwards.add(edge)) {
                    val back = if (edge.kind == EdgeKind.RADIO_NEAR) 1.0 else t.reverse
                    out.getOrPut(edge.to) { HashMap() }.merge(edge.from, w * back, Double::plus)
                }
            }
        }
        // Each song's ways on, as shares (only the ones that count more than 0).
        fun shares(from: String): List<Pair<String, Double>> {
            val ways = out[from]?.filter { it.value > 0 } ?: return emptyList()
            val sum = ways.values.sum()
            return ways.map { (to, w) -> to to w / sum }
        }
        val onward = t.secondHop.coerceIn(0.0, 0.9)
        fun walk(): HashMap<String, Double> {
            val scores = HashMap<String, Double>()
            var at = mapOf(seed to 1.0)
            for (step in 1..WALK_STEPS) {
                val next = HashMap<String, Double>()
                for ((node, p) in at) for ((to, share) in shares(node)) if (to != seed) next.merge(to, p * share, Double::plus)
                for ((node, p) in next) scores.merge(node, p, Double::plus)
                at = next.mapValues { it.value * onward }
                if (onward == 0.0 || at.isEmpty()) break
            }
            return scores
        }
        // The seed's links, then those of the best songs found so far, a few more each time.
        learn(listOf(seed))
        var scores = walk()
        for (width in EXPAND) {
            if (onward == 0.0) break
            learn(scores.entries.sortedByDescending { it.value }.map { it.key }.filter { it !in expanded }.take(width))
            scores = walk()
        }
        val home = group?.invoke(seed)
        if (home != null && t.sameGroup != 0.0) for (key in scores.keys) if (group(key) == home) scores[key] = scores.getValue(key) * (1 + t.sameGroup)
        val best = scores.values.maxOrNull() ?: return emptyList()
        return scores.entries.filter { it.value >= best * MIN_SHARE }.sortedByDescending { it.value }.map { it.key }
    }
}
