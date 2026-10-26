package com.verdictzero.darkhall.core.export

import com.verdictzero.darkhall.core.raw.GpsInfo
import com.verdictzero.darkhall.core.raw.RawMetadata
import java.util.Locale

/** The shooting data an export carries, from the raw's metadata. */
data class PhotoInfo(
    val make: String,
    val model: String,
    /** "YYYY:MM:DD HH:MM:SS", or empty. */
    val dateTime: String,
    val iso: Int,
    val exposureTime: Double,
    val fNumber: Double,
    val focalLength: Double,
    val gps: GpsInfo?,
    /** Free text: the TG-7's depth, temperature and pressure readings, or empty. */
    val description: String,
    val software: String = "Darkhall",
) {
    companion object {
        fun from(meta: RawMetadata): PhotoInfo {
            // EXIF has no widely read tags for the TG series' field sensors (and its text is
            // ASCII-only), so they go into the description, where gallery apps show them.
            val field = buildList {
                meta.estimatedDepth?.takeIf { it >= 0.3 }?.let { add("Depth %.1f m".format(Locale.US, it)) }
                meta.ambientTemperature?.let { add("Temp %.1f C".format(Locale.US, it)) }
                meta.pressureHpa?.let { add("%.0f hPa".format(Locale.US, it)) }
            }
            return PhotoInfo(
                meta.make, meta.model, meta.dateTime, meta.iso, meta.exposureTime, meta.fNumber,
                meta.focalLength, meta.gps, field.joinToString(", "),
            )
        }
    }
}
