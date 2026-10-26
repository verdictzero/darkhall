package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.NoiseProfile
import com.verdictzero.darkhall.core.develop.Region
import com.verdictzero.darkhall.core.raw.Crop
import com.verdictzero.darkhall.core.raw.RafLayout
import com.verdictzero.darkhall.core.raw.RawFormat
import com.verdictzero.darkhall.core.raw.RawImage
import com.verdictzero.darkhall.core.raw.RawMetadata
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fujifilm X-Trans: the 6x6 pattern through the demosaic and noise measurement. */
class XTransTest {
    // The X-T3's pattern at the sensor origin.
    private val pattern = intArrayOf(
        1, 1, 0, 1, 1, 2,
        1, 1, 2, 1, 1, 0,
        2, 0, 1, 0, 2, 1,
        1, 1, 2, 1, 1, 0,
        1, 1, 0, 1, 1, 2,
        0, 2, 1, 2, 0, 1,
    )
    // Big enough for the noise measurement's blocks of 10 same-colour sites, 60 pixels apart.
    private val cw = 1794
    private val ch = 1200
    private val w = cw + 12
    private val h = ch + 6
    private val black = 1024
    private val white = 16383

    private fun meta() = RawMetadata(
        format = RawFormat.RAF, make = "FUJIFILM", model = "X-T3", rawWidth = w, rawHeight = h,
        cfa = intArrayOf(1, 1, 1, 1), xtrans = pattern, blackLevels = IntArray(4) { black }, whiteLevel = white,
        cameraWb = floatArrayOf(1f, 1f, 1f), crop = Crop(6, 0, cw, ch), orientation = 1, iso = 200,
        exposureTime = 0.01, fNumber = 4.0, focalLength = 23.0, dateTime = "", previewOffset = 0, previewLength = 0,
        layout = RafLayout(0, 0, RafLayout.Encoding.UNPACKED, false),
    )

    /** A smooth colour field (crop-relative coordinates) with a sharp vertical edge at x = 900. */
    private fun scene(x: Int, y: Int, c: Int): Double {
        val fx = x / cw.toDouble(); val fy = y / ch.toDouble()
        val base = when (c) { 0 -> 0.2 + 0.4 * fx; 1 -> 0.3 + 0.3 * fy; else -> 0.5 - 0.3 * fx * fy }
        return if (x >= EDGE) base * 0.4 else base
    }

    private fun mosaic(noise: ((Double) -> Double)? = null): RawImage {
        val m = meta()
        val data = ShortArray(w * h) { i ->
            val sy = i / w; val sx = i % w
            val v = scene(sx - m.crop.left, sy - m.crop.top, m.cfaColor(sy, sx)).let { noise?.invoke(it) ?: it }
            (black + v * (white - black)).toInt().coerceIn(0, 65535).toShort()
        }
        return RawImage(w, h, data, m)
    }

    private companion object { const val EDGE = 900 }

    @Test
    fun patternLookup() {
        val m = meta()
        for (y in 0 until 12) for (x in 0 until 12) assertEquals(pattern[(y % 6) * 6 + x % 6], m.cfaColor(y, x))
    }

    @Test
    fun fullResolutionFollowsTheScene() {
        val raw = mosaic()
        val img = Demosaic.malvar(raw, Region(0, 0, cw, ch))
        var flat = 0.0; var nFlat = 0; var edge = 0.0; var nEdge = 0
        for (y in 4 until ch - 4) for (x in 4 until cw - 4) {
            val i = y * cw + x
            val e = abs(img.r[i] - scene(x, y, 0)) + abs(img.g[i] - scene(x, y, 1)) + abs(img.b[i] - scene(x, y, 2))
            if (abs(x - EDGE) <= 2) { edge += e; nEdge++ } else { flat += e; nFlat++ }
        }
        println("X-Trans mean error: smooth %.5f, at the edge %.4f".format(flat / nFlat, edge / nEdge))
        assertTrue(flat / nFlat < 0.003, "smooth areas: ${flat / nFlat}")
        assertTrue(edge / nEdge < 0.15, "edge: ${edge / nEdge}")
        // Bands give the same result as the whole.
        val band = Demosaic.malvar(raw, Region(0, 100, cw, 50))
        for (i in 0 until cw * 50) assertEquals(img.g[100 * cw + i], band.g[i], 1e-6f)
    }

    @Test
    fun halfSizeKeepsFlatColour() {
        val raw = mosaic()
        val img = Demosaic.superpixel(raw)
        assertEquals(cw / 2, img.width); assertEquals(ch / 2, img.height)
        for (y in 2 until ch / 2 - 2) for (x in 2 until EDGE / 2 - 4) {
            val i = y * img.width + x
            // Each colour's sites in the window centre within a pixel or two of the cell.
            for ((c, v) in listOf(0 to img.r[i], 1 to img.g[i], 2 to img.b[i])) {
                assertEquals(scene(2 * x, 2 * y, c), v.toDouble(), 0.003, "colour $c at $x, $y")
            }
        }
    }

    @Test
    fun noiseMeasuredPerColour() {
        val a = 6e-4; val b = 2e-5
        val rnd = Random(3)
        val raw = mosaic { v -> v + rnd.nextGaussian() * sqrt(a * v + b) }
        val p = NoiseProfile.estimate(raw)
        assertTrue(p.measured)
        for (c in 0..2) {
            println("channel $c: a %.2e b %.2e".format(p.a[c], p.b[c]))
            assertTrue(abs(p.a[c] / a - 1) < 0.35, "a[$c] = ${p.a[c]}")
        }
    }
}
