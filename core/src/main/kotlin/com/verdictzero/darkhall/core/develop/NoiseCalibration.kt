package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.develop.ColorScience.LUM_B
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_G
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_R
import com.verdictzero.darkhall.core.raw.Crop
import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.raw.RawImage
import java.util.Random
import kotlin.math.sqrt

/**
 * Measures how much luminance noise survives demosaicing for one file's colour settings.
 *
 * Demosaicing mixes neighbouring photosites and the colour matrix mixes channels with
 * negative coefficients, so the luminance noise of a rendered pixel is not simply the
 * per-sample model. Instead of a constant (which differs between, say, the TG-6 and TG-7
 * matrices by 20 %), a small flat patch of synthetic sensor noise is pushed through the real
 * demosaic with this file's matrix and white balance, and the variance ratio is read off.
 */
object NoiseCalibration {
    private const val SIZE = 192
    private const val LEVEL = 0.2
    private const val A = 1e-3
    private const val B = 1e-5

    /** Returns [full-resolution factor, half-size superpixel factor]. */
    fun demosaicFactors(meta: RawMetadata, matrix: FloatArray): FloatArray {
        // Linear DNGs are not demosaiced here: full size keeps the per-sample noise and the
        // half-size path averages four independent pixels.
        if (meta.isLinear) return floatArrayOf(1f, 0.25f)
        val pad = 8
        val w = SIZE + 2 * pad
        val fake = meta.copy(rawWidth = w, rawHeight = w, crop = Crop(pad, pad, SIZE, SIZE))
        val data = ShortArray(w * w)
        val rnd = Random(42)
        val sd = sqrt(A * LEVEL + B)
        for (y in 0 until w) for (x in 0 until w) {
            val black = fake.blackAt(y, x)
            val span = (fake.whiteLevel - black).toDouble()
            val v = LEVEL + rnd.nextGaussian() * sd
            data[y * w + x] = (black + v * span).toInt().coerceIn(0, 65535).toShort()
        }
        val raw = RawImage(w, w, data, fake)
        val wb = meta.cameraWb
        var nA = 0.0; var nB = 0.0
        val beta = DoubleArray(3)
        for (c in 0 until 3) {
            beta[c] = (LUM_R * matrix[c] + LUM_G * matrix[3 + c] + LUM_B * matrix[6 + c]).toDouble()
            nA += wb[c] * beta[c] * beta[c] * A
            nB += wb[c] * wb[c] * beta[c] * beta[c] * B
        }
        fun lumVar(img: LinearImage, border: Int): Pair<Double, Double> {
            var s = 0.0; var s2 = 0.0; var n = 0
            for (y in border until img.height - border) for (x in border until img.width - border) {
                val i = y * img.width + x
                val yy = beta[0] * img.r[i] * wb[0] + beta[1] * img.g[i] * wb[1] + beta[2] * img.b[i] * wb[2]
                s += yy; s2 += yy * yy; n++
            }
            val mean = s / n
            return mean to (s2 / n - mean * mean)
        }
        val (mf, vf) = lumVar(Demosaic.malvar(raw), 4)
        val (mh, vh) = lumVar(Demosaic.superpixel(raw), 2)
        val full = (vf / (nA * mf + nB)).toFloat()
        val half = (vh / (nA * mh + nB)).toFloat()
        // Guard against a degenerate matrix; fall back to typical values.
        return floatArrayOf(
            if (full.isFinite() && full in 0.2f..2f) full else 0.89f,
            if (half.isFinite() && half in 0.1f..1.5f) half else 0.55f,
        )
    }
}
