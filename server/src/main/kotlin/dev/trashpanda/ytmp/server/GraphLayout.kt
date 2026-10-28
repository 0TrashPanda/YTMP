package dev.trashpanda.ytmp.server

import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where each song goes in the whole-graph view: ForceAtlas2 (songs push each other away, links
 * pull them together, a pull to the middle keeps them in one round map), with Barnes-Hut so the
 * pushing is n log n: 10,000 songs take a few seconds. Songs that go together end up close, so
 * the groups show as areas. Same songs and links in the same order: the same map.
 * See docs/implementation/storage.md#the-whole-graph-view.
 */
object GraphLayout {
    private const val ITERATIONS = 300
    private const val SCALING = 4.0
    private const val GRAVITY = 1.0
    private const val WEIGHT_INFLUENCE = 0.5
    private const val SLOW_DOWN = 2.0
    private const val THETA = 1.0

    /**
     * Positions (x, y per song) for [n] songs with links [from]–[to] that count [weight]. With
     * [groups] (0: the biggest), each song starts near its group's spot: the groups come apart
     * in far fewer steps than from one heap. With [startX]/[startY] (NaN: a new song), songs start
     * where they were on the map before, a new one among its linked songs or its group: the
     * map stays as it was, and as it's almost laid out, half the steps do.
     */
    fun place(
        n: Int,
        from: IntArray,
        to: IntArray,
        weight: DoubleArray,
        groups: IntArray? = null,
        iterations: Int = ITERATIONS,
        startX: DoubleArray? = null,
        startY: DoubleArray? = null,
    ): Pair<DoubleArray, DoubleArray> {
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        // A fixed start: a spiral of groups (biggest in the middle), each a small spiral of its songs.
        val group = groups ?: IntArray(n)
        val size = IntArray((group.maxOrNull() ?: 0) + 1).also { s -> for (g in group) s[g]++ }
        val before = IntArray(size.size).also { b -> for (g in 1 until size.size) b[g] = b[g - 1] + size[g - 1] }
        val placed = IntArray(size.size)
        for (i in 0 until n) {
            val g = group[i]
            val ga = g * 2.399963
            val gd = sqrt(before[g] + size[g] / 2.0)
            val k = placed[g]++
            val a = k * 2.399963
            val d = 0.6 * sqrt(k + 0.5)
            x[i] = gd * cos(ga) + d * cos(a)
            y[i] = gd * sin(ga) + d * sin(a)
        }
        var steps = iterations
        if (startX != null && startY != null) {
            val known = BooleanArray(n) { !startX[it].isNaN() && !startY[it].isNaN() }
            val count = known.count { it }
            if (count > 0) {
                // The map's size against the spiral's: a song with nothing placed to go by starts on the spiral at the map's size.
                fun rms(xs: DoubleArray, ys: DoubleArray, which: (Int) -> Boolean) =
                    sqrt((0 until n).filter(which).sumOf { xs[it] * xs[it] + ys[it] * ys[it] } / (0 until n).count(which).coerceAtLeast(1))
                val scale = (rms(startX, startY) { known[it] } / rms(x, y) { known[it] }.coerceAtLeast(1e-9)).takeIf { it.isFinite() } ?: 1.0
                for (i in 0 until n) if (!known[i]) {
                    x[i] *= scale
                    y[i] *= scale
                }
                for (i in 0 until n) if (known[i]) {
                    x[i] = startX[i]
                    y[i] = startY[i]
                }
                // A new song: in the middle of its linked songs that have a place, else of its group's.
                val sx = DoubleArray(n)
                val sy = DoubleArray(n)
                val sn = IntArray(n)
                for (k in from.indices) {
                    val a = from[k]
                    val b = to[k]
                    if (known[b] && !known[a]) { sx[a] += x[b]; sy[a] += y[b]; sn[a]++ }
                    if (known[a] && !known[b]) { sx[b] += x[a]; sy[b] += y[a]; sn[b]++ }
                }
                val gx = DoubleArray(size.size)
                val gy = DoubleArray(size.size)
                val gn = IntArray(size.size)
                for (i in 0 until n) if (known[i]) { gx[group[i]] += x[i]; gy[group[i]] += y[i]; gn[group[i]]++ }
                for (i in 0 until n) {
                    if (known[i]) continue
                    // A little apart, so new songs on the same spot don't sit on each other.
                    val jx = cos(i * 2.399963)
                    val jy = sin(i * 2.399963)
                    if (sn[i] > 0) {
                        x[i] = sx[i] / sn[i] + jx
                        y[i] = sy[i] / sn[i] + jy
                    } else if (gn[group[i]] > 0) {
                        x[i] = gx[group[i]] / gn[group[i]] + jx
                        y[i] = gy[group[i]] / gn[group[i]] + jy
                    }
                }
                if (count >= n * 0.8) steps = iterations / 2
            }
        }
        if (n < 2) return x to y
        val mass = DoubleArray(n) { 1.0 }
        for (k in from.indices) {
            mass[from[k]]++
            mass[to[k]]++
        }
        val pull = DoubleArray(weight.size) { weight[it].coerceAtLeast(0.0).pow(WEIGHT_INFLUENCE) }
        val fx = DoubleArray(n)
        val fy = DoubleArray(n)
        val oldX = DoubleArray(n)
        val oldY = DoubleArray(n)
        val tree = Tree(n)
        repeat(steps) {
            fx.copyInto(oldX)
            fy.copyInto(oldY)
            fx.fill(0.0)
            fy.fill(0.0)
            tree.build(x, y, mass)
            // Each song's push is its own (only its fx, fy): on all cores.
            IntStream.range(0, n).parallel().forEach { i -> tree.push(i, x, y, mass, fx, fy, SCALING, THETA) }
            for (i in 0 until n) {
                // Strong gravity: the further out, the harder the pull to the middle.
                fx[i] -= GRAVITY * mass[i] * x[i] / SCALING
                fy[i] -= GRAVITY * mass[i] * y[i] / SCALING
            }
            for (k in from.indices) {
                val a = from[k]
                val b = to[k]
                val dx = x[a] - x[b]
                val dy = y[a] - y[b]
                fx[a] -= dx * pull[k]
                fy[a] -= dy * pull[k]
                fx[b] += dx * pull[k]
                fy[b] += dy * pull[k]
            }
            for (i in 0 until n) {
                // Songs that keep changing direction slow down (ForceAtlas2's swinging).
                val swing = mass[i] * sqrt((oldX[i] - fx[i]).pow(2) + (oldY[i] - fy[i]).pow(2))
                val traction = sqrt((oldX[i] + fx[i]).pow(2) + (oldY[i] + fy[i]).pow(2)) / 2
                val speed = 0.1 * ln(1 + traction) / (1 + sqrt(swing)) / SLOW_DOWN
                x[i] += fx[i] * speed
                y[i] += fy[i] * speed
            }
        }
        return x to y
    }

    /** A quadtree of the songs with their mass and its middle, for pushing far-away songs as one. */
    private class Tree(n: Int) {
        private val cap = 4 * n + 8
        private val minX = DoubleArray(cap)
        private val minY = DoubleArray(cap)
        private val size = DoubleArray(cap)
        private val mass = DoubleArray(cap)
        private val cx = DoubleArray(cap)
        private val cy = DoubleArray(cap)
        /** First of four children, or -1: a leaf. */
        private val child = IntArray(cap)
        /** The song in a leaf, or -1. */
        private val song = IntArray(cap)
        private var count = 0

        fun build(x: DoubleArray, y: DoubleArray, m: DoubleArray) {
            var lx = Double.MAX_VALUE
            var ly = Double.MAX_VALUE
            var hx = -Double.MAX_VALUE
            var hy = -Double.MAX_VALUE
            for (i in x.indices) {
                if (x[i] < lx) lx = x[i]
                if (y[i] < ly) ly = y[i]
                if (x[i] > hx) hx = x[i]
                if (y[i] > hy) hy = y[i]
            }
            count = 0
            region(lx, ly, maxOf(hx - lx, hy - ly) + 1e-6)
            for (i in x.indices) insert(0, i, x, y, m, 0)
        }

        private fun region(x: Double, y: Double, s: Double): Int {
            val r = count++
            minX[r] = x
            minY[r] = y
            size[r] = s
            mass[r] = 0.0
            cx[r] = 0.0
            cy[r] = 0.0
            child[r] = -1
            song[r] = -1
            return r
        }

        private fun insert(r0: Int, i: Int, x: DoubleArray, y: DoubleArray, m: DoubleArray, depth0: Int) {
            var r = r0
            var depth = depth0
            while (true) {
                // Its mass and middle take the song in on the way down.
                val total = mass[r] + m[i]
                cx[r] = (cx[r] * mass[r] + x[i] * m[i]) / total
                cy[r] = (cy[r] * mass[r] + y[i] * m[i]) / total
                mass[r] = total
                if (child[r] < 0) {
                    if (song[r] < 0 && total == m[i]) {
                        song[r] = i
                        return
                    }
                    // Songs on (almost) the same spot share a leaf: deeper doesn't part them.
                    if (depth > 40 || count + 4 > cap) return
                    val half = size[r] / 2
                    val first = region(minX[r], minY[r], half)
                    region(minX[r] + half, minY[r], half)
                    region(minX[r], minY[r] + half, half)
                    region(minX[r] + half, minY[r] + half, half)
                    child[r] = first
                    val old = song[r]
                    song[r] = -1
                    if (old >= 0) placeInto(r, old, x, y, m, depth)
                }
                r = child[r] + quadrant(r, x[i], y[i])
                depth++
            }
        }

        /** Moves the song that was the leaf's own into one of its new children. */
        private fun placeInto(r: Int, i: Int, x: DoubleArray, y: DoubleArray, m: DoubleArray, depth: Int) {
            val c = child[r] + quadrant(r, x[i], y[i])
            mass[c] = m[i]
            cx[c] = x[i]
            cy[c] = y[i]
            song[c] = i
        }

        private fun quadrant(r: Int, px: Double, py: Double): Int {
            val half = size[r] / 2
            return (if (px >= minX[r] + half) 1 else 0) + (if (py >= minY[r] + half) 2 else 0)
        }

        /** Pushes song [i] away from all the others: near ones one by one, far groups as one. */
        fun push(i: Int, x: DoubleArray, y: DoubleArray, m: DoubleArray, fx: DoubleArray, fy: DoubleArray, k: Double, theta: Double) {
            val stack = IntArray(256)
            var top = 0
            stack[top++] = 0
            while (top > 0) {
                val r = stack[--top]
                if (mass[r] == 0.0) continue
                val dx = x[i] - cx[r]
                val dy = y[i] - cy[r]
                val d2 = dx * dx + dy * dy
                val leaf = child[r] < 0
                if (leaf && song[r] == i) continue
                if (leaf || size[r] * size[r] < theta * theta * d2) {
                    if (d2 == 0.0) continue
                    // The song's own mass isn't in a far group; in its own leaf it is.
                    val other = if (leaf || !contains(r, x[i], y[i])) mass[r] else mass[r] - m[i]
                    val f = k * m[i] * other / d2
                    fx[i] += dx * f
                    fy[i] += dy * f
                } else {
                    if (top + 4 > stack.size) continue
                    for (c in 0 until 4) stack[top++] = child[r] + c
                }
            }
        }

        private fun contains(r: Int, px: Double, py: Double) =
            px >= minX[r] && px < minX[r] + size[r] && py >= minY[r] && py < minY[r] + size[r]
    }
}
