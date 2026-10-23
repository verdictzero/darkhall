package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.LookSource
import com.verdictzero.darkhall.core.develop.Lut
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.orf.OlympusDecoder
import com.verdictzero.darkhall.core.orf.OrfParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LookTest {
    private val looksDir = File(System.getProperty("darkhall.looksDir").orEmpty())

    private fun index(): List<Map<String, String>> {
        // Minimal parse of index.json: the build script writes one object per look.
        val text = File(looksDir, "index.json").readText()
        return Regex("\\{\\s*\"id\": \"([^\"]+)\",\\s*\"name\": \"([^\"]*)\",\\s*\"group\": \"([^\"]+)\",\\s*\"category\": \"([^\"]+)\",\\s*\"source\": \"([^\"]+)\"\\s*}")
            .findAll(text).map { m ->
                mapOf("id" to m.groupValues[1], "name" to m.groupValues[2], "group" to m.groupValues[3], "category" to m.groupValues[4], "source" to m.groupValues[5])
            }.toList()
    }

    @Test
    fun everyBundledLookDecodes() {
        assumeTrue(File(looksDir, "index.json").exists())
        val idx = index()
        println("${idx.size} looks in index")
        assertTrue(idx.size >= 350, "expected the full library, got ${idx.size}")
        val files = looksDir.listFiles { f -> f.name.endsWith(".dlut") }!!.map { it.name.removeSuffix(".dlut") }.toSet()
        assertEquals(idx.map { it["id"] }.toSet(), files, "index and files disagree")
        val out = FloatArray(3)
        val rnd = Random(5)
        for (e in idx) {
            val lut = Lut.decode(File(looksDir, e["id"] + ".dlut").readBytes())
            assertEquals(33, lut.size)
            val mono = e["group"] == "Film B&W" || e["category"] == "Black & white"
            if (mono) {
                // Black-and-white looks must not tint.
                repeat(200) {
                    lut.sample(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), out)
                    assertTrue(abs(out[0] - out[1]) < 1.5f / 255 && abs(out[1] - out[2]) < 1.5f / 255, "${e["id"]} is not neutral: ${out.toList()}")
                }
            }
        }
    }

    @Test
    fun identityLutChangesNothing() {
        val lut = Lut.identity(33)
        val out = FloatArray(3)
        val rnd = Random(9)
        repeat(10_000) {
            val r = rnd.nextFloat(); val g = rnd.nextFloat(); val b = rnd.nextFloat()
            lut.sample(r, g, b, out)
            assertTrue(abs(out[0] - r) < 0.6f / 255 && abs(out[1] - g) < 0.6f / 255 && abs(out[2] - b) < 0.6f / 255)
        }
    }

    /** With -Pdarkhall.hald=/path/to/HaldCLUT: the bundled table reproduces the RawTherapee source. */
    @Test
    fun filmLookMatchesRawTherapeeSource() {
        val hald = System.getProperty("darkhall.hald").orEmpty()
        assumeTrue(hald.isNotEmpty() && File(looksDir, "index.json").exists())
        val img = ImageIO.read(File(hald, "Color/Fuji/Fuji Velvia 50.png"))
        val n = 144
        val lut = Lut.decode(File(looksDir, "rt-color-fuji-fuji-velvia-50.dlut").readBytes())
        val out = FloatArray(3)
        val rnd = Random(3)
        var err = 0.0
        val count = 20_000
        repeat(count) {
            // Exact Hald grid points: compare against the source pixel directly.
            val ir = rnd.nextInt(n); val ig = rnd.nextInt(n); val ib = rnd.nextInt(n)
            val idx = ir + ig * n + ib * n * n
            val px = img.getRGB(idx % (n * 12), idx / (n * 12))
            lut.sample(ir / (n - 1f), ig / (n - 1f), ib / (n - 1f), out)
            err += abs(out[0] * 255 - ((px ushr 16) and 0xFF)) + abs(out[1] * 255 - ((px ushr 8) and 0xFF)) + abs(out[2] * 255 - (px and 0xFF))
        }
        val mean = err / (count * 3)
        println("Velvia 50: mean difference to the source Hald = %.3f levels".format(mean))
        assertTrue(mean < 1.5, "mean $mean")
    }

    @Test
    fun lookIsAppliedInThePipeline() {
        val sample = System.getProperty("darkhall.sample").orEmpty()
        assumeTrue(sample.isNotEmpty() && File(sample).exists() && File(looksDir, "index.json").exists())
        val bytes = File(sample).readBytes()
        val meta = OrfParser.parse(bytes)
        val raw = OlympusDecoder.decode(bytes, meta)
        val pv = Demosaic.superpixel(raw).resizeArea(800, 600)
        val identity = Lut.identity(33)
        val ctx = RenderContext.forFile(meta, pv, raw, looks = LookSource { id ->
            if (id == "identity") identity else File(looksDir, "$id.dlut").takeIf { it.exists() }?.let { Lut.decode(it.readBytes()) }
        })
        val frame = Frame(0, 0, pv.width.toFloat() / meta.crop.width)
        val dev = Developer()
        val base = DevelopParams.defaultsFor(meta, ctx.noise)
        val plain = dev.render(pv, ctx, base, frame)
        val same = dev.render(pv, ctx, base.withLook("identity"), frame)
        var maxD = 0
        for (i in plain.indices) for (sh in intArrayOf(16, 8, 0)) maxD = maxOf(maxD, abs(((plain[i] ushr sh) and 0xFF) - ((same[i] ushr sh) and 0xFF)))
        assertTrue(maxD <= 1, "identity look changed the picture by $maxD levels")
        val mono = dev.render(pv, ctx, base.withLook("dh-black-white-neutral"), frame)
        var tinted = 0
        for (px in mono) {
            val r = (px ushr 16) and 0xFF; val g = (px ushr 8) and 0xFF; val b = px and 0xFF
            if (abs(r - g) > 2 || abs(g - b) > 2) tinted++
        }
        assertTrue(tinted < mono.size / 1000, "black & white look left $tinted coloured pixels")
        val half = dev.render(pv, ctx, base.withLook("dh-black-white-neutral").with(Adjustment.LOOK_AMOUNT, 50f), frame)
        var between = 0
        for (i in half.indices) {
            val r = (half[i] ushr 16) and 0xFF; val g = (half[i] ushr 8) and 0xFF
            val r0 = (plain[i] ushr 16) and 0xFF; val g0 = (plain[i] ushr 8) and 0xFF
            if (abs(r - g) <= abs(r0 - g0)) between++
        }
        assertTrue(between > half.size * 0.95, "50% look should sit between the original and the full look")
    }
}
