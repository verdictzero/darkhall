package com.verdictzero.darkhall.core.raw

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the library and the info panel show about a shot: camera, time, exposure and, from the
 * TG series' field sensors, depth, temperature, pressure and altitude. Small enough to cache
 * per file, so the library can sort, group and badge a whole folder without opening raws.
 */
data class ShotFacts(
    val make: String,
    val model: String,
    /** "YYYY:MM:DD HH:MM:SS" as the camera wrote it, or empty. */
    val dateTime: String,
    val iso: Int,
    val exposureTime: Double,
    val fNumber: Double,
    val focalLength: Double,
    /** Upright size of the developed picture, in pixels. */
    val width: Int,
    val height: Int,
    /** Metres below the surface, from the depth or pressure sensor; null when not recorded or in air. */
    val depth: Double?,
    /** Degrees Celsius, or null. */
    val temperature: Double?,
    val pressureHpa: Double?,
    /** Metres above sea level from GPS, or null. */
    val altitude: Double?,
    val latitude: Double?,
    val longitude: Double?,
) {
    /** True for a shot taken underwater (deeper than 30 cm). */
    val underwater: Boolean get() = (depth ?: 0.0) >= 0.3

    /** "8.2 m · 24 °C" for an underwater shot, for a corner of its thumbnail; null otherwise. */
    val badge: String?
        get() = if (!underwater) null else buildList {
            add("%.1f m".format(Locale.US, depth))
            temperature?.let { add("%.0f °C".format(Locale.US, it)) }
        }.joinToString(" · ")

    /** The info panel's rows: label and value, only those the file has. */
    fun rows(): List<Pair<String, String>> = buildList {
        add("Camera" to listOf(make, model).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Unknown" })
        when {
            dateTime.length >= 16 -> add("Taken" to "${dateTime.take(10).replace(':', '-')} ${dateTime.substring(11, 16)}")
            dateTime.isNotBlank() -> add("Taken" to dateTime)
        }
        val exposure = listOfNotNull(
            shutter(),
            fNumber.takeIf { it > 0 }?.let { "f/%s".format(Locale.US, trim(it)) },
            iso.takeIf { it > 0 }?.let { "ISO $it" },
            focalLength.takeIf { it > 0 }?.let { "${trim(it)} mm" },
        )
        if (exposure.isNotEmpty()) add("Exposure" to exposure.joinToString("  ·  "))
        if (width > 0 && height > 0) add("Size" to "%,d × %,d px (%.1f MP)".format(Locale.US, width, height, width * height / 1e6))
        if (underwater) add("Depth" to "%.1f m".format(Locale.US, depth))
        temperature?.let { add((if (underwater) "Water temperature" else "Temperature") to "%.1f °C".format(Locale.US, it)) }
        pressureHpa?.let { add("Pressure" to "%,.0f hPa".format(Locale.US, it)) }
        altitude?.let { add("Altitude" to "%,d m".format(Locale.US, it.roundToInt())) }
        if (latitude != null && longitude != null) {
            add("Location" to "%.5f° %s, %.5f° %s".format(Locale.US, abs(latitude), if (latitude >= 0) "N" else "S", abs(longitude), if (longitude >= 0) "E" else "W"))
        }
    }

    private fun shutter(): String? = when {
        exposureTime <= 0 -> null
        exposureTime < 1 -> "1/${Math.round(1 / exposureTime)} s"
        else -> "${trim(exposureTime)} s"
    }

    private fun trim(v: Double) = "%.1f".format(Locale.US, v).removeSuffix(".0")

    companion object {
        fun from(meta: RawMetadata): ShotFacts {
            val turned = meta.orientation in setOf(5, 6, 7, 8)
            return ShotFacts(
                make = meta.make.trim(), model = meta.model.trim(), dateTime = meta.dateTime.trim(),
                iso = meta.iso, exposureTime = meta.exposureTime, fNumber = meta.fNumber, focalLength = meta.focalLength,
                width = if (turned) meta.crop.height else meta.crop.width,
                height = if (turned) meta.crop.width else meta.crop.height,
                depth = meta.estimatedDepth?.takeIf { it >= 0.3 },
                temperature = meta.ambientTemperature,
                pressureHpa = meta.pressureHpa,
                altitude = meta.gps?.altitude,
                latitude = meta.gps?.latitude,
                longitude = meta.gps?.longitude,
            )
        }
    }
}
