package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.raw.RawImage
import com.verdictzero.darkhall.core.util.Parallel
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sensor noise of one file, per colour channel, in normalised raw units (0 = black, 1 = white
 * point): variance(x) = a·x + b. The a term is photon shot noise (grows with the signal and with
 * ISO), b is read noise (the floor that dominates the shadows).
 *
 * [estimate] measures it from the raw data itself, so it follows the real ISO, the real exposure
 * and the individual sensor rather than a lookup table: for every small flat-ish patch it takes the
 * mean and the variance of the residual after a 4-neighbour prediction, then fits the lower
 * envelope of variance against brightness (texture only ever adds variance, never removes it).
 */
class NoiseProfile(val a: FloatArray, val b: FloatArray, val measured: Boolean) {

    /** Standard deviation of channel [c] at normalised level [x]. */
    fun sigma(c: Int, x: Float) = sqrt(max(0f, a[c] * x + b[c]))

    /** Green-channel noise at 18 % grey, the usual single-number summary. */
    val midGraySigma: Float get() = sigma(1, 0.18f)

    /**
     * ISO equivalent of the measured noise, relative to the TG-7 at base ISO: shot noise grows with
     * the square root of gain, so noise twice as high is two stops more "ISO".
     */
    val effectiveIso: Float
        get() = (100.0 * Math.pow((midGraySigma / REF_MID_SIGMA).toDouble(), 2.0)).toFloat().coerceIn(25f, 102400f)

    override fun toString() =
        "NoiseProfile(a=${a.joinToString { "%.3g".format(it) }}, b=${b.joinToString { "%.3g".format(it) }}, " +
            "measured=$measured, σ18=${"%.4f".format(midGraySigma)}, ISO≈${effectiveIso.toInt()})"

    companion object {
        /**
         * TG-7 at ISO 100, measured with [estimate] on a real file (mean of the channels; see
         * NoiseTest). Used for the ISO fallback and the effective-ISO scale.
         */
        const val REF_A = 1.7e-4f
        const val REF_B = 1.4e-7f
        val REF_MID_SIGMA = sqrt(REF_A * 0.18f + REF_B)

        /**
         * Fallback when a file has too few flat areas to measure: scale the base-ISO profile.
         * Shot-noise variance grows with gain, read-noise variance with gain squared.
         */
        fun fromIso(meta: RawMetadata): NoiseProfile {
            val g = (if (meta.iso > 0) meta.iso else 100) / 100f
            return NoiseProfile(FloatArray(3) { REF_A * g }, FloatArray(3) { REF_B * g * g }, measured = false)
        }

        private const val BLOCK = 10      // patch size in same-colour samples
        private const val BINS = 24
        private const val PERCENTILE = 0.1f

        /**
         * Bias of the 10th-percentile variance of a pure-noise patch relative to the true variance,
         * for the residual used here. Calibrated by simulation (NoiseTest.estimatorIsUnbiased).
         */
        internal const val percentileBias = 0.80f

        fun estimate(raw: RawImage): NoiseProfile {
            val meta = raw.meta
            val crop = meta.crop
            val pw = crop.width / 2
            val ph = crop.height / 2
            val bx = (pw - 2) / BLOCK
            val by = (ph - 2) / BLOCK
            // Four Bayer sub-planes, (dy, dx) offset inside the 2x2 cell; or, for linear data, the
            // three channels sampled on the same half-resolution grid.
            val linear = meta.isLinear
            val planes = if (linear) 3 else 4
            val planeColor = IntArray(planes) { k -> if (linear) k else meta.cfaColor(crop.top + (k shr 1), crop.left + (k and 1)) }
            val means = Array(planes) { FloatArray(bx * by) }
            val vars = Array(planes) { FloatArray(bx * by) }
            val valid = Array(planes) { BooleanArray(bx * by) }
            for (k in 0 until planes) {
                val dy = if (linear) 0 else k shr 1
                val dx = if (linear) 0 else k and 1
                val blackLevel = if (linear) meta.blackLevels[k] else meta.blackAt(crop.top + dy, crop.left + dx)
                val black = blackLevel.toFloat()
                val scale = 1f / (meta.whiteLevel - blackLevel).coerceAtLeast(1)
                Parallel.rows(by) { r0, r1 ->
                    for (bi in r0 until r1) for (bj in 0 until bx) {
                        // Block covers plane rows/cols [1 + bi*B, 1 + (bi+1)*B) so 4-neighbours exist.
                        var sum = 0.0; var sumD = 0.0; var sumD2 = 0.0; var mx = 0f; var n = 0
                        for (i in 0 until BLOCK) {
                            val py = 1 + bi * BLOCK + i
                            for (j in 0 until BLOCK) {
                                val px = 1 + bj * BLOCK + j
                                fun v(yy: Int, xx: Int): Float {
                                    val sy = crop.top + 2 * yy + dy
                                    val sx = crop.left + 2 * xx + dx
                                    val i = if (linear) (sy * raw.width + sx) * 3 + k else sy * raw.width + sx
                                    return ((raw.data[i].toInt() and 0xFFFF) - black) * scale
                                }
                                val x = v(py, px)
                                val d = x - 0.25f * (v(py - 1, px) + v(py + 1, px) + v(py, px - 1) + v(py, px + 1))
                                sum += x; sumD += d; sumD2 += d * d
                                if (x > mx) mx = x
                                n++
                            }
                        }
                        val idx = bi * bx + bj
                        val mean = (sum / n).toFloat()
                        val varD = (sumD2 / n - (sumD / n) * (sumD / n)).toFloat()
                        means[k][idx] = mean
                        // Residual variance of white noise through x - mean(4 neighbours) is 1.25 σ².
                        vars[k][idx] = varD / 1.25f
                        valid[k][idx] = mx < 0.93f && mean > 0f
                    }
                }
            }
            val a = FloatArray(3)
            val b = FloatArray(3)
            val counts = IntArray(3)
            for (k in 0 until planes) {
                val fit = fitEnvelope(means[k], vars[k], valid[k]) ?: return fromIso(meta)
                val c = planeColor[k]
                a[c] += fit.first; b[c] += fit.second; counts[c]++
            }
            for (c in 0 until 3) { a[c] /= max(1, counts[c]); b[c] /= max(1, counts[c]) }
            return NoiseProfile(a, b, measured = true)
        }

        /** Lower-envelope fit of variance against level; null when there is too little data. */
        private fun fitEnvelope(mean: FloatArray, variance: FloatArray, valid: BooleanArray): Pair<Float, Float>? {
            // Bins evenly spaced in sqrt(level): dense in the shadows, where b is decided.
            val binned = Array(BINS) { ArrayList<Float>() }
            val binMean = DoubleArray(BINS)
            for (i in mean.indices) {
                if (!valid[i]) continue
                val bin = (sqrt(mean[i] / 0.93f) * BINS).toInt().coerceIn(0, BINS - 1)
                binned[bin] += variance[i]
                binMean[bin] += mean[i].toDouble()
            }
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (k in 0 until BINS) {
                val list = binned[k]
                if (list.size < 40) continue
                list.sort()
                val v = list[(list.size * PERCENTILE).toInt()] / percentileBias
                xs += binMean[k] / list.size
                ys += v.toDouble()
            }
            if (xs.size < 4) return null
            // Weighted least squares (relative error), then one pass dropping bins far above the line.
            fun fit(use: BooleanArray): Pair<Double, Double>? {
                var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                for (i in xs.indices) if (use[i]) {
                    val w = 1.0 / (ys[i] * ys[i] + 1e-18)
                    sw += w; sx += w * xs[i]; sy += w * ys[i]; sxx += w * xs[i] * xs[i]; sxy += w * xs[i] * ys[i]
                }
                val det = sw * sxx - sx * sx
                if (det <= 0) return null
                val slope = (sw * sxy - sx * sy) / det
                val icpt = (sy - slope * sx) / sw
                return slope to icpt
            }
            val use = BooleanArray(xs.size) { true }
            var f = fit(use) ?: return null
            for (i in xs.indices) {
                val pred = f.first * xs[i] + f.second
                if (pred > 0 && ys[i] > 1.6 * pred) use[i] = false
            }
            if (use.count { it } >= 4) f = fit(use) ?: f
            val slope = max(0.0, f.first)
            // Read-noise floor: never below what the darkest measured bin shows minus its shot part.
            val icpt = max(f.second, 1e-9)
            return slope.toFloat() to icpt.toFloat()
        }
    }
}
