package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.raw.RawImage

class RenderedImage(val width: Int, val height: Int, val argb: IntArray)

/**
 * A 16-bit render: RGB triplets before geometry, plus the [geometry] (null for none) to apply
 * when the rows are read out, see [com.verdictzero.darkhall.core.export.GeometryRows16].
 */
class RenderedImage16(val width: Int, val height: Int, val rgb: ShortArray, val geometry: Geometry?)

/**
 * Full-resolution export. The 12 MP frame is demosaiced and developed in horizontal
 * bands with enough overlap for the largest spatial filter, so peak memory stays
 * around 200 MB instead of the ~400 MB a whole-frame float pipeline would need.
 */
object FullRender {
    const val BAND_ROWS = 384
    const val MARGIN = 256

    fun render(
        raw: RawImage,
        ctx: RenderContext,
        params: DevelopParams,
        progress: ((Float) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): RenderedImage? {
        val w = raw.meta.crop.width
        val h = raw.meta.crop.height
        val out = IntArray(w * h)
        val dev = Developer()
        var y = 0
        while (y < h) {
            if (isCancelled()) return null
            val y1 = minOf(y + BAND_ROWS, h)
            val top = maxOf(0, y - MARGIN)
            val bottom = minOf(h, y1 + MARGIN)
            val band = Demosaic.malvar(raw, top, bottom)
            val argb = dev.render(band, ctx, params, Frame(0, top, 1f), inPlace = true)
            System.arraycopy(argb, (y - top) * w, out, y * w, (y1 - y) * w)
            progress?.invoke(y1.toFloat() / h)
            y = y1
        }
        dev.release()
        if (isCancelled()) return null
        val finalPixels = Geometry.apply(out, w, h, params, ctx.lens)
        return RenderedImage(w, h, finalPixels)
    }

    /**
     * Like [render], keeping 16 bits per channel. Geometry is left to the reader of the result,
     * so only one full-size 16-bit copy (6 bytes a pixel) is ever held.
     */
    fun render16(
        raw: RawImage,
        ctx: RenderContext,
        params: DevelopParams,
        progress: ((Float) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): RenderedImage16? {
        val w = raw.meta.crop.width
        val h = raw.meta.crop.height
        val out = ShortArray(3 * w * h)
        val dev = Developer()
        var y = 0
        while (y < h) {
            if (isCancelled()) return null
            val y1 = minOf(y + BAND_ROWS, h)
            val top = maxOf(0, y - MARGIN)
            val bottom = minOf(h, y1 + MARGIN)
            val band = Demosaic.malvar(raw, top, bottom)
            val rgb = dev.render16(band, ctx, params, Frame(0, top, 1f), inPlace = true)
            System.arraycopy(rgb, 3 * (y - top) * w, out, 3 * y * w, 3 * (y1 - y) * w)
            progress?.invoke(y1.toFloat() / h)
            y = y1
        }
        dev.release()
        if (isCancelled()) return null
        return RenderedImage16(w, h, out, Geometry.create(w, h, params, ctx.lens))
    }
}
