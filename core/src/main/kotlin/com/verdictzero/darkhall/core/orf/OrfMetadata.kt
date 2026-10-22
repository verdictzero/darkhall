package com.verdictzero.darkhall.core.orf

/** Crop rectangle (in sensor pixels) that the camera considers the real image. */
data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Everything we need from an Olympus ORF container to decode and colour-manage
 * the raw data. Written for the Tough TG-7 (identical to the TG-6/TG-5 layout),
 * but the parser is generic enough for any "OLYMPUS\0II" style ORF.
 */
data class OrfMetadata(
    val make: String,
    val model: String,
    val rawWidth: Int,
    val rawHeight: Int,
    val bitsPerSample: Int,
    val compression: Int,
    val stripOffset: Long,
    val stripBytes: Long,
    /** 2x2 CFA at the sensor origin, row-major; 0 = R, 1 = G, 2 = B. */
    val cfa: IntArray,
    /** Per-colour black levels indexed by colour (R, G, B, G2). */
    val blackLevels: IntArray,
    val whiteLevel: Int,
    /** As-shot white balance multipliers (R, G, B) with G normalised to 1. */
    val cameraWb: FloatArray,
    /** Camera RGB (after WB) -> sRGB matrix embedded by the camera, row-major 3x3, or null. */
    val cameraMatrix: FloatArray?,
    val crop: Crop,
    val orientation: Int,
    val iso: Int,
    val exposureTime: Double,
    val fNumber: Double,
    val focalLength: Double,
    val dateTime: String,
    val previewOffset: Long,
    val previewLength: Int,
    val thumbnailOffset: Long,
    val thumbnailLength: Int,
) {
    val isOlympusCompressed: Boolean
        get() = compression == 1 && bitsPerSample == 16 && stripBytes * 7 > rawWidth.toLong() * rawHeight

    val isUnpacked16: Boolean
        get() = compression == 1 && bitsPerSample == 16 && stripBytes >= rawWidth.toLong() * rawHeight * 2

    /** Colour of the CFA site at absolute sensor row/col. */
    fun cfaColor(row: Int, col: Int): Int = cfa[((row and 1) shl 1) or (col and 1)]

    fun describe(): String {
        val shutter = if (exposureTime > 0 && exposureTime < 1) "1/${Math.round(1 / exposureTime)}s" else "${exposureTime}s"
        return "$model  ISO $iso  $shutter  f/${"%.1f".format(fNumber)}  ${"%.1f".format(focalLength)}mm"
    }
}
