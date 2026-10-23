package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.orf.RawImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders one rectangle of the final picture straight from the raw data, at any scale up to
 * 1:1. This is what makes zooming in show real detail instead of an enlarged preview: the
 * slider pipeline runs on just the visible area (plus a margin for the spatial filters), and
 * the result matches the same area of a full export.
 */
object RegionRender {
    /** Larger than the reach of any spatial filter in [Developer] at full resolution. */
    const val MARGIN = FullRender.MARGIN

    /**
     * [out] is in output (post-geometry) full-resolution pixels, sensor orientation.
     * The result is round(out.width * scale) x round(out.height * scale).
     */
    fun render(raw: RawImage, ctx: RenderContext, params: DevelopParams, out: Region, scale: Float, dev: Developer): RenderedImage {
        val fw = raw.meta.crop.width
        val fh = raw.meta.crop.height
        val s = scale.coerceIn(0.05f, 1f)
        val ow = max(1, (out.width * s).roundToInt())
        val oh = max(1, (out.height * s).roundToInt())
        val geom = Geometry.create(fw, fh, params, ctx.lens)

        // Source area that the output rectangle reads from.
        var sx0 = out.x.toFloat(); var sy0 = out.y.toFloat()
        var sx1 = out.right.toFloat(); var sy1 = out.bottom.toFloat()
        if (geom != null) {
            sx0 = Float.MAX_VALUE; sy0 = Float.MAX_VALUE; sx1 = -Float.MAX_VALUE; sy1 = -Float.MAX_VALUE
            val buf = FloatArray(6)
            val steps = 24
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                for ((px, py) in listOf(
                    out.x + t * out.width to out.y.toFloat(), out.x + t * out.width to out.bottom.toFloat(),
                    out.x.toFloat() to out.y + t * out.height, out.right.toFloat() to out.y + t * out.height,
                )) {
                    geom.map(px, py, buf)
                    for (c in 0 until 3) {
                        sx0 = min(sx0, buf[2 * c]); sx1 = max(sx1, buf[2 * c])
                        sy0 = min(sy0, buf[2 * c + 1]); sy1 = max(sy1, buf[2 * c + 1])
                    }
                }
            }
        }
        val src = run {
            val x0 = (floor(sx0).toInt() - MARGIN).coerceIn(0, fw - 2)
            val y0 = (floor(sy0).toInt() - MARGIN).coerceIn(0, fh - 2)
            val x1 = (ceil(sx1).toInt() + 2 + MARGIN).coerceIn(x0 + 2, fw)
            val y1 = (ceil(sy1).toInt() + 2 + MARGIN).coerceIn(y0 + 2, fh)
            if (s <= 0.5f) Region(x0 and 1.inv(), y0 and 1.inv(), x1 - (x0 and 1.inv()), y1 - (y0 and 1.inv()))
            else Region(x0, y0, x1 - x0, y1 - y0)
        }

        // Demosaic just that area at (about) the requested scale.
        val lin: LinearImage = if (s <= 0.5f) {
            val half = Demosaic.superpixel(raw, src)
            val tw = max(1, (src.width * s).roundToInt())
            val th = max(1, (src.height * s).roundToInt())
            if (tw < half.width) half.resizeArea(tw, th) else half
        } else {
            val full = Demosaic.malvar(raw, src)
            val tw = max(1, (src.width * s).roundToInt())
            val th = max(1, (src.height * s).roundToInt())
            if (tw < full.width) full.resizeArea(tw, th) else full
        }
        val effX = lin.width.toFloat() / src.width
        val effY = lin.height.toFloat() / src.height
        val argb = dev.render(lin, ctx, params, Frame(src.x, src.y, effX), inPlace = true)
        val lw = lin.width
        val lh = lin.height

        // Resample into the output rectangle through the geometry.
        val result = IntArray(ow * oh)
        val buf = FloatArray(6)
        for (v in 0 until oh) {
            val fy = out.y + (v + 0.5f) / s - 0.5f
            var o = v * ow
            for (u in 0 until ow) {
                val fx = out.x + (u + 0.5f) / s - 0.5f
                if (geom != null) geom.map(fx, fy, buf) else { buf[0] = fx; buf[1] = fy; buf[2] = fx; buf[3] = fy; buf[4] = fx; buf[5] = fy }
                var outside = false
                for (c in 0 until 3) {
                    val gx = buf[2 * c]; val gy = buf[2 * c + 1]
                    if (gx < -0.5f || gy < -0.5f || gx > fw - 0.5f || gy > fh - 0.5f) outside = true
                    buf[2 * c] = (gx + 0.5f - src.x) * effX - 0.5f
                    buf[2 * c + 1] = (gy + 0.5f - src.y) * effY - 0.5f
                }
                result[o++] = when {
                    outside -> 0xFF000000.toInt()
                    geom != null && geom.hasTca -> Geometry.sample3(argb, lw, lh, buf)
                    else -> Geometry.bilinear(argb, lw, lh, buf[2].coerceIn(0f, lw - 1f), buf[3].coerceIn(0f, lh - 1f))
                }
            }
        }
        return RenderedImage(ow, oh, result)
    }
}
