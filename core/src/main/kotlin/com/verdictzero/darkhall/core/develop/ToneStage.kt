package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.develop.ColorScience.LUM_B
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_G
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_R
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The per-pixel tone and colour stage, shared by the renderer and [AutoTone] so the auto
 * search judges exactly what will be drawn: shadows/highlights, the highlight shoulder, sRGB
 * encoding, the blacks/whites/contrast curve, vibrance/saturation and the look.
 */
object ToneStage {
    private val LN2 = ln(2.0).toFloat()

    /**
     * Maps linear working-space RGB to display-encoded RGB in [out]. [scratch] needs 3 floats
     * (look sampling); pass the same arrays repeatedly to avoid allocation.
     */
    fun apply(plan: RenderPlan, r: Float, g: Float, b: Float, out: FloatArray, scratch: FloatArray) {
        var rr = r; var gg = g; var bb = b
        if (plan.shadowsHighlightsOn) {
            val l = LUM_R * rr + LUM_G * gg + LUM_B * bb
            // Above display white (recovered highlights) keep counting up, so a negative
            // Highlights slider can pull that detail back into range.
            val yd = if (l <= 1f) ColorScience.encode(l) else 1f + 0.5f * ln(l) / LN2
            val ys = min(yd, 1f)
            val k = exp(plan.shadows * (1f - ys) * (1f - ys) + plan.highlights * yd * yd)
            rr *= k; gg *= k; bb *= k
        }
        // Soft shoulder: compress the top of the range instead of clipping it, scaling all
        // three channels together so bright colours keep their hue...
        val mx = max(rr, max(gg, bb))
        if (mx > RenderPlan.SHOULDER) {
            val s = RenderPlan.SHOULDER
            val f = s + (1f - s) * (1f - exp(-(mx - s) / (1f - s)))
            val k = f / mx
            rr *= k; gg *= k; bb *= k
            // ...and, like film, let colours that went far past white fade towards white
            // instead of turning into pale pastels (overexposed beige must not read as pink).
            val t = ((mx - s) / (RenderPlan.DESAT_END - s)).coerceIn(0f, 1f)
            val d = t * t * (3f - 2f * t) * RenderPlan.DESAT_MAX
            val l = LUM_R * rr + LUM_G * gg + LUM_B * bb
            rr += (l - rr) * d; gg += (l - gg) * d; bb += (l - bb) * d
        }
        val lut = plan.curve
        var dr = lut[(ColorScience.encode(rr) * 4096f).toInt()]
        var dg = lut[(ColorScience.encode(gg) * 4096f).toInt()]
        var db = lut[(ColorScience.encode(bb) * 4096f).toInt()]
        if (plan.saturationOn) {
            val yv = LUM_R * dr + LUM_G * dg + LUM_B * db
            val sp = max(dr, max(dg, db)) - min(dr, min(dg, db))
            val k = (1f + plan.vibrance * (1f - sp)) * (1f + plan.saturation)
            dr = clamp01(yv + (dr - yv) * k)
            dg = clamp01(yv + (dg - yv) * k)
            db = clamp01(yv + (db - yv) * k)
        }
        val look = plan.look
        if (plan.lookOn && look != null) {
            look.sample(dr, dg, db, scratch)
            val a = plan.lookAmount
            dr += (scratch[0] - dr) * a
            dg += (scratch[1] - dg) * a
            db += (scratch[2] - db) * a
        }
        out[0] = dr; out[1] = dg; out[2] = db
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
}
