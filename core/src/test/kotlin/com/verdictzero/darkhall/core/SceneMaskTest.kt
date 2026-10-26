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
import com.verdictzero.darkhall.core.develop.MaskKind
import com.verdictzero.darkhall.core.raw.RawFormats.hasRawExtension
import com.verdictzero.darkhall.core.sky.MaskRegion
import com.verdictzero.darkhall.core.sky.RegionMask
import com.verdictzero.darkhall.core.sky.SceneMasks
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SceneMaskTest {

    /** A grid that is sky over its top half, upright. */
    private fun topHalfSky(orientation: Int) = RegionMask(9, 9, FloatArray(81) { if (it / 9 < 4) 1f else 0f }, 33, 33, orientation)

    @Test
    fun followsTheCameraOrientation() {
        // Upright, the top is sky; in camera coordinates that is the top (1), the bottom (3),
        // the left edge (6: the camera was turned clockwise) or the right edge (8).
        val m1 = topHalfSky(1); assertTrue(m1.coarse(0.5f, 0.05f) > 0.9f && m1.coarse(0.5f, 0.95f) < 0.1f)
        val m3 = topHalfSky(3); assertTrue(m3.coarse(0.5f, 0.95f) > 0.9f && m3.coarse(0.5f, 0.05f) < 0.1f)
        val m6 = topHalfSky(6); assertTrue(m6.coarse(0.05f, 0.5f) > 0.9f && m6.coarse(0.95f, 0.5f) < 0.1f)
        val m8 = topHalfSky(8); assertTrue(m8.coarse(0.95f, 0.5f) > 0.9f && m8.coarse(0.05f, 0.5f) < 0.1f)
        val scene = SceneMasks(MaskRegion.entries.associateWith { if (it == MaskRegion.SKY) m6 else topHalfSky(1) })
        val back = assertNotNull(SceneMasks.fromBytes(scene.toBytes()))
        assertEquals(m6.coarse(0.3f, 0.7f), back[MaskRegion.SKY]!!.coarse(0.3f, 0.7f))
        assertEquals(scene.coverage(MaskRegion.WATER), back.coverage(MaskRegion.WATER))
        // An older cache (one region, or another format) is not taken for a complete one.
        assertEquals(null, SceneMasks.fromBytes(SceneMasks(mapOf(MaskRegion.SKY to m6)).toBytes()))
        assertEquals(null, SceneMasks.fromBytes(ByteArray(64)))
    }

    /**
     * Real photos (-Pdarkhall.rawsDir): each region found where it is and not where it isn't,
     * and each region's sliders change that region and leave the rest alone. With
     * -Pdarkhall.outDir, writes every photo with each region's mask.
     */
    @Test
    fun realPhotos() {
        val dir = System.getProperty("darkhall.rawsDir").orEmpty()
        assumeTrue("no -Pdarkhall.rawsDir", dir.isNotEmpty())
        val outDir = System.getProperty("darkhall.outDir").orEmpty()
        // What each sample shows, by file-name prefix: present, then absent.
        val expect = mapOf(
            MaskRegion.SKY to (listOf("Q2_", "E-M1X", "OM-5MarkII", "K-1_", "DSC-RX100M5A", "FC7303", "DMC-LX100") to
                // (Not here: the K-70's dim, bare room around a light bulb, which the model takes
                // for a night sky. A known limit; the Show mask view makes it plain.)
                listOf("EOS_5D_Mark_III__5G4", "E-M1MarkIII", "DC-S5", "Z_6_")),
            // (Not here: the GH5's weathered bench, which the model partly takes for water.)
            MaskRegion.WATER to (listOf("E-M1X", "K-1_", "FC7303", "Q2_", "ILCE-6400") to
                listOf("Z_6_", "EOS_6D_Mark_II", "DMC-G85", "Pixel_6_Pro", "DMC-LX100")),
            MaskRegion.SUBJECT to (listOf("DC-GH5S", "DMC-GX7", "DC-G9") to listOf("K-1_", "Q2_", "DMC-LX100", "Pixel_6_Pro")),
            MaskRegion.FOLIAGE to (listOf("DMC-G85", "EOS_6D_Mark_II", "TG-7", "Pixel_6_Pro", "DMC-LX100") to listOf("E-M1X", "DC-G9", "K-70", "Z_6_")),
        )
        val files = File(dir).listFiles()!!.filter { hasRawExtension(it.name) }.sortedBy { it.name }
        val failures = ArrayList<String>()
        for (file in files) {
            val bytes = file.readBytes()
            val meta = runCatching { RawFormats.parse(bytes) }.getOrNull() ?: continue
            val raw = runCatching { RawFormats.decode(bytes, meta) }.getOrNull() ?: continue
            val pv = Demosaic.preview(raw, 1280)
            val ctx = RenderContext.forFile(meta, pv, raw)
            val defaults = DevelopParams.defaultsFor(meta, ctx.noise)
            val frame = Frame(0, 0, pv.width.toFloat() / meta.crop.width)
            val dev = Developer()
            val base = dev.render(pv, ctx, defaults, frame)
            val t0 = System.nanoTime()
            val scene = SceneMasks.detect(SkyNetTest.model, base, pv.width, pv.height, meta.orientation)
            val ms = (System.nanoTime() - t0) / 1_000_000
            ctx.scene = scene
            println("%-44s %s  (%d ms)".format(file.name.take(44), MaskRegion.entries.joinToString("  ") { "%s %3.0f%%".format(it.title.lowercase(), scene.coverage(it) * 100) }, ms))
            for ((region, lists) in expect) {
                val c = scene.coverage(region)
                if (lists.first.any { file.name.startsWith(it) } && c < 0.08f) failures += "${file.name}: no ${region.noun} found"
                if (lists.second.any { file.name.startsWith(it) } && c > 0.03f) failures += "${file.name}: $c ${region.noun} where there is none"
            }
            for (region in MaskRegion.entries) {
                if (scene.coverage(region) < 0.08f) continue
                val mask = scene[region]!!.forFrame(pv, frame, meta.crop.width, meta.crop.height)
                // Darker, more saturated, crisper, cooler: inside the region only.
                val edited = defaults.with(Adjustment.masked(region, MaskKind.EXPOSURE), -0.7f)
                    .with(Adjustment.masked(region, MaskKind.SATURATION), 40f)
                    .with(Adjustment.masked(region, MaskKind.CLARITY), 50f)
                    .with(Adjustment.masked(region, MaskKind.WARMTH), -20f)
                val after = dev.render(pv, ctx, edited, frame)
                var inSum = 0.0; var inBase = 0.0; var inN = 0; var outDiff = 0.0; var outN = 0
                for (i in base.indices) {
                    val l0 = luma(base[i]); val l1 = luma(after[i])
                    if (mask[i] > 0.95f) { inSum += l1 - l0; inBase += l0; inN++ }
                    if (mask[i] < 0.01f) { outDiff += kotlin.math.abs(l1 - l0); outN++ }
                }
                val inShift = if (inN > 0) inSum / inN else 0.0
                val outShift = if (outN > 0) outDiff / outN else 0.0
                println("   %s: brightness change %.1f inside, %.2f outside".format(region.title, inShift, outShift))
                // (A blown-out region stays white however much darker it is made.)
                val blown = inN > 0 && inBase / inN > 245
                if (inN > 500 && !blown && inShift > -4) failures += "${file.name}: ${region.noun} only changed by $inShift"
                if (outN > 2000 && outShift > 1.5) failures += "${file.name}: outside the ${region.noun} changed by $outShift"
                if (outDir.isNotEmpty()) {
                    val tint = IntArray(base.size) { i ->
                        val m = mask[i]; val p = base[i]
                        fun mix(c: Int, t: Int) = (c * (1 - 0.6f * m) + t * 0.6f * m).toInt()
                        (0xFF shl 24) or (mix((p shr 16) and 0xFF, 255) shl 16) or (mix((p shr 8) and 0xFF, 40) shl 8) or mix(p and 0xFF, 200)
                    }
                    write(tint, pv.width, pv.height, File(outDir, "scene/${file.nameWithoutExtension}_${region.name.lowercase()}.jpg"))
                }
                if (file.name.startsWith("Q2_") && region == MaskRegion.SKY) {
                    // The full-size export path (bands, in place) applies the masks too.
                    val small = FullRender.render(raw, ctx, edited)!!
                    val plain = FullRender.render(raw, ctx, defaults)!!
                    var d = 0.0
                    for (i in small.argb.indices step 97) d += luma(small.argb[i]) - luma(plain.argb[i])
                    println("   full-size render: mean brightness change %.1f".format(d / (small.argb.size / 97)))
                    if (d >= 0) failures += "${file.name}: the full-size render ignores the sky"
                }
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
