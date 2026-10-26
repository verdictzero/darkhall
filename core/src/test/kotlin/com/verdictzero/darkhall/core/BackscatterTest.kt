package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Backscatter
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.assertTrue

class BackscatterTest {

    private class Scene(val w: Int, val h: Int) {
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        val specks = ArrayList<Triple<Int, Int, Int>>()
    }

    /** Blue-green water with a gradient and grain, specks of 1-9 px radius, and a large bright fish. */
    private fun scene(): Scene {
        val s = Scene(800, 600)
        val rnd = Random(11)
        for (y in 0 until s.h) for (x in 0 until s.w) {
            val i = y * s.w + x
            val depth = 0.6f + 0.4f * y / s.h
            s.r[i] = 0.02f * depth + rnd.nextFloat() * 0.004f
            s.g[i] = 0.12f * depth + rnd.nextFloat() * 0.006f
            s.b[i] = 0.18f * depth + rnd.nextFloat() * 0.006f
        }
        // The fish: a bright ellipse 140 x 70 px.
        for (y in 0 until s.h) for (x in 0 until s.w) {
            val dx = (x - 560) / 70f; val dy = (y - 300) / 35f
            if (dx * dx + dy * dy < 1f) { val i = y * s.w + x; s.r[i] = 0.5f; s.g[i] = 0.45f; s.b[i] = 0.35f }
        }
        // 40 specks over a 400 x 560 stretch of water: a heavy but realistic dose.
        repeat(40) {
            val cx = rnd.nextInt(20, 420); val cy = rnd.nextInt(20, s.h - 20); val rad = rnd.nextInt(1, 10)
            s.specks += Triple(cx, cy, rad)
            for (y in cy - 2 * rad..cy + 2 * rad) for (x in cx - 2 * rad..cx + 2 * rad) {
                val d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat()
                val a = 0.35f * exp(-d2 / (rad * rad * 0.6f))
                val i = y * s.w + x
                s.r[i] += a; s.g[i] += a; s.b[i] += a * 0.9f
            }
        }
        return s
    }

    @Test
    fun specksGoTheFishStays() {
        val s = scene()
        val before = s.g.copyOf()
        Backscatter.apply(s.r, s.g, s.b, s.w, s.h, amount = 0.6f, radius = 12)
        // Each speck's peak must sink most of the way back to the water around it.
        var worst = 0f
        for ((cx, cy, rad) in s.specks) {
            val i = cy * s.w + cx
            val water = 0.12f * (0.6f + 0.4f * cy / s.h)
            val left = (s.g[i] - water) / (before[i] - water)
            worst = maxOf(worst, left)
            assertTrue(left < 0.25f, "speck at ($cx, $cy) r=$rad kept ${(left * 100).toInt()}% of its brightness")
        }
        println("worst speck kept ${(worst * 100).toInt()}%")
        // The fish (much wider than the window) is untouched inside.
        var change = 0f; var count = 0
        for (y in 280..320) for (x in 520..600) { val i = y * s.w + x; change += abs(s.g[i] - before[i]); count++ }
        assertTrue(change / count < 0.005f, "fish changed by ${change / count}")
        // Open water away from specks barely moves.
        var waterChange = 0f
        for (x in 450..500) { val i = 50 * s.w + x; waterChange += abs(s.g[i] - before[i]) }
        assertTrue(waterChange / 51 < 0.003f, "water changed by ${waterChange / 51}")
    }

    @Test
    fun moreSliderTakesFainterSpecks() {
        fun kept(amount: Float): Float {
            val s = scene()
            val before = s.g.copyOf()
            Backscatter.apply(s.r, s.g, s.b, s.w, s.h, amount, radius = 12)
            return s.specks.map { (cx, cy, _) -> val i = cy * s.w + cx; s.g[i] / before[i] }.average().toFloat()
        }
        assertTrue(kept(1f) <= kept(0.3f) + 1e-4f)
    }

    /** Dense fine bright texture (sand sparkle, foliage) is not backscatter and stays. */
    @Test
    fun textureStays() {
        val w = 400; val h = 300
        val rnd = Random(5)
        val g = FloatArray(w * h) { 0.1f + if (rnd.nextFloat() < 0.35f) 0.3f else 0f }
        val r = g.copyOf(); val b = g.copyOf()
        val before = g.copyOf()
        Backscatter.apply(r, g, b, w, h, amount = 1f, radius = 6)
        var moved = 0
        for (i in g.indices) if (abs(g[i] - before[i]) > 0.02f) moved++
        assertTrue(moved < g.size / 100, "${moved * 100 / g.size}% of a dense texture was taken for specks")
    }
}
