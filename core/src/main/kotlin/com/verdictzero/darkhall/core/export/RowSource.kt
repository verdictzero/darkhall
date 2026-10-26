package com.verdictzero.darkhall.core.export

import com.verdictzero.darkhall.core.develop.Crop
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.develop.PixelRect

/**
 * A finished picture handed to an encoder one row at a time, already turned upright, so a
 * 16-bit export never needs a second full-size copy in memory.
 */
interface RowSource {
    val width: Int
    val height: Int
    /** 8 or 16. */
    val bits: Int
    /** Row [y] as interleaved RGB: 3 * [width] values, 0..255 or 0..65535. */
    fun row(y: Int, out: IntArray)
}

/**
 * Turns the camera's frame upright (EXIF orientation 3, 6 or 8; anything else stays as is):
 * for output pixel (x, y) of the upright picture, the pixel of the sensor-frame picture.
 */
class Upright(val srcWidth: Int, val srcHeight: Int, orientation: Int) {
    private val turn = when (orientation) { 3 -> 2; 6 -> 1; 8 -> 3; else -> 0 }
    val width = if (turn % 2 == 1) srcHeight else srcWidth
    val height = if (turn % 2 == 1) srcWidth else srcHeight

    fun srcX(x: Int, y: Int) = when (turn) { 1 -> y; 2 -> srcWidth - 1 - x; 3 -> srcWidth - 1 - y; else -> x }
    fun srcY(x: Int, y: Int) = when (turn) { 1 -> srcHeight - 1 - x; 2 -> srcHeight - 1 - y; 3 -> x; else -> y }
}

/** 8-bit packed 0xAARRGGBB pixels, geometry already applied. */
class ArgbRows(private val argb: IntArray, srcWidth: Int, srcHeight: Int, orientation: Int) : RowSource {
    private val up = Upright(srcWidth, srcHeight, orientation)
    private val sw = srcWidth
    override val width = up.width
    override val height = up.height
    override val bits = 8

    override fun row(y: Int, out: IntArray) {
        for (x in 0 until width) {
            val p = argb[up.srcY(x, y) * sw + up.srcX(x, y)]
            out[3 * x] = (p shr 16) and 0xFF
            out[3 * x + 1] = (p shr 8) and 0xFF
            out[3 * x + 2] = p and 0xFF
        }
    }
}

/**
 * 16-bit RGB triplets straight from the develop stage, with straighten, distortion and the
 * lens profile ([geometry], null for none) applied as each row is asked for.
 */
class GeometryRows16(
    private val rgb: ShortArray,
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val geometry: Geometry?,
    orientation: Int,
) : RowSource {
    private val up = Upright(srcWidth, srcHeight, orientation)
    override val width = up.width
    override val height = up.height
    override val bits = 16
    private val pos = FloatArray(6)

    override fun row(y: Int, out: IntArray) {
        for (x in 0 until width) {
            val sx = up.srcX(x, y)
            val sy = up.srcY(x, y)
            if (geometry == null) {
                val i = 3 * (sy * srcWidth + sx)
                out[3 * x] = rgb[i].toInt() and 0xFFFF
                out[3 * x + 1] = rgb[i + 1].toInt() and 0xFFFF
                out[3 * x + 2] = rgb[i + 2].toInt() and 0xFFFF
            } else {
                geometry.map(sx.toFloat(), sy.toFloat(), pos)
                for (c in 0 until 3) out[3 * x + c] = sample(pos[2 * c], pos[2 * c + 1], c)
            }
        }
    }

    /** Bilinear sample of channel [c]; black outside the frame, as the 8-bit path does. */
    private fun sample(sx: Float, sy: Float, c: Int): Int {
        if (sx < -0.5f || sy < -0.5f || sx > srcWidth - 0.5f || sy > srcHeight - 0.5f) return 0
        val x = sx.coerceIn(0f, srcWidth - 1f); val y = sy.coerceIn(0f, srcHeight - 1f)
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = minOf(x0 + 1, srcWidth - 1); val y1 = minOf(y0 + 1, srcHeight - 1)
        val fx = x - x0; val fy = y - y0
        fun v(px: Int, py: Int) = (rgb[3 * (py * srcWidth + px) + c].toInt() and 0xFFFF).toFloat()
        val s = v(x0, y0) * (1 - fx) * (1 - fy) + v(x1, y0) * fx * (1 - fy) + v(x0, y1) * (1 - fx) * fy + v(x1, y1) * fx * fy
        return (s + 0.5f).toInt().coerceIn(0, 65535)
    }
}

/** The [rect] part of [src] (upright pixels), for the crop. */
class CroppedRows(private val src: RowSource, private val rect: PixelRect) : RowSource {
    override val width = rect.width
    override val height = rect.height
    override val bits = src.bits
    private val full = IntArray(3 * src.width)

    override fun row(y: Int, out: IntArray) {
        src.row(rect.y + y, full)
        System.arraycopy(full, 3 * rect.x, out, 0, 3 * rect.width)
    }

    companion object {
        /** [src] cropped as [p] says; [src] itself when there is no crop. */
        fun of(src: RowSource, p: DevelopParams): RowSource {
            val crop = Crop.of(p)
            return if (crop.isFull) src else CroppedRows(src, crop.pixels(src.width, src.height))
        }
    }
}
