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
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Underwater statistics of a file, in white-balanced camera space: [floor] is the
 * blue-green veil (a low percentile per channel) and [gain] the grey-world multipliers
 * that neutralise what remains after removing it.
 */
class WaterStats(val floor: FloatArray, val gain: FloatArray)

/** Per-file constants shared by preview, zoom and export renders. */
class RenderContext(
    val cameraWb: FloatArray,
    val matrix: FloatArray,
    val fullWidth: Int,
    val fullHeight: Int,
    /** Atmospheric light estimate in camera-linear space (pre white balance). */
    val haze: FloatArray,
    val water: WaterStats = WaterStats(FloatArray(3), floatArrayOf(1f, 1f, 1f)),
    /** Built-in lens profile for TG-5/6/7 files, null for anything else. */
    val lens: LensProfile? = null,
) {
    companion object {
        fun forFile(meta: OrfMetadata, preview: LinearImage): RenderContext = RenderContext(
            cameraWb = meta.cameraWb.copyOf(),
            matrix = ColorScience.cameraToSrgb(meta),
            fullWidth = meta.crop.width,
            fullHeight = meta.crop.height,
            haze = Developer.estimateHaze(preview),
            water = Developer.estimateWater(preview, meta.cameraWb),
            lens = LensProfile.forFile(meta),
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
 *  0. highlight reconstruction (clipped channels rebuilt from their neighbours' colour)
 *  1. white balance, underwater correction, temperature/tint, camera matrix, exposure, vignette
 *  2. dehaze (dark channel prior)
 *  3. noise reduction (luminance: edge-aware; colour: chroma blur)
 *  4. shadows / highlights (luminance masked gains)
 *  5. sRGB encode, blacks / whites / contrast curve, vibrance / saturation
 *  6. clarity + sharpening (unsharp masks on display luminance)
 * Geometry (lens profile, distortion, straighten) is a separate pass on the 8-bit result, see [Geometry].
 */
class Developer {

    private var cap = 0
    private var wr = FloatArray(0); private var wg = FloatArray(0); private var wb = FloatArray(0)
    private var t1 = FloatArray(0); private var t2 = FloatArray(0); private var t3 = FloatArray(0); private var t4 = FloatArray(0)
    private var t5 = FloatArray(0)
    private val curve = FloatArray(4097)

    /** Rebuild clipped highlights from surrounding colour. Off only for comparison tests. */
    var highlightReconstruction = true

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
        t5 = FloatArray(0)
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

        // ---- 0. highlight reconstruction ------------------------------------------------
        var srcR = img.r; var srcG = img.g; var srcB = img.b
        if (highlightReconstruction && hasClipped(img)) {
            if (t5.size < n) t5 = FloatArray(cap)
            if (!inPlace) {
                System.arraycopy(img.r, 0, r, 0, n); System.arraycopy(img.g, 0, g, 0, n); System.arraycopy(img.b, 0, b, 0, n)
            }
            reconstructHighlights(r, g, b, w, h, ctx, frame)
            srcR = r; srcG = g; srcB = b
        }

        // ---- 1. colour: WB, underwater, temperature/tint, matrix, exposure, vignette ----
        val temp = p[Adjustment.TEMPERATURE] / 100f
        val tint = p[Adjustment.TINT] / 100f
        val user = floatArrayOf(pow2(temp * 0.8f), pow2(-tint * 0.5f), pow2(-temp * 0.8f))
        // Per channel: c = max(0, raw * A - B). B removes the water's veil, A holds the camera WB,
        // the underwater grey-world gain and the user's temperature/tint.
        val water = p[Adjustment.WATER] / 100f
        val mulA = FloatArray(3)
        val subB = FloatArray(3)
        for (c in 0 until 3) {
            // Geometric blend: half the slider is half the correction in stops.
            val wg = exp(water * ln(ctx.water.gain[c]))
            mulA[c] = ctx.cameraWb[c] * wg * user[c]
            subB[c] = water * ctx.water.floor[c] * wg * user[c]
        }
        val gainR = mulA[0]; val gainG = mulA[1]; val gainB = mulA[2]
        val subR = subB[0]; val subG = subB[1]; val subBl = subB[2]
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
                    val cr = max(0f, srcR[i] * gainR - subR)
                    val cg = max(0f, srcG[i] * gainG - subG)
                    val cb = max(0f, srcB[i] * gainB - subBl)
                    val sr = m[0] * cr + m[1] * cg + m[2] * cb
                    val sg = m[3] * cr + m[4] * cg + m[5] * cb
                    val sb = m[6] * cr + m[7] * cg + m[8] * cb
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
            val hr0 = max(0f, ctx.haze[0] * gainR - subR)
            val hg0 = max(0f, ctx.haze[1] * gainG - subG)
            val hb0 = max(0f, ctx.haze[2] * gainB - subBl)
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
                    // Above display white (recovered highlights) keep counting up, so a negative
                    // Highlights slider can pull that detail back into range.
                    val yd = if (l <= 1f) ColorScience.encode(l) else 1f + 0.5f * ln(l) / LN2
                    val ys = min(yd, 1f)
                    val k = exp(sh * (1f - ys) * (1f - ys) + hl * yd * yd)
                    rr *= k; gg *= k; bb *= k
                }
                // Soft shoulder: compress the top of the range instead of clipping it, scaling all
                // three channels together so bright colours keep their hue.
                val mx = max(rr, max(gg, bb))
                if (mx > SHOULDER) {
                    val f = SHOULDER + (1f - SHOULDER) * (1f - exp(-(mx - SHOULDER) / (1f - SHOULDER)))
                    val k = f / mx
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

    private fun hasClipped(img: LinearImage): Boolean {
        val r = img.r; val g = img.g; val b = img.b
        for (i in 0 until img.size) if (r[i] >= CLIP || g[i] >= CLIP || b[i] >= CLIP) return true
        return false
    }

    /**
     * Rebuilds clipped channels in raw camera space (before white balance).
     *
     * Bright pixels that are not clipped vote for the local colour (chromaticity), weighted
     * towards the brightest; the votes are blurred so they reach into the clipped area. A pixel
     * with some channels clipped keeps its good channels and gets the clipped ones from that
     * colour; a pixel with every channel clipped becomes neutral white at least as bright as
     * the sensor saw. Values may exceed 1.0; the tone stage's shoulder brings them back.
     */
    private fun reconstructHighlights(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, ctx: RenderContext, frame: Frame) {
        // Neutral (white after white balance) chromaticity in raw space.
        var nR = 1f / ctx.cameraWb[0]; var nG = 1f / ctx.cameraWb[1]; var nB = 1f / ctx.cameraWb[2]
        val ns = nR + nG + nB
        nR /= ns; nG /= ns; nB /= ns
        Parallel.rows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val rr = r[i]; val gg = g[i]; val bb = b[i]
                val mx = max(rr, max(gg, bb))
                val sum = rr + gg + bb
                if (mx < CLIP && mx > CLIP * 0.25f && sum > 1e-4f) {
                    val q = mx / CLIP
                    val wgt = q * q
                    val inv = wgt / sum
                    t1[i] = rr * inv; t2[i] = gg * inv; t3[i] = bb * inv; t4[i] = wgt
                } else {
                    t1[i] = 0f; t2[i] = 0f; t3[i] = 0f; t4[i] = 0f
                }
            }
        }
        val rad = max(2, (0.01f * ctx.fullWidth * frame.scale).roundToInt())
        Filters.blur(t1, t1, t5, w, h, rad, passes = 3)
        Filters.blur(t2, t2, t5, w, h, rad, passes = 3)
        Filters.blur(t3, t3, t5, w, h, rad, passes = 3)
        Filters.blur(t4, t4, t5, w, h, rad, passes = 3)
        Parallel.rows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val rr = r[i]; val gg = g[i]; val bb = b[i]
                val cR = rr >= CLIP; val cG = gg >= CLIP; val cB = bb >= CLIP
                if (!cR && !cG && !cB) continue
                val wgt = t4[i]
                val conf = wgt / (wgt + 0.02f)
                var er = nR; var eg = nG; var eb = nB
                if (wgt > 1e-7f) { er = t1[i] / wgt; eg = t2[i] / wgt; eb = t3[i] / wgt }
                val chR = conf * er + (1f - conf) * nR
                val chG = conf * eg + (1f - conf) * nG
                val chB = conf * eb + (1f - conf) * nB
                val scale = if (cR && cG && cB) {
                    // Nothing to anchor to: neutral, no channel darker than what was recorded.
                    val nmin = min(nR, min(nG, nB))
                    CLIP / nmin
                } else {
                    var su = 0f; var sc = 0f
                    if (!cR) { su += rr; sc += chR }
                    if (!cG) { su += gg; sc += chG }
                    if (!cB) { su += bb; sc += chB }
                    su / max(sc, 1e-4f)
                }
                if (cR && cG && cB) {
                    r[i] = max(rr, min(MAX_RECOVERED, scale * nR))
                    g[i] = max(gg, min(MAX_RECOVERED, scale * nG))
                    b[i] = max(bb, min(MAX_RECOVERED, scale * nB))
                } else {
                    if (cR) r[i] = max(rr, min(MAX_RECOVERED, scale * chR))
                    if (cG) g[i] = max(gg, min(MAX_RECOVERED, scale * chG))
                    if (cB) b[i] = max(bb, min(MAX_RECOVERED, scale * chB))
                }
            }
        }
    }

    companion object {
        private val LN2 = ln(2.0).toFloat()

        /** Normalised raw level treated as clipped (the 12-bit white point minus a safety margin). */
        const val CLIP = 0.96f
        private const val MAX_RECOVERED = 4f
        /** Linear level where the highlight shoulder starts. */
        private const val SHOULDER = 0.85f

        /**
         * Underwater statistics: per-channel 0.5th percentile (the veil) and grey-world gains
         * on what remains, both after the camera's white balance. Computed once per file.
         */
        fun estimateWater(img: LinearImage, wb: FloatArray): WaterStats {
            val bins = 2048
            val maxV = 2f
            val hist = Array(3) { IntArray(bins) }
            val n = img.size
            for (i in 0 until n) {
                val vr = img.r[i] * wb[0]; val vg = img.g[i] * wb[1]; val vb = img.b[i] * wb[2]
                hist[0][((vr / maxV).coerceIn(0f, 1f) * (bins - 1)).toInt()]++
                hist[1][((vg / maxV).coerceIn(0f, 1f) * (bins - 1)).toInt()]++
                hist[2][((vb / maxV).coerceIn(0f, 1f) * (bins - 1)).toInt()]++
            }
            val floor = FloatArray(3)
            val want = max(1, n / 200)
            for (c in 0 until 3) {
                var acc = 0; var bin = 0
                while (bin < bins - 1 && acc + hist[c][bin] < want) { acc += hist[c][bin]; bin++ }
                floor[c] = bin.toFloat() / (bins - 1) * maxV
            }
            val sum = DoubleArray(3)
            var cnt = 0
            for (i in 0 until n) {
                val rr = img.r[i]; val gg = img.g[i]; val bb = img.b[i]
                if (max(rr, max(gg, bb)) >= 0.95f) continue
                val vr = rr * wb[0] - floor[0]; val vg = gg * wb[1] - floor[1]; val vb = bb * wb[2] - floor[2]
                if (vr + vg + vb < 0.01f) continue
                sum[0] += max(0f, vr); sum[1] += max(0f, vg); sum[2] += max(0f, vb)
                cnt++
            }
            if (cnt < 100 || sum.any { it <= 1e-6 }) return WaterStats(floor, floatArrayOf(1f, 1f, 1f))
            // Red can be down more than 4 stops at 10 m; allow up to ~4.3 stops of recovery.
            // Colour noise reduction (on by default) keeps the amplified red channel clean.
            val gain = FloatArray(3) { c -> (sum[1] / sum[c]).toFloat().coerceIn(0.3f, 20f) }
            return WaterStats(floor, gain)
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun pow2(x: Float): Float = exp(x * LN2)

        @Suppress("NOTHING_TO_INLINE")
        private inline fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v


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
