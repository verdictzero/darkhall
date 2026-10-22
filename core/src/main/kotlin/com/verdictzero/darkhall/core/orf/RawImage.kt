package com.verdictzero.darkhall.core.orf

/**
 * Decoded sensor data: one 12-bit sample per photosite, row-major, full sensor
 * size (not yet cropped). Values are stored in a ShortArray and must be read as
 * unsigned (`toInt() and 0xFFFF`).
 */
class RawImage(
    val width: Int,
    val height: Int,
    val data: ShortArray,
    val meta: OrfMetadata,
) {
    inline fun get(row: Int, col: Int): Int = data[row * width + col].toInt() and 0xFFFF
}
