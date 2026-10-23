package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.orf.RawImage
import com.verdictzero.darkhall.core.util.Parallel

/** A rectangle in crop-relative sensor pixels. */
data class Region(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height
}

/**
 * Converts Bayer data to a [LinearImage].
 *
 * Two strategies: [superpixel] bins each 2x2 Bayer cell into one RGB pixel (half
 * resolution, zero colour artefacts, very fast; ideal for the live preview) and
 * [malvar] runs the Malvar-He-Cutler 5x5 gradient-corrected linear demosaic at
 * full resolution for export and zoom. Both work on any crop-relative [Region];
 * pixels outside the crop are mirrored with a period of two so the Bayer phase holds.
 */
object Demosaic {

    private class Levels(raw: RawImage) {
        val black = FloatArray(4) { raw.meta.blackLevels[it].toFloat() }
        val scale = FloatArray(4) { 1f / (raw.meta.whiteLevel - raw.meta.blackLevels[it]).coerceAtLeast(1) }
        val cfa = raw.meta.cfa
        val left = raw.meta.crop.left
        val top = raw.meta.crop.top
        val w = raw.meta.crop.width
        val h = raw.meta.crop.height
        /** Colour of a crop-relative site. */
        fun colorAt(row: Int, col: Int) = cfa[(((row + top) and 1) shl 1) or ((col + left) and 1)]
        fun reflectX(c: Int) = if (c < 0) -c else if (c >= w) 2 * (w - 1) - c else c
        fun reflectY(r: Int) = if (r < 0) -r else if (r >= h) 2 * (h - 1) - r else r
    }

    fun superpixel(raw: RawImage): LinearImage =
        superpixel(raw, Region(0, 0, raw.meta.crop.width, raw.meta.crop.height))

    /** Half-resolution binning of [region] (snapped outward to even coordinates). */
    fun superpixel(raw: RawImage, region: Region): LinearImage {
        val crop = raw.meta.crop
        val lv = Levels(raw)
        val x0 = region.x and 1.inv()
        val y0 = region.y and 1.inv()
        val w = (minOf(crop.width, region.right) - x0) / 2
        val h = (minOf(crop.height, region.bottom) - y0) / 2
        require(w > 0 && h > 0) { "empty region $region" }
        val out = LinearImage.allocate(w, h)
        val rw = raw.width
        Parallel.rows(h) { b0, b1 ->
            for (y in b0 until b1) {
                val cy = y0 + y * 2
                val srow = crop.top + cy
                var o = y * w
                for (x in 0 until w) {
                    val cx = x0 + x * 2
                    val scol = crop.left + cx
                    var r = 0f; var g = 0f; var b = 0f; var gn = 0
                    for (dy in 0..1) for (dx in 0..1) {
                        val c = lv.colorAt(cy + dy, cx + dx)
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

    /** Full-resolution demosaic of crop-relative rows [rowStart, rowEnd), full width. */
    fun malvar(raw: RawImage, rowStart: Int = 0, rowEnd: Int = raw.meta.crop.height): LinearImage =
        malvar(raw, Region(0, rowStart, raw.meta.crop.width, rowEnd - rowStart))

    /** Full-resolution Malvar-He-Cutler demosaic of [region]. */
    fun malvar(raw: RawImage, region: Region): LinearImage {
        val lv = Levels(raw)
        val crop = raw.meta.crop
        require(region.width > 0 && region.height > 0 && region.x >= 0 && region.y >= 0 &&
            region.right <= crop.width && region.bottom <= crop.height) { "bad region $region" }
        val w = region.width
        val h = region.height
        val rw = raw.width
        val out = LinearImage.allocate(w, h)
        val data = raw.data
        val black = lv.black
        val scale = lv.scale

        Parallel.rows(h) { b0, b1 ->
            // Five normalised rows of the window, each padded by two pixels on both sides.
            val rows = Array(5) { FloatArray(w + 4) }
            fun loadRow(dst: FloatArray, cropRow: Int) {
                val yy = lv.reflectY(cropRow)
                val base = (crop.top + yy) * rw + crop.left
                for (i in 0 until w + 4) {
                    val col = lv.reflectX(region.x + i - 2)
                    val c = lv.colorAt(yy, col)
                    dst[i] = ((data[base + col].toInt() and 0xFFFF) - black[c]) * scale[c]
                }
            }
            for (k in 0 until 5) loadRow(rows[k], region.y + b0 - 2 + k)
            var ring = 0 // rows[(ring + k) % 5] holds crop row y-2+k
            for (by in b0 until b1) {
                val y = region.y + by
                if (by != b0) {
                    loadRow(rows[ring], y + 2)
                    ring = (ring + 1) % 5
                }
                val rm2 = rows[ring]
                val rm1 = rows[(ring + 1) % 5]
                val r0 = rows[(ring + 2) % 5]
                val rp1 = rows[(ring + 3) % 5]
                val rp2 = rows[(ring + 4) % 5]
                val o = by * w
                for (x in 0 until w) {
                    val cx = region.x + x
                    val c = lv.colorAt(y, cx)
                    val xi = x + 2
                    val p = r0[xi]
                    val plusH = r0[xi - 1] + r0[xi + 1]
                    val plusV = rm1[xi] + rp1[xi]
                    val cross = plusH + plusV
                    val diag = rm1[xi - 1] + rm1[xi + 1] + rp1[xi - 1] + rp1[xi + 1]
                    val far2H = r0[xi - 2] + r0[xi + 2]
                    val far2V = rm2[xi] + rp2[xi]
                    val far2 = far2H + far2V
                    var rr: Float; var gg: Float; var bb: Float
                    when (c) {
                        1 -> { // green site
                            gg = p
                            val horizIsRed = lv.colorAt(y, cx + 1) == 0
                            val hv = (4f * plusH + 5f * p + 0.5f * far2V - far2H - diag) * 0.125f
                            val vv = (4f * plusV + 5f * p + 0.5f * far2H - far2V - diag) * 0.125f
                            if (horizIsRed) { rr = hv; bb = vv } else { bb = hv; rr = vv }
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
