package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.develop.ColorScience.LUM_B
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_G
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_R
import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.raw.RawImage
import com.verdictzero.darkhall.core.util.Parallel
import com.verdictzero.darkhall.core.sky.MaskRegion
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    /** Built-in lens profile (TG-5/6/7 files only), null for anything else. */
    val lens: LensProfile? = null,
    /** Where look (LUT) ids are resolved; the app supplies its bundled library. */
    val looks: LookSource = LookSource.NONE,
    /** Sensor noise of this file; drives the luminance noise reduction thresholds. */
    val noise: NoiseProfile = NoiseProfile(FloatArray(3) { NoiseProfile.REF_A }, FloatArray(3) { NoiseProfile.REF_B }, false),
    /**
     * Luminance noise variance kept by demosaicing, relative to the per-sample model:
     * [0] full-resolution (Malvar), [1] half-size superpixel. Depends on the colour matrix
     * and white balance, so it is measured per file ([NoiseCalibration]).
     */
    val demosaicNoise: FloatArray = floatArrayOf(0.89f, 0.55f),
    /** Exposure the file asks to be added before the Exposure slider (DNG BaselineExposure), in EV. */
    val baselineEv: Float = 0f,
) {
    /** Where the sky, water, people and foliage are, once found (see [com.verdictzero.darkhall.core.sky.SceneMasks]); the masked sliders need it. */
    @Volatile var scene: com.verdictzero.darkhall.core.sky.SceneMasks? = null

    /** Noise variance of one rendered pixel relative to one raw sample, at render [scale]. */
    fun demosaicNoise(scale: Float): Float {
        val s = min(1f, scale)
        // The superpixel path (half size and below) is followed by area resizing, which
        // averages (0.5 / s)² pixels.
        return if (s <= 0.5f) demosaicNoise[1] * 4f * s * s else demosaicNoise[0] * s * s
    }

    companion object {
        /** [raw] enables per-file noise measurement; without it the ISO-based model is used. */
        fun forFile(meta: RawMetadata, preview: LinearImage, raw: RawImage? = null, looks: LookSource = LookSource.NONE): RenderContext = RenderContext(
            cameraWb = meta.cameraWb.copyOf(),
            matrix = ColorScience.cameraToSrgb(meta),
            fullWidth = meta.crop.width,
            fullHeight = meta.crop.height,
            haze = Developer.estimateHaze(preview),
            water = Developer.estimateWater(preview, meta.cameraWb),
            lens = LensProfile.forFile(meta),
            looks = looks,
            noise = raw?.let { NoiseProfile.estimate(it) } ?: NoiseProfile.fromIso(meta),
            demosaicNoise = NoiseCalibration.demosaicFactors(meta, ColorScience.cameraToSrgb(meta)),
            baselineEv = meta.baselineExposure,
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
 *  1. white balance, underwater correction, temperature/tint, camera matrix, exposure, vignette;
 *     the masked regions (sky, water, subject, foliage); backscatter removal
 *  2. dehaze (dark channel prior)
 *  3. noise reduction (luminance: profiled wavelet shrinkage; colour: chroma blur)
 *  4. shadows / highlights (luminance masked gains)
 *  5. sRGB encode, blacks / whites / contrast curve, vibrance / saturation, colour mixer,
 *     colour grading, look (3D LUT)
 *  6. clarity + sharpening (unsharp masks on display luminance)
 *  7. grain
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
        maskCache.clear()
    }

    /**
     * Renders [img] to packed 0xAARRGGBB pixels (before geometry). With [inPlace] the
     * image's own planes are used as the working buffers and are destroyed.
     */
    fun render(img: LinearImage, ctx: RenderContext, p: DevelopParams, frame: Frame, inPlace: Boolean = false): IntArray =
        develop(img, ctx, p, frame, inPlace) { r, g, b, n, w, h ->
            val out = IntArray(n)
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    val ri = (r[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val gi = (g[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val bi = (b[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    out[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
            out
        }

    /**
     * Like [render], but keeps 16 bits per channel: RGB triplets (3 shorts per pixel, read
     * them unsigned), for 16-bit TIFF and PNG export.
     */
    fun render16(img: LinearImage, ctx: RenderContext, p: DevelopParams, frame: Frame, inPlace: Boolean = false): ShortArray =
        develop(img, ctx, p, frame, inPlace) { r, g, b, n, w, h ->
            val out = ShortArray(3 * n)
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    out[3 * i] = (r[i] * 65535f + 0.5f).toInt().coerceIn(0, 65535).toShort()
                    out[3 * i + 1] = (g[i] * 65535f + 0.5f).toInt().coerceIn(0, 65535).toShort()
                    out[3 * i + 2] = (b[i] * 65535f + 0.5f).toInt().coerceIn(0, 65535).toShort()
                }
            }
            out
        }

    /** The whole pipeline up to display values in 0..1; [pack] turns the planes into pixels. */
    private fun <T> develop(
        img: LinearImage, ctx: RenderContext, p: DevelopParams, frame: Frame, inPlace: Boolean,
        pack: (r: FloatArray, g: FloatArray, b: FloatArray, n: Int, w: Int, h: Int) -> T,
    ): T {
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

        val plan = RenderPlan(ctx, p, frame)
        // The masks this frame needs, from the untouched input (before any in-place work).
        val masks = plan.regions.map { it to maskFor(it.region, img, ctx, frame, inPlace) }

        // ---- 1. colour: WB, underwater, temperature/tint, matrix, exposure, vignette ----
        val gainR = plan.mulA[0]; val gainG = plan.mulA[1]; val gainB = plan.mulA[2]
        val subR = plan.subB[0]; val subG = plan.subB[1]; val subBl = plan.subB[2]
        val ev = plan.ev
        val m = plan.matrix
        val vig = plan.vignette
        val cx = plan.centerX
        val cy = plan.centerY
        val invR2 = plan.invR2
        val invScale = plan.invScale

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

        // ---- 1b. regions: exposure, warmth and saturation through each mask ---------------
        for ((rp, mask) in masks) {
            if (!rp.colourOn) continue
            val ev = rp.ev; val warm = rp.warmth * 0.25f; val sat = rp.saturation
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    val mk = mask[i]
                    if (mk < 1e-3f) continue
                    val k = pow2(ev * mk)
                    var cr = r[i] * k * (1f + warm * mk)
                    val cg = g[i] * k
                    var cb = b[i] * k * (1f - warm * mk)
                    var cg2 = cg
                    if (sat != 0f) {
                        val y = LUM_R * cr + LUM_G * cg + LUM_B * cb
                        val f = max(0f, 1f + sat * mk)
                        cr = y + (cr - y) * f; cg2 = y + (cg - y) * f; cb = y + (cb - y) * f
                    }
                    r[i] = max(0f, cr); g[i] = max(0f, cg2); b[i] = max(0f, cb)
                }
            }
        }

        // ---- 1c. backscatter -------------------------------------------------------------
        if (plan.backscatterOn) Backscatter.apply(r, g, b, w, h, plan.backscatter, plan.backscatterRadius)

        // ---- 2. dehaze ----------------------------------------------------------------
        if (plan.dehazeOn) {
            val ar = plan.atmosphere[0]; val ag = plan.atmosphere[1]; val ab = plan.atmosphere[2]
            if (plan.dehaze > 0f) {
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        t1[i] = min(1f, max(0f, min(r[i] / ar, min(g[i] / ag, b[i] / ab))))
                    }
                }
                Filters.minFilter(t1, t2, t3, w, h, plan.dehazeMinRadius)
                Filters.blur(t2, t1, t3, w, h, plan.dehazeBlurRadius, passes = 3)
                val omega = plan.dehazeOmega
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
                val k = plan.dehazeNegative
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        r[i] += (ar - r[i]) * k; g[i] += (ag - g[i]) * k; b[i] += (ab - b[i]) * k
                    }
                }
            }
        }

        // ---- 3. noise reduction ---------------------------------------------------------
        if (plan.lumaOn || plan.chromaOn) {
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) t1[i] = LUM_R * r[i] + LUM_G * g[i] + LUM_B * b[i]
            }
            if (plan.chromaOn) {
                val rad = plan.chromaRadius
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
            if (plan.lumaOn) {
                if (t5.size < n) t5 = FloatArray(cap)
                profiledLumaDenoise(t1, w, h, plan)
                // Noise is additive, so is the correction: scaling RGB by new/old luminance would
                // turn noisy shadow pixels' wild ratios into colour noise.
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        val add = t4[i] - t1[i]
                        r[i] += add; g[i] += add; b[i] += add
                    }
                }
            }
        }

        // ---- 4/5. tone + colour (shared with AutoTone) -------------------------------------
        Parallel.rows(h) { y0, y1 ->
            val o = FloatArray(3)
            val lk = FloatArray(3)
            for (i in y0 * w until y1 * w) {
                ToneStage.apply(plan, r[i], g[i], b[i], o, lk)
                r[i] = o[0]; g[i] = o[1]; b[i] = o[2]
            }
        }

        // ---- 6. clarity + sharpening ------------------------------------------------------
        if (plan.detailOn) {
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    t1[i] = LUM_R * r[i] + LUM_G * g[i] + LUM_B * b[i]
                    t2[i] = 0f
                }
            }
            if (plan.sharpenOn) {
                Filters.blur(t1, t4, t3, w, h, plan.sharpenRadius, passes = 2)
                val a = plan.sharpenAmount
                val tau2 = 0.006f * 0.006f
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        val d = t1[i] - t4[i]
                        val d2 = d * d
                        t2[i] += a * d * (d2 / (d2 + tau2))
                    }
                }
            }
            if (plan.clarityOn) {
                Filters.blur(t1, t4, t3, w, h, plan.clarityRadius, passes = 3)
                val a = plan.clarityAmount
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        val d = t1[i] - t4[i]
                        t2[i] += a * d / (1f + 3f * abs(d))
                    }
                }
            }
            var blurred = plan.clarityOn
            for ((rp, mask) in masks) {
                if (!rp.clarityOn) continue
                // The same local contrast as Clarity (same blur, already in t4 when Clarity is on),
                // only in the region: clouds, ripples, faces or leaves gain shape.
                if (!blurred) { Filters.blur(t1, t4, t3, w, h, plan.clarityRadius, passes = 3); blurred = true }
                val a = rp.clarity
                Parallel.rows(h) { y0, y1 ->
                    for (i in y0 * w until y1 * w) {
                        val d = t1[i] - t4[i]
                        t2[i] += a * mask[i] * d / (1f + 3f * abs(d))
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

        // ---- 7. grain ----------------------------------------------------------------------
        if (plan.grainOn) Grain.apply(r, g, b, w, h, plan)

        return pack(r, g, b, n, w, h)
    }

    // The preview renders the same image at the same frame over and over: keep its masks.
    private val maskCache = HashMap<MaskRegion, Pair<List<Any>, FloatArray>>()

    private fun maskFor(region: MaskRegion, img: LinearImage, ctx: RenderContext, frame: Frame, inPlace: Boolean): FloatArray {
        val scene = ctx.scene!!
        val mask = scene[region] ?: return FloatArray(img.width * img.height)
        val key = listOf(System.identityHashCode(img), img.width, img.height, mask, frame.x0, frame.y0, frame.scale)
        if (!inPlace) maskCache[region]?.takeIf { it.first == key }?.let { return it.second }
        val m = mask.forFrame(img, frame, ctx.fullWidth, ctx.fullHeight)
        if (!inPlace) maskCache[region] = key to m
        return m
    }

    /**
     * Wavelet shrinkage of luminance [y] (left untouched) into t4. Five "à trous" levels; each
     * detail coefficient is shrunk (non-negative garrote) against k times the noise expected at
     * that scale and that brightness, var = nA·Y + nB (all from [RenderPlan]). Brightness comes from the next coarser
     * approximation, which is already free of fine noise.
     */
    private fun profiledLumaDenoise(y: FloatArray, w: Int, h: Int, plan: RenderPlan) {
        val n = w * h
        val nA = plan.noiseA
        val nB = plan.noiseB
        val k = plan.nrK
        val levels = plan.nrLevels
        System.arraycopy(y, 0, t2, 0, n)
        java.util.Arrays.fill(t4, 0, n, 0f)
        var cur = t2
        var next = t3
        for (j in 0 until levels) {
            Filters.atrous(cur, next, t5, w, h, 1 shl j)
            // The downscale is already in nA/nB, so each level sees white noise of that size.
            // The finest level gets a higher threshold and soft shrinkage: isolated outlier
            // pixels live there, and the garrote would let them through as speckles.
            val finest = j == 0
            val kk = k * RenderPlan.ATROUS_NOISE[j] * (if (finest) RenderPlan.FINEST_BOOST else 1f)
            val c = cur; val nx = next
            Parallel.rows(h) { y0, y1 ->
                for (i in y0 * w until y1 * w) {
                    val d = c[i] - nx[i]
                    val level = max(nx[i], 0f)
                    val t = kk * sqrt(nA * level + nB)
                    t4[i] += if (finest) {
                        if (d > t) d - t else if (d < -t) d + t else 0f
                    } else {
                        // Non-negative garrote: strong edges pass almost untouched, noise-sized detail goes.
                        val t2v = t * t
                        if (d * d > t2v) d - t2v / d else 0f
                    }
                }
            }
            val tmp = cur; cur = next; next = tmp
        }
        val c = cur
        Parallel.rows(h) { y0, y1 -> for (i in y0 * w until y1 * w) t4[i] += c[i] }
    }

    /**
     * The render input after highlight reconstruction: [img] itself when nothing is clipped,
     * otherwise a reconstructed copy. Reconstruction depends only on the file, never on the
     * sliders, so the GPU renderer runs it once per image and starts from the result.
     */
    fun withReconstructedHighlights(img: LinearImage, ctx: RenderContext, frame: Frame): LinearImage {
        if (!highlightReconstruction || !hasClipped(img)) return img
        val n = img.size
        ensureScratch(n, false)
        if (t5.size < n) t5 = FloatArray(cap)
        val out = LinearImage(img.width, img.height, img.r.copyOf(), img.g.copyOf(), img.b.copyOf())
        reconstructHighlights(out.r, out.g, out.b, img.width, img.height, ctx, frame)
        return out
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
        val wbR = ctx.cameraWb[0]; val wbG = ctx.cameraWb[1]; val wbB = ctx.cameraWb[2]
        // Neutral (white after white balance) chromaticity in raw space.
        var nR = 1f / wbR; var nG = 1f / wbG; var nB = 1f / wbB
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
                    // The surrounding colour predicts each clipped channel. A prediction below the
                    // clip level is known to be wrong (the sensor saw at least that much): the
                    // neighbours don't share this surface's colour. Then fall back to the
                    // brightest unclipped channel after white balance, i.e. towards neutral;
                    // otherwise near-white surfaces whose green clipped first turn magenta.
                    val floorWb = max(if (cR) 0f else rr * wbR, max(if (cG) 0f else gg * wbG, if (cB) 0f else bb * wbB))
                    // Neither may an estimate leave the pixel less neutral than the data allows.
                    // One channel clipped (nearly always green, the most sensitive): keep it at least
                    // at the mean of the other two after white balance, or a bright overcast sky goes
                    // pink when its neighbours' colour is a little warm. Two clipped: the higher of
                    // their recorded levels is a hard lower bound, so keep the other at least halfway
                    // between it and the unclipped channel; otherwise a blown sky is ringed in
                    // lavender where blue joins green in clipping.
                    val twoClipped = (if (cR) 1 else 0) + (if (cG) 1 else 0) + (if (cB) 1 else 0) == 2
                    val clipWb = max(if (cR) rr * wbR else 0f, max(if (cG) gg * wbG else 0f, if (cB) bb * wbB else 0f))
                    val neutralWb = if (twoClipped) {
                        0.5f * (floorWb + clipWb)
                    } else {
                        0.5f * ((if (cR) 0f else rr * wbR) + (if (cG) 0f else gg * wbG) + (if (cB) 0f else bb * wbB))
                    }
                    fun recover(v: Float, chroma: Float, wb: Float): Float {
                        val est = scale * chroma
                        val pick = max(if (est >= CLIP) est else max(est, floorWb / wb), neutralWb / wb)
                        return max(v, min(MAX_RECOVERED, pick))
                    }
                    if (cR) r[i] = recover(rr, chR, wbR)
                    if (cG) g[i] = recover(gg, chG, wbG)
                    if (cB) b[i] = recover(bb, chB, wbB)
                }
            }
        }
    }

    companion object {
        private val LN2 = ln(2.0).toFloat()

        /** Normalised raw level treated as clipped (the white point minus a safety margin). */
        const val CLIP = 0.96f
        private const val MAX_RECOVERED = 4f



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
