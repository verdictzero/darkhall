package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.orf.RawImage
import com.verdictzero.darkhall.core.util.Parallel

/**
 * Converts Bayer data to a [LinearImage].
 *
 * Two strategies: [superpixel] bins each 2x2 Bayer cell into one RGB pixel (half
 * resolution, zero colour artefacts, very fast; ideal for the live preview) and
 * [malvar] runs the Malvar-He-Cutler 5x5 gradient-corrected linear demosaic at
 * full resolution for export.
 */
object Demosaic {

    private class Levels(raw: RawImage) {
        val black = FloatArray(4) { raw.meta.blackLevels[it].toFloat() }
        val scale = FloatArray(4) { 1f / (raw.meta.whiteLevel - raw.meta.blackLevels[it]).coerceAtLeast(1) }
        // colour of a site given the crop-relative parity
        val cfa = raw.meta.cfa
        val left = raw.meta.crop.left
        val top = raw.meta.crop.top
        fun colorAt(row: Int, col: Int) = cfa[(((row + top) and 1) shl 1) or ((col + left) and 1)]
    }

    /** Index into black/scale arrays: R=0, G=1, B=2, the second green shares G. */
    private fun levelIndex(color: Int) = color

    fun superpixel(raw: RawImage): LinearImage {
        val crop = raw.meta.crop
        val lv = Levels(raw)
        val w = crop.width / 2
        val h = crop.height / 2
        val out = LinearImage.allocate(w, h)
        val rw = raw.width
        Parallel.rows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val srow = crop.top + y * 2
                var o = y * w
                for (x in 0 until w) {
                    val scol = crop.left + x * 2
                    var r = 0f; var g = 0f; var b = 0f; var gn = 0
                    for (dy in 0..1) for (dx in 0..1) {
                        val c = lv.colorAt(y * 2 + dy, x * 2 + dx)
                        val v = (raw.data[(srow + dy) * rw + scol + dx].toInt() and 0xFFFF) - lv.black[c]
                        val n = v * lv.scale[c]
                        when (c) { 0 -> r = n; 2 -> b = n; else -> { g += n; gn++ } }
                    }
                    out.r[o] = r; out.g[o] = if (gn > 0) g / gn else g; out.b[o] = b
                    o++
                }
            }
        }
        return out
    }

    /**
     * Full-resolution demosaic of crop-relative rows [rowStart, rowEnd). Rendering in
     * bands keeps the 12 MP export within a phone's heap.
     */
    fun malvar(raw: RawImage, rowStart: Int = 0, rowEnd: Int = raw.meta.crop.height): LinearImage {
        val crop = raw.meta.crop
        val lv = Levels(raw)
        val w = crop.width
        val h = crop.height
        val rw = raw.width
        val bandH = rowEnd - rowStart
        require(rowStart in 0 until rowEnd && rowEnd <= h) { "bad band $rowStart..$rowEnd" }
        val out = LinearImage.allocate(w, bandH)
        val data = raw.data
        val black = lv.black
        val scale = lv.scale


        Parallel.rows(bandH) { b0, b1 ->
            val y0 = rowStart + b0
            val y1 = rowStart + b1
            // Five normalised rows, re-fetched per output row (cheap relative to the kernels).
            val rows = Array(5) { FloatArray(w + 4) }
            fun loadRow(dst: FloatArray, y: Int) {
                val yy = y.coerceIn(0, h - 1)
                val base = (crop.top + yy) * rw + crop.left
                val parity = ((yy + lv.top) and 1) shl 1
                val cEven = lv.cfa[parity or ((lv.left) and 1)]
                val cOdd = lv.cfa[parity or ((lv.left + 1) and 1)]
                for (x in 0 until w) {
                    val c = if ((x and 1) == 0) cEven else cOdd
                    val v = (data[base + x].toInt() and 0xFFFF) - black[c]
                    dst[x + 2] = v * scale[c]
                }
                // clamp edges
                dst[1] = dst[2]; dst[0] = dst[2]
                dst[w + 2] = dst[w + 1]; dst[w + 3] = dst[w + 1]
            }
            for (k in 0 until 5) loadRow(rows[k], y0 - 2 + k)
            var ring = 0 // rows[(ring + k) % 5] is row y-2+k
            for (y in y0 until y1) {
                if (y != y0) {
                    // slide window down by one: the oldest row buffer takes row y+2
                    loadRow(rows[ring], y + 2)
                    ring = (ring + 1) % 5
                }
                val rm2 = rows[ring]
                val rm1 = rows[(ring + 1) % 5]
                val r0 = rows[(ring + 2) % 5]
                val rp1 = rows[(ring + 3) % 5]
                val rp2 = rows[(ring + 4) % 5]
                val o = (y - rowStart) * w
                for (x in 0 until w) {
                    val c = lv.colorAt(y, x)
                    val xi = x + 2
                    val p = r0[xi]
                    // Neighbour sums used by the MHC kernels.
                    val plusH = r0[xi - 1] + r0[xi + 1]      // left+right
                    val plusV = rm1[xi] + rp1[xi]            // up+down
                    val cross = plusH + plusV                // 4-neighbourhood
                    val diag = rm1[xi - 1] + rm1[xi + 1] + rp1[xi - 1] + rp1[xi + 1]
                    val far2H = r0[xi - 2] + r0[xi + 2]
                    val far2V = rm2[xi] + rp2[xi]
                    val far2 = far2H + far2V
                    var rr: Float; var gg: Float; var bb: Float
                    when (c) {
                        1 -> { // green site
                            gg = p
                            // Which colour sits to the left/right? Same row, other parity.
                            val horizIsRed = lv.colorAt(y, x + 1) == 0
                            // Colour at horizontal neighbours (R or B):
                            val h = (4f * plusH + 5f * p + 0.5f * far2V - far2H - diag) * 0.125f
                            // Colour at vertical neighbours:
                            val v = (4f * plusV + 5f * p + 0.5f * far2H - far2V - diag) * 0.125f
                            if (horizIsRed) { rr = h; bb = v } else { bb = h; rr = v }
                        }
                        0 -> { // red site
                            rr = p
                            gg = (2f * cross + 4f * p - far2) * 0.125f
                            bb = (2f * diag + 6f * p - 1.5f * far2) * 0.125f
                        }
                        else -> { // blue site
                            bb = p
                            gg = (2f * cross + 4f * p - far2) * 0.125f
                            rr = (2f * diag + 6f * p - 1.5f * far2) * 0.125f
                        }
                    }
                    out.r[o + x] = rr; out.g[o + x] = gg; out.b[o + x] = bb
                }
            }
        }
        return out
    }
}
