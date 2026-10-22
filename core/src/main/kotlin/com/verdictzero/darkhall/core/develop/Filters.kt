package com.verdictzero.darkhall.core.develop

import com.verdictzero.darkhall.core.util.Parallel

/** Separable image filters on float planes. */
object Filters {

    /** Horizontal box blur with edge clamping, src -> dst. */
    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        Parallel.rows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var sum = 0f
                for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    dst[row + x] = sum * inv
                    val add = src[row + (x + r + 1).coerceAtMost(w - 1)]
                    val sub = src[row + (x - r).coerceAtLeast(0)]
                    sum += add - sub
                }
            }
        }
    }

    /** Vertical box blur with edge clamping, src -> dst. Processes column bands for cache locality. */
    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        Parallel.cols(w) { x0, x1 ->
            val n = x1 - x0
            val sum = FloatArray(n)
            for (k in -r..r) {
                val row = k.coerceIn(0, h - 1) * w
                for (i in 0 until n) sum[i] += src[row + x0 + i]
            }
            for (y in 0 until h) {
                val row = y * w
                val addRow = (y + r + 1).coerceAtMost(h - 1) * w
                val subRow = (y - r).coerceAtLeast(0) * w
                for (i in 0 until n) {
                    dst[row + x0 + i] = sum[i] * inv
                    sum[i] += src[addRow + x0 + i] - src[subRow + x0 + i]
                }
            }
        }
    }

    /**
     * Gaussian-like blur: [passes] box blurs of radius [r]. Result in [dst]; [tmp]
     * is scratch. [src] is left untouched.
     */
    fun blur(src: FloatArray, dst: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int, passes: Int = 3) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, w * h); return }
        var input = src
        for (p in 0 until passes) {
            boxH(input, tmp, w, h, r)
            boxV(tmp, dst, w, h, r)
            input = dst
        }
    }

    /** 1-D sliding window minimum (van Herk / Gil-Werman), window = 2r+1, edge clamped. */
    private fun minLine(src: FloatArray, srcOff: Int, srcStride: Int, dst: FloatArray, dstOff: Int, dstStride: Int, n: Int, r: Int, g: FloatArray, hArr: FloatArray) {
        val k = 2 * r + 1
        // g: prefix minima within blocks of size k; h: suffix minima within blocks.
        for (i in 0 until n) {
            val v = src[srcOff + i * srcStride]
            g[i] = if (i % k == 0) v else minOf(g[i - 1], v)
        }
        for (i in n - 1 downTo 0) {
            val v = src[srcOff + i * srcStride]
            hArr[i] = if (i % k == k - 1 || i == n - 1) v else minOf(hArr[i + 1], v)
        }
        for (i in 0 until n) {
            val lo = i - r
            val hi = i + r
            val a = if (lo < 0) hArr[0] else hArr[lo]
            val b = if (hi >= n) g[n - 1] else g[hi]
            dst[dstOff + i * dstStride] = minOf(a, b)
        }
    }

    /** Separable square minimum filter of radius [r]: src -> dst, using [tmp] as scratch. */
    fun minFilter(src: FloatArray, dst: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, w * h); return }
        Parallel.rows(h) { y0, y1 ->
            val g = FloatArray(w); val hh = FloatArray(w)
            for (y in y0 until y1) minLine(src, y * w, 1, tmp, y * w, 1, w, r, g, hh)
        }
        Parallel.cols(w) { x0, x1 ->
            val g = FloatArray(h); val hh = FloatArray(h)
            for (x in x0 until x1) minLine(tmp, x, w, dst, x, w, h, r, g, hh)
        }
    }
}
