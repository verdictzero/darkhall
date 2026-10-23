package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.develop.ColorScience.LUM_B
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_G
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every number the slider pipeline derives from the sliders, the file and the render scale,
 * computed once per render. The CPU [Developer] and the GPU renderer both execute this plan,
 * so they cannot drift apart in how a slider is interpreted.
 */
class RenderPlan(ctx: RenderContext, p: DevelopParams, frame: Frame) {
    // ---- colour ------------------------------------------------------------------------------
    /** Per channel: c = max(0, raw·mulA − subB), then the camera matrix. */
    val mulA = FloatArray(3)
    val subB = FloatArray(3)
    val ev: Float = pow2(p[Adjustment.EXPOSURE])
    val matrix: FloatArray = ctx.matrix
    val vignette: Float = p[Adjustment.VIGNETTE] / 100f * 0.8f
    val centerX: Float = ctx.fullWidth * 0.5f
    val centerY: Float = ctx.fullHeight * 0.5f
    val invR2: Float = 1f / (centerX * centerX + centerY * centerY)
    val invScale: Float = 1f / frame.scale
    val frameX0: Int = frame.x0
    val frameY0: Int = frame.y0

    // ---- dehaze ------------------------------------------------------------------------------
    val dehaze: Float = p[Adjustment.DEHAZE] / 100f
    val dehazeOn: Boolean = abs(dehaze) > 0.005f
    /** Atmospheric light in working space. */
    val atmosphere = FloatArray(3)
    val dehazeMinRadius: Int = max(1, (0.008f * ctx.fullWidth * frame.scale).roundToInt())
    val dehazeBlurRadius: Int = max(1, dehazeMinRadius * 2)
    val dehazeOmega: Float = 0.85f * dehaze
    val dehazeNegative: Float = -dehaze * 0.5f

    // ---- noise reduction -------------------------------------------------------------------
    val lumaNr: Float = p[Adjustment.LUMA_NR] / 100f
    val chromaNr: Float = p[Adjustment.CHROMA_NR] / 100f
    val lumaOn: Boolean = lumaNr > 0.005f
    val chromaOn: Boolean = chromaNr > 0.005f
    val chromaRadius: Int = max(1, ((4f + 32f * chromaNr) * frame.scale).roundToInt())
    /** Luminance noise variance in working space: var = nA·Y + nB. */
    val noiseA: Float
    val noiseB: Float
    /** Garrote threshold in noise sigmas. */
    val nrK: Float = lumaNr * 3f
    /** Wavelet levels: fewer when the image is downscaled, as its pixels are coarser scales. */
    val nrLevels: Int = 5 - (if (frame.scale >= 1f) 0 else (ln(1f / frame.scale) / LN2).roundToInt().coerceIn(0, 3))

    // ---- tone + colour ----------------------------------------------------------------------
    val shadows: Float = p[Adjustment.SHADOWS] / 100f * 1.5f * LN2
    val highlights: Float = p[Adjustment.HIGHLIGHTS] / 100f * 1.2f * LN2
    val shadowsHighlightsOn: Boolean = abs(shadows) > 1e-4f || abs(highlights) > 1e-4f
    val vibrance: Float = p[Adjustment.VIBRANCE] / 100f
    val saturation: Float = p[Adjustment.SATURATION] / 100f
    val saturationOn: Boolean = abs(vibrance) > 1e-4f || abs(saturation) > 1e-4f
    /** Display-space curve (blacks → whites → contrast) on 4097 points. */
    val curve = FloatArray(CURVE_SIZE)

    // ---- look (3D LUT on display-encoded colour, after tone and saturation) ---------------------
    val lookId: String? = p.look
    val look: Lut? = p.look?.let { ctx.looks.lut(it) }
    val lookAmount: Float = p[Adjustment.LOOK_AMOUNT] / 100f
    val lookOn: Boolean = look != null && lookAmount > 0.005f

    // ---- detail ------------------------------------------------------------------------------
    val clarity: Float = p[Adjustment.CLARITY] / 100f
    val sharpen: Float = p[Adjustment.SHARPEN] / 100f
    val sharpenOn: Boolean = sharpen > 0.005f
    val clarityOn: Boolean = abs(clarity) > 0.005f
    val detailOn: Boolean = sharpenOn || clarityOn
    val sharpenRadius: Int = max(1, (1.0f * frame.scale).roundToInt())
    val sharpenAmount: Float = sharpen * 1.6f
    val clarityRadius: Int = max(2, (0.015f * ctx.fullWidth * frame.scale).roundToInt())
    val clarityAmount: Float = clarity * 0.7f

    init {
        val temp = p[Adjustment.TEMPERATURE] / 100f
        val tint = p[Adjustment.TINT] / 100f
        val user = floatArrayOf(pow2(temp * 0.8f), pow2(-tint * 0.5f), pow2(-temp * 0.8f))
        // B removes the water's veil; A holds the camera WB, the underwater grey-world gain
        // (geometric blend: half the slider is half the correction in stops) and temperature/tint.
        val water = p[Adjustment.WATER] / 100f
        for (c in 0 until 3) {
            val wg = exp(water * ln(ctx.water.gain[c]))
            mulA[c] = ctx.cameraWb[c] * wg * user[c]
            subB[c] = water * ctx.water.floor[c] * wg * user[c]
        }
        val m = matrix
        // Push the haze estimate through the same colour transform.
        val hr0 = max(0f, ctx.haze[0] * mulA[0] - subB[0])
        val hg0 = max(0f, ctx.haze[1] * mulA[1] - subB[1])
        val hb0 = max(0f, ctx.haze[2] * mulA[2] - subB[2])
        atmosphere[0] = max(0.05f, (m[0] * hr0 + m[1] * hg0 + m[2] * hb0) * ev)
        atmosphere[1] = max(0.05f, (m[3] * hr0 + m[4] * hg0 + m[5] * hb0) * ev)
        atmosphere[2] = max(0.05f, (m[6] * hr0 + m[7] * hg0 + m[8] * hb0) * ev)

        // Luminance noise model. For a neutral pixel of linear luminance Y, raw channel c sits at
        // Y / (ev·gain_c), so var(Y) = Y·ev·Σ gain_c β_c² a_c + ev²·Σ gain_c² β_c² b_c, with β_c the
        // luminance weight of camera channel c through the matrix. Downscaled renders average
        // 1/scale² sensor pixels, which divides the variance accordingly.
        var nA = 0f; var nB = 0f
        for (c in 0 until 3) {
            val beta = LUM_R * m[c] + LUM_G * m[3 + c] + LUM_B * m[6 + c]
            nA += mulA[c] * beta * beta * ctx.noise.a[c]
            nB += mulA[c] * mulA[c] * beta * beta * ctx.noise.b[c]
        }
        val s2 = ctx.demosaicNoise(frame.scale)
        noiseA = nA * ev * s2
        noiseB = nB * ev * ev * s2

        val blacks = p[Adjustment.BLACKS] / 100f * 0.3f
        val whites = p[Adjustment.WHITES] / 100f * 0.25f
        val contrast = p[Adjustment.CONTRAST] / 100f
        for (i in 0 until CURVE_SIZE) {
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
        const val CURVE_SIZE = 4097
        val LN2 = ln(2.0).toFloat()

        /** Linear level where the highlight shoulder starts. */
        const val SHOULDER = 0.85f
        /** Pre-shoulder level (about 1.8 stops over white) where highlight desaturation peaks. */
        const val DESAT_END = 3.0f
        const val DESAT_MAX = 0.85f
        const val FINEST_BOOST = 1.25f

        /**
         * Noise standard deviation in each "à trous" detail level for unit white noise
         * (Starck & Murtagh, B3 spline). Index 0 is the finest scale.
         */
        val ATROUS_NOISE = floatArrayOf(0.889f, 0.200f, 0.086f, 0.041f, 0.020f, 0.010f, 0.005f, 0.0025f)

        private fun pow2(x: Float): Float = exp(x * LN2)
        private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
    }
}
