package com.verdictzero.darkhall.core.raw

/**
 * Byte-level reader over a whole raw file in either byte order. Every multi-byte read is
 * bounds-checked by the JVM, and the format parsers turn the resulting exceptions into
 * [RawFormatException], so a corrupt offset can never read outside the file.
 */
class ByteReader(val data: ByteArray, val bigEndian: Boolean) {
    val size get() = data.size

    fun withOrder(bigEndian: Boolean) = if (bigEndian == this.bigEndian) this else ByteReader(data, bigEndian)

    fun u8(o: Int) = data[o].toInt() and 0xFF

    fun u16(o: Int): Int = if (bigEndian) (u8(o) shl 8) or u8(o + 1) else u8(o) or (u8(o + 1) shl 8)

    fun s16(o: Int): Int = u16(o).toShort().toInt()

    fun s32(o: Int): Int = if (bigEndian) {
        (u8(o) shl 24) or (u8(o + 1) shl 16) or (u8(o + 2) shl 8) or u8(o + 3)
    } else {
        u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)
    }

    fun u32(o: Int): Long = s32(o).toLong() and 0xFFFFFFFFL

    fun ascii(o: Int, n: Int): String {
        val end = minOf(o + n, size)
        if (o < 0 || o >= end) return ""
        var e = o
        while (e < end && data[e] != 0.toByte()) e++
        return String(data, o, e - o, Charsets.ISO_8859_1)
    }

    fun startsWith(o: Int, s: String): Boolean {
        if (o < 0 || o + s.length > size) return false
        for (i in s.indices) if (data[o + i] != s[i].code.toByte()) return false
        return true
    }
}

/** One TIFF directory entry; [offset] is the absolute position of its value. */
class TiffEntry(val tag: Int, val type: Int, val count: Int, val offset: Int, private val r: ByteReader) {

    private val unit get() = TYPE_SIZE.getOrElse(type) { 1 }

    /** Element [i] as an integer (BYTE, SHORT, LONG and their signed forms), or null. */
    fun int(i: Int = 0): Int? = long(i)?.toInt()

    fun long(i: Int = 0): Long? {
        if (i >= count) return null
        val o = offset + i * unit
        return when (type) {
            1, 7 -> r.u8(o).toLong()
            6 -> r.data[o].toLong()
            3 -> r.u16(o).toLong()
            8 -> r.s16(o).toLong()
            4, 13 -> r.u32(o)
            9 -> r.s32(o).toLong()
            else -> null
        }
    }

    /** Element [i] as a number of any numeric type; null for a zero or "unknown" denominator. */
    fun double(i: Int = 0): Double? {
        if (i >= count) return null
        val o = offset + i * unit
        return when (type) {
            5 -> {
                val num = r.u32(o); val den = r.u32(o + 4)
                if (den == 0L || den == 0xFFFFFFFFL) null else num.toDouble() / den
            }
            10 -> {
                val num = r.s32(o); val den = r.s32(o + 4)
                if (den == 0 || den == -1) null else num.toDouble() / den
            }
            11 -> Float.fromBits(r.s32(o)).toDouble()
            12 -> Double.fromBits((r.u32(if (r.bigEndian) o else o + 4) shl 32) or r.u32(if (r.bigEndian) o + 4 else o))
            else -> long(i)?.toDouble()
        }
    }

    fun ints(n: Int = count): IntArray? = if (count < n) null else IntArray(n) { int(it) ?: return null }

    fun doubles(n: Int = count): DoubleArray? = if (count < n) null else DoubleArray(n) { double(it) ?: return null }

    fun string(): String = if (type == 2 || type == 7 || type == 1) r.ascii(offset, count).trim() else ""

    /** Absolute offset stored in this LONG/IFD entry, plus [base]. */
    fun pointer(base: Int = 0): Int? = long()?.let { (it + base).toInt() }

    val byteLength: Long get() = unit.toLong() * count

    companion object {
        val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)
    }
}

class Ifd(val offset: Int, val entries: Map<Int, TiffEntry>, val next: Long, val reader: ByteReader) {
    operator fun get(tag: Int): TiffEntry? = entries[tag]
    fun int(tag: Int): Int? = entries[tag]?.int()
    fun long(tag: Int): Long? = entries[tag]?.long()
    fun double(tag: Int): Double? = entries[tag]?.double()
    fun string(tag: Int): String = entries[tag]?.string() ?: ""
}

object Tiff {
    private const val MAX_ENTRIES = 4096

    /**
     * Reads the directory at [off]. Value offsets are relative to [base] (the start of the
     * TIFF header, which is not the file start for maker notes with their own header).
     * Entries whose data would run past the end of the file are dropped.
     */
    fun readIfd(r: ByteReader, off: Int, base: Int = 0): Ifd {
        if (off < 0 || off + 2 > r.size) throw RawFormatException("Directory offset out of range ($off)")
        val n = r.u16(off)
        if (n > MAX_ENTRIES || off + 2 + n * 12 > r.size) throw RawFormatException("Corrupt directory at $off")
        val map = HashMap<Int, TiffEntry>(n * 2)
        for (i in 0 until n) {
            val e = off + 2 + i * 12
            val tag = r.u16(e)
            val type = r.u16(e + 2)
            val count = r.u32(e + 4)
            if (count > Int.MAX_VALUE) continue
            val sz = TiffEntry.TYPE_SIZE.getOrElse(type) { 1 }.toLong() * count
            val valueOffset = if (sz <= 4) (e + 8).toLong() else base + r.u32(e + 8)
            if (valueOffset < 0 || valueOffset + maxOf(sz, 4L) > r.size) continue
            // First occurrence wins, as in libtiff.
            map.putIfAbsent(tag, TiffEntry(tag, type, count.toInt(), valueOffset.toInt(), r))
        }
        val nextOff = off + 2 + n * 12
        val next = if (nextOff + 4 <= r.size) r.u32(nextOff) else 0L
        return Ifd(off, map, if (next > 0) next + base else 0L, r)
    }

    fun readIfdOrNull(r: ByteReader, off: Int, base: Int = 0): Ifd? =
        try { readIfd(r, off, base) } catch (e: RawFormatException) { null } catch (e: RuntimeException) { null }

    /** The IFD chain starting at [first] (IFD0, IFD1, ...), guarded against loops. */
    fun chain(r: ByteReader, first: Long, base: Int = 0, limit: Int = 16): List<Ifd> {
        val out = ArrayList<Ifd>()
        val seen = HashSet<Long>()
        var off = first
        while (off > 0 && off < r.size && seen.add(off) && out.size < limit) {
            val ifd = readIfdOrNull(r, off.toInt(), base) ?: break
            out += ifd
            off = ifd.next
        }
        return out
    }

    /** Every directory reachable from [roots] through SubIFDs (0x14A) and the EXIF pointer, depth first. */
    fun tree(r: ByteReader, roots: List<Ifd>): List<Ifd> {
        val out = ArrayList<Ifd>()
        val seen = HashSet<Int>()
        fun visit(ifd: Ifd, depth: Int) {
            if (!seen.add(ifd.offset) || depth > 4) return
            out += ifd
            ifd[0x014A]?.let { e ->
                for (i in 0 until minOf(e.count, 32)) {
                    val o = e.long(i) ?: continue
                    if (o in 8 until r.size) readIfdOrNull(r, o.toInt())?.let { visit(it, depth + 1) }
                }
            }
        }
        roots.forEach { visit(it, 0) }
        return out
    }
}

/** The shooting data every TIFF-based raw keeps in its EXIF directory. */
class ExifInfo(
    var iso: Int = 0,
    var exposureTime: Double = 0.0,
    var fNumber: Double = 0.0,
    var focalLength: Double = 0.0,
    var dateTime: String = "",
    var ambientTemperature: Double? = null,
    var pressureHpa: Double? = null,
    var waterDepth: Double? = null,
    /** EXIF CFAPattern as a 2x2 colour array, when present. */
    var cfa: IntArray? = null,
    var makerNote: TiffEntry? = null,
    var gps: GpsInfo? = null,
) {
    companion object {
        /** Reads the EXIF and GPS directories pointed to by [ifd0] (and, for missing values, [others]). */
        fun read(r: ByteReader, ifd0: Ifd, others: List<Ifd> = emptyList()): ExifInfo {
            val info = ExifInfo()
            info.dateTime = ifd0.string(0x0132)
            for (ifd in listOf(ifd0) + others) {
                val off = ifd.long(0x8769) ?: continue
                if (off !in 8 until r.size) continue
                val exif = Tiff.readIfdOrNull(r, off.toInt()) ?: continue
                info.merge(r, exif)
                break
            }
            // Some formats (DNG, NEF) also put shooting data straight into IFD0.
            info.merge(r, ifd0)
            ifd0.long(0x8825)?.let { off ->
                if (off in 8 until r.size) {
                    info.gps = runCatching { Tiff.readIfdOrNull(r, off.toInt())?.let { parseGps(it) } }.getOrNull()
                }
            }
            return info
        }

        private fun ExifInfo.merge(r: ByteReader, exif: Ifd) {
            if (iso == 0) iso = exif.int(0x8827) ?: exif.int(0x8833) ?: 0
            if (exposureTime == 0.0) exposureTime = exif.double(0x829A) ?: 0.0
            if (fNumber == 0.0) fNumber = exif.double(0x829D) ?: 0.0
            if (focalLength == 0.0) focalLength = exif.double(0x920A) ?: 0.0
            exif.string(0x9003).takeIf { it.isNotEmpty() }?.let { dateTime = it }
            // EXIF 2.31 environment tags; a denominator of 0 or -1 means "unknown".
            if (ambientTemperature == null) ambientTemperature = exif.double(0x9400)?.takeIf { it > -60 && it < 80 }
            if (pressureHpa == null) pressureHpa = exif.double(0x9402)?.takeIf { it > 100 && it < 20000 }
            if (waterDepth == null) waterDepth = exif.double(0x9403)?.takeIf { it > -5 && it < 500 }
            if (cfa == null) exif[0xA302]?.let { e ->
                // EXIF CFAPattern: 2 shorts (h, v repeat) followed by the pattern bytes.
                if (e.count >= 8) {
                    val h = r.u16(e.offset)
                    val v = r.u16(e.offset + 2)
                    // Some writers store the repeat counts in the other byte order.
                    val ok = (h == 2 && v == 2) || (h == 0x200 && v == 0x200)
                    if (ok) {
                        val p = IntArray(4) { r.u8(e.offset + 4 + it) }
                        if (p.all { it in 0..2 }) cfa = p
                    }
                }
            }
            if (makerNote == null) makerNote = exif[0x927C]
        }

        /** A GPS directory on its own (CR3 keeps it in a separate TIFF). */
        fun gpsFrom(gps: Ifd): GpsInfo? = parseGps(gps)

        private fun parseGps(gps: Ifd): GpsInfo? {
            val lat = gps[0x0002]?.doubles(3) ?: return null
            val lon = gps[0x0004]?.doubles(3) ?: return null
            val latRef = gps.string(0x0001).uppercase()
            val lonRef = gps.string(0x0003).uppercase()
            if (latRef.isEmpty() || lonRef.isEmpty()) return null
            var latDeg = lat[0] + lat[1] / 60.0 + lat[2] / 3600.0
            var lonDeg = lon[0] + lon[1] / 60.0 + lon[2] / 3600.0
            if (latDeg.isNaN() || lonDeg.isNaN() || latDeg > 90.0 || lonDeg > 180.0) return null
            if (latDeg == 0.0 && lonDeg == 0.0) return null // receiver had no fix
            if (latRef.startsWith("S")) latDeg = -latDeg
            if (lonRef.startsWith("W")) lonDeg = -lonDeg
            var alt = gps.double(0x0006)
            if (alt != null && (gps.long(0x0005) ?: 0L) == 1L) alt = -alt
            val time = gps[0x0007]?.doubles(3)?.let { t -> "%02d:%02d:%02d".format(t[0].toInt(), t[1].toInt(), t[2].toInt()) }
            val date = gps.string(0x001D).ifEmpty { null }
            return GpsInfo(latDeg, lonDeg, alt, time, date)
        }
    }
}

/** Finds the largest embedded JPEG preview in a set of TIFF directories. */
object Previews {
    class Found(val offset: Long, val length: Int)

    fun largestJpeg(r: ByteReader, ifds: List<Ifd>, extra: List<Found> = emptyList()): Found? {
        val candidates = ArrayList<Found>(extra)
        for (ifd in ifds) {
            val o = ifd.long(0x0201); val n = ifd.long(0x0202)
            if (o != null && n != null) candidates += Found(o, n.toInt())
            val comp = ifd.int(0x0103)
            val photometric = ifd.int(0x0106)
            if ((comp == 6 || comp == 7) && photometric != 32803 && photometric != 34892) {
                val so = ifd[0x0111]; val sb = ifd[0x0117]
                if (so != null && sb != null && so.count == 1) {
                    val off = so.long(); val len = sb.long()
                    if (off != null && len != null) candidates += Found(off, len.toInt())
                }
            }
        }
        return candidates
            .filter { it.length > 1024 && it.offset > 0 && it.offset + it.length <= r.size && isJpeg(r, it.offset.toInt()) }
            .maxByOrNull { it.length }
    }

    /** Baseline/progressive JPEG (not the lossless-JPEG raw data some formats store the same way). */
    private fun isJpeg(r: ByteReader, o: Int): Boolean {
        if (r.u8(o) != 0xFF || r.u8(o + 1) != 0xD8) return false
        var p = o + 2
        var guard = 0
        while (p + 4 < r.size && guard++ < 64) {
            if (r.u8(p) != 0xFF) return false
            val marker = r.u8(p + 1)
            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) return true
            if (marker == 0xC3 || marker == 0xDA) return false
            p += 2 + ((r.u8(p + 2) shl 8) or r.u8(p + 3))
        }
        return false
    }
}
