package com.verdictzero.darkhall.core.raw

/** Crop rectangle (in sensor pixels) that the camera considers the real image. */
data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

/** GPS fix recorded by the camera. Latitude/longitude in signed decimal degrees. */
data class GpsInfo(
    val latitude: Double,
    val longitude: Double,
    /** Metres above sea level, negative below; null when not recorded. */
    val altitude: Double?,
    /** "HH:MM:SS" UTC, or null. */
    val timeStamp: String?,
    /** "YYYY:MM:DD", or null. */
    val dateStamp: String?,
)

/** The raw container formats Darkhall reads. */
enum class RawFormat(val label: String) {
    ORF("Olympus / OM System ORF"),
    DNG("DNG"),
    ARW("Sony ARW"),
    NEF("Nikon NEF"),
    CR2("Canon CR2"),
    RW2("Panasonic RW2"),
    PEF("Pentax PEF"),
    RAF("Fujifilm RAF"),
}

/**
 * Everything needed to decode and colour-manage one raw file, whatever its format.
 *
 * Mosaic (Bayer) files have [samplesPerPixel] 1 and a 2x2 [cfa]; Fujifilm X-Trans files a 6x6
 * [xtrans] pattern as well. LinearRaw DNGs (Apple ProRAW, Samsung Expert RAW) are already
 * demosaiced and carry three samples per pixel.
 */
data class RawMetadata(
    val format: RawFormat,
    val make: String,
    val model: String,
    val rawWidth: Int,
    val rawHeight: Int,
    /** 1 for a colour filter array, 3 for linear RGB. */
    val samplesPerPixel: Int = 1,
    /** 2x2 CFA at the sensor origin, row-major; 0 = R, 1 = G, 2 = B. */
    val cfa: IntArray,
    /** Fujifilm X-Trans: the 6x6 pattern at the sensor origin, row-major, else null. */
    val xtrans: IntArray? = null,
    /**
     * Black levels. For a CFA, indexed by position in the 2x2 cell at the sensor origin
     * (`(row & 1) * 2 + (col & 1)`); for linear RGB, by channel.
     */
    val blackLevels: IntArray,
    val whiteLevel: Int,
    /** As-shot white balance multipliers (R, G, B) with G normalised to 1. */
    val cameraWb: FloatArray,
    /** Camera RGB (after WB) -> sRGB matrix embedded by the camera, row-major 3x3, or null. */
    val cameraMatrix: FloatArray? = null,
    /** XYZ (D65) -> camera matrix in the Adobe/DNG convention, row-major 3x3, or null. */
    val xyzToCam: FloatArray? = null,
    /** Exposure the camera wants added to its raw data (DNG BaselineExposure), in EV. */
    val baselineExposure: Float = 0f,
    val crop: Crop,
    val orientation: Int,
    val iso: Int,
    val exposureTime: Double,
    val fNumber: Double,
    val focalLength: Double,
    val dateTime: String,
    val previewOffset: Long,
    val previewLength: Int,
    val thumbnailOffset: Long = 0,
    val thumbnailLength: Int = 0,
    /** EXIF 2.31 field-sensor data the TG series records. Null when absent or marked unknown. */
    val ambientTemperature: Double? = null,
    val pressureHpa: Double? = null,
    val waterDepth: Double? = null,
    val gps: GpsInfo? = null,
    /** Distortion/CA correction the file carries (DNG WarpRectilinear), or null. */
    val lensWarp: LensWarp? = null,
    /** How the sensor data is stored; only the decoder looks inside. */
    val layout: RawLayout,
) {
    /**
     * Best guess at how deep the shot was taken, in metres. Uses the recorded water depth,
     * otherwise derives it from the pressure sensor (about 100 hPa per metre of sea water)
     * when the reading is well above anything possible in air.
     */
    val estimatedDepth: Double? get() = depthFrom(waterDepth, pressureHpa)

    companion object {
        /** [estimatedDepth] from a recorded water depth or, failing that, a pressure reading. */
        fun depthFrom(waterDepth: Double?, pressureHpa: Double?): Double? {
            waterDepth?.let { return it.coerceAtLeast(0.0) }
            val p = pressureHpa ?: return null
            return if (p > 1100.0) ((p - 1013.25) / 100.5).coerceAtLeast(0.0) else null
        }
    }

    val isLinear: Boolean get() = samplesPerPixel == 3

    val isXTrans: Boolean get() = xtrans != null

    /** Colour of the CFA site at absolute sensor row/col. */
    fun cfaColor(row: Int, col: Int): Int =
        xtrans?.let { it[(row % 6) * 6 + col % 6] } ?: cfa[((row and 1) shl 1) or (col and 1)]

    /** Black level of the CFA site at absolute sensor row/col. */
    fun blackAt(row: Int, col: Int): Int = blackLevels[((row and 1) shl 1) or (col and 1)]

    fun describe(): String {
        val shutter = when {
            exposureTime <= 0 -> ""
            exposureTime < 1 -> "1/${Math.round(1 / exposureTime)}s"
            else -> "${"%.1f".format(exposureTime).removeSuffix(".0")}s"
        }
        val depth = estimatedDepth?.let { if (it >= 0.3) "  ${"%.1f".format(it)} m deep" else "" } ?: ""
        val parts = listOf(
            model,
            if (iso > 0) "ISO $iso" else "",
            shutter,
            if (fNumber > 0) "f/${"%.1f".format(fNumber)}" else "",
            if (focalLength > 0) "${"%.1f".format(focalLength)}mm" else "",
        ).filter { it.isNotEmpty() }
        return parts.joinToString("  ") + depth
    }
}

/**
 * A DNG WarpRectilinear correction: per colour plane, the radial factor k0 + k1 r^2 + k2 r^4 + k3 r^6
 * with r = 1 at the corners of the [refWidth] x [refHeight] area it was calibrated on (the active area).
 */
class LensWarp(val planes: List<DoubleArray>, val refWidth: Int, val refHeight: Int)

/** Format-specific description of where and how the sensor samples are stored. */
sealed interface RawLayout {
    /** Start of the sample data in the file. */
    val dataOffset: Long
}
