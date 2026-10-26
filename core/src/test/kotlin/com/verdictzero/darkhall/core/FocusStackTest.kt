package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Filters
import com.verdictzero.darkhall.core.stack.FocusStack
import com.verdictzero.darkhall.core.stack.Similarity
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.assertTrue

class FocusStackTest {
    private val w = 640
    private val h = 480

    /** A detailed grey scene in 0..1: random rectangles and dots of every size. */
    private fun truth(): FloatArray {
        val rnd = Random(21)
        val p = FloatArray(w * h) { 0.45f }
        repeat(900) {
            val x0 = rnd.nextInt(w); val y0 = rnd.nextInt(h)
            val sw = 1 + rnd.nextInt(24); val sh = 1 + rnd.nextInt(24)
            val v = rnd.nextFloat()
            for (y in y0 until min(h, y0 + sh)) for (x in x0 until min(w, x0 + sw)) p[y * w + x] = v
        }
        return p
    }

    private fun inverse(t: Similarity): Similarity {
        val d = t.a * t.a + t.b * t.b
        val a = t.a / d; val b = -t.b / d
        return Similarity(a, b, -(a * t.tx - b * t.ty), -(b * t.tx + a * t.ty))
    }

    private fun bilinear(p: FloatArray, x: Double, y: Double): Float {
        val cx = x.coerceIn(0.0, w - 1.001); val cy = y.coerceIn(0.0, h - 1.001)
        val x0 = cx.toInt(); val y0 = cy.toInt(); val fx = (cx - x0).toFloat(); val fy = (cy - y0).toFloat()
        val i = y0 * w + x0
        return (p[i] * (1 - fx) + p[i + 1] * fx) * (1 - fy) + (p[i + w] * (1 - fx) + p[i + w + 1] * fx) * fy
    }

    /** Frame [k] of 3: sharp in the [k]th vertical third, blurred elsewhere, seen through [t]. */
    private fun frame(truth: FloatArray, k: Int, t: Similarity): FloatArray {
        val blurred = FloatArray(w * h); val tmp = FloatArray(w * h)
        Filters.blur(truth, blurred, tmp, w, h, 5, passes = 3)
        val focused = FloatArray(w * h) { i -> if ((i % w) * 3 / w == k) truth[i] else blurred[i] }
        val inv = inverse(t)
        return FloatArray(w * h) { i ->
            val x = (i % w).toDouble(); val y = (i / w).toDouble()
            bilinear(focused, inv.mapX(x, y), inv.mapY(x, y))
        }
    }

    private fun to16(p: FloatArray) = ShortArray(3 * p.size) { (p[it / 3] * 65535f + 0.5f).toInt().toShort() }

    @Test
    fun alignsAndMergesAFocusSeries() {
        val truth = truth()
        val actual = listOf(
            Similarity.IDENTITY,
            Similarity(1.008, 0.001, -3.5, 2.0),
            Similarity(1.017, -0.0015, 4.0, -5.5),
        )
        val frames = actual.mapIndexed { k, t -> frame(truth, k, t) }

        // Chain: each frame aligned to the one before, as the app does.
        var chained = Similarity.IDENTITY
        val found = ArrayList<Similarity>()
        found += chained
        for (k in 1 until frames.size) {
            chained = chained.then(FocusStack.align(frames[k - 1], frames[k], w, h))
            found += chained
        }
        for ((k, t) in found.withIndex()) {
            val e = actual[k]
            // Where the reference's corners land in each frame: within half a pixel.
            for ((x, y) in listOf(0.0 to 0.0, w - 1.0 to 0.0, 0.0 to h - 1.0, w - 1.0 to h - 1.0)) {
                val dx = t.mapX(x, y) - e.mapX(x, y); val dy = t.mapY(x, y) - e.mapY(x, y)
                assertTrue(abs(dx) < 0.5 && abs(dy) < 0.5, "frame $k corner ($x, $y) off by ($dx, $dy): found $t, actual $e")
            }
        }

        val merger = FocusStack.Merger(w, h)
        for ((k, f) in frames.withIndex()) merger.add(to16(f), FocusStack.sharpness(f, w, h), found[k])
        val merged = FocusStack.luminance(merger.rgb, w, h)

        // Away from the borders (which later frames don't cover) and the band seams.
        fun error(p: FloatArray): Double {
            var s = 0.0; var n = 0
            for (y in 30 until h - 30) for (x in 30 until w - 30) {
                if (abs(x - w / 3) < 12 || abs(x - 2 * w / 3) < 12) continue
                s += abs(p[y * w + x] - truth[y * w + x]); n++
            }
            return s / n
        }
        val single = (0 until 3).map { k ->
            val inv = found[k]
            error(FloatArray(w * h) { i -> bilinear(frames[k], inv.mapX((i % w).toDouble(), (i / w).toDouble()), inv.mapY((i % w).toDouble(), (i / w).toDouble())) })
        }
        val stacked = error(merged)
        println("mean error: frames ${single.map { "%.4f".format(it) }}, stack %.4f".format(stacked))
        assertTrue(stacked < 0.5 * single.min(), "stack ($stacked) not much better than the best frame (${single.min()})")
    }
}
