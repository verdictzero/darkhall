package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.develop.ColorScience.LUM_B
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_G
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_R
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
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
        if (plan.mixerOn) {
            mix(plan.mix, dr, dg, db, scratch)
            dr = scratch[0]; dg = scratch[1]; db = scratch[2]
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

    /**
     * The colour mixer on display-encoded [r], [g], [b] into [out]: the pixel's HSV hue picks
     * its place between the two nearest band centres (smoothly), and that blend of the two
     * bands' settings turns its hue about the grey axis, scales its saturation and brightens or
     * darkens it in proportion to how saturated it is, so greys never change. The GPU's TONE
     * shader does exactly the same.
     */
    fun mix(m: FloatArray, r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val c = mx - mn
        if (c < 1e-5f) { out[0] = r; out[1] = g; out[2] = b; return }
        var h = 60f * when {
            mx == r -> (g - b) / c
            mx == g -> (b - r) / c + 2f
            else -> (r - g) / c + 4f
        }
        if (h < 0f) h += 360f
        val centres = RenderPlan.MIX_CENTRES
        var i = 0
        while (i < 7 && h >= centres[i + 1]) i++
        var t = (h - centres[i]) / (centres[i + 1] - centres[i])
        t = t * t * (3f - 2f * t)
        val j = (i + 1) % 8
        val turn = m[3 * i] * (1f - t) + m[3 * j] * t
        val sat = m[3 * i + 1] * (1f - t) + m[3 * j + 1] * t
        val lum = m[3 * i + 2] * (1f - t) + m[3 * j + 2] * t
        var rr = r; var gg = g; var bb = b
        if (turn != 0f) {
            // Rotation about the grey axis: red turns towards yellow for a positive angle.
            val co = cos(turn); val si = sin(turn) * INV_SQRT3
            val a = co + (1f - co) / 3f
            val p = (1f - co) / 3f - si
            val q = (1f - co) / 3f + si
            rr = a * r + p * g + q * b
            gg = q * r + a * g + p * b
            bb = p * r + q * g + a * b
        }
        if (sat != 0f) {
            val y = LUM_R * rr + LUM_G * gg + LUM_B * bb
            val k = 1f + sat
            rr = y + (rr - y) * k; gg = y + (gg - y) * k; bb = y + (bb - y) * k
        }
        if (lum != 0f) {
            val k = exp2(lum * min(1f, 2f * c / mx))
            rr *= k; gg *= k; bb *= k
        }
        out[0] = clamp01(rr); out[1] = clamp01(gg); out[2] = clamp01(bb)
    }

    private val INV_SQRT3 = (1.0 / kotlin.math.sqrt(3.0)).toFloat()

    private fun exp2(v: Float) = exp(v * LN2)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
}
