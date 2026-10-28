package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.EdgeKind
import dev.trashpanda.ytmp.protocol.GraphEdge
import dev.trashpanda.ytmp.protocol.GraphTuning
import dev.trashpanda.ytmp.protocol.HistoryRadio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphRankTest {
    private val now = 1_000_000_000_000L

    private fun link(from: String, to: String, plays: Int = 1, kind: EdgeKind = EdgeKind.PERSONAL) =
        GraphEdge(from, to, kind, plays, 0, 0, 0, null, 0, now, true)

    /** Two groups (rock r0–r5, pop p0–p5), each song linked to the next two, and one stray jump r0 → p0. */
    private val links = buildList {
        for (g in listOf("r", "p")) for (i in 0 until 6) for (d in 1..2) add(link("$g$i", "$g${(i + d) % 6}", plays = 3))
        add(link("r0", "p0", plays = 3))
    }

    private fun around(nodes: List<String>) = links.filter { it.from in nodes || it.to in nodes }

    @Test
    fun `songs linked more to each other than to the rest are a group`() {
        val names = links.flatMap { listOf(it.from, it.to) }.distinct()
        val index = names.withIndex().associate { it.value to it.index }
        val groups = GraphGroups.find(
            names.size,
            links.map { index.getValue(it.from) }.toIntArray(),
            links.map { index.getValue(it.to) }.toIntArray(),
            links.map { it.solo.toDouble() }.toDoubleArray(),
        )
        val of = names.associateWith { groups[index.getValue(it)] }
        assertEquals(1, (0 until 6).map { of["r$it"] }.distinct().size)
        assertEquals(1, (0 until 6).map { of["p$it"] }.distinct().size)
        assertTrue(of["r0"] != of["p0"])
        // The same links give the same groups.
        assertEquals(
            groups.toList(),
            GraphGroups.find(names.size, links.map { index.getValue(it.from) }.toIntArray(), links.map { index.getValue(it.to) }.toIntArray(), links.map { it.solo.toDouble() }.toDoubleArray()).toList(),
        )
    }

    @Test
    fun `a radio walks further than the neighbours, and keeps to the seed's group`() {
        val t = GraphTuning()
        val plain = GraphRank.rank("r0", ::around, HistoryRadio.ALL, t, now)
        // Further than two steps: r3 is three songs on.
        assertTrue("r3" in plain)
        val group = { id: String -> if (id.startsWith("r")) 0 else 1 }
        val grouped = GraphRank.rank("r0", ::around, HistoryRadio.ALL, t, now, group)
        // The stray jump's song goes before songs of the seed's group two steps away, but with
        // the extra for the group after them.
        assertTrue(plain.indexOf("p0") < plain.indexOf("r4"))
        assertTrue(grouped.indexOf("p0") > grouped.indexOf("r4"))
        assertTrue(grouped.indexOf("p0") > grouped.indexOf("r5"))
    }

    @Test
    fun `neighbours of neighbours at 0 keeps to the seed's own links`() {
        val ranked = GraphRank.rank("r0", ::around, HistoryRadio.ALL, GraphTuning(secondHop = 0.0), now)
        assertEquals(setOf("r1", "r2", "p0", "r4", "r5"), ranked.toSet())
    }
}
