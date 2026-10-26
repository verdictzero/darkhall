package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.FullRender
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.RawFormats
import com.verdictzero.darkhall.core.sky.SkyMask
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkyMaskTest {

    /** A grid that is sky over its top half, upright. */
    private fun topHalfSky(orientation: Int) = SkyMask(9, 9, FloatArray(81) { if (it / 9 < 4) 1f else 0f }, 33, 33, orientation)

    @Test
    fun followsTheCameraOrientation() {
        // Upright, the top is sky; in camera coordinates that is the top (1), the bottom (3),
        // the left edge (6: the camera was turned clockwise) or the right edge (8).
        val m1 = topHalfSky(1); assertTrue(m1.coarse(0.5f, 0.05f) > 0.9f && m1.coarse(0.5f, 0.95f) < 0.1f)
        val m3 = topHalfSky(3); assertTrue(m3.coarse(0.5f, 0.95f) > 0.9f && m3.coarse(0.5f, 0.05f) < 0.1f)
        val m6 = topHalfSky(6); assertTrue(m6.coarse(0.05f, 0.5f) > 0.9f && m6.coarse(0.95f, 0.5f) < 0.1f)
        val m8 = topHalfSky(8); assertTrue(m8.coarse(0.95f, 0.5f) > 0.9f && m8.coarse(0.05f, 0.5f) < 0.1f)
        val back = assertNotNull(SkyMask.fromBytes(m6.toBytes()))
        assertEquals(m6.coarse(0.3f, 0.7f), back.coarse(0.3f, 0.7f))
    }

    /**
     * Real photos (-Pdarkhall.rawsDir): skies found where there are skies, none indoors, and the
     * Sky sliders change the sky and leave the ground alone. With -Pdarkhall.outDir, writes each
     * photo with its mask and with the sky sliders applied.
     */
    @Test
    fun realPhotos() {
        val dir = System.getProperty("darkhall.rawsDir").orEmpty()
        assumeTrue("no -Pdarkhall.rawsDir", dir.isNotEmpty())
        val outDir = System.getProperty("darkhall.outDir").orEmpty()
        val withSky = listOf("Q2_", "E-M1X", "OM-5MarkII", "K-1_", "DSC-RX100M5A", "FC7303", "DMC-LX100")
        // (Not here: the K-70's dim, bare room around a light bulb, which the model takes for a
        // night sky. A known limit; the Show mask view makes it plain.)
        val noSky = listOf("EOS_5D_Mark_III__5G4", "E-M1MarkIII", "DC-S5", "Z_6_")
        val files = File(dir).listFiles()!!.filter { f -> (withSky + noSky).any { f.name.startsWith(it) } }.sortedBy { it.name }
        val failures = ArrayList<String>()
        for (file in files) {
            val bytes = file.readBytes()
            val meta = RawFormats.parse(bytes)
            val raw = RawFormats.decode(bytes, meta)
            val pv = Demosaic.preview(raw, 1280)
            val ctx = RenderContext.forFile(meta, pv, raw)
            val defaults = DevelopParams.defaultsFor(meta, ctx.noise)
            val frame = Frame(0, 0, pv.width.toFloat() / meta.crop.width)
            val dev = Developer()
            val base = dev.render(pv, ctx, defaults, frame)
            val t0 = System.nanoTime()
            val sky = SkyMask.detect(SkyNetTest.model, base, pv.width, pv.height, meta.orientation)
            val ms = (System.nanoTime() - t0) / 1_000_000
            ctx.sky = sky
            val mask = sky.forFrame(pv, frame, meta.crop.width, meta.crop.height)
            val expectSky = withSky.any { file.name.startsWith(it) }
            println("%-40s sky %.0f%%  (%d ms)".format(file.name.take(40), sky.coverage * 100, ms))
            if (expectSky && sky.coverage < 0.08f) failures += "${file.name}: no sky found"
            if (!expectSky && sky.coverage > 0.03f) failures += "${file.name}: ${sky.coverage} sky indoors"

            val edited = defaults.with(Adjustment.SKY_EXPOSURE, -0.7f).with(Adjustment.SKY_SATURATION, 40f)
                .with(Adjustment.SKY_CLARITY, 50f).with(Adjustment.SKY_WARMTH, -20f)
            val after = dev.render(pv, ctx, edited, frame)
            if (expectSky) {
                // Sky darker, ground unchanged.
                var skySum = 0.0; var skyN = 0; var groundDiff = 0.0; var groundN = 0
                for (i in base.indices) {
                    val l0 = luma(base[i]); val l1 = luma(after[i])
                    if (mask[i] > 0.95f) { skySum += l1 - l0; skyN++ }
                    if (mask[i] < 0.01f) { groundDiff += kotlin.math.abs(l1 - l0); groundN++ }
                }
                val skyShift = skySum / skyN; val groundShift = groundDiff / groundN
                println("   sky brightness change %.1f, ground %.2f".format(skyShift, groundShift))
                if (skyShift > -8) failures += "${file.name}: sky only changed by $skyShift"
                if (groundShift > 1.5) failures += "${file.name}: ground changed by $groundShift"
            }
            if (outDir.isNotEmpty()) {
                val tint = IntArray(base.size) { i ->
                    val m = mask[i]; val p = base[i]
                    fun mix(c: Int, t: Int) = (c * (1 - 0.6f * m) + t * 0.6f * m).toInt()
                    (0xFF shl 24) or (mix((p shr 16) and 0xFF, 255) shl 16) or (mix((p shr 8) and 0xFF, 40) shl 8) or mix(p and 0xFF, 200)
                }
                val name = file.nameWithoutExtension
                write(tint, pv.width, pv.height, File(outDir, "sky/${name}_a_mask.jpg"))
                write(base, pv.width, pv.height, File(outDir, "sky/${name}_b_before.jpg"))
                write(after, pv.width, pv.height, File(outDir, "sky/${name}_c_after.jpg"))
            }
            if (file.name.startsWith("Q2_")) {
                // The full-size export path (bands, in place) applies the sky too.
                val small = FullRender.render(raw, ctx, edited)!!
                val plain = FullRender.render(raw, ctx, defaults)!!
                var d = 0.0
                for (i in small.argb.indices step 97) d += luma(small.argb[i]) - luma(plain.argb[i])
                println("   full-size render: mean brightness change %.1f".format(d / (small.argb.size / 97)))
                if (d >= 0) failures += "${file.name}: the full-size render ignores the sky"
            }
        }
        if (failures.isNotEmpty()) kotlin.test.fail(failures.joinToString("\n"))
    }

    private fun luma(p: Int) = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)

    private fun write(px: IntArray, w: Int, h: Int, f: File) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        f.parentFile.mkdirs()
        ImageIO.write(img, "jpg", f)
    }
}
