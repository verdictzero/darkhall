package com.verdictzero.darkhall.core.raw

/**
 * Decoded sensor data, full sensor size (not yet cropped), row-major. A CFA file has one
 * sample per photosite; a linear DNG has three interleaved samples (R, G, B) per pixel.
 * Values are stored in a ShortArray and must be read as unsigned (`toInt() and 0xFFFF`).
 */
class RawImage(
    val width: Int,
    val height: Int,
    val data: ShortArray,
    val meta: RawMetadata,
) {
    val samplesPerPixel get() = meta.samplesPerPixel

    inline fun get(row: Int, col: Int): Int = data[row * width + col].toInt() and 0xFFFF

    /** Sample [channel] of a linear image's pixel. */
    inline fun get(row: Int, col: Int, channel: Int): Int = data[(row * width + col) * 3 + channel].toInt() and 0xFFFF
}
