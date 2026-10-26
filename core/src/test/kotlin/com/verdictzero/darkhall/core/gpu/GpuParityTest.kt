package com.verdictzero.darkhall.core.gpu

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.develop.LookSource
import com.verdictzero.darkhall.core.develop.Lut
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.OlympusDecoder
import com.verdictzero.darkhall.core.raw.OrfParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * Runs the GPU pipeline on a real OpenGL ES 3 implementation and requires it to reproduce
 * the CPU renderer (the export reference) for every slider at both extremes.
 */
class GpuParityTest {
    private val samplePath = System.getProperty("darkhall.sample").orEmpty()

    @Test
    fun gpuMatchesCpuForEverySlider() = run(halfFloat = false)

    /**
     * The 16-bit float path (GPUs without 32-bit float render targets). Reported, not asserted:
     * the app's on-device check decides whether such a GPU is good enough.
     */
    @Test
    fun halfFloatPathReport() = run(halfFloat = true)

    private fun run(halfFloat: Boolean) {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val egl = EglContext.create()
        assumeTrue("no OpenGL ES 3 available", egl != null)
        try {
            val bytes = File(samplePath).readBytes()
            val meta = OrfParser.parse(bytes)
            val raw = OlympusDecoder.decode(bytes, meta)
            val pv = Demosaic.superpixel(raw).resizeArea(1280, 960)
            val looksDir = File(System.getProperty("darkhall.looksDir").orEmpty())
            val ctx = RenderContext.forFile(meta, pv, raw, looks = LookSource { id ->
                File(looksDir, "$id.dlut").takeIf { it.exists() }?.let { Lut.decode(it.readBytes()) }
            })
            val frame = Frame(0, 0, pv.width.toFloat() / meta.crop.width)
            val cpu = Developer()
            val gpu = GpuDeveloper(LwjglGl(), forceHalfFloat = halfFloat)
            gpu.init()
            println("GPU: ${gpu.description}")
            gpu.setImage(cpu.withReconstructedHighlights(pv, ctx, frame))
            val base = DevelopParams.defaultsFor(meta, ctx.noise)
            val sets = buildList {
                add("defaults" to base)
                for (a in Adjustment.entries) {
                    add("${a.name}=min" to base.with(a, a.min))
                    add("${a.name}=max" to base.with(a, a.max))
                }
                for (look in listOf("rt-color-fuji-fuji-velvia-50", "rt-black-and-white-ilford-ilford-hp5-plus-400",
                        "rt-color-creativepack-1-tealorange", "dh-duotone-darkhall-violet", "dh-underwater-reef")) {
                    add("look $look" to base.withLook(look))
                }
                add("look at 40%" to base.withLook("rt-color-kodak-kodak-portra-400-2").with(Adjustment.LOOK_AMOUNT, 40f))
                add("grading, mixer and grain" to base.with(Adjustment.GRADE_SHADOW, 70f).with(Adjustment.GRADE_SHADOW_HUE, 200f)
                    .with(Adjustment.GRADE_MIDTONE, 40f).with(Adjustment.GRADE_MIDTONE_HUE, 300f)
                    .with(Adjustment.GRADE_HIGHLIGHT, 80f).with(Adjustment.GRADE_HIGHLIGHT_HUE, 40f).with(Adjustment.GRADE_BALANCE, 35f)
                    .with(Adjustment.MIX_BLUE_HUE, -60f).with(Adjustment.MIX_GREEN_SAT, -70f).with(Adjustment.MIX_ORANGE_LUM, 50f)
                    .with(Adjustment.GRAIN, 80f).with(Adjustment.GRAIN_SIZE, 60f))
                add("combo" to base.with(Adjustment.DEHAZE, 40f).with(Adjustment.CLARITY, 30f).with(Adjustment.WATER, 50f)
                    .with(Adjustment.EXPOSURE, 1.2f).with(Adjustment.STRAIGHTEN, 3f).with(Adjustment.LUMA_NR, 60f))
            }
            var worst = 0.0
            val failures = ArrayList<String>()
            for ((label, p) in sets) {
                val c = Geometry.apply(cpu.render(pv, ctx, p, frame), pv.width, pv.height, p, ctx.lens)
                val t0 = System.nanoTime()
                val g = gpu.render(ctx, p, frame)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val s = ImageCompare.of(c, g)
                println("%-44s %s  (%d ms)".format(label, s, ms))
                worst = maxOf(worst, s.meanAbs)
                if (!s.acceptable) failures += "$label: $s"
                val out = System.getProperty("darkhall.outDir").orEmpty()
                if (out.isNotEmpty() && (label == "defaults" || label == "combo")) {
                    for ((n, px) in listOf("cpu" to c, "gpu" to g)) {
                        val img = BufferedImage(pv.width, pv.height, BufferedImage.TYPE_INT_RGB)
                        img.setRGB(0, 0, pv.width, pv.height, px, 0, pv.width)
                        ImageIO.write(img, "png", File(out, "parity_${label}_$n.png"))
                    }
                }
            }
            gpu.release()
            println("worst mean difference: $worst (${if (halfFloat) "16-bit" else "32-bit"} float)")
            if (halfFloat) println("16-bit sets outside tolerance: ${failures.size} of ${sets.size}: " + failures.joinToString("; ").take(800))
            else assertTrue(failures.isEmpty(), "GPU differs from CPU:\n" + failures.joinToString("\n"))
        } finally {
            egl!!.close()
        }
    }
}
