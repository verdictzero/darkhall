package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.FullRender
import com.verdictzero.darkhall.core.develop.LensProfile
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.develop.Region
import com.verdictzero.darkhall.core.develop.RegionRender
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.OlympusDecoder
import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.raw.OrfParser
import com.verdictzero.darkhall.core.raw.RawImage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTest {

    private val samplePath = System.getProperty("darkhall.sample").orEmpty()
    private val outDir = System.getProperty("darkhall.outDir").orEmpty()

    private fun save(name: String, w: Int, h: Int, argb: IntArray) {
        if (outDir.isEmpty()) return
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, argb, 0, w)
        ImageIO.write(img, "png", File(outDir, name))
    }

    private class Loaded(val meta: RawMetadata, val raw: RawImage, val preview: LinearImage)

    private fun load(): Loaded {
        val bytes = File(samplePath).readBytes()
        val meta = OrfParser.parse(bytes)
        val raw = OlympusDecoder.decode(bytes, meta)
        return Loaded(meta, raw, Demosaic.superpixel(raw).resizeArea(1000, 750))
    }

    private fun copy(img: LinearImage) = LinearImage(img.width, img.height, img.r.copyOf(), img.g.copyOf(), img.b.copyOf())

    /** Mean of (R - G) and (B - G) over the picture, in 8-bit display units. */
    private fun cast(argb: IntArray): Pair<Double, Double> {
        var rg = 0.0; var bg = 0.0
        for (px in argb) {
            val r = (px ushr 16) and 0xFF; val g = (px ushr 8) and 0xFF; val b = px and 0xFF
            rg += r - g; bg += b - g
        }
        return rg / argb.size to bg / argb.size
    }

    @Test
    fun underwaterCorrectionRemovesSimulatedWaterCast() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val s = load()
        // Simulate ~8 m of sea water: Beer-Lambert absorption on the way down plus a blue-green veil.
        val depth = 8.0
        val kr = Math.exp(-0.40 * depth).toFloat(); val kg = Math.exp(-0.07 * depth).toFloat(); val kb = Math.exp(-0.03 * depth).toFloat()
        val uw = copy(s.preview)
        val wb = s.meta.cameraWb
        for (i in 0 until uw.size) {
            uw.r[i] = uw.r[i] * kr + 0.005f / wb[0]
            uw.g[i] = uw.g[i] * kg + 0.08f / wb[1]
            uw.b[i] = uw.b[i] * kb + 0.10f / wb[2]
        }
        val ctx = RenderContext.forFile(s.meta, uw)
        println("water floor=${ctx.water.floor.toList()} gain=${ctx.water.gain.toList()}")
        val dev = Developer()
        val frame = Frame(0, 0, uw.width.toFloat() / s.meta.crop.width)
        val base = DevelopParams.defaultsFor(s.meta)
        val before = dev.render(uw, ctx, base, frame)
        val after = dev.render(uw, ctx, base.with(Adjustment.WATER, 80f), frame)
        val reference = dev.render(s.preview, RenderContext.forFile(s.meta, s.preview), base, frame)
        save("water_before.png", uw.width, uw.height, before)
        save("water_after.png", uw.width, uw.height, after)
        save("water_reference.png", uw.width, uw.height, reference)
        val cb = cast(before); val ca = cast(after); val cr = cast(reference)
        println("cast before=$cb after=$ca land-reference=$cr")
        // The simulated water drives R far below G; the slider must remove most of that gap.
        val gapBefore = abs(cb.first - cr.first) + abs(cb.second - cr.second)
        val gapAfter = abs(ca.first - cr.first) + abs(ca.second - cr.second)
        assertTrue(gapAfter < gapBefore * 0.4, "cast gap before=$gapBefore after=$gapAfter")
    }

    @Test
    fun underwaterDefaultFollowsRecordedDepth() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val meta = load().meta
        assertEquals(0f, DevelopParams.defaultsFor(meta)[Adjustment.WATER]) // shot on land
        val dive = meta.copy(waterDepth = 12.0)
        assertEquals(88f, DevelopParams.defaultsFor(dive)[Adjustment.WATER], 0.01f)
        val pressureOnly = meta.copy(waterDepth = null, pressureHpa = 1013.25 + 100.5 * 5)
        assertEquals(5.0, pressureOnly.estimatedDepth!!, 1e-6)
        assertEquals(60f, DevelopParams.defaultsFor(pressureOnly)[Adjustment.WATER], 0.01f)
    }

    @Test
    fun highlightReconstructionRecoversClippedColour() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val s = load()
        // Overexpose by 1.5 stops and clip at the sensor's white point.
        val factor = 2.83f
        val over = copy(s.preview)
        var clipped = 0
        for (i in 0 until over.size) {
            val r = over.r[i] * factor; val g = over.g[i] * factor; val b = over.b[i] * factor
            if (r >= 1f || g >= 1f || b >= 1f) clipped++
            over.r[i] = minOf(r, 1f); over.g[i] = minOf(g, 1f); over.b[i] = minOf(b, 1f)
        }
        println("clipped pixels: $clipped of ${over.size}")
        assumeTrue("sample has too few highlights to test", clipped > over.size / 50)
        val ctx = RenderContext.forFile(s.meta, s.preview)
        val frame = Frame(0, 0, over.width.toFloat() / s.meta.crop.width)
        val p = DevelopParams.NEUTRAL.with(Adjustment.EXPOSURE, -(ln(factor.toDouble()) / ln(2.0)).toFloat())
            .with(Adjustment.SHARPEN, 0f).with(Adjustment.CHROMA_NR, 0f)
        val dev = Developer()
        val truth = dev.render(s.preview, ctx, DevelopParams.NEUTRAL.with(Adjustment.SHARPEN, 0f).with(Adjustment.CHROMA_NR, 0f), frame)
        val withRec = dev.render(over, ctx, p, frame)
        dev.highlightReconstruction = false
        val without = dev.render(over, ctx, p, frame)
        save("hl_truth.png", over.width, over.height, truth)
        save("hl_reconstructed.png", over.width, over.height, withRec)
        save("hl_clipped.png", over.width, over.height, without)
        // Colour error (chroma only) in the clipped pixels.
        fun chromaErr(a: IntArray): Double {
            var e = 0.0; var n = 0
            for (i in 0 until over.size) {
                val isClipped = over.r[i] >= 0.999f || over.g[i] >= 0.999f || over.b[i] >= 0.999f
                if (!isClipped) continue
                val pa = a[i]; val pt = truth[i]
                fun ch(px: Int): Triple<Int, Int, Int> { val r = (px ushr 16) and 0xFF; val g = (px ushr 8) and 0xFF; val b = px and 0xFF; val m = (r + g + b) / 3; return Triple(r - m, g - m, b - m) }
                val (ar, ag, ab) = ch(pa); val (tr, tg, tb) = ch(pt)
                e += abs(ar - tr) + abs(ag - tg) + abs(ab - tb); n++
            }
            return e / maxOf(n, 1)
        }
        val eRec = chromaErr(withRec)
        val eNo = chromaErr(without)
        println("chroma error in clipped areas: reconstructed=$eRec clipped=$eNo")
        assertTrue(eRec < eNo * 0.75, "reconstruction should beat plain clipping: $eRec vs $eNo")
    }

    @Test
    fun lensProfileForTgModelsOnly() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val meta = load().meta
        assertNotNull(LensProfile.forFile(meta), "TG-6/TG-7 should get the built-in profile")
        assertNull(LensProfile.forFile(meta.copy(model = "E-M1MarkIII")))
        // Interpolation hits the table at calibrated focal lengths.
        assertEquals(0.02587f, LensProfile.forFocal(4.5).a, 1e-6f)
        assertEquals(0.00722f, LensProfile.forFocal(9.0).a, 1e-6f)
        assertEquals((0.02587f + 0.00722f) / 2, LensProfile.forFocal(6.75).a, 1e-6f)
    }

    @Test
    fun zoomRegionMatchesFullExport() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val s = load()
        val ctx = RenderContext.forFile(s.meta, s.preview)
        val w = s.meta.crop.width
        for ((label, p) in listOf(
            "plain" to DevelopParams.defaultsFor(s.meta).with(Adjustment.CLARITY, 50f).with(Adjustment.DEHAZE, 40f).with(Adjustment.LUMA_NR, 40f),
            "geometry" to DevelopParams.defaultsFor(s.meta).with(Adjustment.STRAIGHTEN, 2.5f).with(Adjustment.DISTORTION, 20f),
        )) {
            val full = FullRender.render(s.raw, ctx, p)!!
            for (rect in listOf(Region(1500, 1100, 600, 400), Region(0, 0, 500, 300), Region(3500, 2600, 500, 400))) {
                val region = RegionRender.render(s.raw, ctx, p, rect, 1f, Developer())
                assertEquals(rect.width, region.width)
                assertEquals(rect.height, region.height)
                var diff = 0L; var maxDiff = 0
                for (y in 0 until rect.height) for (x in 0 until rect.width) {
                    val a = region.argb[y * rect.width + x]
                    val b = full.argb[(rect.y + y) * w + rect.x + x]
                    for (sh in intArrayOf(16, 8, 0)) {
                        val d = abs(((a ushr sh) and 0xFF) - ((b ushr sh) and 0xFF))
                        diff += d; if (d > maxDiff) maxDiff = d
                    }
                }
                val mean = diff.toDouble() / (rect.width * rect.height * 3)
                println("$label region $rect: mean diff=$mean max=$maxDiff")
                assertTrue(mean < 0.5, "$label region $rect differs from full export: mean $mean")
                if (label == "plain" && rect.x == 1500) save("zoom_region_1to1.png", region.width, region.height, region.argb)
            }
        }
        // Reduced-scale region (the in-between zoom levels) renders without error.
        val small = RegionRender.render(s.raw, ctx, DevelopParams.defaultsFor(s.meta), Region(1000, 800, 2000, 1500), 0.4f, Developer())
        assertEquals(800, small.width)
        assertEquals(600, small.height)
    }
}
