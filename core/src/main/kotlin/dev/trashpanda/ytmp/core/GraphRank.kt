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

    private const val SECOND_HOP_FROM = 8
    private const val MIN_SCORE = 0.05

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
     * The songs (nodes) that go with [seed], best first: its neighbours, and theirs (weaker).
     * [around] gives the links of some nodes, both ways round.
     */
    fun rank(seed: String, around: (nodes: List<String>) -> List<GraphEdge>, use: HistoryRadio, t: GraphTuning, now: Long): List<String> {
        val scores = HashMap<String, Double>()
        for (edge in around(listOf(seed))) {
            if (edge.from != seed && edge.to != seed) continue
            val other = if (edge.from == seed) edge.to else edge.from
            // Songs close together in a radio go together either way round.
            val back = if (edge.from == seed || edge.kind == EdgeKind.RADIO_NEAR) 1.0 else t.reverse
            scores.merge(other, weight(edge, use, t, now) * back, Double::plus)
        }
        scores.remove(seed)
        val near = scores.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(SECOND_HOP_FROM)
        val best = near.firstOrNull()?.value
        if (best != null && t.secondHop > 0) {
            val strength = near.associate { it.key to it.value / best }
            for (edge in around(strength.keys.toList())) {
                // Onwards from a neighbour; songs close together in a radio either way round.
                val w = weight(edge, use, t, now) * t.secondHop
                strength[edge.from]?.let { if (edge.to != seed) scores.merge(edge.to, it * w, Double::plus) }
                if (edge.kind == EdgeKind.RADIO_NEAR) strength[edge.to]?.let { if (edge.from != seed) scores.merge(edge.from, it * w, Double::plus) }
            }
        }
        return scores.entries.filter { it.value >= MIN_SCORE }.sortedByDescending { it.value }.map { it.key }
    }
}
