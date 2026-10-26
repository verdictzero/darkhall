package com.verdictzero.darkhall.core.export

/** File formats Darkhall exports to. */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    /** Can hold 16 bits per channel. */
    val supports16Bit: Boolean,
    /** Carries the shooting data (EXIF). */
    val carriesExif: Boolean,
) {
    JPEG("JPEG", "jpg", "image/jpeg", false, true),
    PNG("PNG", "png", "image/png", true, true),
    TIFF("TIFF", "tif", "image/tiff", true, true),
    WEBP("WebP", "webp", "image/webp", false, true),
    TGA("TGA", "tga", "image/x-tga", false, false),
}

/** How to export: the format and its options. */
data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    /** JPEG quality, 1-100. */
    val jpegQuality: Int = 95,
    /** 16 bits per channel, for formats that support it. */
    val sixteenBit: Boolean = false,
    /** TIFF: Deflate (ZIP) compression with a horizontal predictor; off writes it uncompressed. */
    val tiffCompressed: Boolean = true,
    /** Long edge of the exported picture in pixels; 0 for the full size. Never enlarges. */
    val longEdge: Int = 0,
) {
    val bits: Int get() = if (sixteenBit && format.supports16Bit) 16 else 8

    /** One line for the UI, e.g. "TIFF · 16-bit · ZIP". */
    fun describe(): String = buildList {
        if (longEdge > 0) add("$longEdge px")
        add(format.label)
        when (format) {
            ExportFormat.JPEG -> add("quality $jpegQuality")
            ExportFormat.PNG -> add("$bits-bit")
            ExportFormat.TIFF -> { add("$bits-bit"); add(if (tiffCompressed) "ZIP" else "uncompressed") }
            ExportFormat.WEBP -> add("lossless")
            ExportFormat.TGA -> add("8-bit RLE")
        }
    }.joinToString(" · ")
}

/** Long edges offered for export, 0 being the full size. */
val EXPORT_SIZES = listOf(0, 4096, 3000, 2048, 1600, 1080)
