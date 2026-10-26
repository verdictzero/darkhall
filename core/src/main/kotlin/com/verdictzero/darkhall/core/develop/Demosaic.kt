package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.raw.RawImage
import com.verdictzero.darkhall.core.util.Parallel

/** A rectangle in crop-relative sensor pixels. */
data class Region(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height
}

/**
 * Converts Bayer (and Fujifilm X-Trans) data to a [LinearImage].
 *
 * Two strategies: [superpixel] bins each 2x2 Bayer cell into one RGB pixel (half
 * resolution, zero colour artefacts, very fast; ideal for the live preview) and
 * [malvar] runs the Malvar-He-Cutler 5x5 gradient-corrected linear demosaic at
 * full resolution for export and zoom. Both work on any crop-relative [Region];
 * pixels outside the crop are mirrored with a period of two so the Bayer phase holds.
 * Linear (already demosaiced) DNGs go through the same entry points: [superpixel]
 * box-averages 2x2 pixels and [malvar] just normalises. X-Trans sensors, whose 6x6 pattern
 * has no complete 2x2 cell, take their own routes: [superpixel] weighs a 4x4 window around
 * each cell, and [malvar] interpolates green along the flatter direction, then red and blue
 * as differences from it.
 */
object Demosaic {

    private class Levels(raw: RawImage) {
        /** Indexed by CFA position (see [posAt]), or by channel for linear data. */
        val black = FloatArray(4) { raw.meta.blackLevels[it].toFloat() }
        val scale = FloatArray(4) { 1f / (raw.meta.whiteLevel - raw.meta.blackLevels[it]).coerceAtLeast(1) }
        val cfa = raw.meta.cfa
        val meta = raw.meta
        val left = raw.meta.crop.left
        val top = raw.meta.crop.top
        val w = raw.meta.crop.width
        val h = raw.meta.crop.height
        /** Colour of a crop-relative site. */
        fun colorAt(row: Int, col: Int) = cfa[posAt(row, col)]
        /** Position of a crop-relative site in the 2x2 cell at the sensor origin. */
        fun posAt(row: Int, col: Int) = (((row + top) and 1) shl 1) or ((col + left) and 1)
        /** Colour of a crop-relative site, X-Trans included. */
        fun colorOf(row: Int, col: Int) = meta.cfaColor(row + top, col + left)
        fun reflectX(c: Int) = if (c < 0) -c else if (c >= w) 2 * (w - 1) - c else c
        fun reflectY(r: Int) = if (r < 0) -r else if (r >= h) 2 * (h - 1) - r else r
    }

    fun superpixel(raw: RawImage): LinearImage =
        superpixel(raw, Region(0, 0, raw.meta.crop.width, raw.meta.crop.height))

    /** Half-resolution binning of [region] (snapped outward to even coordinates). */
    fun superpixel(raw: RawImage, region: Region): LinearImage {
        if (raw.meta.isLinear) return linear(raw, region, 2)
        val crop = raw.meta.crop
        val wb = raw.meta.cameraWb
        val lv = Levels(raw)
        val x0 = region.x and 1.inv()
        val y0 = region.y and 1.inv()
        val w = (minOf(crop.width, region.right) - x0) / 2
        val h = (minOf(crop.height, region.bottom) - y0) / 2
        require(w > 0 && h > 0) { "empty region $region" }
        if (raw.meta.isXTrans) return superpixelXTrans(raw, lv, x0, y0, w, h)
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
                        val p = lv.posAt(cy + dy, cx + dx)
                        val c = lv.cfa[p]
                        val v = (raw.data[(srow + dy) * rw + scol + dx].toInt() and 0xFFFF) - lv.black[p]
                        val n = v * lv.scale[p]
                        when (c) { 0 -> r = n; 2 -> b = n; else -> { g += n; gn++ } }
                    }
                    out.r[o] = r; out.g[o] = if (gn > 0) g / gn else g; out.b[o] = b
                    neutralizeClipped(out.r, out.g, out.b, o, wb)
                    o++
                }
            }
        }
        return out
    }

    /**
     * X-Trans at half size: each 2x2 cell's colours from the 4x4 window around it, weighted
     * 1-3-3-1 each way so the result stays centred on the cell (every such window holds at
     * least three red and three blue sites).
     */
    private fun superpixelXTrans(raw: RawImage, lv: Levels, x0: Int, y0: Int, w: Int, h: Int): LinearImage {
        val crop = raw.meta.crop
        val wb = raw.meta.cameraWb
        val out = LinearImage.allocate(w, h)
        val rw = raw.width
        val weight = floatArrayOf(1f, 3f, 3f, 1f)
        val clip = Developer.CLIP
        Parallel.rows(h) { b0, b1 ->
            val sum = FloatArray(3); val wsum = FloatArray(3); val peak = FloatArray(3)
            for (y in b0 until b1) {
                val cy = y0 + y * 2
                var o = y * w
                for (x in 0 until w) {
                    val cx = x0 + x * 2
                    sum.fill(0f); wsum.fill(0f); peak.fill(0f)
                    for (dy in 0..3) {
                        val ry = lv.reflectY(cy - 1 + dy)
                        val base = (crop.top + ry) * rw + crop.left
                        for (dx in 0..3) {
                            val rx = lv.reflectX(cx - 1 + dx)
                            val p = lv.posAt(ry, rx)
                            val c = lv.colorOf(ry, rx)
                            val n = ((raw.data[base + rx].toInt() and 0xFFFF) - lv.black[p]) * lv.scale[p]
                            val k = weight[dy] * weight[dx]
                            sum[c] += n * k; wsum[c] += k
                            if (n > peak[c]) peak[c] = n
                        }
                    }
                    // A colour with any clipped site counts as clipped, so the neutralising sees it.
                    fun ch(c: Int) = if (peak[c] >= clip) peak[c] else if (wsum[c] > 0f) sum[c] / wsum[c] else 0f
                    out.r[o] = ch(0); out.g[o] = ch(1); out.b[o] = ch(2)
                    neutralizeClipped(out.r, out.g, out.b, o, wb)
                    o++
                }
            }
        }
        return out
    }

    /**
     * The interactive preview: the half-size superpixel image scaled to [width] pixels across
     * (never enlarged). Large sensors are binned straight to near that size, a band of rows at a
     * time, so a 60 MP file never needs its 180 MB half-size float image in memory at once.
     */
    fun preview(raw: RawImage, width: Int): LinearImage {
        val crop = raw.meta.crop
        val hw = crop.width / 2
        val hh = crop.height / 2
        val k = maxOf(1, hw / maxOf(1, width))
        val binned = if (k == 1) {
            superpixel(raw)
        } else {
            val bw = hw / k
            val bh = hh / k
            val out = LinearImage.allocate(bw, bh)
            val band = 2 * k * maxOf(1, 64 / k)
            var y = 0
            while (y < bh) {
                // A band of output rows from one superpixel strip, then binned k x k.
                val rows = minOf(band / (2 * k), bh - y)
                val strip = superpixel(raw, Region(0, y * k * 2, bw * k * 2, rows * k * 2))
                val inv = 1f / (k * k)
                for (by in 0 until rows) for (bx in 0 until bw) {
                    var sr = 0f; var sg = 0f; var sb = 0f
                    for (dy in 0 until k) {
                        var i = (by * k + dy) * strip.width + bx * k
                        for (dx in 0 until k) { sr += strip.r[i]; sg += strip.g[i]; sb += strip.b[i]; i++ }
                    }
                    val o = (y + by) * bw + bx
                    out.r[o] = sr * inv; out.g[o] = sg * inv; out.b[o] = sb * inv
                }
                y += rows
            }
            out
        }
        if (binned.width <= width) return binned
        return binned.resizeArea(width, maxOf(1, (binned.height.toLong() * width / binned.width).toInt()))
    }

    /** Full-resolution demosaic of crop-relative rows [rowStart, rowEnd), full width. */
    fun malvar(raw: RawImage, rowStart: Int = 0, rowEnd: Int = raw.meta.crop.height): LinearImage =
        malvar(raw, Region(0, rowStart, raw.meta.crop.width, rowEnd - rowStart))

    /** Full-resolution Malvar-He-Cutler demosaic of [region]. */
    fun malvar(raw: RawImage, region: Region): LinearImage {
        if (raw.meta.isLinear) return linear(raw, region, 1)
        val lv = Levels(raw)
        val crop = raw.meta.crop
        require(region.width > 0 && region.height > 0 && region.x >= 0 && region.y >= 0 &&
            region.right <= crop.width && region.bottom <= crop.height) { "bad region $region" }
        if (raw.meta.isXTrans) return xtrans(raw, lv, region)
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
                    val p = lv.posAt(yy, col)
                    dst[i] = ((data[base + col].toInt() and 0xFFFF) - black[p]) * scale[p]
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

    /**
     * Full-resolution X-Trans demosaic of [region]. Green first: every red or blue site has three
     * green neighbours (two of them opposite each other), and the eight around it are weighted
     * by how flat the green is horizontally and vertically there, so edges are followed rather
     * than crossed. Red and blue then come from the green plus the mean colour difference
     * (red minus green, blue minus green) of the nearest sites of that colour, at least one of
     * each lying in every 3x3 window.
     */
    private fun xtrans(raw: RawImage, lv: Levels, region: Region): LinearImage {
        val crop = raw.meta.crop
        val w = region.width
        val h = region.height
        val rw = raw.width
        val out = LinearImage.allocate(w, h)
        val eps = 0.002f
        Parallel.rows(h) { b0, b1 ->
            // The band plus two sites all round: values, colours and the green plane.
            val bw = w + 4
            val bh = b1 - b0 + 4
            val v = FloatArray(bw * bh)
            val col = ByteArray(bw * bh)
            val g = FloatArray(bw * bh)
            for (j in 0 until bh) {
                val ry = lv.reflectY(region.y + b0 - 2 + j)
                val base = (crop.top + ry) * rw + crop.left
                for (i in 0 until bw) {
                    val rx = lv.reflectX(region.x - 2 + i)
                    val p = lv.posAt(ry, rx)
                    v[j * bw + i] = ((raw.data[base + rx].toInt() and 0xFFFF) - lv.black[p]) * lv.scale[p]
                    col[j * bw + i] = lv.colorOf(ry, rx).toByte()
                }
            }
            for (j in 1 until bh - 1) for (i in 1 until bw - 1) {
                val k = j * bw + i
                if (col[k].toInt() == 1) { g[k] = v[k]; continue }
                // Green gradients across the 3x3 window, each way.
                var gh = 0f; var nh = 0; var gv = 0f; var nv = 0
                for (d in -1..1) {
                    val a = k + d * bw - 1; val b = k + d * bw + 1
                    if (col[a].toInt() == 1 && col[b].toInt() == 1) { gh += abs(v[a] - v[b]); nh++ }
                    val c = k + d - bw; val e = k + d + bw
                    if (col[c].toInt() == 1 && col[e].toInt() == 1) { gv += abs(v[c] - v[e]); nv++ }
                }
                if (nh > 0) gh /= nh
                if (nv > 0) gv /= nv
                if (nh == 0) gh = gv
                if (nv == 0) gv = gh
                val wh = 2f / (eps + gh); val wv = 2f / (eps + gv); val wd = 1f / (eps + 0.5f * (gh + gv))
                var s = 0f; var ws = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    if (dy == 0 && dx == 0) continue
                    val n = k + dy * bw + dx
                    if (col[n].toInt() != 1) continue
                    val wt = if (dy == 0) wh else if (dx == 0) wv else wd
                    s += v[n] * wt; ws += wt
                }
                g[k] = if (ws > 0f) s / ws else v[k]
            }
            for (by in b0 until b1) {
                val j = by - b0 + 2
                val o = by * w
                for (x in 0 until w) {
                    val k = j * bw + x + 2
                    val own = col[k].toInt()
                    val gg = g[k]
                    var dr = 0f; var wr = 0f; var db = 0f; var wbs = 0f
                    for (dy in -1..1) for (dx in -1..1) {
                        val n = k + dy * bw + dx
                        val wt = if (dy == 0 && dx == 0) 4f else if (dy == 0 || dx == 0) 2f else 1f
                        when (col[n].toInt()) {
                            0 -> { dr += (v[n] - g[n]) * wt; wr += wt }
                            2 -> { db += (v[n] - g[n]) * wt; wbs += wt }
                        }
                    }
                    out.r[o + x] = if (own == 0) v[k] else gg + (if (wr > 0f) dr / wr else 0f)
                    out.g[o + x] = gg
                    out.b[o + x] = if (own == 2) v[k] else gg + (if (wbs > 0f) db / wbs else 0f)
                }
            }
        }
        return out
    }

    private fun abs(x: Float) = if (x < 0f) -x else x

    /**
     * The half-size image feeds area-averaged previews, and an average of clipped sky with dark
     * branches falls below the clip level, where highlight reconstruction no longer sees it: the
     * green that clipped first comes out short and the sky turns magenta. So clipped channels are
     * raised here, per pixel, to the most neutral level the data allows (the rules of
     * [Developer]'s reconstruction without its neighbourhood colour), before any averaging.
     */
    private fun neutralizeClipped(pr: FloatArray, pg: FloatArray, pb: FloatArray, i: Int, wb: FloatArray) {
        val rr = pr[i]; val gg = pg[i]; val bb = pb[i]
        val clip = Developer.CLIP
        val cR = rr >= clip; val cG = gg >= clip; val cB = bb >= clip
        if (!cR && !cG && !cB) return
        val xr = rr * wb[0]; val xg = gg * wb[1]; val xb = bb * wb[2]
        val clipped = (if (cR) 1 else 0) + (if (cG) 1 else 0) + (if (cB) 1 else 0)
        val target = when (clipped) {
            // Neutral at the brightest recorded level.
            3 -> maxOf(xr, xg, xb)
            // The mean of the other two: in a blue sky whose green clipped, the brighter (blue)
            // would read cyan.
            1 -> 0.5f * ((if (cR) 0f else xr) + (if (cG) 0f else xg) + (if (cB) 0f else xb))
            // Halfway between the unclipped level and the higher clipped floor.
            else -> {
                val u = if (!cR) xr else if (!cG) xg else xb
                val hi = maxOf(if (cR) xr else 0f, if (cG) xg else 0f, if (cB) xb else 0f)
                maxOf(u, 0.5f * (u + hi))
            }
        }
        if (cR) pr[i] = maxOf(rr, target / wb[0])
        if (cG) pg[i] = maxOf(gg, target / wb[1])
        if (cB) pb[i] = maxOf(bb, target / wb[2])
    }

    /**
     * Linear RGB data: averages [factor] x [factor] pixel blocks of [region] (1 = full
     * resolution) after black subtraction and normalisation.
     */
    private fun linear(raw: RawImage, region: Region, factor: Int): LinearImage {
        val lv = Levels(raw)
        val crop = raw.meta.crop
        val x0 = if (factor == 2) region.x and 1.inv() else region.x
        val y0 = if (factor == 2) region.y and 1.inv() else region.y
        val w = (minOf(crop.width, region.right) - x0) / factor
        val h = (minOf(crop.height, region.bottom) - y0) / factor
        require(w > 0 && h > 0 && x0 >= 0 && y0 >= 0) { "bad region $region" }
        val out = LinearImage.allocate(w, h)
        val rw = raw.width
        val data = raw.data
        val inv = 1f / (factor * factor)
        Parallel.rows(h) { b0, b1 ->
            for (y in b0 until b1) {
                var o = y * w
                for (x in 0 until w) {
                    var r = 0f; var g = 0f; var b = 0f
                    for (dy in 0 until factor) {
                        var i = ((crop.top + y0 + y * factor + dy) * rw + crop.left + x0 + x * factor) * 3
                        for (dx in 0 until factor) {
                            r += (data[i].toInt() and 0xFFFF) - lv.black[0]
                            g += (data[i + 1].toInt() and 0xFFFF) - lv.black[1]
                            b += (data[i + 2].toInt() and 0xFFFF) - lv.black[2]
                            i += 3
                        }
                    }
                    out.r[o] = r * inv * lv.scale[0]; out.g[o] = g * inv * lv.scale[1]; out.b[o] = b * inv * lv.scale[2]
                    if (factor == 2) neutralizeClipped(out.r, out.g, out.b, o, raw.meta.cameraWb)
                    o++
                }
            }
        }
        return out
    }
}
