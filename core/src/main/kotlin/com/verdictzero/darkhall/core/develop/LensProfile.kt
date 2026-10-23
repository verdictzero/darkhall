package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.raw.RawMetadata
import kotlin.math.sqrt

/**
 * Distortion and lateral chromatic aberration correction, as a radial scale per colour:
 * the output pixel at radius r (1 = half the frame diagonal) samples the image at r * f_c(r).
 *
 * Two sources:
 *  - the built-in calibration of the 4.5-18 mm f/2.0-4.9 zoom shared by the Tough TG-5, TG-6
 *    and TG-7, from the lensfun database ("Olympus Tough TG-5" in compact-olympus.xml, CC BY-SA
 *    3.0, https://lensfun.github.io), measured on a TG-5. Model (lensfun "ptlens"): for a
 *    corrected radius ru (1 = half the short image side) the lens put the light at
 *    ru * (a ru^3 + b ru^2 + c ru + 1 - a - b - c); TCA scales red and blue by vr and vb.
 *  - the WarpRectilinear opcode a DNG carries (phones, drones, Leica), per-plane polynomials
 *    k0 + k1 r^2 + k2 r^4 + k3 r^6 in units of the distance from the centre to a corner of
 *    the camera's active area.
 */
class LensProfile private constructor(
    val a: Float, val b: Float, val c: Float, val vr: Float, val vb: Float,
    /** DNG warp: [channel][k0..k3], already rescaled to this frame's half diagonal; null for ptlens. */
    private val dng: Array<FloatArray>?,
) {
    /**
     * Coefficients of f_c(r) = sum p[i][c] r^i (i = 0..6, c = R, G, B) for a frame whose half
     * diagonal is [shortRatio] times its half short side.
     */
    fun polynomial(shortRatio: Float): Array<FloatArray> {
        val p = Array(7) { FloatArray(3) }
        if (dng != null) {
            for (ch in 0 until 3) {
                val k = dng[ch]
                p[0][ch] = k[0]; p[2][ch] = k[1]; p[4][ch] = k[2]; p[6][ch] = k[3]
            }
        } else {
            val s = shortRatio
            val g = floatArrayOf(1f - a - b - c, c * s, b * s * s, a * s * s * s)
            val scale = floatArrayOf(vr, 1f, vb)
            for (i in 0 until 4) for (ch in 0 until 3) p[i][ch] = g[i] * scale[ch]
        }
        return p
    }

    companion object {
        private val MODELS = setOf("TG-5", "TG-6", "TG-7")

        // focal, a, b, c
        private val DISTORTION = arrayOf(
            doubleArrayOf(4.5, 0.02587, -0.14809, 0.118),
            doubleArrayOf(9.0, 0.00722, -0.02867, 0.05229),
            doubleArrayOf(13.5, 0.00581, -0.00942, 0.03504),
            doubleArrayOf(15.4, 0.00277, -0.00923, 0.05269),
            doubleArrayOf(18.0, 0.00743, -0.01308, 0.03623),
        )

        // focal, vr, vb
        private val TCA = arrayOf(
            doubleArrayOf(4.5, 1.0003588, 1.0000108),
            doubleArrayOf(9.0, 1.0000205, 1.0001196),
            doubleArrayOf(13.5, 0.9999607, 1.0001646),
            doubleArrayOf(15.4, 0.9999136, 1.0000947),
            doubleArrayOf(18.0, 0.9999040, 1.0001551),
        )

        fun forFile(meta: RawMetadata): LensProfile? {
            meta.lensWarp?.let { w ->
                // Rescale from the active area's centre-to-corner distance to the cropped frame's.
                val t = (sqrt(meta.crop.width.toDouble() * meta.crop.width + meta.crop.height.toDouble() * meta.crop.height) /
                    sqrt(w.refWidth.toDouble() * w.refWidth + w.refHeight.toDouble() * w.refHeight)).toFloat()
                val k = Array(3) { ch ->
                    val pl = w.planes[minOf(ch, w.planes.size - 1)]
                    floatArrayOf(pl[0].toFloat(), (pl[1] * t * t).toFloat(), (pl[2] * Math.pow(t.toDouble(), 4.0)).toFloat(), (pl[3] * Math.pow(t.toDouble(), 6.0)).toFloat())
                }
                return LensProfile(0f, 0f, 0f, 1f, 1f, k)
            }
            if (meta.model.trim().uppercase() !in MODELS) return null
            val f = meta.focalLength
            if (f <= 0.0) return null
            return forFocal(f)
        }

        fun forFocal(focal: Double): LensProfile {
            val d = interpolate(DISTORTION, focal)
            val t = interpolate(TCA, focal)
            return LensProfile(d[0].toFloat(), d[1].toFloat(), d[2].toFloat(), t[0].toFloat(), t[1].toFloat(), null)
        }

        /** Linear interpolation in focal length, clamped to the calibrated range. */
        private fun interpolate(table: Array<DoubleArray>, focal: Double): DoubleArray {
            val n = table[0].size - 1
            if (focal <= table.first()[0]) return table.first().copyOfRange(1, n + 1)
            if (focal >= table.last()[0]) return table.last().copyOfRange(1, n + 1)
            for (i in 0 until table.size - 1) {
                val lo = table[i]; val hi = table[i + 1]
                if (focal <= hi[0]) {
                    val t = (focal - lo[0]) / (hi[0] - lo[0])
                    return DoubleArray(n) { k -> lo[k + 1] + (hi[k + 1] - lo[k + 1]) * t }
                }
            }
            return table.last().copyOfRange(1, n + 1)
        }
    }
}
