package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.Grain
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.develop.RenderPlan
import com.verdictzero.darkhall.core.develop.ToneStage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradingGrainTest {

    /** Identity colour, no file: what the tone stage does on its own. */
    private val neutral = RenderContext(floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 100, 100, floatArrayOf(0.1f, 0.1f, 0.1f))

    /** A grey ramp's display values after the tone stage with [p] (linear in, curve off). */
    private fun tone(p: DevelopParams, linear: Float): FloatArray {
        val plan = RenderPlan(neutral, p, Frame(0, 0, 1f))
        return FloatArray(3).also { ToneStage.apply(plan, linear, linear, linear, it, FloatArray(3)) }
    }

    @Test
    fun tintsGoWhereTheyBelong() {
        val p = DevelopParams.NEUTRAL.with(Adjustment.GRADE_SHADOW, 100f).with(Adjustment.GRADE_SHADOW_HUE, 240f)
            .with(Adjustment.GRADE_HIGHLIGHT, 100f).with(Adjustment.GRADE_HIGHLIGHT_HUE, 40f)
        val dark = tone(p, 0.01f)
        val bright = tone(p, 0.7f)
        assertTrue(dark[2] > dark[0] + 0.05f, "shadows should turn blue: ${dark.toList()}")
        assertTrue(bright[0] > bright[2] + 0.05f, "highlights should turn warm: ${bright.toList()}")
        // The tint changes colour, hardly brightness.
        val plain = tone(DevelopParams.NEUTRAL, 0.2f)
        val tinted = tone(p, 0.2f)
        fun y(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
        assertEquals(y(plain), y(tinted), 0.02f)
    }

    @Test
    fun balanceShiftsTheSplit() {
        val p = DevelopParams.NEUTRAL.with(Adjustment.GRADE_HIGHLIGHT, 100f).with(Adjustment.GRADE_HIGHLIGHT_HUE, 40f)
        fun warmth(q: DevelopParams) = tone(q, 0.18f).let { it[0] - it[2] }
        assertTrue(warmth(p.with(Adjustment.GRADE_BALANCE, 80f)) > warmth(p) + 0.02f)
        assertTrue(warmth(p.with(Adjustment.GRADE_BALANCE, -80f)) < warmth(p) - 0.02f)
    }

    @Test
    fun grainIsStableAndMidtoneOnly() {
        // The same full-resolution spot gets the same grain however the frame around it is placed.
        val a = Grain.noise(1234.5f, 777.25f, 2.5f)
        assertEquals(a, Grain.noise(1234.5f, 777.25f, 2.5f))
        // Mean near zero, spread sensible, bounded.
        var sum = 0.0; var sq = 0.0; val n = 200_000
        for (i in 0 until n) {
            val v = Grain.noise((i % 997) * 1.7f, (i / 997) * 1.3f, 2f)
            assertTrue(v in -1f..1f)
            sum += v; sq += v * v
        }
        assertTrue(abs(sum / n) < 0.02, "mean ${sum / n}")
        val sd = sqrt(sq / n)
        assertTrue(sd in 0.2..0.7, "sd $sd")
        // Black and white stay put.
        val plan = RenderPlan(neutral, DevelopParams.NEUTRAL.with(Adjustment.GRAIN, 100f), Frame(0, 0, 1f))
        val r = floatArrayOf(0f, 1f, 0.5f, 0.5f); val g = r.copyOf(); val b = r.copyOf()
        Grain.apply(r, g, b, 4, 1, plan)
        assertEquals(0f, r[0]); assertEquals(1f, r[1])
        assertTrue(r[2] != 0.5f || r[3] != 0.5f, "midtones get grain")
    }
}
