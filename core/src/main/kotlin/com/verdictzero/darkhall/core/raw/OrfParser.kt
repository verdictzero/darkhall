package com.verdictzero.darkhall.core.raw

/** Where an ORF keeps its sensor data. */
data class OrfLayout(
    override val dataOffset: Long,
    val dataBytes: Long,
    /** Olympus lossless predictive coding; otherwise 16-bit words with 12 bits left-aligned. */
    val compressed: Boolean,
) : RawLayout

/**
 * TIFF/EXIF/Olympus-MakerNote reader for ORF files.
 *
 * Olympus ORFs are TIFF containers with an "IIRO"/"IIRS" (little endian) or
 * "MMOR" (big endian) signature. IFD0 describes the raw strip; the EXIF IFD holds
 * the shooting data plus a MakerNote whose sub-IFDs (CameraSettings 0x2020 and
 * ImageProcessing 0x2040) carry white balance, black level, crop and the camera's
 * own colour matrix. Sub-IFD offsets inside a "OLYMPUS\0" MakerNote are relative to
 * the start of the MakerNote blob.
 */
object OrfParser {

    /** Larger than any sensor Olympus has shipped; guards allocations against corrupt headers. */
    private const val MAX_DIMENSION = 16384

    fun isOrf(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val sig = String(data, 0, 4, Charsets.US_ASCII)
        return sig == "IIRO" || sig == "IIRS" || sig == "MMOR"
    }

    fun parse(data: ByteArray): RawMetadata = try {
        parseUnchecked(data)
    } catch (e: RawFormatException) {
        throw e
    } catch (e: RuntimeException) {
        throw RawFormatException("Corrupt ORF header (${e.javaClass.simpleName})", e)
    }

    private fun parseUnchecked(data: ByteArray): RawMetadata {
        if (data.size < 16) throw RawFormatException("File too small")
        val r = when (data[0].toInt().toChar()) {
            'I' -> ByteReader(data, bigEndian = false)
            'M' -> ByteReader(data, bigEndian = true)
            else -> throw RawFormatException("Not a TIFF/ORF file")
        }
        val ifd0Off = r.u32(4)
        if (ifd0Off <= 0 || ifd0Off >= r.size) throw RawFormatException("Bad IFD0 offset")
        val ifd0 = Tiff.readIfd(r, ifd0Off.toInt())

        val make = ifd0.string(0x010F)
        val model = ifd0.string(0x0110)
        if (!make.uppercase().contains("OLYMPUS") && !make.uppercase().contains("OM DIGITAL")) {
            throw RawFormatException("Not an Olympus/OM raw file (make='$make')")
        }
        val rawWidth = ifd0.int(0x0100) ?: throw RawFormatException("Missing ImageWidth")
        val rawHeight = ifd0.int(0x0101) ?: throw RawFormatException("Missing ImageLength")
        if (rawWidth !in 16..MAX_DIMENSION || rawHeight !in 16..MAX_DIMENSION) {
            throw RawFormatException("Implausible raw size ${rawWidth}x$rawHeight")
        }
        val bps = ifd0.int(0x0102) ?: 16
        val compression = ifd0.int(0x0103) ?: 1
        val stripOffset = ifd0.long(0x0111) ?: throw RawFormatException("Missing StripOffsets")
        val stripBytes = ifd0.long(0x0117) ?: (data.size - stripOffset)
        if (stripOffset <= 0 || stripOffset >= data.size) throw RawFormatException("Raw data offset outside the file")
        val orientation = ifd0.int(0x0112) ?: 1

        val exif = ExifInfo.read(r, ifd0)
        // GRBG is what the TG-5/6/7 report; EXIF overrides where present.
        val cfa = exif.cfa ?: intArrayOf(1, 0, 2, 1)
        var wb = floatArrayOf(1f, 1f, 1f)
        var blackRggb = intArrayOf(256, 256, 256, 256)
        var validBits = 12
        var matrix: FloatArray? = null
        var crop = Crop(0, 0, rawWidth, rawHeight)
        var previewOffset = 0L
        var previewLength = 0
        var thumbOffset = 0L
        var thumbLength = 0

        exif.makerNote?.let { mn ->
            parseMakerNote(r, mn.offset)?.let { m ->
                m.wb?.let { wb = it }
                m.black?.let { blackRggb = it }
                m.validBits?.let { validBits = it }
                m.matrix?.let { matrix = it }
                m.crop?.let { crop = it }
                previewOffset = m.previewOffset
                previewLength = m.previewLength
                thumbOffset = m.thumbOffset
                thumbLength = m.thumbLength
            }
        }

        // Sanity-check the crop against the raw dimensions.
        if (crop.left < 0 || crop.top < 0 || crop.width <= 0 || crop.height <= 0 ||
            crop.left + crop.width > rawWidth || crop.top + crop.height > rawHeight
        ) {
            crop = Crop(0, 0, rawWidth, rawHeight)
        }
        if (previewOffset + previewLength > r.size || previewLength <= 0) {
            previewOffset = 0; previewLength = 0
        }
        if (thumbOffset + thumbLength > r.size || thumbLength <= 0) {
            thumbOffset = 0; thumbLength = 0
        }

        return RawMetadata(
            format = RawFormat.ORF,
            make = make, model = model,
            rawWidth = rawWidth, rawHeight = rawHeight,
            cfa = cfa,
            blackLevels = TiffRaw.blackFromRggb(blackRggb, cfa),
            whiteLevel = (1 shl validBits) - 1,
            cameraWb = wb,
            cameraMatrix = matrix,
            crop = crop,
            orientation = orientation,
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            dateTime = exif.dateTime,
            previewOffset = previewOffset, previewLength = previewLength,
            thumbnailOffset = thumbOffset, thumbnailLength = thumbLength,
            ambientTemperature = exif.ambientTemperature,
            pressureHpa = exif.pressureHpa,
            waterDepth = exif.waterDepth,
            gps = exif.gps,
            layout = OrfLayout(
                dataOffset = stripOffset,
                dataBytes = stripBytes,
                compressed = compression == 1 && bps == 16 && stripBytes * 7 > rawWidth.toLong() * rawHeight &&
                    stripBytes < rawWidth.toLong() * rawHeight * 2,
            ),
        )
    }

    private class MakerNoteData(
        val wb: FloatArray?, val black: IntArray?, val validBits: Int?, val matrix: FloatArray?,
        val crop: Crop?, val previewOffset: Long, val previewLength: Int,
        val thumbOffset: Long, val thumbLength: Int,
    )

    private fun parseMakerNote(r: ByteReader, mnOff: Int): MakerNoteData? {
        if (mnOff + 20 > r.size) return null
        // Long enough for "OM SYSTEM\0\0\0" (12 bytes), the header OM Digital Solutions
        // bodies such as the TG-7 write; Olympus-era files use "OLYMPUS\0" or "OLYMP\0".
        val base: Int
        val ifdOff: Int
        val reader: ByteReader
        when {
            r.startsWith(mnOff, "OLYMPUS\u0000") -> {
                // New style: "OLYMPUS\0" + "II"/"MM" + version(2) ; IFD at +12 ; offsets relative to mnOff.
                reader = r.withOrder(r.startsWith(mnOff + 8, "MM"))
                base = mnOff
                ifdOff = mnOff + 12
            }
            r.startsWith(mnOff, "OM SYSTEM") -> {
                // OM System cameras: "OM SYSTEM\0\0\0" + "II"/"MM" + version(2); IFD at +16.
                reader = r.withOrder(r.startsWith(mnOff + 12, "MM"))
                base = mnOff
                ifdOff = mnOff + 16
            }
            r.startsWith(mnOff, "OLYMP\u0000") -> {
                // Old style: "OLYMP\0" + 2 bytes ; IFD at +8 ; offsets absolute within the file.
                reader = r
                base = 0
                ifdOff = mnOff + 8
            }
            else -> return null
        }
        if (ifdOff + 2 > reader.size) return null
        val mn = Tiff.readIfdOrNull(reader, ifdOff, base) ?: return null

        var thumbOffset = 0L
        var thumbLength = 0
        mn[0x0100]?.let { e ->
            if (e.type == 7 && e.count > 4) { thumbOffset = e.offset.toLong(); thumbLength = e.count }
        }

        var previewOffset = 0L
        var previewLength = 0
        subIfd(reader, mn, 0x2020, base)?.let { cs ->
            val valid = cs.int(0x0100) ?: 1
            val start = cs.long(0x0101)
            val len = cs.int(0x0102)
            if (valid != 0 && start != null && len != null && len > 0) {
                previewOffset = base + start
                previewLength = len
            }
        }

        var wb: FloatArray? = null
        var black: IntArray? = null
        var validBits: Int? = null
        var matrix: FloatArray? = null
        var crop: Crop? = null
        subIfd(reader, mn, 0x2040, base)?.let { ip ->
            ip[0x0100]?.let { e ->
                if (e.count >= 2 && e.type == 3) {
                    val rr = reader.u16(e.offset)
                    val bb = reader.u16(e.offset + 2)
                    if (rr > 0 && bb > 0) wb = floatArrayOf(rr / 256f, 1f, bb / 256f)
                }
            }
            ip[0x0200]?.let { e ->
                if (e.count >= 9 && e.type == 3) {
                    val m = FloatArray(9) { reader.s16(e.offset + 2 * it) / 256f }
                    // Reject degenerate matrices (all zero or absurd).
                    if (m.any { it != 0f } && m.all { it > -4f && it < 4f }) matrix = m
                }
            }
            ip[0x0600]?.let { e ->
                // File order is R, G, G2, B (RGGB positions).
                if (e.count >= 4 && e.type == 3) black = IntArray(4) { reader.u16(e.offset + 2 * it) }
            }
            ip.int(0x0611)?.let { if (it in 8..16) validBits = it }
            val cl = ip.int(0x0612)
            val ct = ip.int(0x0613)
            val cw = ip.int(0x0614)
            val ch = ip.int(0x0615)
            if (cl != null && ct != null && cw != null && ch != null && cw > 0 && ch > 0) {
                crop = Crop(cl, ct, cw, ch)
            }
        }
        return MakerNoteData(wb, black, validBits, matrix, crop, previewOffset, previewLength, thumbOffset, thumbLength)
    }

    private fun subIfd(r: ByteReader, parent: Ifd, tag: Int, base: Int): Ifd? {
        val e = parent[tag] ?: return null
        if (e.type != 13 && e.type != 4) return null
        val abs = base + r.u32(e.offset).toInt()
        if (abs <= 0 || abs + 2 > r.size) return null
        return Tiff.readIfdOrNull(r, abs, base)
    }
}
