package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.FullRender
import com.verdictzero.darkhall.core.develop.NoiseCalibration
import com.verdictzero.darkhall.core.develop.NoiseProfile
import com.verdictzero.darkhall.core.develop.Region
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.orf.OlympusDecoder
import com.verdictzero.darkhall.core.orf.OrfParser
import com.verdictzero.darkhall.core.orf.RawImage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class NoiseTest {

    private val samplePath = System.getProperty("darkhall.sample").orEmpty()

    private fun loadRaw(): RawImage {
        val bytes = File(samplePath).readBytes()
        val meta = OrfParser.parse(bytes)
        return OlympusDecoder.decode(bytes, meta)
    }

    /** Adds Poisson-Gaussian noise var = a·x + b (normalised units) to a copy of [raw]. */
    private fun addNoise(raw: RawImage, a: Double, b: Double, seed: Long, replaceWith: ((Int, Int) -> Double)? = null): RawImage {
        val m = raw.meta
        val rnd = Random(seed)
        val out = raw.data.copyOf()
        val span = (m.whiteLevel - m.blackLevels[1]).toDouble()
        for (y in 0 until raw.height) for (x in 0 until raw.width) {
            val i = y * raw.width + x
            val c = m.cfaColor(y, x)
            val black = m.blackLevels[c]
            val base = replaceWith?.invoke(y, x) ?: (((raw.data[i].toInt() and 0xFFFF) - black) / span)
            val sd = sqrt(maxOf(0.0, a * base + b))
            val v = base + rnd.nextGaussian() * sd
            out[i] = (black + v * span).toInt().coerceIn(0, 4095).toShort()
        }
        return RawImage(raw.width, raw.height, out, m)
    }

    @Test
    fun estimatorIsUnbiased() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val raw = loadRaw()
        // A smooth synthetic scene (no texture) spanning the full range, with known noise.
        val w = raw.width.toDouble(); val h = raw.height.toDouble()
        val trueA = 8e-4; val trueB = 4e-5
        val synth = addNoise(raw, trueA, trueB, 1, replaceWith = { y, x -> 0.01 + 0.85 * (x / w) * (0.3 + 0.7 * y / h) })
        val p = NoiseProfile.estimate(synth)
        println("synthetic truth a=$trueA b=$trueB -> $p")
        for (c in 0 until 3) {
            assertTrue(p.a[c] in (trueA * 0.85).toFloat()..(trueA * 1.15).toFloat(), "a[$c]=${p.a[c]}")
            assertTrue(p.b[c] in (trueB * 0.6).toFloat()..(trueB * 1.4).toFloat(), "b[$c]=${p.b[c]}")
        }
    }

    /** Prints measured / modelled luminance noise after demosaicing; used to set DEMOSAIC_NOISE. */
    @Test
    fun demosaicNoiseFactor() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val raw = loadRaw()
        val a = 1e-3; val b = 1e-5; val level = 0.2
        val flat = addNoise(raw, a, b, 3, replaceWith = { _, _ -> level })
        val ctx = RenderContext.forFile(flat.meta, Demosaic.superpixel(flat).resizeArea(400, 300))
        val m = ctx.matrix; val wb = ctx.cameraWb
        fun lumVar(img: com.verdictzero.darkhall.core.develop.LinearImage): Pair<Double, Double> {
            var s = 0.0; var s2 = 0.0; var n = 0
            for (y in 20 until img.height - 20) for (x in 20 until img.width - 20) {
                val i = y * img.width + x
                val cr = img.r[i] * wb[0]; val cg = img.g[i] * wb[1]; val cb = img.b[i] * wb[2]
                var yy = 0.0
                for (k in 0 until 3) {
                    val lum = doubleArrayOf(0.2126, 0.7152, 0.0722)[k]
                    yy += lum * (m[3 * k] * cr + m[3 * k + 1] * cg + m[3 * k + 2] * cb)
                }
                s += yy; s2 += yy * yy; n++
            }
            val mean = s / n
            return mean to (s2 / n - mean * mean)
        }
        // Model without the demosaic factor (ev = 1, gains = camera WB).
        var nA = 0.0; var nB = 0.0
        for (c in 0 until 3) {
            val beta = 0.2126 * m[c] + 0.7152 * m[3 + c] + 0.0722 * m[6 + c]
            nA += wb[c] * beta * beta * a
            nB += wb[c] * wb[c] * beta * beta * b
        }
        val full = Demosaic.malvar(flat, Region(1000, 1000, 600, 600))
        val (mf, vf) = lumVar(full)
        val half = Demosaic.superpixel(flat, Region(1000, 1000, 1200, 1200))
        val (mh, vh) = lumVar(half)
        val modelFull = nA * mf + nB
        val modelHalf = (nA * mh + nB) * 0.25
        println("malvar measured/model = ${vf / modelFull}   superpixel measured/(model*0.25) = ${vh / modelHalf}")
        // The per-file calibration must agree with this independent, larger measurement.
        val cal = NoiseCalibration.demosaicFactors(flat.meta, m)
        println("per-file calibration: full=${cal[0]} half=${cal[1]}")
        assertTrue(abs(vf / modelFull - cal[0]) < 0.08, "full-res factor: calibration ${cal[0]} vs measured ${vf / modelFull}")
        assertTrue(abs(vh / (nA * mh + nB) - cal[1]) < 0.08, "half-res factor: calibration ${cal[1]} vs measured ${vh / (nA * mh + nB)}")
        assertTrue(abs(ctx.demosaicNoise(0.5f) - cal[1]) < 1e-5)
    }

    @Test
    fun measuresRealFileAndAddedNoise() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val raw = loadRaw()
        val base = NoiseProfile.estimate(raw)
        println("${raw.meta.model} ISO ${raw.meta.iso}: $base")
        assertTrue(base.measured)
        // Simulate ISO 1600 on top: 16x the shot noise, 256x the read noise of base ISO.
        val addA = 16.0 * NoiseProfile.REF_A; val addB = 256.0 * NoiseProfile.REF_B
        val noisy = NoiseProfile.estimate(addNoise(raw, addA, addB, 7))
        println("plus simulated ISO 1600 noise: $noisy")
        for (c in 0 until 3) {
            val expectA = base.a[c] + addA
            assertTrue(noisy.a[c] in (expectA * 0.75).toFloat()..(expectA * 1.3).toFloat(), "a[$c]=${noisy.a[c]} expected≈$expectA")
        }
        assertTrue(noisy.effectiveIso in 1000f..2600f, "effective ISO ${noisy.effectiveIso}")
    }

    /**
     * Denoising quality on a real scene: add ISO-1600-like noise to the base-ISO raw, then
     * compare the developed result with the clean original. The profiled denoiser must remove
     * most of the added error while keeping edges (gradient energy) close to the clean image.
     */
    @Test
    fun profiledDenoiseRemovesNoiseKeepsDetail() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val clean = loadRaw()
        val noisy = addNoise(clean, 16.0 * NoiseProfile.REF_A, 256.0 * NoiseProfile.REF_B, 11)
        val region = Region(1400, 1000, 700, 500)
        fun render(raw: RawImage, p: DevelopParams): IntArray {
            val ctx = RenderContext.forFile(raw.meta, Demosaic.superpixel(raw).resizeArea(1000, 750), raw)
            return com.verdictzero.darkhall.core.develop.RegionRender.render(raw, ctx, p, region, 1f, Developer()).argb
        }
        val base = DevelopParams.NEUTRAL.with(Adjustment.SHARPEN, 0f).with(Adjustment.CHROMA_NR, 50f)
        val ref = render(clean, base.with(Adjustment.LUMA_NR, 0f))
        val raw0 = render(noisy, base.with(Adjustment.LUMA_NR, 0f))
        val results = (listOf(25f, 50f, 75f)).associateWith { render(noisy, base.with(Adjustment.LUMA_NR, it)) }
        fun mse(a: IntArray, b: IntArray): Double {
            var s = 0.0
            for (i in a.indices) for (sh in intArrayOf(16, 8, 0)) {
                val d = ((a[i] ushr sh) and 0xFF) - ((b[i] ushr sh) and 0xFF); s += d * d
            }
            return s / (a.size * 3)
        }
        fun gradEnergy(a: IntArray): Double {
            var s = 0.0
            val w = region.width
            for (y in 1 until region.height) for (x in 1 until w) {
                val g = (a[y * w + x] ushr 8) and 0xFF
                val gx = g - ((a[y * w + x - 1] ushr 8) and 0xFF); val gy = g - ((a[(y - 1) * w + x] ushr 8) and 0xFF)
                // Only strong edges: noise-sized steps are excluded.
                val e = gx * gx + gy * gy
                if (e > 400) s += e
            }
            return s
        }
        val e0 = mse(raw0, ref)
        println("MSE vs clean: noisy=$e0  " + results.entries.joinToString { "NR ${it.key.toInt()}=${mse(it.value, ref)}" })
        println("strong-edge energy: clean=${gradEnergy(ref)} noisy=${gradEnergy(raw0)} " + results.entries.joinToString { "NR ${it.key.toInt()}=${gradEnergy(it.value)}" })
        val outDir = System.getProperty("darkhall.outDir").orEmpty()
        if (outDir.isNotEmpty()) {
            fun save(n: String, px: IntArray) {
                val img = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_RGB)
                img.setRGB(0, 0, region.width, region.height, px, 0, region.width)
                ImageIO.write(img, "png", File(outDir, n))
            }
            save("nr_clean.png", ref); save("nr_noisy.png", raw0)
            for ((k, v) in results) save("nr_${k.toInt()}.png", v)
        }
        val e50 = mse(results[50f]!!, ref)
        assertTrue(e50 < e0 * 0.5, "NR 50 should halve the error: $e50 vs $e0")
    }
}
