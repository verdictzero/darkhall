package com.verdictzero.darkhall.core.orf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal TIFF/EXIF/Olympus-MakerNote reader for ORF files.
 *
 * Olympus ORFs are TIFF containers with an "IIRO"/"IIRS" (little endian) or
 * "MMOR" (big endian) signature. IFD0 describes the raw strip; the EXIF IFD holds
 * the shooting data plus a MakerNote whose sub-IFDs (CameraSettings 0x2020 and
 * ImageProcessing 0x2040) carry white balance, black level, crop and the camera's
 * own colour matrix. Sub-IFD offsets inside a "OLYMPUS\0" MakerNote are relative to
 * the start of the MakerNote blob.
 */
object OrfParser {

    private class Entry(val type: Int, val count: Int, val valueOffset: Int)

    private class Reader(val buf: ByteBuffer) {
        fun u8(o: Int) = buf.get(o).toInt() and 0xFF
        fun u16(o: Int) = buf.getShort(o).toInt() and 0xFFFF
        fun s16(o: Int) = buf.getShort(o).toInt()
        fun u32(o: Int) = buf.getInt(o).toLong() and 0xFFFFFFFFL
        fun s32(o: Int) = buf.getInt(o)
        val size get() = buf.limit()
    }

    private val typeSize = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)

    /** Larger than any sensor Olympus has shipped; guards allocations against corrupt headers. */
    private const val MAX_DIMENSION = 16384

    private class Ifd(val entries: Map<Int, Entry>, val next: Long)

    fun isOrf(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val sig = String(data, 0, 4, Charsets.US_ASCII)
        return sig == "IIRO" || sig == "IIRS" || sig == "MMOR" || sig == "II*\u0000" || sig == "MM\u0000*"
    }

    fun parse(data: ByteArray): OrfMetadata = try {
        parseUnchecked(data)
    } catch (e: OrfParseException) {
        throw e
    } catch (e: RuntimeException) {
        throw OrfParseException("Corrupt ORF header (${e.javaClass.simpleName})", e)
    }

    private fun parseUnchecked(data: ByteArray): OrfMetadata {
        if (data.size < 16) throw OrfParseException("File too small")
        val sig = String(data, 0, 4, Charsets.US_ASCII)
        val order = when {
            sig.startsWith("II") -> ByteOrder.LITTLE_ENDIAN
            sig.startsWith("MM") -> ByteOrder.BIG_ENDIAN
            else -> throw OrfParseException("Not a TIFF/ORF file (signature '$sig')")
        }
        val r = Reader(ByteBuffer.wrap(data).order(order))
        val ifd0Off = r.u32(4)
        if (ifd0Off <= 0 || ifd0Off >= r.size) throw OrfParseException("Bad IFD0 offset")
        val ifd0 = readIfd(r, ifd0Off.toInt(), 0)

        val make = ascii(r, ifd0.entries[0x010F]).trim()
        val model = ascii(r, ifd0.entries[0x0110]).trim()
        if (!make.uppercase().contains("OLYMPUS") && !make.uppercase().contains("OM DIGITAL")) {
            throw OrfParseException("Not an Olympus/OM raw file (make='$make')")
        }
        val rawWidth = int(r, ifd0.entries[0x0100]) ?: throw OrfParseException("Missing ImageWidth")
        val rawHeight = int(r, ifd0.entries[0x0101]) ?: throw OrfParseException("Missing ImageLength")
        if (rawWidth !in 16..MAX_DIMENSION || rawHeight !in 16..MAX_DIMENSION) {
            throw OrfParseException("Implausible raw size ${rawWidth}x$rawHeight")
        }
        val bps = int(r, ifd0.entries[0x0102]) ?: 16
        val compression = int(r, ifd0.entries[0x0103]) ?: 1
        val stripOffset = long(r, ifd0.entries[0x0111]) ?: throw OrfParseException("Missing StripOffsets")
        val stripBytes = long(r, ifd0.entries[0x0117]) ?: (data.size - stripOffset)
        if (stripOffset <= 0 || stripOffset >= data.size) throw OrfParseException("Raw data offset outside the file")
        val orientation = int(r, ifd0.entries[0x0112]) ?: 1
        var dateTime = ascii(r, ifd0.entries[0x0132]).trim()

        // Defaults for a TG-series sensor; everything below overrides from the file where present.
        var cfa = intArrayOf(1, 0, 2, 1) // GRBG as the TG-5/6/7 report in EXIF
        var iso = 0
        var exposure = 0.0
        var fNumber = 0.0
        var focal = 0.0
        var wb = floatArrayOf(1f, 1f, 1f)
        var black = intArrayOf(256, 256, 256, 256)
        var validBits = 12
        var matrix: FloatArray? = null
        var crop = Crop(0, 0, rawWidth, rawHeight)
        var previewOffset = 0L
        var previewLength = 0
        var thumbOffset = 0L
        var thumbLength = 0
        var ambientTemp: Double? = null
        var pressure: Double? = null
        var waterDepth: Double? = null

        val exifOff = long(r, ifd0.entries[0x8769])
        if (exifOff != null && exifOff in 1 until r.size) {
            val exif = readIfd(r, exifOff.toInt(), 0)
            iso = int(r, exif.entries[0x8827]) ?: 0
            exposure = rational(r, exif.entries[0x829A]) ?: 0.0
            fNumber = rational(r, exif.entries[0x829D]) ?: 0.0
            focal = rational(r, exif.entries[0x920A]) ?: 0.0
            val dto = ascii(r, exif.entries[0x9003]).trim()
            if (dto.isNotEmpty()) dateTime = dto
            // EXIF 2.31 environment tags; a denominator of 0 or -1 means "unknown".
            ambientTemp = rational(r, exif.entries[0x9400])?.takeIf { it > -60 && it < 80 }
            pressure = rational(r, exif.entries[0x9402])?.takeIf { it > 100 && it < 20000 }
            waterDepth = rational(r, exif.entries[0x9403])?.takeIf { it > -5 && it < 500 }
            exif.entries[0xA302]?.let { e ->
                // EXIF CFAPattern: 2 shorts (h, v repeat) followed by the pattern bytes.
                if (e.count >= 8 && e.valueOffset + 8 <= r.size) {
                    val h = r.u16(e.valueOffset)
                    val v = r.u16(e.valueOffset + 2)
                    if (h == 2 && v == 2) {
                        val p = IntArray(4) { r.u8(e.valueOffset + 4 + it) }
                        if (p.all { it in 0..2 }) cfa = p
                    }
                }
            }
            exif.entries[0x927C]?.let { mn ->
                val mnOff = mn.valueOffset
                val mnLen = mn.count
                parseMakerNote(r, mnOff, mnLen)?.let { m ->
                    m.wb?.let { wb = it }
                    m.black?.let { black = it }
                    m.validBits?.let { validBits = it }
                    m.matrix?.let { matrix = it }
                    m.crop?.let { crop = it }
                    previewOffset = m.previewOffset
                    previewLength = m.previewLength
                    thumbOffset = m.thumbOffset
                    thumbLength = m.thumbLength
                }
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

        val gps = long(r, ifd0.entries[0x8825])?.let { off ->
            if (off in 8 until r.size) runCatching { parseGps(r, readIfd(r, off.toInt(), 0)) }.getOrNull() else null
        }

        return OrfMetadata(
            make = make, model = model,
            rawWidth = rawWidth, rawHeight = rawHeight,
            bitsPerSample = bps, compression = compression,
            stripOffset = stripOffset, stripBytes = stripBytes,
            cfa = cfa,
            blackLevels = black,
            whiteLevel = (1 shl validBits) - 1,
            cameraWb = wb,
            cameraMatrix = matrix,
            crop = crop,
            orientation = orientation,
            iso = iso, exposureTime = exposure, fNumber = fNumber, focalLength = focal,
            dateTime = dateTime,
            previewOffset = previewOffset, previewLength = previewLength,
            thumbnailOffset = thumbOffset, thumbnailLength = thumbLength,
            ambientTemperature = ambientTemp,
            pressureHpa = pressure,
            waterDepth = waterDepth,
            gps = gps,
        )
    }

    private fun parseGps(r: Reader, gps: Ifd): GpsInfo? {
        val lat = rationals(r, gps.entries[0x0002], 3) ?: return null
        val lon = rationals(r, gps.entries[0x0004], 3) ?: return null
        val latRef = ascii(r, gps.entries[0x0001]).trim().uppercase()
        val lonRef = ascii(r, gps.entries[0x0003]).trim().uppercase()
        if (latRef.isEmpty() || lonRef.isEmpty()) return null
        var latDeg = lat[0] + lat[1] / 60.0 + lat[2] / 3600.0
        var lonDeg = lon[0] + lon[1] / 60.0 + lon[2] / 3600.0
        if (latDeg.isNaN() || lonDeg.isNaN() || latDeg > 90.0 || lonDeg > 180.0) return null
        if (latDeg == 0.0 && lonDeg == 0.0) return null // receiver had no fix
        if (latRef.startsWith("S")) latDeg = -latDeg
        if (lonRef.startsWith("W")) lonDeg = -lonDeg
        var alt = rational(r, gps.entries[0x0006])
        if (alt != null && (long(r, gps.entries[0x0005]) ?: 0L) == 1L) alt = -alt
        val time = rationals(r, gps.entries[0x0007], 3)?.let { t ->
            "%02d:%02d:%02d".format(t[0].toInt(), t[1].toInt(), t[2].toInt())
        }
        val date = ascii(r, gps.entries[0x001D]).trim().ifEmpty { null }
        return GpsInfo(latDeg, lonDeg, alt, time, date)
    }

    /** Reads [n] unsigned rationals, or null if any is missing or has a zero denominator. */
    private fun rationals(r: Reader, e: Entry?, n: Int): DoubleArray? {
        if (e == null || e.type != 5 || e.count < n) return null
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val num = r.u32(e.valueOffset + 8 * i)
            val den = r.u32(e.valueOffset + 8 * i + 4)
            if (den == 0L) return null
            out[i] = num.toDouble() / den
        }
        return out
    }

    private class MakerNoteData(
        val wb: FloatArray?, val black: IntArray?, val validBits: Int?, val matrix: FloatArray?,
        val crop: Crop?, val previewOffset: Long, val previewLength: Int,
        val thumbOffset: Long, val thumbLength: Int,
    )

    private fun parseMakerNote(r: Reader, mnOff: Int, mnLen: Int): MakerNoteData? {
        if (mnOff + 20 > r.size) return null
        // Long enough for "OM SYSTEM\0\0\0" (12 bytes), the header OM Digital Solutions
        // bodies such as the TG-7 write; Olympus-era files use "OLYMPUS\0" or "OLYMP\0".
        val head = String(ByteArray(12) { r.u8(mnOff + it).toByte() }, Charsets.ISO_8859_1)
        val base: Int
        val ifdOff: Int
        val reader: Reader
        when {
            head.startsWith("OLYMPUS\u0000") -> {
                // New style: "OLYMPUS\0" + "II"/"MM" + version(2) ; IFD at +12 ; offsets relative to mnOff.
                val bo = String(ByteArray(2) { r.u8(mnOff + 8 + it).toByte() }, Charsets.US_ASCII)
                val order = if (bo == "MM") ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                reader = Reader(r.buf.duplicate().order(order))
                base = mnOff
                ifdOff = mnOff + 12
            }
            head.startsWith("OM SYSTEM") -> {
                // OM System cameras: "OM SYSTEM\0\0\0" + "II"/"MM" + version(2); IFD at +16.
                val bo = String(ByteArray(2) { r.u8(mnOff + 12 + it).toByte() }, Charsets.US_ASCII)
                val order = if (bo == "MM") ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                reader = Reader(r.buf.duplicate().order(order))
                base = mnOff
                ifdOff = mnOff + 16
            }
            head.startsWith("OLYMP\u0000") -> {
                // Old style: "OLYMP\0" + 2 bytes ; IFD at +8 ; offsets absolute within the file.
                reader = r
                base = 0
                ifdOff = mnOff + 8
            }
            else -> return null
        }
        if (ifdOff + 2 > reader.size) return null
        val mn = try { readIfd(reader, ifdOff, base) } catch (e: Exception) { return null }

        var thumbOffset = 0L
        var thumbLength = 0
        mn.entries[0x0100]?.let { e ->
            if (e.type == 7 && e.count > 4) { thumbOffset = e.valueOffset.toLong(); thumbLength = e.count }
        }

        var previewOffset = 0L
        var previewLength = 0
        subIfd(reader, mn, 0x2020, base)?.let { cs ->
            val valid = int(reader, cs.entries[0x0100]) ?: 1
            val start = long(reader, cs.entries[0x0101])
            val len = int(reader, cs.entries[0x0102])
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
            ip.entries[0x0100]?.let { e ->
                if (e.count >= 2 && e.type == 3) {
                    val rr = reader.u16(e.valueOffset)
                    val bb = reader.u16(e.valueOffset + 2)
                    if (rr > 0 && bb > 0) wb = floatArrayOf(rr / 256f, 1f, bb / 256f)
                }
            }
            ip.entries[0x0200]?.let { e ->
                if (e.count >= 9 && e.type == 3) {
                    val m = FloatArray(9) { reader.s16(e.valueOffset + 2 * it) / 256f }
                    // Reject degenerate matrices (all zero or absurd).
                    if (m.any { it != 0f } && m.all { it > -4f && it < 4f }) matrix = m
                }
            }
            ip.entries[0x0600]?.let { e ->
                if (e.count >= 4 && e.type == 3) {
                    val v = IntArray(4) { reader.u16(e.valueOffset + 2 * it) }
                    // File order is R, G, G2, B (RGGB positions); map to colour index R, G, B, G2.
                    black = intArrayOf(v[0], v[1], v[3], v[2])
                }
            }
            int(reader, ip.entries[0x0611])?.let { if (it in 8..16) validBits = it }
            val cl = int(reader, ip.entries[0x0612])
            val ct = int(reader, ip.entries[0x0613])
            val cw = int(reader, ip.entries[0x0614])
            val ch = int(reader, ip.entries[0x0615])
            if (cl != null && ct != null && cw != null && ch != null && cw > 0 && ch > 0) {
                crop = Crop(cl, ct, cw, ch)
            }
        }
        return MakerNoteData(wb, black, validBits, matrix, crop, previewOffset, previewLength, thumbOffset, thumbLength)
    }

    private fun subIfd(r: Reader, parent: Ifd, tag: Int, base: Int): Ifd? {
        val e = parent.entries[tag] ?: return null
        val off = when (e.type) {
            13, 4 -> r.u32(e.valueOffset).toInt()
            else -> return null
        }
        val abs = base + off
        if (abs <= 0 || abs + 2 > r.size) return null
        return try { readIfd(r, abs, base) } catch (ex: Exception) { null }
    }

    private fun readIfd(r: Reader, off: Int, base: Int): Ifd {
        if (off < 0 || off + 2 > r.size) throw OrfParseException("IFD offset out of range: $off")
        val n = r.u16(off)
        if (n > 4096 || off + 2 + n * 12 > r.size) throw OrfParseException("Corrupt IFD at $off")
        val map = HashMap<Int, Entry>(n * 2)
        for (i in 0 until n) {
            val e = off + 2 + i * 12
            val tag = r.u16(e)
            val type = r.u16(e + 2)
            val count = r.u32(e + 4)
            if (count > Int.MAX_VALUE) continue
            val sz = (if (type in 1..13) typeSize[type] else 1).toLong() * count
            val valueOffsetL = if (sz <= 4) (e + 8).toLong() else base + r.u32(e + 8)
            // Skip entries whose data would run off the end of the file (corrupt or truncated).
            if (valueOffsetL < 0 || valueOffsetL + maxOf(sz, 4L) > r.size) continue
            val valueOffset = valueOffsetL.toInt()
            map[tag] = Entry(type, count.toInt(), valueOffset)
        }
        val nextOff = off + 2 + n * 12
        val next = if (nextOff + 4 <= r.size) r.u32(nextOff) else 0L
        return Ifd(map, next)
    }

    private fun int(r: Reader, e: Entry?): Int? = long(r, e)?.toInt()

    private fun long(r: Reader, e: Entry?): Long? {
        if (e == null || e.count < 1) return null
        return when (e.type) {
            1, 7 -> r.u8(e.valueOffset).toLong()
            3 -> r.u16(e.valueOffset).toLong()
            8 -> r.s16(e.valueOffset).toLong()
            4, 13 -> r.u32(e.valueOffset)
            9 -> r.s32(e.valueOffset).toLong()
            else -> null
        }
    }

    private fun rational(r: Reader, e: Entry?): Double? {
        if (e == null || e.count < 1) return null
        return when (e.type) {
            5 -> {
                val num = r.u32(e.valueOffset); val den = r.u32(e.valueOffset + 4)
                if (den == 0L || den == 0xFFFFFFFFL) null else num.toDouble() / den
            }
            10 -> {
                val num = r.s32(e.valueOffset); val den = r.s32(e.valueOffset + 4)
                if (den == 0 || den == -1) null else num.toDouble() / den
            }
            3, 4 -> long(r, e)?.toDouble()
            else -> null
        }
    }

    private fun ascii(r: Reader, e: Entry?): String {
        if (e == null || e.type != 2 || e.count <= 0) return ""
        val n = minOf(e.count, r.size - e.valueOffset)
        if (n <= 0) return ""
        val bytes = ByteArray(n) { r.u8(e.valueOffset + it).toByte() }
        val end = bytes.indexOf(0).let { if (it < 0) n else it }
        return String(bytes, 0, end, Charsets.ISO_8859_1)
    }
}
