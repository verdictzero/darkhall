package com.verdictzero.darkhall.core.develop

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds how far a rendered picture is from level, from the long straight lines in it:
 * horizons, shorelines, roof and window edges, door frames, poles.
 *
 * Pixels on clean edges are found with the local structure tensor; only edges whose direction
 * is consistent across a 5x5 window count, so foliage, sand and water texture drop out. Those
 * within [RANGE] degrees of horizontal or vertical then vote, Hough-style, for the straight
 * lines they could lie on. A real line collects its votes into one narrow spot, at one angle;
 * scattered short edges never do. The longest line wins (upright lines near the middle of the
 * frame are favoured, since perspective tilts the ones towards the sides), and when no line
 * is long enough and sharply defined, nothing is returned rather than a guess.
 *
 * Angles are in the picture's own coordinates (y down): a positive tilt means lines run
 * down to the right (or, for verticals, lean right at the bottom). Adding it to Straighten
 * levels them, because the geometry stage rotates the source by the Straighten angle.
 */
object AutoStraighten {

    /** Largest tilt considered, in degrees; a little more than the Straighten slider's range. */
    const val RANGE = 11f
    /** Angle step of the line search, degrees. */
    private const val STEP = 0.1f
    /** How far from its own measured direction an edge pixel votes, degrees. */
    private const val SPREAD = 1.5f
    /** Line offset resolution, pixels of the working image. */
    private const val RHO_BIN = 2f
    private const val MAX_SIDE = 1024

    /**
     * @property degrees tilt of the picture's lines; add it to Straighten to level them.
     * @property fromVerticals true when an upright line decided it rather than a horizontal one.
     * @property length the line's length as a share of the frame's width (or height, for a
     *   vertical), counting only the stretches where it shows as a clean edge.
     * @property prominence how much the line stands out: its strength against the typical
     *   strongest line at the other angles.
     */
    class Result(val degrees: Float, val fromVerticals: Boolean, val length: Float, val prominence: Float) {
        val confident: Boolean get() = length >= MIN_LENGTH && prominence >= MIN_PROMINENCE
    }

    private const val MIN_LENGTH = 0.35f
    private const val MIN_PROMINENCE = 2.5f
    /** Verticals converge under perspective, so they count a little less than horizontals. */
    private const val VERTICAL_WEIGHT = 0.8f
    /** Votes a clean edge leaves per pixel of its length (it shows as a band a few pixels wide). */
    private const val BAND = 4f
    /** Squared gain over the Sobel units the thresholds are in: Scharr is 4x Sobel, and the two 3x3 box blurs sum rather than average (9x each). */
    private const val SCHARR_GAIN = 16f * 81f * 81f

    /** Measures packed ARGB [argb] (w x h); null when the picture has no clear lines. */
    fun detect(argb: IntArray, w: Int, h: Int): Result? = measure(argb, w, h)?.takeIf { it.confident }

    /** The best candidate whether or not it is clear enough to act on; null when there is none at all. */
    internal fun measure(argb: IntArray, w: Int, h: Int): Result? {
        val step = max(1, ceil(max(w, h).toFloat() / MAX_SIDE).toInt())
        val sw = w / step
        val sh = h / step
        if (sw < 32 || sh < 32) return null
        val lum = luminance(argb, w, step, sw, sh)

        // A light blur, then Scharr gradients: both keep the measured direction of sharp edges
        // from being pulled towards the axes, which plain Sobel does by a few percent.
        boxBlur(lum, sw, sh, 1)
        boxBlur(lum, sw, sh, 1)
        val n = sw * sh
        val jxx = FloatArray(n); val jxy = FloatArray(n); val jyy = FloatArray(n)
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val i = y * sw + x
                val gx = 3 * (lum[i - sw + 1] - lum[i - sw - 1]) + 10 * (lum[i + 1] - lum[i - 1]) + 3 * (lum[i + sw + 1] - lum[i + sw - 1])
                val gy = 3 * (lum[i + sw - 1] - lum[i - sw - 1]) + 10 * (lum[i + sw] - lum[i - sw]) + 3 * (lum[i + sw + 1] - lum[i - sw + 1])
                jxx[i] = gx * gx; jxy[i] = gx * gy; jyy[i] = gy * gy
            }
        }
        boxBlur(jxx, sw, sh, 2); boxBlur(jxy, sw, sh, 2); boxBlur(jyy, sw, sh, 2)

        // Line search tables: angle j is -RANGE + j * STEP; family 0 horizontal, 1 vertical.
        val angles = (2 * RANGE / STEP).roundToInt() + 1
        val cosA = FloatArray(angles) { cos((-RANGE + it * STEP) * PI.toFloat() / 180f) }
        val sinA = FloatArray(angles) { sin((-RANGE + it * STEP) * PI.toFloat() / 180f) }
        val diag = sqrt((sw * sw + sh * sh).toFloat())
        val rhos = (2 * diag / RHO_BIN).toInt() + 3
        val acc = FloatArray(2 * angles * rhos)
        val spread = (SPREAD / STEP).roundToInt()

        // Keeps clear of the frame's own edges (and any odd row or column of pixels at them).
        val margin = 8
        var voters = 0
        for (y in margin until sh - margin) {
            for (x in margin until sw - margin) {
                val i = y * sw + x
                val a = jxx[i]; val b = jxy[i]; val c = jyy[i]
                val trace = a + c
                // About a 6-level step in 8-bit luminance; below that it's noise.
                if (trace < 600f * SCHARR_GAIN) continue
                val coherence = sqrt((a - c) * (a - c) + 4 * b * b) / trace
                if (coherence < 0.75f) continue
                // Gradient direction, then the edge (line) direction at right angles to it.
                var line = 0.5f * atan2(2 * b, a - c) * 180f / PI.toFloat() + 90f
                if (line > 90f) line -= 180f
                if (line <= -90f) line += 180f
                val family: Int
                val tilt: Float
                when {
                    abs(line) <= RANGE + SPREAD -> { family = 0; tilt = line }
                    abs(line) >= 90f - RANGE - SPREAD -> { family = 1; tilt = if (line > 0) line - 90f else line + 90f }
                    else -> continue
                }
                voters++
                val weight = coherence * coherence
                val centre = ((tilt + RANGE) / STEP).roundToInt()
                val base = family * angles
                for (j in max(0, centre - spread)..min(angles - 1, centre + spread)) {
                    // Offset of the line through (x, y) at this angle.
                    val rho = if (family == 0) y * cosA[j] - x * sinA[j] else x * cosA[j] + y * sinA[j]
                    acc[(base + j) * rhos + ((rho + diag) / RHO_BIN).toInt() + 1] += weight
                }
            }
        }
        if (voters == 0) return null

        // Strength of the best line at each angle: the strongest offset (three bins wide, to
        // hold an edge band), verticals weighted towards the middle of the frame.
        val strength = Array(2) { FloatArray(angles) }
        val where = Array(2) { IntArray(angles) }
        val span = floatArrayOf(sw.toFloat(), sh.toFloat())
        for (family in 0..1) {
            for (j in 0 until angles) {
                val o = (family * angles + j) * rhos
                var best = 0f
                for (r in 1 until rhos - 1) {
                    var v = acc[o + r - 1] + acc[o + r] + acc[o + r + 1]
                    if (v <= best) continue
                    if (family == 1) {
                        // For a near-vertical line the offset is about its x position.
                        val x = (r - 1 + 0.5f) * RHO_BIN - diag
                        v *= 1f - 0.5f * min(1f, abs(x - sw / 2f) / (sw / 2f))
                        if (v <= best) continue
                    }
                    best = v
                    where[family][j] = r
                }
                strength[family][j] = best / (BAND * span[family])
            }
        }

        // For each family, its longest line within the slider's range and how much that line
        // stands out from the best line at the other angles (texture and perspective give many
        // middling candidates; a real horizon or upright stands alone). The family whose line
        // is longest and clearest wins.
        val limit = ((RANGE - SPREAD) / STEP).roundToInt()
        val mid = angles / 2
        var bestFamily = -1; var bestJ = mid; var bestScore = 0f; var length = 0f; var prominence = 0f
        for (family in 0..1) {
            val st = strength[family]
            var j0 = mid
            for (j in mid - limit..mid + limit) if (st[j] > st[j0]) j0 = j
            if (st[j0] <= 0f) continue
            val others = (mid - limit..mid + limit).filter { abs(it - j0) * STEP >= 1.5f }.map { st[it] }.sorted()
            val prom = st[j0] / max(others[others.size / 2], st[j0] * 1e-3f)
            val score = st[j0] * (if (family == 0) 1f else VERTICAL_WEIGHT) * sqrt(min(prom, 16f))
            if (score > bestScore) { bestScore = score; bestFamily = family; bestJ = j0; length = st[j0]; prominence = prom }
        }
        if (bestFamily < 0) return null

        // Refine the angle: the average edge direction of the pixels along the winning line.
        // Each is measured to a few hundredths of a degree on a clean edge, and a long line
        // has thousands of them.
        val rhoStar = (where[bestFamily][bestJ] - 1 + 0.5f) * RHO_BIN - diag
        val c0 = cosA[bestJ]; val s0 = sinA[bestJ]
        val tilt0 = -RANGE + bestJ * STEP
        var sumW = 0.0; var sumT = 0.0
        for (y in margin until sh - margin) {
            for (x in margin until sw - margin) {
                val rho = if (bestFamily == 0) y * c0 - x * s0 else x * c0 + y * s0
                if (abs(rho - rhoStar) > 1.5f * RHO_BIN) continue
                val i = y * sw + x
                val a = jxx[i]; val b = jxy[i]; val c = jyy[i]
                val trace = a + c
                if (trace < 600f * SCHARR_GAIN) continue
                val coherence = sqrt((a - c) * (a - c) + 4 * b * b) / trace
                if (coherence < 0.75f) continue
                var line = 0.5f * atan2(2 * b, a - c) * 180f / PI.toFloat() + 90f
                if (bestFamily == 1) line -= 90f
                while (line > 90f) line -= 180f
                while (line <= -90f) line += 180f
                if (abs(line - tilt0) > SPREAD) continue
                val wt = (coherence * coherence).toDouble()
                sumW += wt; sumT += wt * line
            }
        }
        var degrees = tilt0
        if (sumW > 0) degrees = (sumT / sumW).toFloat()
        return Result(degrees, bestFamily == 1, length, prominence)
    }

    private fun luminance(argb: IntArray, w: Int, step: Int, sw: Int, sh: Int): FloatArray {
        val out = FloatArray(sw * sh)
        val norm = 1f / (step * step)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                var s = 0f
                for (dy in 0 until step) {
                    var i = (y * step + dy) * w + x * step
                    for (dx in 0 until step) {
                        val p = argb[i++]
                        s += 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                    }
                }
                out[y * sw + x] = s * norm
            }
        }
        return out
    }

    /** In-place (2r+1)^2 box average, separable, clamped at the edges. */
    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(max(w, h))
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                var s = 0f
                for (d in -r..r) s += a[o + (x + d).coerceIn(0, w - 1)]
                tmp[x] = s
            }
            for (x in 0 until w) a[o + x] = tmp[x]
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var s = 0f
                for (d in -r..r) s += a[(y + d).coerceIn(0, h - 1) * w + x]
                tmp[y] = s
            }
            for (y in 0 until h) a[y * w + x] = tmp[y]
        }
    }
}
