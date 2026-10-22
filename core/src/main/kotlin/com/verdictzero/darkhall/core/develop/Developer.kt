package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.develop.ColorScience.LUM_B
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_G
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_R
import com.verdictzero.darkhall.core.orf.OrfMetadata
import com.verdictzero.darkhall.core.util.Parallel
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/** Per-file constants shared by preview and export renders. */
class RenderContext(
    val cameraWb: FloatArray,
    val matrix: FloatArray,
    val fullWidth: Int,
    val fullHeight: Int,
    /** Atmospheric light estimate in camera-linear space (pre white balance). */
    val haze: FloatArray,
) {
    companion object {
        fun forFile(meta: OrfMetadata, preview: LinearImage): RenderContext = RenderContext(
            cameraWb = meta.cameraWb.copyOf(),
            matrix = ColorScience.cameraToSrgb(meta),
            fullWidth = meta.crop.width,
            fullHeight = meta.crop.height,
            haze = Developer.estimateHaze(preview),
        )
    }
}

/** Where an image (preview or export band) sits inside the full-resolution frame. */
class Frame(val x0: Int, val y0: Int, val scale: Float)

/**
 * The slider pipeline. One instance keeps its scratch buffers between renders so
 * dragging a slider does not churn the garbage collector.
 *
 * Order of operations (linear light first, display space afterwards):
 *  1. white balance + temperature/tint, camera matrix, highlight desaturation, exposure, vignette
 *  2. dehaze (dark channel prior)
 *  3. noise reduction (luminance: edge-aware; colour: chroma blur)
 *  4. shadows / highlights (luminance masked gains)
 *  5. sRGB encode, blacks / whites / contrast curve, vibrance / saturation
 *  6. clarity + sharpening (unsharp masks on display luminance)
 * Geometry (distortion, straighten) is a separate pass on the 8-bit result, see [applyGeometry].
 */
class Developer {

    private var cap = 0
    private var wr = FloatArray(0); private var wg = FloatArray(0); private var wb = FloatArray(0)
    private var t1 = FloatArray(0); private var t2 = FloatArray(0); private var t3 = FloatArray(0); private var t4 = FloatArray(0)
    private val curve = FloatArray(4097)

    private fun ensureScratch(n: Int, needWork: Boolean) {
        if (n > cap) {
            cap = n
            t1 = FloatArray(n); t2 = FloatArray(n); t3 = FloatArray(n); t4 = FloatArray(n)
            if (needWork) { wr = FloatArray(n); wg = FloatArray(n); wb = FloatArray(n) }
        } else if (needWork && wr.size < n) {
            wr = FloatArray(cap); wg = FloatArray(cap); wb = FloatArray(cap)
        }
    }

    /** Frees scratch memory (e.g. after an export). */
    fun release() {
        cap = 0
        wr = FloatArray(0); wg = FloatArray(0); wb = FloatArray(0)
        t1 = FloatArray(0); t2 = FloatArray(0); t3 = FloatArray(0); t4 = FloatArray(0)
    }

    /**
     * Renders [img] to packed 0xAARRGGBB pixels (before geometry). With [inPlace] the
     * image's own planes are used as the working buffers and are destroyed.
     */
    fun render(img: LinearImage, ctx: RenderContext, p: DevelopParams, frame: Frame, inPlace: Boolean = false): IntArray {
        val w = img.width
        val h = img.height
        val n = w * h
        ensureScratch(n, !inPlace)
        val r: FloatArray; val g: FloatArray; val b: FloatArray
        if (inPlace) { r = img.r; g = img.g; b = img.b } else { r = wr; g = wg; b = wb }

        // ---- 1. colour: WB, matrix, highlights, exposure, vignette --------------------
        val temp = p[Adjustment.TEMPERATURE] / 100f
        val tint = p[Adjustment.TINT] / 100f
        val gainR = ctx.cameraWb[0] * pow2(temp * 0.8f)
        val gainG = ctx.cameraWb[1] * pow2(-tint * 0.5f)
        val gainB = ctx.cameraWb[2] * pow2(-temp * 0.8f)
        val ev = pow2(p[Adjustment.EXPOSURE])
        val m = ctx.matrix
        val vig = p[Adjustment.VIGNETTE] / 100f * 0.8f
        val cx = ctx.fullWidth * 0.5f
        val cy = ctx.fullHeight * 0.5f
        val invR2 = 1f / (cx * cx + cy * cy)
        val invScale = 1f / frame.scale

        Parallel.rows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val fy = frame.y0 + y * invScale - cy
                val fy2 = fy * fy
                var i = y * w
                for (x in 0 until w) {
                    val rr = img.r[i]; val gg = img.g[i]; val bb = img.b[i]
                    val mx = max(rr, max(gg, bb))
                    val cr = rr * gainR; val cg = gg * gainG; val cb = bb * gainB
                    var sr = m[0] * cr + m[1] * cg + m[2] * cb
                    var sg = m[3] * cr + m[4] * cg + m[5] * cb
                    var sb = m[6] * cr + m[7] * cg + m[8] * cb
                    if (mx > 0.8f) {
                        // Sensor channel near clipping: pull toward neutral so blown areas go white, not magenta.
                        val f = smooth((mx - 0.8f) * 5f)
                        val l = LUM_R * sr + LUM_G * sg + LUM_B * sb
                        sr += (l - sr) * f; sg += (l - sg) * f; sb += (l - sb) * f
                    }
                    var k = ev
                    if (vig != 0f) {
                        val fx = frame.x0 + x * invScale - cx
                        k *= 1f + vig * (fx * fx + fy2) * invR2
                    }
                    r[i] = sr * k; g[i] = sg * k; b[i] = sb * k
                    i++
                }
            }
        }

        // ---- 2. dehaze ----------------------------------------------------------------
        val dehaze = p[Adjustment.DEHAZE] / 100f
        if (abs(dehaze) > 0.005f) {
            // Push the haze estimate through the same colour transform.
            val hr0 = ctx.haze[0] * gainR; val hg0 = ctx.haze[1] * gainG; val hb0 = ctx.haze[2] * gainB
            val ar = max(0.05f, (m[0] * hr0 + m[1] * hg0 + m[2] * hb0) * ev)
            val ag = max(0.05f, (m[3] * hr0 + m[4] * hg0 + m[5] * hb0) * ev)
            val ab = max(0.05f, (m[6] * hr0 + m[7] * hg0 + m[8] * hb0) * ev)
            if (dehaze > 0f) {
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        t1[i] = min(1f, max(0f, min(r[i] / ar, min(g[i] / ag, b[i] / ab))))
                    }
                }
                val rad = max(1, (0.008f * ctx.fullWidth * frame.scale).roundToInt())
                Filters.minFilter(t1, t2, t3, w, h, rad)
                Filters.blur(t2, t1, t3, w, h, max(1, rad * 2), passes = 3)
                val omega = 0.85f * dehaze
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        val t = max(0.2f, 1f - omega * t1[i])
                        val inv = 1f / t
                        r[i] = (r[i] - ar) * inv + ar
                        g[i] = (g[i] - ag) * inv + ag
                        b[i] = (b[i] - ab) * inv + ab
                    }
                }
            } else {
                val k = -dehaze * 0.5f
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        r[i] += (ar - r[i]) * k; g[i] += (ag - g[i]) * k; b[i] += (ab - b[i]) * k
                    }
                }
            }
        }

        // ---- 3. noise reduction ---------------------------------------------------------
        val lumaNr = p[Adjustment.LUMA_NR] / 100f
        val chromaNr = p[Adjustment.CHROMA_NR] / 100f
        if (lumaNr > 0.005f || chromaNr > 0.005f) {
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) t1[i] = LUM_R * r[i] + LUM_G * g[i] + LUM_B * b[i]
            }
            if (chromaNr > 0.005f) {
                val rad = max(1, ((4f + 32f * chromaNr) * frame.scale).roundToInt())
                Parallel.rows(h) { y0, y1 -> for (i in y0 * w until y1 * w) t2[i] = r[i] - t1[i] }
                Filters.blur(t2, r, t3, w, h, rad, passes = 2)
                Parallel.rows(h) { y0, y1 -> for (i in y0 * w until y1 * w) t2[i] = b[i] - t1[i] }
                Filters.blur(t2, b, t3, w, h, rad, passes = 2)
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        val yy = t1[i]
                        r[i] += yy; b[i] += yy
                        g[i] = (yy - LUM_R * r[i] - LUM_B * b[i]) / LUM_G
                    }
                }
            }
            if (lumaNr > 0.005f) {
                val rad = max(1, ((1.5f + 4f * lumaNr) * frame.scale).roundToInt())
                Filters.blur(t1, t2, t3, w, h, rad, passes = 2)
                val s0 = 0.003f * lumaNr
                val s1 = 0.09f * lumaNr
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        val yy = t1[i]
                        val d = t2[i] - yy
                        val sigma = s0 + s1 * sqrt(max(yy, 0f))
                        val k = exp(-(d * d) / (2f * sigma * sigma))
                        val ny = yy + k * d
                        if (yy > 1e-5f) {
                            val ratio = ny / yy
                            r[i] *= ratio; g[i] *= ratio; b[i] *= ratio
                        } else {
                            val add = ny - yy
                            r[i] += add; g[i] += add; b[i] += add
                        }
                    }
                }
            }
        }

        // ---- 4/5. tone + colour -----------------------------------------------------------
        buildCurve(p)
        val sh = p[Adjustment.SHADOWS] / 100f * 1.5f * LN2
        val hl = p[Adjustment.HIGHLIGHTS] / 100f * 1.2f * LN2
        val doSh = abs(sh) > 1e-4f || abs(hl) > 1e-4f
        val vib = p[Adjustment.VIBRANCE] / 100f
        val sat = p[Adjustment.SATURATION] / 100f
        val doSat = abs(vib) > 1e-4f || abs(sat) > 1e-4f
        val lut = curve
        Parallel.rows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                var rr = r[i]; var gg = g[i]; var bb = b[i]
                if (doSh) {
                    val l = LUM_R * rr + LUM_G * gg + LUM_B * bb
                    val yd = ColorScience.encode(l)
                    val k = exp(sh * (1f - yd) * (1f - yd) + hl * yd * yd)
                    rr *= k; gg *= k; bb *= k
                }
                var dr = lut[(ColorScience.encode(rr) * 4096f).toInt()]
                var dg = lut[(ColorScience.encode(gg) * 4096f).toInt()]
                var db = lut[(ColorScience.encode(bb) * 4096f).toInt()]
                if (doSat) {
                    val yv = LUM_R * dr + LUM_G * dg + LUM_B * db
                    val s = max(dr, max(dg, db)) - min(dr, min(dg, db))
                    val k = (1f + vib * (1f - s)) * (1f + sat)
                    dr = clamp01(yv + (dr - yv) * k)
                    dg = clamp01(yv + (dg - yv) * k)
                    db = clamp01(yv + (db - yv) * k)
                }
                r[i] = dr; g[i] = dg; b[i] = db
            }
        }

        // ---- 6. clarity + sharpening ------------------------------------------------------
        val clarity = p[Adjustment.CLARITY] / 100f
        val sharpen = p[Adjustment.SHARPEN] / 100f
        if (abs(clarity) > 0.005f || sharpen > 0.005f) {
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    t1[i] = LUM_R * r[i] + LUM_G * g[i] + LUM_B * b[i]
                    t2[i] = 0f
                }
            }
            if (sharpen > 0.005f) {
                val rad = max(1, (1.0f * frame.scale).roundToInt())
                Filters.blur(t1, t4, t3, w, h, rad, passes = 2)
                val a = sharpen * 1.6f
                val tau2 = 0.006f * 0.006f
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        val d = t1[i] - t4[i]
                        val d2 = d * d
                        t2[i] += a * d * (d2 / (d2 + tau2))
                    }
                }
            }
            if (abs(clarity) > 0.005f) {
                val rad = max(2, (0.015f * ctx.fullWidth * frame.scale).roundToInt())
                Filters.blur(t1, t4, t3, w, h, rad, passes = 3)
                val a = clarity * 0.7f
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        val d = t1[i] - t4[i]
                        t2[i] += a * d / (1f + 3f * abs(d))
                    }
                }
            }
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    val yy = t1[i]
                    val ny = clamp01(yy + t2[i])
                    if (yy > 1e-4f) {
                        val ratio = ny / yy
                        r[i] = clamp01(r[i] * ratio); g[i] = clamp01(g[i] * ratio); b[i] = clamp01(b[i] * ratio)
                    } else {
                        r[i] = clamp01(r[i] + ny - yy); g[i] = clamp01(g[i] + ny - yy); b[i] = clamp01(b[i] + ny - yy)
                    }
                }
            }
        }

        // ---- pack ------------------------------------------------------------------------
        val out = IntArray(n)
        Parallel.rows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val ri = (r[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (g[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (b[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        return out
    }

    /** Display-space curve: blacks -> whites -> contrast, sampled on 4097 points. */
    private fun buildCurve(p: DevelopParams) {
        val blacks = p[Adjustment.BLACKS] / 100f * 0.3f
        val whites = p[Adjustment.WHITES] / 100f * 0.25f
        val contrast = p[Adjustment.CONTRAST] / 100f
        for (i in 0..4096) {
            var v = i / 4096f
            v = clamp01(v + blacks * (1f - v) * (1f - v))
            v = clamp01(v + whites * v * v)
            if (contrast > 0f) {
                val s = 0.5f - 0.5f * cos(PI.toFloat() * v)
                v += contrast * 0.9f * (s - v)
            } else if (contrast < 0f) {
                val inv = (acos((1f - 2f * v).coerceIn(-1f, 1f)) / PI).toFloat()
                v += -contrast * 0.8f * (inv - v)
            }
            curve[i] = clamp01(v)
        }
    }

    companion object {
        private val LN2 = ln(2.0).toFloat()

        @Suppress("NOTHING_TO_INLINE")
        private inline fun pow2(x: Float): Float = exp(x * LN2)

        @Suppress("NOTHING_TO_INLINE")
        private inline fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

        @Suppress("NOTHING_TO_INLINE")
        private inline fun smooth(x: Float): Float { val t = clamp01(x); return t * t * (3f - 2f * t) }

        /**
         * Dark-channel-prior atmospheric light: the mean colour of the brightest 0.1% of
         * pixels ranked by their darkest channel. Computed once per file on the preview.
         */
        fun estimateHaze(img: LinearImage): FloatArray {
            val n = img.size
            val hist = IntArray(1024)
            val mins = FloatArray(n)
            for (i in 0 until n) {
                val mn = min(img.r[i], min(img.g[i], img.b[i])).coerceIn(0f, 1f)
                mins[i] = mn
                hist[(mn * 1023f).toInt()]++
            }
            val want = max(50, n / 1000)
            var acc = 0
            var bin = 1023
            while (bin > 0 && acc < want) { acc += hist[bin]; bin-- }
            val thr = bin / 1023f
            var sr = 0.0; var sg = 0.0; var sb = 0.0; var cnt = 0
            for (i in 0 until n) if (mins[i] >= thr) { sr += img.r[i]; sg += img.g[i]; sb += img.b[i]; cnt++ }
            if (cnt == 0) return floatArrayOf(1f, 1f, 1f)
            return floatArrayOf(
                (sr / cnt).toFloat().coerceIn(0.05f, 1f),
                (sg / cnt).toFloat().coerceIn(0.05f, 1f),
                (sb / cnt).toFloat().coerceIn(0.05f, 1f),
            )
        }

        fun needsGeometry(p: DevelopParams) =
            abs(p[Adjustment.DISTORTION]) > 0.5f || abs(p[Adjustment.STRAIGHTEN]) > 0.02f

        /**
         * Radial distortion correction plus straightening, bilinear resampled, auto-zoomed
         * so no empty corners appear. Returns a new buffer (or [src] when nothing to do).
         */
        fun applyGeometry(src: IntArray, w: Int, h: Int, p: DevelopParams): IntArray {
            if (!needsGeometry(p)) return src
            val k = p[Adjustment.DISTORTION] / 100f * 0.3f
            val theta = p[Adjustment.STRAIGHTEN] / 180f * PI.toFloat()
            val cx = (w - 1) * 0.5f
            val cy = (h - 1) * 0.5f
            val radius = sqrt(cx * cx + cy * cy)
            // zoom so the rotated frame still covers the output
            val aspect = max(w, h).toFloat() / min(w, h)
            val sRot = cos(abs(theta)) + aspect * sin(abs(theta))
            // zoom so barrel correction (k > 0) does not pull the corners inside the frame
            var sK = 1f
            if (k > 0f) {
                // Corner (r = 1) must map back onto the source corner: (1/s)(1 + k/s^2) = 1.
                var s = 1f + k
                repeat(16) { s -= (s * s * s - s * s - k) / (3f * s * s - 2f * s) }
                sK = max(1f, s)
            }
            val zoom = 1f / (sRot * sK)
            val cosT = cos(theta); val sinT = sin(theta)
            val out = IntArray(w * h)
            Parallel.rows(h) { y0, y1 ->
                for (y in y0 until y1) {
                    val dy = (y - cy) / radius
                    var o = y * w
                    for (x in 0 until w) {
                        val dx = (x - cx) / radius
                        var qx = (dx * cosT - dy * sinT) * zoom
                        var qy = (dx * sinT + dy * cosT) * zoom
                        val r2 = qx * qx + qy * qy
                        val f = 1f + k * r2
                        qx *= f; qy *= f
                        val sx = cx + qx * radius
                        val sy = cy + qy * radius
                        out[o++] = bilinear(src, w, h, sx, sy)
                    }
                }
            }
            return out
        }

        private fun bilinear(src: IntArray, w: Int, h: Int, sx: Float, sy: Float): Int {
            if (sx < 0f || sy < 0f || sx > w - 1f || sy > h - 1f) return 0xFF000000.toInt()
            val x0 = sx.toInt(); val y0 = sy.toInt()
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val fx = sx - x0; val fy = sy - y0
            val p00 = src[y0 * w + x0]; val p10 = src[y0 * w + x1]
            val p01 = src[y1 * w + x0]; val p11 = src[y1 * w + x1]
            val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy); val w01 = (1 - fx) * fy; val w11 = fx * fy
            var out = 0xFF shl 24
            for (shift in intArrayOf(16, 8, 0)) {
                val v = ((p00 ushr shift) and 0xFF) * w00 + ((p10 ushr shift) and 0xFF) * w10 +
                    ((p01 ushr shift) and 0xFF) * w01 + ((p11 ushr shift) and 0xFF) * w11
                out = out or ((v + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
            return out
        }

        /** RGB histogram (3 x 256 bins) of packed pixels. */
        fun histogram(argb: IntArray): IntArray {
            val bins = IntArray(768)
            for (px in argb) {
                bins[(px ushr 16) and 0xFF]++
                bins[256 + ((px ushr 8) and 0xFF)]++
                bins[512 + (px and 0xFF)]++
            }
            return bins
        }
    }
}
