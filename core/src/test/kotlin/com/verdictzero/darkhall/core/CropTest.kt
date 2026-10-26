package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Crop
import com.verdictzero.darkhall.core.develop.CropAspect
import com.verdictzero.darkhall.core.develop.CropRect
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.export.ArgbRows
import com.verdictzero.darkhall.core.export.CroppedRows
import com.verdictzero.darkhall.core.export.ResizedRows
import com.verdictzero.darkhall.core.export.RowSource
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropTest {

    private fun inside(r: CropRect) =
        r.left >= -1e-5f && r.top >= -1e-5f && r.right <= 1f + 1e-5f && r.bottom <= 1f + 1e-5f &&
            r.width >= CropRect.MIN_SIDE - 1e-5f && r.height >= CropRect.MIN_SIDE - 1e-5f

    @Test
    fun noCropByDefault() {
        assertTrue(Crop.of(DevelopParams.NEUTRAL).isFull)
        assertEquals(CropAspect.FREE, Crop.aspect(DevelopParams.NEUTRAL))
        // A degenerate rectangle (e.g. from a damaged edits file) means no crop.
        assertTrue(Crop.of(DevelopParams.NEUTRAL.with(Adjustment.CROP_LEFT, 1f)).isFull)
    }

    @Test
    fun fitKeepsTheRatioAndStaysInside() {
        val w = 4000; val h = 3000
        for (aspect in CropAspect.entries) for (portrait in listOf(false, true)) {
            val ratio = Crop.ratio(aspect, w, h, portrait) ?: continue
            val r = Crop.fit(ratio, CropRect(0.6f, 0.1f, 0.9f, 0.3f), w, h)
            assertTrue(inside(r), "$aspect $portrait: $r")
            assertEquals(ratio, r.width * w / (r.height * h), 1e-3f, "$aspect $portrait")
            // As large as the frame allows: it touches two opposite sides.
            assertTrue(abs(r.width - 1f) < 1e-4f || abs(r.height - 1f) < 1e-4f, "$aspect not maximal: $r")
        }
        // Original keeps the frame's own shape, turned when asked.
        assertEquals(4f / 3f, Crop.ratio(CropAspect.ORIGINAL, w, h, portrait = false)!!, 1e-6f)
        assertEquals(3f / 4f, Crop.ratio(CropAspect.ORIGINAL, w, h, portrait = true)!!, 1e-6f)
    }

    @Test
    fun draggingKeepsTheRatioInsideTheFrame() {
        val w = 4000; val h = 3000
        val rnd = Random(7)
        for (ratio in listOf(null, 1f, 1.5f, 4f / 5f, 16f / 9f)) {
            var r = if (ratio == null) CropRect(0.2f, 0.2f, 0.8f, 0.8f) else Crop.fit(ratio, CropRect.FULL, w, h)
            repeat(2000) {
                val handle = Crop.Handle.entries[rnd.nextInt(Crop.Handle.entries.size)]
                r = Crop.drag(r, handle, rnd.nextFloat() * 0.6f - 0.3f, rnd.nextFloat() * 0.6f - 0.3f, ratio, w, h)
                assertTrue(inside(r), "$ratio $handle: $r")
                if (ratio != null) assertEquals(ratio, r.width * w / (r.height * h), 2e-3f, "$ratio after $handle: $r")
            }
        }
    }

    @Test
    fun cornerDragMovesOnlyThatCorner() {
        val r = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val d = Crop.drag(r, Crop.Handle.TOP_LEFT, 0.1f, 0.05f, null, 1000, 1000)
        assertEquals(CropRect(0.3f, 0.25f, 0.8f, 0.8f), d)
        val m = Crop.drag(r, Crop.Handle.MOVE, 0.5f, -0.5f, null, 1000, 1000)
        assertEquals(0.4f, m.left, 1e-6f); assertEquals(1f, m.right, 1e-6f)
        assertEquals(0f, m.top, 1e-6f); assertEquals(0.6f, m.bottom, 1e-6f)
    }

    /** Cutting the camera-oriented picture then turning it upright equals turning then cutting. */
    @Test
    fun sensorCropMatchesUprightCrop() {
        val w = 60; val h = 40
        val px = IntArray(w * h) { it }
        val crop = CropRect(0.1f, 0.25f, 0.7f, 0.9f)
        val p = Crop.with(DevelopParams.NEUTRAL, crop)
        for (orientation in listOf(1, 3, 6, 8)) {
            val upright = CroppedRows.of(ArgbRows(px, w, h, orientation), p)
            val s = Crop.sensorPixels(crop, orientation, w, h)
            val cut = Crop.cut(px, w, h, s)
            val turned = ArgbRows(cut, s.width, s.height, orientation)
            assertEquals(upright.width, turned.width, "orientation $orientation")
            assertEquals(upright.height, turned.height, "orientation $orientation")
            val a = IntArray(3 * upright.width); val b = IntArray(3 * upright.width)
            for (y in 0 until upright.height) {
                upright.row(y, a); turned.row(y, b)
                assertTrue(a.contentEquals(b), "orientation $orientation row $y")
            }
        }
    }

    @Test
    fun perShotSettingsCopy() {
        val cropped = Crop.with(DevelopParams.NEUTRAL, CropRect(0.1f, 0.1f, 0.5f, 0.6f), CropAspect.R3_2)
        val other = DevelopParams.NEUTRAL.with(Adjustment.EXPOSURE, 1f)
        val merged = Crop.copy(other, cropped)
        assertEquals(1f, merged[Adjustment.EXPOSURE])
        assertEquals(Crop.of(cropped), Crop.of(merged))
        assertEquals(CropAspect.R3_2, Crop.aspect(merged))
    }

    /** Resizing: the long edge as asked, flat areas unchanged, fine detail averaged rather than aliased. */
    @Test
    fun resizingAveragesArea() {
        val w = 300; val h = 200
        // Left half a one-pixel checkerboard of black and white, right half flat 0.4 grey.
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = if (x < w / 2) (if ((x + y) % 2 == 0) 255 else 0) else 102
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val small = ResizedRows.of(ArgbRows(px, w, h, 1), 100)
        assertEquals(100, small.width); assertEquals(67, small.height)
        val row = IntArray(3 * small.width)
        for (y in 0 until small.height) {
            small.row(y, row)
            // The checkerboard becomes mid grey, give or take the partly covered pixels.
            for (x in 2 until 48) assertTrue(abs(row[3 * x] - 128) <= 25, "x $x y $y: ${row[3 * x]}")
            for (x in 52 until 100) assertEquals(102, row[3 * x], "x $x y $y")
        }
        // Not larger than asked, and never enlarged.
        assertTrue(ResizedRows.of(ArgbRows(px, w, h, 1), 0) is ArgbRows)
        assertTrue(ResizedRows.of(ArgbRows(px, w, h, 1), 4000) is ArgbRows)
        val upright: RowSource = ResizedRows.of(ArgbRows(px, w, h, 6), 150)
        assertEquals(100, upright.width); assertEquals(150, upright.height)
    }
}
