package com.verdictzero.darkhall.core.stack

import com.verdictzero.darkhall.core.develop.Filters
import com.verdictzero.darkhall.core.util.Parallel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A similarity transform (scale, rotation, shift) from reference coordinates to a frame's:
 * x' = a·x − b·y + tx, y' = b·x + a·y + ty. Focus breathing (each focus step magnifies the
 * picture a little) and hand-held drift between bracketed shots are both of this kind.
 */
data class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double) {
    fun mapX(x: Double, y: Double) = a * x - b * y + tx
    fun mapY(x: Double, y: Double) = b * x + a * y + ty

    /** This, then [next]: reference → this frame → the next frame. */
    fun then(next: Similarity) = Similarity(
        next.a * a - next.b * b,
        next.b * a + next.a * b,
        next.a * tx - next.b * ty + next.tx,
        next.b * tx + next.a * ty + next.ty,
    )

    /** The same transform for pictures [k] times larger. */
    fun scaled(k: Double) = Similarity(a, b, tx * k, ty * k)

    val scale: Double get() = sqrt(a * a + b * b)

    companion object {
        val IDENTITY = Similarity(1.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Focus stacking: lining up a focus-bracketed series and merging it so every part of the
 * picture comes from the frame where it was sharpest.
 */
object FocusStack {

    /**
     * Finds the transform from [ref] to [img] (luminance planes, both [w] x [h]) by
     * Lucas-Kanade on an image pyramid: at each level, Gauss-Newton steps on the four
     * parameters minimise the difference between [ref] and [img] seen through the transform.
     * The planes are lightly blurred first, so that frames focused at different distances
     * still compare well.
     */
    fun align(ref: FloatArray, img: FloatArray, w: Int, h: Int): Similarity {
        val levels = ArrayList<Triple<FloatArray, FloatArray, IntArray>>()
        var r = smooth(ref, w, h); var m = smooth(img, w, h); var lw = w; var lh = h
        levels += Triple(r, m, intArrayOf(lw, lh))
        while (min(lw, lh) > 96) {
            r = half(r, lw, lh); m = half(m, lw, lh)
            lw /= 2; lh /= 2
            levels += Triple(r, m, intArrayOf(lw, lh))
        }
        var p = Similarity.IDENTITY
        for (level in levels.indices.reversed()) {
            val (lr, lm, size) = levels[level]
            val factor = if (level == levels.size - 1) 1.0 else 2.0
            p = p.scaled(factor)
            p = refine(lr, lm, size[0], size[1], p)
        }
        return p
    }

    private fun smooth(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(src.size); val tmp = FloatArray(src.size)
        Filters.blur(src, out, tmp, w, h, 1, passes = 2)
        return out
    }

    private fun half(src: FloatArray, w: Int, h: Int): FloatArray {
        val hw = w / 2; val hh = h / 2
        return FloatArray(hw * hh) { i ->
            val x = (i % hw) * 2; val y = (i / hw) * 2
            0.25f * (src[y * w + x] + src[y * w + x + 1] + src[(y + 1) * w + x] + src[(y + 1) * w + x + 1])
        }
    }

    /** Gauss-Newton on one pyramid level, coordinates centred for good conditioning. */
    private fun refine(ref: FloatArray, img: FloatArray, w: Int, h: Int, start: Similarity): Similarity {
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        // In centred coordinates: x'c = a xc − b yc + ux, with ux = tx + a cx − b cy − cx.
        var a = start.a; var b = start.b
        var ux = start.tx + a * cx - b * cy - cx
        var uy = start.ty + b * cx + a * cy - cy
        val margin = max(2, min(w, h) / 20)
        repeat(30) {
            val hm = DoubleArray(16); val g = DoubleArray(4)
            for (y in margin until h - margin step 1) {
                val yc = y - cy
                for (x in margin until w - margin) {
                    val xc = x - cx
                    val sx = a * xc - b * yc + ux + cx
                    val sy = b * xc + a * yc + uy + cy
                    if (sx < 1 || sy < 1 || sx > w - 2 || sy > h - 2) continue
                    val v = sample(img, w, sx, sy)
                    val gx = (sample(img, w, sx + 1, sy) - sample(img, w, sx - 1, sy)) * 0.5
                    val gy = (sample(img, w, sx, sy + 1) - sample(img, w, sx, sy - 1)) * 0.5
                    val e = ref[y * w + x] - v
                    // d(sx, sy)/d(a, b, ux, uy)
                    val j0 = gx * xc + gy * yc
                    val j1 = -gx * yc + gy * xc
                    val j = doubleArrayOf(j0, j1, gx, gy)
                    for (r in 0 until 4) {
                        g[r] += j[r] * e
                        for (c in 0 until 4) hm[r * 4 + c] += j[r] * j[c]
                    }
                }
            }
            val d = solve4(hm, g) ?: return@repeat
            a += d[0]; b += d[1]; ux += d[2]; uy += d[3]
            if (abs(d[2]) < 1e-3 && abs(d[3]) < 1e-3 && abs(d[0]) < 1e-6 && abs(d[1]) < 1e-6) {
                return Similarity(a, b, ux - a * cx + b * cy + cx, uy - b * cx - a * cy + cy)
            }
        }
        return Similarity(a, b, ux - a * cx + b * cy + cx, uy - b * cx - a * cy + cy)
    }

    private fun sample(p: FloatArray, w: Int, x: Double, y: Double): Double {
        val x0 = x.toInt(); val y0 = y.toInt()
        val fx = x - x0; val fy = y - y0
        val i = y0 * w + x0
        return (p[i] * (1 - fx) + p[i + 1] * fx) * (1 - fy) + (p[i + w] * (1 - fx) + p[i + w + 1] * fx) * fy
    }

    /** Solves the 4x4 system [m] x = [v] by Gaussian elimination; null if singular. */
    private fun solve4(m: DoubleArray, v: DoubleArray): DoubleArray? {
        val a = Array(4) { r -> DoubleArray(5) { c -> if (c < 4) m[r * 4 + c] else v[r] } }
        for (c in 0 until 4) {
            val p = (c until 4).maxByOrNull { abs(a[it][c]) }!!
            if (abs(a[p][c]) < 1e-12) return null
            val t = a[p]; a[p] = a[c]; a[c] = t
            for (r in 0 until 4) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (k in c until 5) a[r][k] -= f * a[c][k]
            }
        }
        return DoubleArray(4) { a[it][4] / a[it][it] }
    }

    /**
     * Local sharpness of a picture, from its luminance [lum] ([w] x [h]): the strength of
     * the Laplacian, pooled over a small neighbourhood so noise doesn't decide. Computed at
     * half size (it is smooth anyway) and returned as such: ([w]/2) x ([h]/2).
     */
    fun sharpness(lum: FloatArray, w: Int, h: Int): FloatArray {
        val hw = w / 2; val hh = h / 2
        val small = half(lum, w, h)
        val lap = FloatArray(hw * hh)
        Parallel.rows(hh) { y0, y1 ->
            for (y in max(1, y0) until min(hh - 1, y1)) {
                for (x in 1 until hw - 1) {
                    val i = y * hw + x
                    lap[i] = abs(4 * small[i] - small[i - 1] - small[i + 1] - small[i - hw] - small[i + hw])
                }
            }
        }
        val out = FloatArray(hw * hh); val tmp = FloatArray(hw * hh)
        Filters.blur(lap, out, tmp, hw, hh, 3, passes = 2)
        return out
    }

    /**
     * The merged picture, built one frame at a time so only one frame need be in memory:
     * each pixel leans towards the frame where it is sharpest, softly (sharpness to the
     * fourth power), so flat areas average out (less noise) and edges come from the frame
     * that has them.
     */
    class Merger(val width: Int, val height: Int) {
        /** 16-bit RGB triplets, display-encoded. */
        val rgb = ShortArray(3 * width * height)
        private val sw = width / 2
        private val sh = height / 2
        /** Weight gathered so far per half-size cell (sum of sharpness^4). */
        private val weight = FloatArray(sw * sh)
        var frames = 0
            private set

        /**
         * Adds a frame: [frame] its 16-bit RGB ([width] x [height], its own coordinates),
         * [sharp] its half-size sharpness, [t] the transform from the reference to it.
         */
        fun add(frame: ShortArray, sharp: FloatArray, t: Similarity) {
            val first = frames == 0
            Parallel.rows(height) { y0, y1 ->
                for (y in y0 until y1) {
                    for (x in 0 until width) {
                        val sx = t.mapX(x.toDouble(), y.toDouble())
                        val sy = t.mapY(x.toDouble(), y.toDouble())
                        if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) continue
                        val s = sampleSmall(sharp, sx * 0.5 - 0.25, sy * 0.5 - 0.25)
                        val wNew = s * s * s * s + 1e-12f
                        val ci = (y / 2).coerceAtMost(sh - 1) * sw + (x / 2).coerceAtMost(sw - 1)
                        val wOld = weight[ci]
                        val k = if (first) 1f else wNew / (wNew + wOld)
                        val o = 3 * (y * width + x)
                        for (c in 0 until 3) {
                            val v = sampleRgb(frame, sx, sy, c)
                            val old = rgb[o + c].toInt() and 0xFFFF
                            rgb[o + c] = (old + (v - old) * k + 0.5f).toInt().coerceIn(0, 65535).toShort()
                        }
                    }
                }
            }
            // Cells' weights: add this frame's, sampled at the cell centres.
            Parallel.rows(sh) { y0, y1 ->
                for (cy in y0 until y1) for (cx in 0 until sw) {
                    val px = 2.0 * cx + 0.5; val py = 2.0 * cy + 0.5
                    val sx = t.mapX(px, py); val sy = t.mapY(px, py)
                    if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) continue
                    val s = sampleSmall(sharp, sx * 0.5 - 0.25, sy * 0.5 - 0.25)
                    weight[cy * sw + cx] += s * s * s * s
                }
            }
            frames++
        }

        private fun sampleSmall(p: FloatArray, x: Double, y: Double): Float {
            val cx = x.coerceIn(0.0, sw - 1.0); val cy = y.coerceIn(0.0, sh - 1.0)
            val x0 = cx.toInt(); val y0 = cy.toInt()
            val x1 = min(x0 + 1, sw - 1); val y1 = min(y0 + 1, sh - 1)
            val fx = (cx - x0).toFloat(); val fy = (cy - y0).toFloat()
            return (p[y0 * sw + x0] * (1 - fx) + p[y0 * sw + x1] * fx) * (1 - fy) + (p[y1 * sw + x0] * (1 - fx) + p[y1 * sw + x1] * fx) * fy
        }

        private fun sampleRgb(p: ShortArray, x: Double, y: Double, c: Int): Float {
            val x0 = x.toInt(); val y0 = y.toInt()
            val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
            val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
            fun v(px: Int, py: Int) = (p[3 * (py * width + px) + c].toInt() and 0xFFFF).toFloat()
            return (v(x0, y0) * (1 - fx) + v(x1, y0) * fx) * (1 - fy) + (v(x0, y1) * (1 - fx) + v(x1, y1) * fx) * fy
        }
    }

    /** Luminance of 16-bit display RGB triplets, 0..1. */
    fun luminance(rgb: ShortArray, w: Int, h: Int): FloatArray = FloatArray(w * h).also { out ->
        Parallel.rows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                out[i] = (0.2126f * (rgb[3 * i].toInt() and 0xFFFF) + 0.7152f * (rgb[3 * i + 1].toInt() and 0xFFFF) +
                    0.0722f * (rgb[3 * i + 2].toInt() and 0xFFFF)) / 65535f
            }
        }
    }
}
