package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.util.Parallel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Output-to-source mapping for straighten, manual distortion and the lens profile
 * (distortion + lateral CA). Coordinates are normalised to the frame, so the same
 * [DevelopParams] give the same picture at preview size, full size, or for a zoomed region.
 * The frame is automatically zoomed just enough that no empty corners appear.
 */
class Geometry private constructor(
    val width: Int,
    val height: Int,
    private val cosT: Float,
    private val sinT: Float,
    private val k: Float,
    private val lens: LensProfile?,
    private val amount: Float,
) {
    private val cx = (width - 1) * 0.5f
    private val cy = (height - 1) * 0.5f
    private val radius = sqrt(cx * cx + cy * cy)
    /** Converts half-diagonal units to lensfun's half-short-side units. */
    private val shortRatio = radius / min(cx, cy)
    private var zoom = 1f

    /** Everything a shader needs to evaluate [map]. */
    class Uniforms(
        val cx: Float, val cy: Float, val radius: Float, val shortRatio: Float, val zoom: Float,
        val cos: Float, val sin: Float, val k: Float,
        val lensOn: Boolean, val a: Float, val b: Float, val c: Float, val amount: Float, val vr: Float, val vb: Float,
        val hasTca: Boolean,
    )

    fun uniforms() = Uniforms(
        cx, cy, radius, shortRatio, zoom, cosT, sinT, k,
        lens != null && amount > 0f, lens?.a ?: 0f, lens?.b ?: 0f, lens?.c ?: 0f, amount, lens?.vr ?: 1f, lens?.vb ?: 1f,
        hasTca,
    )

    /** True when red and blue need sampling at different positions from green. */
    val hasTca: Boolean = lens != null && amount > 0f && (abs(lens.vr - 1f) > 1e-6f || abs(lens.vb - 1f) > 1e-6f)

    /** Maps output pixel (x, y) to source positions: out = [rx, ry, gx, gy, bx, by]. */
    fun map(x: Float, y: Float, out: FloatArray) {
        val dx = (x - cx) / radius
        val dy = (y - cy) / radius
        var qx = (dx * cosT - dy * sinT) * zoom
        var qy = (dx * sinT + dy * cosT) * zoom
        if (k != 0f) {
            val f = 1f + k * (qx * qx + qy * qy)
            qx *= f; qy *= f
        }
        var fg = 1f; var fr = 1f; var fb = 1f
        if (lens != null && amount > 0f) {
            val ru = sqrt(qx * qx + qy * qy) * shortRatio
            val d = 1f - lens.a - lens.b - lens.c
            val fd = ((lens.a * ru + lens.b) * ru + lens.c) * ru + d
            fg = 1f + amount * (fd - 1f)
            fr = fg * (1f + amount * (lens.vr - 1f))
            fb = fg * (1f + amount * (lens.vb - 1f))
        }
        out[0] = cx + qx * fr * radius; out[1] = cy + qy * fr * radius
        out[2] = cx + qx * fg * radius; out[3] = cy + qy * fg * radius
        out[4] = cx + qx * fb * radius; out[5] = cy + qy * fb * radius
    }

    /** Largest zoom (at most 1) for which every border pixel maps inside the source frame. */
    private fun fitZoom() {
        val pts = ArrayList<Pair<Float, Float>>()
        val steps = 32
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            pts += (t * (width - 1)) to 0f
            pts += (t * (width - 1)) to (height - 1f)
            pts += 0f to (t * (height - 1))
            pts += (width - 1f) to (t * (height - 1))
        }
        val buf = FloatArray(6)
        fun fits(z: Float): Boolean {
            zoom = z
            for ((x, y) in pts) {
                map(x, y, buf)
                for (c in 0 until 3) {
                    val sx = buf[2 * c]; val sy = buf[2 * c + 1]
                    if (sx < -0.01f || sy < -0.01f || sx > width - 0.99f || sy > height - 0.99f) return false
                }
            }
            return true
        }
        if (fits(1f)) { zoom = 1f; return }
        var lo = 0.2f; var hi = 1f
        repeat(24) {
            val mid = (lo + hi) * 0.5f
            if (fits(mid)) lo = mid else hi = mid
        }
        zoom = lo
    }

    companion object {
        fun isIdentity(p: DevelopParams, lens: LensProfile?): Boolean =
            abs(p[Adjustment.DISTORTION]) < 0.5f && abs(p[Adjustment.STRAIGHTEN]) < 0.02f &&
                (lens == null || p[Adjustment.LENS_PROFILE] < 0.5f)

        /** Returns null when the parameters need no geometric change. */
        fun create(width: Int, height: Int, p: DevelopParams, lens: LensProfile?): Geometry? {
            if (isIdentity(p, lens)) return null
            val theta = p[Adjustment.STRAIGHTEN] / 180f * PI.toFloat()
            val g = Geometry(
                width, height, cos(theta), sin(theta),
                p[Adjustment.DISTORTION] / 100f * 0.3f,
                lens, (p[Adjustment.LENS_PROFILE] / 100f).coerceIn(0f, 1f),
            )
            g.fitZoom()
            return g
        }

        /** Resamples packed ARGB [src] (w x h) through the geometry; returns [src] when there is nothing to do. */
        fun apply(src: IntArray, w: Int, h: Int, p: DevelopParams, lens: LensProfile?): IntArray {
            val g = create(w, h, p, lens) ?: return src
            val out = IntArray(w * h)
            Parallel.rows(h) { y0, y1 ->
                val buf = FloatArray(6)
                for (y in y0 until y1) {
                    var o = y * w
                    for (x in 0 until w) {
                        g.map(x.toFloat(), y.toFloat(), buf)
                        out[o++] = if (g.hasTca) sample3(src, w, h, buf) else bilinear(src, w, h, buf[2], buf[3])
                    }
                }
            }
            return out
        }

        /** Samples red, green and blue each at their own position. */
        fun sample3(src: IntArray, w: Int, h: Int, b: FloatArray): Int {
            val r = channel(src, w, h, b[0], b[1], 16)
            val g = channel(src, w, h, b[2], b[3], 8)
            val bl = channel(src, w, h, b[4], b[5], 0)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }

        private fun channel(src: IntArray, w: Int, h: Int, sx: Float, sy: Float, shift: Int): Int {
            if (sx < -0.5f || sy < -0.5f || sx > w - 0.5f || sy > h - 0.5f) return 0
            val x = sx.coerceIn(0f, w - 1f); val y = sy.coerceIn(0f, h - 1f)
            val x0 = x.toInt(); val y0 = y.toInt()
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val fx = x - x0; val fy = y - y0
            val v = ((src[y0 * w + x0] ushr shift) and 0xFF) * (1 - fx) * (1 - fy) +
                ((src[y0 * w + x1] ushr shift) and 0xFF) * fx * (1 - fy) +
                ((src[y1 * w + x0] ushr shift) and 0xFF) * (1 - fx) * fy +
                ((src[y1 * w + x1] ushr shift) and 0xFF) * fx * fy
            return (v + 0.5f).toInt().coerceIn(0, 255)
        }

        fun bilinear(src: IntArray, w: Int, h: Int, sx: Float, sy: Float): Int {
            if (sx < -0.5f || sy < -0.5f || sx > w - 0.5f || sy > h - 0.5f) return 0xFF000000.toInt()
            val r = channel(src, w, h, sx, sy, 16)
            val g = channel(src, w, h, sx, sy, 8)
            val b = channel(src, w, h, sx, sy, 0)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
