package com.verdictzero.darkhall.core.develop

import java.util.zip.Inflater

/**
 * A 3D colour lookup table on sRGB-encoded values: [size]³ entries, 8-bit RGB, red varying
 * fastest, then green, then blue (the Hald order). Sampled with trilinear interpolation; the
 * GPU shader performs exactly the same arithmetic.
 */
class Lut(val size: Int, val rgb: ByteArray) {
    init {
        require(size in 2..64 && rgb.size == size * size * size * 3) { "bad LUT: size $size, ${rgb.size} bytes" }
    }

    private val table = FloatArray(rgb.size) { (rgb[it].toInt() and 0xFF) / 255f }

    /** Looks up display-encoded (r, g, b) in [0, 1]; writes the result to [out]. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        val pr = r * n1; val pg = g * n1; val pb = b * n1
        val ir = pr.toInt().coerceIn(0, n1 - 1)
        val ig = pg.toInt().coerceIn(0, n1 - 1)
        val ib = pb.toInt().coerceIn(0, n1 - 1)
        val fr = pr - ir; val fg = pg - ig; val fb = pb - ib
        val t = table
        val s = size
        val i000 = ((ib * s + ig) * s + ir) * 3
        val i001 = i000 + 3
        val i010 = i000 + s * 3
        val i011 = i010 + 3
        val i100 = i000 + s * s * 3
        val i101 = i100 + 3
        val i110 = i100 + s * 3
        val i111 = i110 + 3
        for (c in 0 until 3) {
            val c00 = t[i000 + c] * (1f - fr) + t[i001 + c] * fr
            val c01 = t[i010 + c] * (1f - fr) + t[i011 + c] * fr
            val c10 = t[i100 + c] * (1f - fr) + t[i101 + c] * fr
            val c11 = t[i110 + c] * (1f - fr) + t[i111 + c] * fr
            val c0 = c00 * (1f - fg) + c01 * fg
            val c1 = c10 * (1f - fg) + c11 * fg
            out[c] = c0 * (1f - fb) + c1 * fb
        }
    }

    companion object {
        /**
         * Decodes the .dlut format written by tools/looks/build_looks.py: "DLUT", version 1,
         * size, 2 reserved bytes, then zlib-compressed channel-planar residuals of the 3D
         * Lorenzo predictor (mod 256), loop order blue, green, red.
         */
        fun decode(bytes: ByteArray): Lut {
            require(bytes.size > 8 && bytes[0] == 'D'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                bytes[2] == 'U'.code.toByte() && bytes[3] == 'T'.code.toByte()) { "not a .dlut file" }
            require(bytes[4].toInt() == 1) { "unsupported .dlut version ${bytes[4]}" }
            val n = bytes[5].toInt() and 0xFF
            val count = n * n * n
            val inflater = Inflater()
            inflater.setInput(bytes, 8, bytes.size - 8)
            val residual = ByteArray(count * 3)
            var off = 0
            while (off < residual.size) {
                val got = inflater.inflate(residual, off, residual.size - off)
                if (got == 0) {
                    require(!inflater.needsInput() && !inflater.finished()) { "truncated .dlut" }
                }
                off += got
            }
            inflater.end()
            val rgb = ByteArray(count * 3)
            val plane = IntArray(count)
            for (c in 0 until 3) {
                val base = c * count
                var i = 0
                for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                    var p = 0
                    if (r > 0) p += plane[i - 1]
                    if (g > 0) p += plane[i - n]
                    if (b > 0) p += plane[i - n * n]
                    if (r > 0 && g > 0) p -= plane[i - n - 1]
                    if (r > 0 && b > 0) p -= plane[i - n * n - 1]
                    if (g > 0 && b > 0) p -= plane[i - n * n - n]
                    if (r > 0 && g > 0 && b > 0) p += plane[i - n * n - n - 1]
                    val v = ((residual[base + i].toInt() and 0xFF) + p) and 0xFF
                    plane[i] = v
                    rgb[i * 3 + c] = v.toByte()
                    i++
                }
            }
            return Lut(n, rgb)
        }

        /** Identity table (useful for tests). */
        fun identity(size: Int): Lut {
            val rgb = ByteArray(size * size * size * 3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                rgb[i++] = Math.round(r * 255f / (size - 1)).toByte()
                rgb[i++] = Math.round(g * 255f / (size - 1)).toByte()
                rgb[i++] = Math.round(b * 255f / (size - 1)).toByte()
            }
            return Lut(size, rgb)
        }

    }
}

/** Resolves a look id to its table; implemented by the app from its bundled assets. */
fun interface LookSource {
    fun lut(id: String): Lut?

    companion object {
        val NONE = LookSource { null }
    }
}
