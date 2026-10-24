package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.AutoStraighten
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.RawFormats
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AutoStraightenTest {

    private fun rgb(v: Int): Int { val c = v.coerceIn(0, 255); return (0xFF shl 24) or (c shl 16) or (c shl 8) or c }

    /** Average of [f] over a 4x4 grid inside pixel (x, y): edges come out anti-aliased, as a lens draws them. */
    private fun supersample(x: Int, y: Int, f: (Double, Double) -> Double): Double {
        var s = 0.0
        for (j in 0 until 4) for (i in 0 until 4) s += f(x + (i + 0.5) / 4, y + (j + 0.5) / 4)
        return s / 16
    }

    /** A sea horizon tilted by [deg] (y down, positive runs down to the right), with ripples and grain. */
    private fun horizon(w: Int, h: Int, deg: Double, seed: Int = 1): IntArray {
        val rnd = Random(seed)
        val t = tan(deg * PI / 180)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = supersample(x, y) { px, py ->
                val sea = py > h * 0.45 + t * (px - w / 2.0)
                if (sea) 70 + 10 * kotlin.math.sin(px * 0.08 + py * 0.28) else 190.0 - py * 60.0 / h
            }
            rgb((v + rnd.nextInt(-6, 7)).toInt())
        }
    }

    /** Poles whose bottom ends sit further right by tan([deg]) per pixel of height, on a textured wall. */
    private fun poles(w: Int, h: Int, deg: Double): IntArray {
        val rnd = Random(3)
        val t = tan(deg * PI / 180)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = supersample(x, y) { px, py ->
                val shifted = px - t * (py - h / 2.0)
                if (((shifted + 4000) % 160) < 22) 40.0 else 160.0
            }
            rgb((v + rnd.nextInt(-8, 9)).toInt())
        }
    }

    private fun straighten(src: IntArray, w: Int, h: Int, deg: Float): IntArray =
        Geometry.apply(src, w, h, DevelopParams.NEUTRAL.with(Adjustment.STRAIGHTEN, deg), null)

    @Test
    fun findsATiltedHorizonAndLevelsIt() {
        val w = 1200; val h = 800
        for (deg in listOf(-6.0, -2.5, -0.7, 1.2, 2.5, 8.0)) {
            val img = horizon(w, h, deg)
            val r = assertNotNull(AutoStraighten.detect(img, w, h), "tilt $deg not found")
            assertTrue(abs(r.degrees - deg) < 0.2, "tilt $deg measured as ${r.degrees}")
            assertTrue(!r.fromVerticals)
            // Adding the measurement to Straighten must level the picture.
            val fixed = straighten(img, w, h, r.degrees)
            val after = assertNotNull(AutoStraighten.detect(fixed, w, h))
            assertTrue(abs(after.degrees) < 0.2, "tilt $deg left ${after.degrees} after straightening")
        }
    }

    @Test
    fun findsLeaningVerticals() {
        val w = 1000; val h = 1300
        for (deg in listOf(-3.0, 1.5, 4.0)) {
            val img = poles(w, h, deg)
            val r = assertNotNull(AutoStraighten.detect(img, w, h), "lean $deg not found")
            assertTrue(r.fromVerticals)
            // Leaning right at the bottom, the poles run at 90 - deg degrees: a tilt of -deg from upright.
            assertTrue(abs(r.degrees + deg) < 0.2, "lean $deg measured as ${r.degrees}")
            val after = assertNotNull(AutoStraighten.detect(straighten(img, w, h, r.degrees), w, h))
            assertTrue(abs(after.degrees) < 0.2, "lean $deg (measured ${r.degrees}) left ${after.degrees}")
        }
    }

    @Test
    fun noLinesNoAnswer() {
        val w = 900; val h = 600
        val rnd = Random(5)
        // Grain and soft blobs: nothing straight to go by.
        val blobs = List(30) { Triple(rnd.nextInt(w), rnd.nextInt(h), 20 + rnd.nextInt(90)) }
        val img = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            var v = 120 + rnd.nextInt(-10, 11)
            for ((bx, by, br) in blobs) {
                val d2 = (x - bx) * (x - bx) + (y - by) * (y - by)
                v += (60 * kotlin.math.exp(-d2 / (2.0 * br * br))).toInt()
            }
            rgb(v)
        }
        assertNull(AutoStraighten.detect(img, w, h))
        assertNull(AutoStraighten.detect(IntArray(w * h) { rgb(128) }, w, h))
    }

    /**
     * Real photos: whatever each one measures, rotating it by a known amount should move the
     * measurement by the same amount. A photo with several competing lines (sheet music on a
     * stand, window frames in perspective) may settle on a different one once turned, so most,
     * not all, must agree. With -Pdarkhall.outDir, writes each photo before and after auto
     * straighten for a look.
     */
    @Test
    fun consistentOnRealPhotos() {
        val dir = System.getProperty("darkhall.rawsDir").orEmpty()
        val files = buildList {
            System.getProperty("darkhall.sample").orEmpty().takeIf { it.isNotEmpty() }?.let { add(File(it)) }
            if (dir.isNotEmpty()) File(dir).listFiles()?.filter { RawFormats.hasRawExtension(it.name) }?.sorted()?.let { addAll(it) }
        }.filter { it.exists() }
        assumeTrue("no -Pdarkhall.sample or -Pdarkhall.rawsDir", files.isNotEmpty())
        val outDir = System.getProperty("darkhall.outDir").orEmpty()
        val failures = mutableListOf<String>()
        val switched = mutableListOf<String>()
        var found = 0
        for (file in files) {
            val bytes = file.readBytes()
            val meta = runCatching { RawFormats.parse(bytes) }.getOrNull() ?: continue
            val raw = runCatching { RawFormats.decode(bytes, meta) }.getOrNull() ?: continue
            val small = Demosaic.preview(raw, 1200)
            val w = small.width; val h = small.height
            val ctx = RenderContext.forFile(raw.meta, small, raw)
            val params = DevelopParams.defaultsFor(raw.meta, ctx.noise)
            val render = { p: DevelopParams ->
                Geometry.apply(Developer().render(small, ctx, p, Frame(0, 0, w.toFloat() / raw.meta.crop.width)), w, h, p, ctx.lens)
            }
            val before = render(params)
            val r0 = AutoStraighten.detect(before, w, h)
            if (outDir.isNotEmpty()) write(before, w, h, File(outDir, "straighten/${file.nameWithoutExtension}_a_before.jpg"))
            val turned = params.with(Adjustment.STRAIGHTEN, 3f)
            val r3 = AutoStraighten.detect(render(turned), w, h)
            println("%-44s %s  after +3°: %s   [best guess %s]".format(file.name.take(44), r0.text(), r3.text(), AutoStraighten.measure(before, w, h).text()))
            if (r0 != null) {
                found++
                if (r3 == null || abs(r3.degrees - (r0.degrees - 3f)) > 0.35f) switched += "${file.name}: ${r0.degrees} then ${r3?.degrees} after +3°"
                if (outDir.isNotEmpty() && abs(r0.degrees) <= 10f) {
                    val fixed = params.with(Adjustment.STRAIGHTEN, r0.degrees)
                    write(render(fixed), w, h, File(outDir, "straighten/${file.nameWithoutExtension}_b_after.jpg"))
                }
            }
        }
        println("$found of ${files.size} photos had clear lines; settled on another line once turned:\n  " + switched.joinToString("\n  "))
        if (switched.size > found / 4) failures += "${switched.size} of $found photos inconsistent"
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun AutoStraighten.Result?.text() =
        if (this == null) "no clear lines" else "%+.2f° %s (length %.2f, prominence %.2f)".format(degrees, if (fromVerticals) "verticals" else "horizon", length, prominence)

    private fun write(px: IntArray, w: Int, h: Int, f: File) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        f.parentFile.mkdirs()
        ImageIO.write(img, "jpg", f)
    }
}
