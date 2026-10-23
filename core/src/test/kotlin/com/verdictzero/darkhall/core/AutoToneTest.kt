package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.AutoTone
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.OlympusDecoder
import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.raw.OrfParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertTrue

class AutoToneTest {
    private val samplePath = System.getProperty("darkhall.sample").orEmpty()
    private val outDir = System.getProperty("darkhall.outDir").orEmpty()

    private class Scene(val meta: RawMetadata, val pv: LinearImage, val ctx: RenderContext, val frame: Frame, val base: DevelopParams)

    private fun load(): Scene {
        val bytes = File(samplePath).readBytes()
        val meta = OrfParser.parse(bytes)
        val raw = OlympusDecoder.decode(bytes, meta)
        val pv = Demosaic.superpixel(raw).resizeArea(800, 600)
        val ctx = RenderContext.forFile(meta, pv, raw)
        return Scene(meta, pv, ctx, Frame(0, 0, pv.width.toFloat() / meta.crop.width), DevelopParams.defaultsFor(meta, ctx.noise))
    }

    private fun transformed(img: LinearImage, f: (Float) -> Float) =
        LinearImage(img.width, img.height, FloatArray(img.size) { f(img.r[it]) }, FloatArray(img.size) { f(img.g[it]) }, FloatArray(img.size) { f(img.b[it]) })

    private fun describe(p: DevelopParams) = AutoTone.SLIDERS.joinToString(" ") { "${it.name.take(4)}=${it.format(p[it])}" }

    @Test
    fun exposureFollowsTheScene() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val s = load()
        val auto = AutoTone.solve(s.pv, s.ctx, s.base, s.frame)
        println("as shot: ${describe(auto.params)}  cost=${auto.cost} evals=${auto.evaluations}")
        // Two stops darker in, two stops more exposure out (the rest of the tone unchanged).
        val dark = AutoTone.solve(transformed(s.pv) { it / 4f }, s.ctx, s.base, s.frame)
        println("-2 EV:   ${describe(dark.params)}")
        val shift = dark.params[Adjustment.EXPOSURE] - auto.params[Adjustment.EXPOSURE]
        assertTrue(abs(shift - 2f) <= 0.15f, "expected +2 EV shift, got $shift")
        val st = AutoTone.evaluate(AutoTone.samples(s.pv, s.ctx, s.base, s.frame), s.ctx, auto.params, s.frame)
        println("auto stats: mean=%.3f p2=%.3f p98=%.3f clipped=%.4f crushed=%.4f".format(st.mean, st.low, st.high, st.clipped, st.crushed))
        assertTrue(st.mean in 0.33f..0.55f, "mean ${st.mean}")
        assertTrue(st.clipped < 0.01f, "clipped ${st.clipped}")
        assertTrue(st.crushed < 0.02f, "crushed ${st.crushed}")
    }

    @Test
    fun rescuesHardScenesAndShowsThem() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val s = load()
        // Hazy, low-contrast scene (like murky water): compress toward grey.
        // Neutral haze: equal after white balance, so it adds grey rather than a colour cast.
        val wb = s.meta.cameraWb
        val hazyImg = LinearImage(s.pv.width, s.pv.height,
            FloatArray(s.pv.size) { 0.06f / wb[0] + s.pv.r[it] * 0.35f },
            FloatArray(s.pv.size) { 0.06f / wb[1] + s.pv.g[it] * 0.35f },
            FloatArray(s.pv.size) { 0.06f / wb[2] + s.pv.b[it] * 0.35f })
        val hazy = AutoTone.solve(hazyImg, s.ctx, s.base, s.frame)
        val hazySamples = AutoTone.samples(hazyImg, s.ctx, s.base, s.frame)
        val hs = AutoTone.evaluate(hazySamples, s.ctx, hazy.params, s.frame)
        val h0 = AutoTone.evaluate(hazySamples, s.ctx, s.base, s.frame)
        println("hazy:   ${describe(hazy.params)}  range ${"%.3f".format(h0.low)}..${"%.3f".format(h0.high)} -> ${"%.3f".format(hs.low)}..${"%.3f".format(hs.high)}")
        assertTrue(hazy.params[Adjustment.BLACKS] < 0f || hazy.params[Adjustment.CONTRAST] > 0f, "haze should get deeper blacks or more contrast")
        assertTrue((hs.high - hs.low) > (h0.high - h0.low) * 1.6f, "auto should widen a hazy scene's range a lot")
        // Overexposed by 1.5 stops with clipping at the sensor.
        val overImg = transformed(s.pv) { minOf(it * 2.83f, 1f) }
        val over = AutoTone.solve(overImg, s.ctx, s.base, s.frame)
        println("over:   ${describe(over.params)}")
        assertTrue(over.params[Adjustment.EXPOSURE] < -0.5f, "overexposed scene should get negative exposure")
        val under = AutoTone.solve(transformed(s.pv) { it / 6f }, s.ctx, s.base, s.frame)
        println("under:  ${describe(under.params)}")

        if (outDir.isNotEmpty()) {
            val dev = Developer()
            val w = s.pv.width / 2; val h = s.pv.height / 2
            // Downscale first, then simulate the exposure problem: clipping happens at the sensor,
            // and clipping before averaging would hide clipped pixels from highlight recovery.
            val small = s.pv.resizeArea(w, h)
            val rows = listOf(
                "as shot" to small, "-2.6 EV" to transformed(small) { it / 6f },
                "+1.5 EV clipped" to transformed(small) { minOf(it * 2.83f, 1f) },
                "hazy" to LinearImage(w, h, FloatArray(w * h) { 0.06f / wb[0] + small.r[it] * 0.35f },
                    FloatArray(w * h) { 0.06f / wb[1] + small.g[it] * 0.35f }, FloatArray(w * h) { 0.06f / wb[2] + small.b[it] * 0.35f }),
            )
            val sheet = BufferedImage(w * 2, h * rows.size, BufferedImage.TYPE_INT_RGB)
            val g = sheet.createGraphics()
            for ((ri, row) in rows.withIndex()) {
                val img = row.second
                val frame = Frame(0, 0, w.toFloat() / s.meta.crop.width)
                val auto = AutoTone.solve(img, s.ctx, s.base, frame).params
                for ((ci, p) in listOf(s.base, auto).withIndex()) {
                    val px = dev.render(img, s.ctx, p, frame)
                    sheet.setRGB(ci * w, ri * h, w, h, px, 0, w)
                }
                g.color = Color.WHITE
                g.drawString("${row.first}: default | auto  (${describe(auto)})", 6, ri * h + 14)
            }
            ImageIO.write(sheet, "png", File(outDir, "auto_tone.png"))
        }
    }
}
