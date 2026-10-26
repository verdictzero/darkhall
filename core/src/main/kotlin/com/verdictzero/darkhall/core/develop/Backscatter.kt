package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.develop.ColorScience.LUM_B
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_G
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_R
import com.verdictzero.darkhall.core.util.Parallel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Backscatter removal: the specks of plankton and silt a flash lights up between the camera
 * and the subject. They are small, much brighter than the water right around them, and
 * soft-edged. A morphological opening of the luminance (a minimum then a maximum filter, as
 * wide as the largest speck) gives the picture without anything that small and bright; where
 * the picture stands well above that, relative to its brightness, is a speck. Specks are
 * filled from each colour channel's own opening, the water right there without them, so the
 * fill follows the water's gradient and doesn't bleed across edges. Anything wider than the
 * window (a fish, a diver's light, coral) is left alone, and so is fine bright texture:
 * specks are scattered, so where candidates crowd together (foliage, sand, polyps) they are
 * taken for texture and kept.
 */
object Backscatter {
    /** Share of a neighbourhood covered by candidates above which they count as texture. */
    private const val CROWD_LO = 0.15f
    /** The faintest speck the slider can reach: this much brighter than the water around it. */
    private const val LO_MIN = 0.13f
    private const val CROWD_HI = 0.3f

    /**
     * Cleans linear working-space planes [r], [g], [b] ([w] x [h]) in place. [amount] 0..1
     * sets both how small and how faint a speck may be; [radius] is the largest speck's
     * radius in pixels of this frame.
     */
    fun apply(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, amount: Float, radius: Int) {
        val n = w * h
        val lum = FloatArray(n)
        Parallel.rows(h) { y0, y1 -> for (i in y0 * w until y1 * w) lum[i] = LUM_R * r[i] + LUM_G * g[i] + LUM_B * b[i] }
        val tmp = FloatArray(n)
        val opened = FloatArray(n)
        // Opening: min filter, then max filter (a min filter of the negated plane).
        Filters.minFilter(lum, opened, tmp, w, h, radius)
        for (i in 0 until n) opened[i] = -opened[i]
        val neg = FloatArray(n)
        Filters.minFilter(opened, neg, tmp, w, h, radius)
        for (i in 0 until n) opened[i] = -neg[i]

        // A speck stands out by a share of the background's brightness; fainter ones need more slider.
        val lo = 0.55f - (0.55f - LO_MIN) * amount
        val hi = lo * 2f
        val floor = 0.004f
        val mask = neg // reuse
        // Candidates at the most sensitive setting, for judging crowding the same way whatever
        // the slider says.
        val any = FloatArray(n)
        Parallel.rows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val top = lum[i] - opened[i]
                if (top <= floor) { mask[i] = 0f; any[i] = 0f; continue }
                val q = top / (opened[i] + 0.01f)
                any[i] = if (q > LO_MIN) 1f else 0f
                val t = ((q - lo) / (hi - lo)).coerceIn(0f, 1f)
                mask[i] = t * t * (3f - 2f * t)
            }
        }
        // Specks are scattered over smooth water; texture (foliage, sand, coral polyps) makes
        // candidates everywhere. Where they crowd, it is texture: leave it.
        val density = FloatArray(n)
        Filters.blur(any, density, tmp, w, h, 3 * radius, passes = 2)
        Parallel.rows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val t = ((density[i] - CROWD_LO) / (CROWD_HI - CROWD_LO)).coerceIn(0f, 1f)
                mask[i] *= 1f - t * t * (3f - 2f * t)
            }
        }
        // Grow the mask over each speck's soft halo, then soften its edge.
        val halo = max(1, (radius * 0.35f).roundToInt())
        for (i in 0 until n) mask[i] = -mask[i]
        Filters.minFilter(mask, lum, tmp, w, h, halo)
        for (i in 0 until n) lum[i] = -lum[i]
        Filters.blur(lum, mask, tmp, w, h, max(1, halo / 2), passes = 2)

        // The fill: each channel's own opening, lightly smoothed: the colour right here with
        // nothing small and bright in it, which follows edges instead of bleeding across them.
        val chan = FloatArray(n); val mn = FloatArray(n); val op = FloatArray(n)
        for (plane in arrayOf(r, g, b)) {
            Filters.minFilter(plane, mn, tmp, w, h, radius)
            for (i in 0 until n) mn[i] = -mn[i]
            Filters.minFilter(mn, op, tmp, w, h, radius)
            for (i in 0 until n) mn[i] = -op[i]
            Filters.blur(mn, chan, tmp, w, h, max(1, halo / 2), passes = 2)
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    val m = mask[i]
                    if (m < 1e-3f) continue
                    plane[i] += (kotlin.math.min(chan[i], plane[i]) - plane[i]) * m
                }
            }
        }
    }
}
