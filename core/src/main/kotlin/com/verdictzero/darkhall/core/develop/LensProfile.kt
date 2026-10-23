package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.orf.OrfMetadata

/**
 * Distortion and lateral chromatic aberration for the 4.5-18 mm f/2.0-4.9 zoom shared by the
 * Tough TG-5, TG-6 and TG-7.
 *
 * Coefficients come from the lensfun database ("Olympus Tough TG-5" in compact-olympus.xml,
 * CC BY-SA 3.0, https://lensfun.github.io), measured on a TG-5. The TG-6 and TG-7 use the
 * same lens and sensor, so the calibration carries over.
 *
 * Model (lensfun "ptlens"): for a corrected radius r (1 = half the short image side) the lens
 * put the light at r * (a r^3 + b r^2 + c r + 1 - a - b - c). TCA scales red and blue radially
 * relative to green by vr and vb.
 */
class LensProfile(val a: Float, val b: Float, val c: Float, val vr: Float, val vb: Float) {

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

        fun forFile(meta: OrfMetadata): LensProfile? {
            if (meta.model.trim().uppercase() !in MODELS) return null
            val f = meta.focalLength
            if (f <= 0.0) return null
            return forFocal(f)
        }

        fun forFocal(focal: Double): LensProfile {
            val d = interpolate(DISTORTION, focal)
            val t = interpolate(TCA, focal)
            return LensProfile(d[0].toFloat(), d[1].toFloat(), d[2].toFloat(), t[0].toFloat(), t[1].toFloat())
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
