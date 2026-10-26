package com.verdictzero.darkhall.core.sky

import com.verdictzero.darkhall.core.develop.Filters
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.export.Upright
import com.verdictzero.darkhall.core.util.Parallel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the sky is in one photo.
 *
 * [detect] runs [SkyNet] once on the photo turned upright (the network learned skies at the
 * top) and keeps its coarse probability grid, about 129 cells across. [forFrame] turns that
 * into a mask for any rendered frame (the preview, a zoomed region, a band of the full-size
 * export) and refines it against that frame's own pixels:
 *  1. an edge-aware ("guided") filter snaps the mask's edge onto the picture's brightness
 *     edges, so skylines and ridges come out crisp at any resolution;
 *  2. where the network is unsure (tree canopies, rigging), each pixel is compared with the
 *     sky colour expected there (the average of nearby certain sky) and kept if it matches,
 *     which picks out the sky between leaves and branches.
 *
 * Coordinates: the grid lives in the upright picture; frames are in the camera's crop, before
 * geometry, which is where the develop stage works.
 */
class SkyMask(
    /** Network grid size and the sky probability per cell, upright. */
    val gridWidth: Int,
    val gridHeight: Int,
    val prob: FloatArray,
    /** Size of the upright picture the grid covers, at network scale. */
    private val netWidth: Int,
    private val netHeight: Int,
    /** EXIF orientation of the camera frame (3, 6 and 8 turn it; anything else doesn't). */
    val orientation: Int,
) {
    /** Share of the picture that is sky (probability over a half). */
    val coverage: Float = prob.count { it > 0.5f }.toFloat() / prob.size

    private val turn = when (orientation) { 3 -> 2; 6 -> 1; 8 -> 3; else -> 0 }

    /** Coarse sky probability at crop-normalised position (u, v), bilinear on the grid. */
    fun coarse(u: Float, v: Float): Float {
        val a: Float; val b: Float
        when (turn) {
            1 -> { a = 1f - v; b = u }
            2 -> { a = 1f - u; b = 1f - v }
            3 -> { a = v; b = 1f - u }
            else -> { a = u; b = v }
        }
        // Grid cell j sits on network input pixel 4j (corner-aligned upsampling).
        val gx = ((a * netWidth - 0.5f) / 4f).coerceIn(0f, gridWidth - 1f)
        val gy = ((b * netHeight - 0.5f) / 4f).coerceIn(0f, gridHeight - 1f)
        val x0 = gx.toInt(); val y0 = gy.toInt()
        val x1 = min(x0 + 1, gridWidth - 1); val y1 = min(y0 + 1, gridHeight - 1)
        val fx = gx - x0; val fy = gy - y0
        val p = prob
        return (p[y0 * gridWidth + x0] * (1 - fx) + p[y0 * gridWidth + x1] * fx) * (1 - fy) +
            (p[y1 * gridWidth + x0] * (1 - fx) + p[y1 * gridWidth + x1] * fx) * fy
    }

    /**
     * The refined mask (0..1 per pixel) for [img], a rendered frame of the crop ([cropWidth] x
     * [cropHeight] camera pixels) placed by [frame]. [img] holds camera-linear RGB.
     */
    fun forFrame(img: LinearImage, frame: Frame, cropWidth: Int, cropHeight: Int): FloatArray {
        val w = img.width; val h = img.height; val n = w * h
        val p = FloatArray(n)
        val inv = 1f / frame.scale
        Parallel.rows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val v = (frame.y0 + (y + 0.5f) * inv) / cropHeight
                for (x in 0 until w) p[y * w + x] = coarse((frame.x0 + (x + 0.5f) * inv) / cropWidth, v)
            }
        }
        if (coverage == 0f && p.all { it < 0.05f }) return p

        // Guide: square-rooted camera RGB, near enough to perceptual for comparing colours.
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val lum = FloatArray(n)
        Parallel.rows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                r[i] = sqrt(max(0f, img.r[i])); g[i] = sqrt(max(0f, img.g[i])); b[i] = sqrt(max(0f, img.b[i]))
                lum[i] = 0.25f * r[i] + 0.5f * g[i] + 0.25f * b[i]
            }
        }
        // Radii in camera pixels, scaled to this frame: 8 px on a 1024 px wide picture.
        val unit = max(cropWidth, cropHeight) / 1024f * frame.scale
        val rad = max(2, (8f * unit).roundToInt())
        val q = guided(lum, p, w, h, rad, 1e-3f)
        for (i in 0 until n) q[i] = q[i].coerceIn(0f, 1f)

        // Where unsure, compare with the sky colour expected at that spot.
        val sure = FloatArray(n) { if (q[it] > 0.9f) 1f else 0f }
        val far = max(4, (24f * unit).roundToInt())
        val tmp = FloatArray(n)
        val wsum = FloatArray(n).also { Filters.blur(sure, it, tmp, w, h, far, 1) }
        val local = Array(3) { FloatArray(n) }
        for ((c, plane) in arrayOf(r, g, b).withIndex()) {
            val weighted = FloatArray(n) { plane[it] * sure[it] }
            Filters.blur(weighted, local[c], tmp, w, h, far, 1)
        }
        val out = FloatArray(n)
        Parallel.rows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val qi = q[i]
                if (qi <= 0.05f || qi >= 0.9f || wsum[i] < 0.02f) { out[i] = qi; continue }
                val k = 1f / wsum[i]
                val dr = r[i] - local[0][i] * k; val dg = g[i] - local[1][i] * k; val db = b[i] - local[2][i] * k
                val colour = (1f - sqrt(dr * dr + dg * dg + db * db) / 0.12f).coerceIn(0f, 1f)
                out[i] = if (qi > 0.2f) max(qi * 0.3f, colour) else qi * 0.3f
            }
        }
        val fine = guided(lum, out, w, h, max(1, rad / 4), 1e-4f)
        for (i in 0 until n) fine[i] = fine[i].coerceIn(0f, 1f)
        return fine
    }

    /** He et al.'s guided filter with a grey guide [guide], radius [r]. */
    private fun guided(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val tmp = FloatArray(n)
        fun mean(src: FloatArray) = FloatArray(n).also { Filters.blur(src, it, tmp, w, h, r, 1) }
        val mI = mean(guide); val mp = mean(p)
        val ip = FloatArray(n) { guide[it] * p[it] }; val ii = FloatArray(n) { guide[it] * guide[it] }
        val mIp = mean(ip); val mII = mean(ii)
        val a = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = mII[i] - mI[i] * mI[i]
            a[i] = (mIp[i] - mI[i] * mp[i]) / (varI + eps)
            b[i] = mp[i] - a[i] * mI[i]
        }
        val ma = mean(a); val mb = mean(b)
        return FloatArray(n) { ma[it] * guide[it] + mb[it] }
    }

    /** For the on-disk cache. */
    fun toBytes(): ByteArray {
        val bb = ByteBuffer.allocate(4 * 6 + 4 * prob.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(MAGIC).putInt(gridWidth).putInt(gridHeight).putInt(netWidth).putInt(netHeight).putInt(orientation)
        prob.forEach { bb.putFloat(it) }
        return bb.array()
    }

    companion object {
        private const val MAGIC = 0x534B5931 // "SKY1"

        fun fromBytes(bytes: ByteArray): SkyMask? {
            if (bytes.size < 24) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.int != MAGIC) return null
            val gw = bb.int; val gh = bb.int; val nw = bb.int; val nh = bb.int; val o = bb.int
            if (gw <= 0 || gh <= 0 || bytes.size != 24 + 4 * gw * gh) return null
            return SkyMask(gw, gh, FloatArray(gw * gh) { bb.float }, nw, nh, o)
        }

        /**
         * Finds the sky in [argb], the whole crop rendered at preview size in camera orientation
         * (8-bit, before geometry), whose EXIF [orientation] says how to turn it upright.
         */
        fun detect(model: SkyModel, argb: IntArray, width: Int, height: Int, orientation: Int): SkyMask {
            val up = Upright(width, height, orientation)
            val s = SkyNet.INPUT.toFloat() / max(up.width, up.height)
            val nw = min(SkyNet.INPUT, max(1, (up.width * s).roundToInt()))
            val nh = min(SkyNet.INPUT, max(1, (up.height * s).roundToInt()))
            // Area-average down to network size, scale to -1..1; the rest of the square stays 0,
            // the mean grey the model was padded with in training.
            val x = Tensor(3, SkyNet.INPUT, SkyNet.INPUT)
            val plane = SkyNet.INPUT * SkyNet.INPUT
            Parallel.rows(nh) { y0, y1 ->
                for (ny in y0 until y1) {
                    val sy0 = ny * up.height / nh; val sy1 = max(sy0 + 1, (ny + 1) * up.height / nh)
                    for (nx in 0 until nw) {
                        val sx0 = nx * up.width / nw; val sx1 = max(sx0 + 1, (nx + 1) * up.width / nw)
                        var sr = 0; var sg = 0; var sb = 0
                        for (yy in sy0 until sy1) for (xx in sx0 until sx1) {
                            val px = argb[up.srcY(xx, yy) * width + up.srcX(xx, yy)]
                            sr += (px shr 16) and 0xFF; sg += (px shr 8) and 0xFF; sb += px and 0xFF
                        }
                        val k = 1f / ((sy1 - sy0) * (sx1 - sx0) * 127.5f)
                        val i = ny * SkyNet.INPUT + nx
                        x.data[i] = sr * k - 1f; x.data[plane + i] = sg * k - 1f; x.data[2 * plane + i] = sb * k - 1f
                    }
                }
            }
            val logits = SkyNet.logits(model, x)
            val all = SkyNet.skyProbability(logits)
            val gw = (nw - 1) / 4 + 1; val gh = (nh - 1) / 4 + 1
            val prob = FloatArray(gw * gh) { all[(it / gw) * logits.w + it % gw] }
            return SkyMask(gw, gh, prob, nw, nh, orientation)
        }
    }
}
