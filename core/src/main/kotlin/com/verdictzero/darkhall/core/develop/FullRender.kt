package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.orf.RawImage

class RenderedImage(val width: Int, val height: Int, val argb: IntArray)

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
}
