package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.develop.ColorScience.LUM_B
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_G
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_R
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Automatic exposure and tone: picks Exposure, Contrast, Highlights, Shadows, Whites and
 * Blacks for a photo.
 *
 * It samples the picture after white balance, the underwater correction and the camera
 * matrix (so it respects the colour settings already made), then searches the six sliders,
 * judging every candidate through the real [ToneStage] — shoulder, curve and clipping
 * included. The target is a balanced mean brightness, a tonal range that reaches from near
 * black to near white, few blown or crushed pixels, and sliders moved no further than needed.
 * Spatial effects (dehaze, clarity, noise reduction) are not part of the judgement; heavy haze
 * is left for the Dehaze slider.
 */
object AutoTone {
    val SLIDERS = listOf(
        Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS,
        Adjustment.SHADOWS, Adjustment.WHITES, Adjustment.BLACKS,
    )

    /** Display-luminance goals; see [cost]. */
    private const val TARGET_MEAN = 0.44f
    private const val TARGET_LOW = 0.045f
    private const val TARGET_HIGH = 0.93f

    /**
     * Search ranges, in the order of [SLIDERS]. Narrower than the sliders themselves and
     * signed the way a person would use them: Shadows only lifts, Highlights only recovers,
     * and Contrast / Whites / Blacks provide the punch within moderate limits, so auto never
     * "wins" with sliders fighting each other.
     */
    private val RANGES = arrayOf(
        Adjustment.EXPOSURE.min..Adjustment.EXPOSURE.max,
        -40f..50f, -100f..0f, 0f..100f, -40f..60f, -60f..30f,
    )

    /** Linear working-space RGB samples at exposure 0. */
    class Samples(val r: FloatArray, val g: FloatArray, val b: FloatArray) {
        val size get() = r.size
    }

    /** What [solve] found, for the UI and tests. */
    class Result(val params: DevelopParams, val cost: Float, val evaluations: Int)

    fun samples(img: LinearImage, ctx: RenderContext, base: DevelopParams, frame: Frame, maxSamples: Int = 6000): Samples {
        val plan = RenderPlan(ctx, base.with(Adjustment.EXPOSURE, 0f), frame)
        val step = max(1, sqrt(img.size.toDouble() / maxSamples).roundToInt())
        val n = ((img.width + step - 1) / step) * ((img.height + step - 1) / step)
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        val m = plan.matrix
        var k = 0
        var y = step / 2
        while (y < img.height) {
            val fy = plan.frameY0 + y * plan.invScale - plan.centerY
            var x = step / 2
            while (x < img.width) {
                val i = y * img.width + x
                val cr = max(0f, img.r[i] * plan.mulA[0] - plan.subB[0])
                val cg = max(0f, img.g[i] * plan.mulA[1] - plan.subB[1])
                val cb = max(0f, img.b[i] * plan.mulA[2] - plan.subB[2])
                var v = 1f
                if (plan.vignette != 0f) {
                    val fx = plan.frameX0 + x * plan.invScale - plan.centerX
                    v = 1f + plan.vignette * (fx * fx + fy * fy) * plan.invR2
                }
                r[k] = (m[0] * cr + m[1] * cg + m[2] * cb) * v
                g[k] = (m[3] * cr + m[4] * cg + m[5] * cb) * v
                b[k] = (m[6] * cr + m[7] * cg + m[8] * cb) * v
                k++
                x += step
            }
            y += step
        }
        return Samples(r.copyOf(k), g.copyOf(k), b.copyOf(k))
    }

    /** Statistics of the rendered display image for one candidate. */
    class Stats(val mean: Float, val low: Float, val high: Float, val clipped: Float, val crushed: Float)

    fun evaluate(samples: Samples, ctx: RenderContext, params: DevelopParams, frame: Frame): Stats {
        val plan = RenderPlan(ctx, params.withLook(null), frame)
        val ev = plan.ev
        val out = FloatArray(3)
        val scratch = FloatArray(3)
        val n = samples.size
        val lum = FloatArray(n)
        var sum = 0.0
        var clipped = 0
        var crushed = 0
        for (i in 0 until n) {
            ToneStage.apply(plan, samples.r[i] * ev, samples.g[i] * ev, samples.b[i] * ev, out, scratch)
            val l = LUM_R * out[0] + LUM_G * out[1] + LUM_B * out[2]
            lum[i] = l
            sum += l
            if (max(out[0], max(out[1], out[2])) > 0.985f) clipped++
            if (l < 0.012f) crushed++
        }
        lum.sort()
        return Stats(
            (sum / n).toFloat(),
            lum[(n * 0.02).toInt()],
            lum[(n * 0.98).toInt().coerceAtMost(n - 1)],
            clipped.toFloat() / n,
            crushed.toFloat() / n,
        )
    }

    private fun cost(s: Stats, sliders: FloatArray): Float {
        var reg = 0f
        for (i in 1 until sliders.size) reg += (sliders[i] / 100f) * (sliders[i] / 100f)
        val dm = s.mean - TARGET_MEAN
        val dl = s.low - TARGET_LOW
        val dh = s.high - TARGET_HIGH
        return 4f * dm * dm +
            2.5f * s.clipped +
            1.0f * s.crushed +
            1.5f * (dl * dl + dh * dh) +
            0.06f * reg
    }

    private fun params(base: DevelopParams, x: FloatArray): DevelopParams {
        var p = base
        for ((i, a) in SLIDERS.withIndex()) p = p.with(a, x[i])
        return p
    }

    /** Returns [base] with the six tone sliders set automatically. */
    fun solve(img: LinearImage, ctx: RenderContext, base: DevelopParams, frame: Frame): Result {
        val s = samples(img, ctx, base, frame)
        if (s.size < 16) return Result(base, 0f, 0)
        // Start from the exposure that puts the scene's log-average luminance at mid-grey.
        var logSum = 0.0
        for (i in 0 until s.size) logSum += ln(LUM_R * s.r[i] + LUM_G * s.g[i] + LUM_B * s.b[i] + 1e-4)
        val key = exp(logSum / s.size).toFloat()
        val ev0 = (ln(0.18f / key) / ln(2f)).coerceIn(Adjustment.EXPOSURE.min, Adjustment.EXPOSURE.max)

        val x = floatArrayOf(ev0, 0f, 0f, 0f, 0f, 0f)
        val steps = floatArrayOf(0.5f, 20f, 20f, 20f, 20f, 20f)
        val minSteps = floatArrayOf(0.02f, 2f, 2f, 2f, 2f, 2f)
        var evals = 0
        fun c(v: FloatArray): Float { evals++; return cost(evaluate(s, ctx, params(base, v), frame), v) }
        var best = c(x)
        var guard = 0
        while (guard++ < 120) {
            var improved = false
            for (i in x.indices) {
                val range = RANGES[i]
                for (dir in floatArrayOf(1f, -1f)) {
                    val trial = x.copyOf()
                    trial[i] = (x[i] + dir * steps[i]).coerceIn(range.start, range.endInclusive)
                    if (trial[i] == x[i]) continue
                    val ct = c(trial)
                    if (ct < best - 1e-7f) {
                        best = ct; x[i] = trial[i]; improved = true
                        break
                    }
                }
            }
            if (!improved) {
                var any = false
                for (i in steps.indices) if (steps[i] > minSteps[i]) { steps[i] = max(minSteps[i], steps[i] / 2f); any = true }
                if (!any) break
            }
        }
        // Round to what a person would set: 0.05 EV, whole slider units.
        x[0] = (x[0] * 20f).roundToInt() / 20f
        for (i in 1 until x.size) x[i] = x[i].roundToInt().toFloat()
        // Drop slider moves too small to matter.
        for (i in 1 until x.size) if (abs(x[i]) < 3f) x[i] = 0f
        val result = params(base, x)
        return Result(result, cost(evaluate(s, ctx, result, frame), x), evals)
    }
}
