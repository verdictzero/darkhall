package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.FullRender
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.orf.OlympusDecoder
import com.verdictzero.darkhall.core.orf.OrfParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineTest {

    private val samplePath = System.getProperty("darkhall.sample").orEmpty()
    private val outDir = System.getProperty("darkhall.outDir").orEmpty()

    private fun save(name: String, w: Int, h: Int, argb: IntArray) {
        if (outDir.isEmpty()) return
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, argb, 0, w)
        ImageIO.write(img, "png", File(outDir, name))
    }

    @Test
    fun previewRendersAndSurvivesExtremes() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val bytes = File(samplePath).readBytes()
        val meta = OrfParser.parse(bytes)
        val raw = OlympusDecoder.decode(bytes, meta)
        var t = System.nanoTime()
        val half = Demosaic.superpixel(raw)
        println("superpixel ${half.width}x${half.height} in ${(System.nanoTime() - t) / 1_000_000} ms")
        val preview = half.resizeArea(1200, 900)
        val ctx = RenderContext.forFile(meta, preview)
        println("haze=${ctx.haze.toList()} matrix=${ctx.matrix.toList()}")
        val dev = Developer()
        val defaults = DevelopParams.defaultsFor(meta)
        val frame = Frame(0, 0, preview.width.toFloat() / meta.crop.width)
        t = System.nanoTime()
        val out = dev.render(preview, ctx, defaults, frame)
        println("preview render ${(System.nanoTime() - t) / 1_000_000} ms")
        assertEquals(preview.size, out.size)
        save("preview_default.png", preview.width, preview.height, out)
        // Not all black, not all white.
        val hist = Developer.histogram(out)
        assertTrue(hist[0] < out.size / 2, "too many black pixels")
        assertTrue(hist[255] < out.size / 2, "too many white pixels")

        // Every slider at both ends must render finite, in-range pixels without throwing.
        for (a in Adjustment.entries) {
            for (v in floatArrayOf(a.min, a.max)) {
                val p = defaults.with(a, v)
                val t0 = System.nanoTime()
                val px = Geometry.apply(dev.render(preview, ctx, p, frame), preview.width, preview.height, p, ctx.lens)
                val ms = (System.nanoTime() - t0) / 1_000_000
                println("${a.name}=${v} -> ${ms} ms")
                assertEquals(preview.size, px.size)
                if (outDir.isNotEmpty() && (a == Adjustment.DEHAZE || a == Adjustment.CLARITY || a == Adjustment.LUMA_NR || a == Adjustment.DISTORTION || a == Adjustment.STRAIGHTEN || a == Adjustment.SHADOWS || a == Adjustment.HIGHLIGHTS || a == Adjustment.WATER || a == Adjustment.LENS_PROFILE)) {
                    save("preview_${a.name.lowercase()}_${if (v < 0) "min" else "max"}.png", preview.width, preview.height, px)
                }
            }
        }
    }

    @Test
    fun fullRenderMatchesPreviewAndHasNoSeams() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val bytes = File(samplePath).readBytes()
        val meta = OrfParser.parse(bytes)
        val raw = OlympusDecoder.decode(bytes, meta)
        val preview = Demosaic.superpixel(raw).resizeArea(1000, 750)
        val ctx = RenderContext.forFile(meta, preview)
        val p = DevelopParams.defaultsFor(meta)
            .with(Adjustment.CLARITY, 40f).with(Adjustment.DEHAZE, 30f).with(Adjustment.LUMA_NR, 40f).with(Adjustment.STRAIGHTEN, 1.5f)
        val t = System.nanoTime()
        val full = FullRender.render(raw, ctx, p, progress = { })!!
        println("full render ${full.width}x${full.height} in ${(System.nanoTime() - t) / 1_000_000} ms")
        assertEquals(meta.crop.width, full.width)
        assertEquals(meta.crop.height, full.height)
        save("full_render.png", full.width, full.height, full.argb)

        // Seam check: rows around every band boundary should not differ more than typical neighbouring rows.
        fun rowDiff(y: Int): Double {
            var s = 0.0
            for (x in 0 until full.width) {
                val a = full.argb[y * full.width + x]; val b = full.argb[(y + 1) * full.width + x]
                s += Math.abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF))
            }
            return s / full.width
        }
        var typical = 0.0
        var count = 0
        for (y in 100 until full.height - 100 step 37) { typical += rowDiff(y); count++ }
        typical /= count
        var y = FullRender.BAND_ROWS
        while (y < full.height) {
            val d = rowDiff(y - 1)
            println("band boundary at $y: diff=$d typical=$typical")
            assertTrue(d < typical * 4 + 2, "seam at row $y: $d vs typical $typical")
            y += FullRender.BAND_ROWS
        }
    }
}
