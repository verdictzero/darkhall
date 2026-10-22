package com.verdictzero.darkhall.core.develop

/**
 * Planar floating point RGB image in camera-native linear space: black has been
 * subtracted and raw white (the 12-bit clip point) maps to 1.0. No white balance
 * or colour matrix has been applied yet, so a [Developer] can do both under
 * slider control.
 */
class LinearImage(val width: Int, val height: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
    val size get() = width * height

    companion object {
        fun allocate(width: Int, height: Int) =
            LinearImage(width, height, FloatArray(width * height), FloatArray(width * height), FloatArray(width * height))
    }

    /** Area-averaging resize used to build the interactive preview. Only shrinks. */
    fun resizeArea(nw: Int, nh: Int): LinearImage {
        if (nw >= width && nh >= height) return this
        val out = allocate(nw, nh)
        val sx = width.toFloat() / nw
        val sy = height.toFloat() / nh
        for (y in 0 until nh) {
            val ya = (y * sy).toInt().coerceIn(0, height - 1)
            val yb = ((y + 1) * sy).toInt().coerceIn(ya + 1, height)
            for (x in 0 until nw) {
                val xa = (x * sx).toInt().coerceIn(0, width - 1)
                val xb = ((x + 1) * sx).toInt().coerceIn(xa + 1, width)
                var sr = 0f; var sg = 0f; var sb = 0f
                for (yy in ya until yb) {
                    var i = yy * width + xa
                    for (xx in xa until xb) { sr += r[i]; sg += g[i]; sb += b[i]; i++ }
                }
                val n = ((yb - ya) * (xb - xa)).toFloat()
                val o = y * nw + x
                out.r[o] = sr / n; out.g[o] = sg / n; out.b[o] = sb / n
            }
        }
        return out
    }
}
