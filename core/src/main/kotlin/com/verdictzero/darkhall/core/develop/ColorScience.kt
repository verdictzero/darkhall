package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.raw.RawMetadata
import kotlin.math.pow

/** Colour matrices and transfer functions. */
object ColorScience {

    /**
     * Adobe/LibRaw XYZ-to-camera matrix for the Tough TG-5/TG-6/TG-7 sensor
     * (12 MP 1/2.33" BSI CMOS, shared across the three bodies). The last resort when
     * neither the file nor the camera table provides a matrix.
     */
    val TG_XYZ_TO_CAM = floatArrayOf(
        1.0899f, -0.3833f, -0.1082f,
        -0.2112f, 1.0736f, 0.1575f,
        -0.0267f, 0.1452f, 0.5269f,
    )

    /** sRGB (linear, D65) to XYZ. */
    private val SRGB_TO_XYZ = floatArrayOf(
        0.412453f, 0.357580f, 0.180423f,
        0.212671f, 0.715160f, 0.072169f,
        0.019334f, 0.119193f, 0.950227f,
    )

    const val LUM_R = 0.2126f
    const val LUM_G = 0.7152f
    const val LUM_B = 0.0722f

    /**
     * Returns the 3x3 row-major matrix mapping white-balanced camera RGB to linear sRGB.
     * Prefers the matrix an Olympus camera writes into its MakerNote (it is what the camera
     * itself uses for its JPEGs, so colours match its own rendering); then the file's or the
     * camera table's Adobe matrix, following dcraw's derivation.
     */
    fun cameraToSrgb(meta: RawMetadata): FloatArray {
        meta.cameraMatrix?.let { return it.copyOf() }
        return fromXyzToCam(meta.xyzToCam ?: TG_XYZ_TO_CAM)
    }

    fun fromXyzToCam(xyzToCam: FloatArray): FloatArray {
        // cam_rgb = xyz_to_cam * srgb_to_xyz
        val camRgb = multiply(xyzToCam, SRGB_TO_XYZ)
        // Normalise rows so that camera white (1,1,1) maps to sRGB white.
        for (i in 0 until 3) {
            val sum = camRgb[i * 3] + camRgb[i * 3 + 1] + camRgb[i * 3 + 2]
            if (sum != 0f) for (j in 0 until 3) camRgb[i * 3 + j] /= sum
        }
        return invert(camRgb) ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
            out[i * 3 + j] = s
        }
        return out
    }

    fun invert(m: FloatArray): FloatArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12f) return null
        val inv = 1f / det
        return floatArrayOf(
            (e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
            (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
            (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv,
        )
    }

    fun srgbEncode(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    }

    fun srgbDecode(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    /** 65536-entry linear [0,1] -> sRGB encoded lookup. */
    val encodeLut: FloatArray by lazy { FloatArray(65536) { srgbEncode(it / 65535f) } }

    @Suppress("NOTHING_TO_INLINE")
    inline fun encode(v: Float): Float {
        val x = if (v <= 0f) 0f else if (v >= 1f) 1f else v
        return encodeLut[(x * 65535f + 0.5f).toInt()]
    }
}
