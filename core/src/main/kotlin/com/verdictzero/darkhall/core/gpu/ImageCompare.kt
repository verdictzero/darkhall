package com.verdictzero.darkhall.core.gpu

import kotlin.math.abs

/** Per-channel difference statistics between two packed ARGB images of the same size. */
class ImageCompare(val meanAbs: Double, val maxAbs: Int, val fractionOver2: Double) {

    /** Within what the GPU must achieve to stand in for the CPU renderer. */
    val acceptable: Boolean get() = meanAbs <= 0.25 && fractionOver2 <= 0.002

    override fun toString() = "mean %.4f, max %d, >2 levels in %.3f%% of pixels".format(meanAbs, maxAbs, fractionOver2 * 100)

    companion object {
        fun of(a: IntArray, b: IntArray): ImageCompare {
            require(a.size == b.size) { "size mismatch" }
            var sum = 0L; var mx = 0; var over = 0
            for (i in a.indices) {
                var o = false
                for (sh in intArrayOf(16, 8, 0)) {
                    val d = abs(((a[i] ushr sh) and 0xFF) - ((b[i] ushr sh) and 0xFF))
                    sum += d
                    if (d > mx) mx = d
                    if (d > 2) o = true
                }
                if (o) over++
            }
            return ImageCompare(sum.toDouble() / (a.size * 3), mx, over.toDouble() / a.size)
        }
    }
}
