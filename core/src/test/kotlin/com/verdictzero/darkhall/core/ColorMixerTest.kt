package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.MixBand
import com.verdictzero.darkhall.core.develop.RenderPlan
import com.verdictzero.darkhall.core.develop.ToneStage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorMixerTest {

    /** The mixer settings a RenderPlan would derive from [p]. */
    private fun mix(p: DevelopParams): FloatArray = FloatArray(24).also { m ->
        for (band in MixBand.entries) {
            val (hue, sat, lum) = Adjustment.mixer(band)
            m[3 * band.ordinal] = p[hue] / 100f * RenderPlan.MIX_MAX_TURN
            m[3 * band.ordinal + 1] = p[sat] / 100f
            m[3 * band.ordinal + 2] = p[lum] / 100f * RenderPlan.MIX_MAX_STOPS
        }
    }

    private fun apply(p: DevelopParams, r: Float, g: Float, b: Float): FloatArray =
        FloatArray(3).also { ToneStage.mix(mix(p), r, g, b, it) }

    private fun hue(c: FloatArray): Float {
        val mx = max(c[0], max(c[1], c[2])); val d = mx - min(c[0], min(c[1], c[2]))
        var h = 60f * when { mx == c[0] -> (c[1] - c[2]) / d; mx == c[1] -> (c[2] - c[0]) / d + 2f; else -> (c[0] - c[1]) / d + 4f }
        if (h < 0f) h += 360f
        return h
    }

    private fun every(value: Float) = MixBand.entries.fold(DevelopParams.NEUTRAL) { p, band ->
        Adjustment.mixer(band).fold(p) { q, a -> q.with(a, value) }
    }

    @Test
    fun greysNeverChange() {
        for (v in listOf(0f, 0.1f, 0.5f, 0.93f, 1f)) for (setting in listOf(-100f, 100f)) {
            val out = apply(every(setting), v, v, v)
            for (c in out) assertEquals(v, c, 1e-6f)
        }
    }

    @Test
    fun aBandMovesItsOwnColoursOnly() {
        val p = DevelopParams.NEUTRAL.with(Adjustment.MIX_GREEN_SAT, -100f).with(Adjustment.MIX_GREEN_LUM, 60f)
        // A green leaf loses its colour and brightens...
        val leaf = apply(p, 0.2f, 0.6f, 0.2f)
        assertTrue(max(leaf[0], max(leaf[1], leaf[2])) - min(leaf[0], min(leaf[1], leaf[2])) < 0.02f, leaf.toList().toString())
        // ...while blue water and a red buoy are untouched.
        for (c in listOf(floatArrayOf(0.1f, 0.3f, 0.8f), floatArrayOf(0.85f, 0.15f, 0.12f))) {
            val out = apply(p, c[0], c[1], c[2])
            for (k in 0..2) assertEquals(c[k], out[k], 1e-6f, "${c.toList()} changed to ${out.toList()}")
        }
    }

    @Test
    fun hueSliderTurnsTowardsTheNeighbour() {
        val red = floatArrayOf(0.8f, 0.1f, 0.1f)
        val warmer = apply(DevelopParams.NEUTRAL.with(Adjustment.MIX_RED_HUE, 100f), red[0], red[1], red[2])
        val cooler = apply(DevelopParams.NEUTRAL.with(Adjustment.MIX_RED_HUE, -100f), red[0], red[1], red[2])
        assertTrue(hue(warmer) in 15f..45f, "red +100 gave hue ${hue(warmer)}")
        assertTrue(hue(cooler) in 315f..345f, "red -100 gave hue ${hue(cooler)}")
    }

    /** Neighbouring hues get neighbouring results: the bands blend without steps. */
    @Test
    fun smoothAcrossTheHueCircle() {
        val rnd = Random(3)
        val p = MixBand.entries.fold(DevelopParams.NEUTRAL) { acc, band ->
            Adjustment.mixer(band).fold(acc) { q, a -> q.with(a, rnd.nextFloat() * 200f - 100f) }
        }
        var prev: FloatArray? = null
        for (k in 0..3600) {
            val h = k / 10f
            // A fully saturated colour of hue h at value 0.8.
            val x = 1f - abs((h / 60f) % 2f - 1f)
            val (r, g, b) = when ((h / 60f).toInt() % 6) {
                0 -> Triple(1f, x, 0f); 1 -> Triple(x, 1f, 0f); 2 -> Triple(0f, 1f, x)
                3 -> Triple(0f, x, 1f); 4 -> Triple(x, 0f, 1f); else -> Triple(1f, 0f, x)
            }
            val out = apply(p, 0.8f * r, 0.8f * g, 0.8f * b)
            prev?.let { q -> for (c in 0..2) assertTrue(abs(out[c] - q[c]) < 0.03f, "step at hue $h: ${q.toList()} -> ${out.toList()}") }
            prev = out
        }
    }
}
