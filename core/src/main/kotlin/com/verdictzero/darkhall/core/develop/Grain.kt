package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.develop.ColorScience.LUM_B
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_G
import com.verdictzero.darkhall.core.develop.ColorScience.LUM_R
import com.verdictzero.darkhall.core.util.Parallel
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Film grain: smooth value noise on a lattice of [RenderPlan.grainCell] full-resolution
 * pixels, so a pixel of the photo always gets the same grain whether it is drawn in the
 * preview, a zoomed region or a band of the export. Monochrome, strongest in the midtones
 * and fading to nothing at black and white. The GPU's GRAIN shader does exactly the same.
 */
object Grain {
    /** Lattice value in 0..1 for integer cell (ix, iy): an integer hash, as the shader's. */
    fun hash(ix: Int, iy: Int): Float {
        var h = ix * 374761393 + iy * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    /** Noise in -1..1 at full-resolution position ([fx], [fy]). */
    fun noise(fx: Float, fy: Float, cell: Float): Float {
        val px = fx / cell; val py = fy / cell
        val x0 = floor(px); val y0 = floor(py)
        var tx = px - x0; var ty = py - y0
        tx = tx * tx * (3f - 2f * tx); ty = ty * ty * (3f - 2f * ty)
        val ix = x0.toInt(); val iy = y0.toInt()
        val a = hash(ix, iy); val b = hash(ix + 1, iy); val c = hash(ix, iy + 1); val d = hash(ix + 1, iy + 1)
        val v = (a * (1f - tx) + b * tx) * (1f - ty) + (c * (1f - tx) + d * tx) * ty
        return 2f * v - 1f
    }

    /** Adds grain to display-encoded planes [r], [g], [b] of a [w] x [h] frame placed by [plan]. */
    fun apply(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, plan: RenderPlan) {
        val a = plan.grainAmount; val cell = plan.grainCell
        Parallel.rows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val fy = plan.frameY0 + (y + 0.5f) * plan.invScale
                var i = y * w
                for (x in 0 until w) {
                    val fx = plan.frameX0 + (x + 0.5f) * plan.invScale
                    val l = (LUM_R * r[i] + LUM_G * g[i] + LUM_B * b[i]).coerceIn(0f, 1f)
                    val add = a * noise(fx, fy, cell) * 2f * sqrt(l * (1f - l))
                    r[i] = (r[i] + add).coerceIn(0f, 1f); g[i] = (g[i] + add).coerceIn(0f, 1f); b[i] = (b[i] + add).coerceIn(0f, 1f)
                    i++
                }
            }
        }
    }
}
