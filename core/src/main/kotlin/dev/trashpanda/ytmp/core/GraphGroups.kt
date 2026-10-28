package dev.trashpanda.ytmp.core

/**
 * Groups of songs in the song graph: songs linked more to each other than to the rest, found
 * with Louvain (modularity, on the whole graph at once). They come out much like genres or
 * moods, because one stray jump to other music counts little against many links within a
 * group. Radios favour the seed's group ([GraphRank]); the whole-graph view colours by it.
 * The same songs and links always give the same groups. See docs/implementation/storage.md#groups.
 */
object GraphGroups {
    /**
     * The group of each of the [n] nodes (0: the biggest group, then smaller), from undirected
     * links [from]–[to] with [weight]s (links that don't count more than 0 are left out; the
     * same pair twice adds up).
     */
    fun find(n: Int, from: IntArray, to: IntArray, weight: DoubleArray, resolution: Double = 1.0): IntArray {
        // Each node's group in the graph of this level, and which node of the level each song is.
        var level = firstLevel(n, from, to, weight)
        val nodeOf = IntArray(n) { it }
        while (true) {
            val community = level.move(resolution) ?: break
            for (i in 0 until n) nodeOf[i] = community[nodeOf[i]]
            level = level.aggregate(community)
        }
        // Biggest first; same size: the one with the first song first.
        val size = IntArray(level.n)
        val first = IntArray(level.n) { Int.MAX_VALUE }
        for (i in 0 until n) {
            size[nodeOf[i]]++
            if (i < first[nodeOf[i]]) first[nodeOf[i]] = i
        }
        val order = (0 until level.n).sortedWith(compareByDescending<Int> { size[it] }.thenBy { first[it] })
        val rank = IntArray(level.n).also { r -> order.forEachIndexed { i, c -> r[c] = i } }
        return IntArray(n) { rank[nodeOf[it]] }
    }

    /** A graph of one level: [n] nodes, links as lists per node, [self] the weight inside a node (a group below). */
    private class Level(val n: Int, val start: IntArray, val other: IntArray, val w: DoubleArray, val self: DoubleArray) {
        /** Weight at each node: its links, and inside it twice. */
        val degree = DoubleArray(n).also { d ->
            for (i in 0 until n) {
                var s = 2 * self[i]
                for (k in start[i] until start[i + 1]) s += w[k]
                d[i] = s
            }
        }
        val total = degree.sum()

        /** Moves nodes to the neighbouring group that adds most, until none moves: each node's group, or null when nothing moved. */
        fun move(resolution: Double): IntArray? {
            if (total <= 0) return null
            val community = IntArray(n) { it }
            val tot = degree.copyOf()
            val toCommunity = DoubleArray(n)
            val seen = IntArray(n)
            val touched = IntArray(n)
            var movedAny = false
            repeat(MAX_PASSES) {
                var moved = false
                for (i in 0 until n) {
                    var count = 0
                    for (k in start[i] until start[i + 1]) {
                        val c = community[other[k]]
                        if (seen[c] != i + 1) {
                            seen[c] = i + 1
                            toCommunity[c] = 0.0
                            touched[count++] = c
                        }
                        toCommunity[c] += w[k]
                    }
                    val own = community[i]
                    tot[own] -= degree[i]
                    val inOwn = if (seen[own] == i + 1) toCommunity[own] else 0.0
                    var best = own
                    var bestGain = inOwn - resolution * tot[own] * degree[i] / total
                    for (j in 0 until count) {
                        val c = touched[j]
                        val gain = toCommunity[c] - resolution * tot[c] * degree[i] / total
                        if (gain > bestGain + 1e-12) {
                            best = c
                            bestGain = gain
                        }
                    }
                    tot[best] += degree[i]
                    if (best != own) {
                        community[i] = best
                        moved = true
                    }
                }
                if (!moved) return if (movedAny) renumber(community) else null
                movedAny = true
            }
            return renumber(community)
        }

        /** The groups as nodes of the next level. */
        fun aggregate(community: IntArray): Level {
            val m = community.max() + 1
            val self = DoubleArray(m)
            val pairs = HashMap<Long, Double>()
            for (i in 0 until n) {
                val a = community[i]
                self[a] += this.self[i]
                for (k in start[i] until start[i + 1]) {
                    val j = other[k]
                    if (j < i) continue
                    val b = community[j]
                    if (a == b) self[a] += w[k]
                    else pairs.merge(key(a, b), w[k], Double::plus)
                }
            }
            return build(m, pairs, self)
        }
    }

    private const val MAX_PASSES = 50

    private fun key(a: Int, b: Int): Long = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()

    private fun renumber(community: IntArray): IntArray {
        val ids = HashMap<Int, Int>()
        return IntArray(community.size) { ids.getOrPut(community[it]) { ids.size } }
    }

    private fun firstLevel(n: Int, from: IntArray, to: IntArray, weight: DoubleArray): Level {
        val pairs = HashMap<Long, Double>()
        for (k in from.indices) {
            if (from[k] == to[k] || from[k] !in 0 until n || to[k] !in 0 until n) continue
            pairs.merge(key(from[k], to[k]), weight[k], Double::plus)
        }
        pairs.values.removeIf { it <= 0 }
        return build(n, pairs, DoubleArray(n))
    }

    private fun build(n: Int, pairs: Map<Long, Double>, self: DoubleArray): Level {
        val count = IntArray(n + 1)
        for (k in pairs.keys) {
            count[(k shr 32).toInt() + 1]++
            count[(k and 0xffffffffL).toInt() + 1]++
        }
        for (i in 0 until n) count[i + 1] += count[i]
        val start = count.copyOf()
        val fill = count.copyOf()
        val other = IntArray(count[n])
        val w = DoubleArray(count[n])
        // In a fixed order, so the same graph always gives the same groups.
        for ((k, v) in pairs.entries.sortedBy { it.key }) {
            val a = (k shr 32).toInt()
            val b = (k and 0xffffffffL).toInt()
            other[fill[a]] = b
            w[fill[a]++] = v
            other[fill[b]] = a
            w[fill[b]++] = v
        }
        return Level(n, start, other, w, self)
    }
}
