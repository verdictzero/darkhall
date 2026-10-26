package com.verdictzero.darkhall.core.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Baseline TIFF, RGB, 8 or 16 bits per channel, little-endian, in strips: uncompressed, or
 * Deflate ("Adobe ZIP", compression 8) with the horizontal-differencing predictor, which
 * Photoshop, Lightroom, GIMP, darktable and ImageMagick all read. The shooting data goes in
 * an EXIF sub-IFD and the position in a GPS IFD.
 *
 * The pixel strips are written first and the directory last (TIFF allows it anywhere), so
 * nothing but one strip is ever held in memory.
 */
object TiffWriter {
    private const val ROWS_PER_STRIP = 16

    fun write(src: RowSource, file: File, compress: Boolean, info: PhotoInfo?, progress: ((Float) -> Unit)? = null) {
        val bytesPerSample = src.bits / 8
        val stride = src.width * 3 * bytesPerSample
        val strips = (src.height + ROWS_PER_STRIP - 1) / ROWS_PER_STRIP
        val offsets = LongArray(strips)
        val counts = LongArray(strips)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 0, 0, 0, 0)) // IFD offset patched below
            val row = IntArray(3 * src.width)
            val strip = ByteBuffer.allocate(stride * ROWS_PER_STRIP).order(ByteOrder.LITTLE_ENDIAN)
            val deflater = Deflater(6)
            val zbuf = ByteArray(64 * 1024)
            for (s in 0 until strips) {
                strip.clear()
                val y0 = s * ROWS_PER_STRIP
                val y1 = minOf(src.height, y0 + ROWS_PER_STRIP)
                for (y in y0 until y1) {
                    src.row(y, row)
                    if (compress) {
                        // Predictor 2: each sample minus the same channel of the pixel to its left.
                        for (i in row.size - 1 downTo 3) row[i] = row[i] - row[i - 3]
                    }
                    if (bytesPerSample == 1) for (v in row) strip.put(v.toByte())
                    else for (v in row) strip.putShort(v.toShort())
                }
                offsets[s] = raf.filePointer
                if (compress) {
                    deflater.reset()
                    deflater.setInput(strip.array(), 0, strip.position())
                    deflater.finish()
                    var written = 0L
                    while (!deflater.finished()) {
                        val k = deflater.deflate(zbuf)
                        raf.write(zbuf, 0, k)
                        written += k
                    }
                    counts[s] = written
                } else {
                    raf.write(strip.array(), 0, strip.position())
                    counts[s] = strip.position().toLong()
                }
                if (progress != null && s % 8 == 0) progress(y1.toFloat() / src.height)
            }
            deflater.end()
            if (raf.filePointer % 2 == 1L) raf.write(0) // directories start on a word boundary

            val ifd = Ifd()
            ifd.long(256, src.width.toLong())
            ifd.long(257, src.height.toLong())
            ifd.shorts(258, IntArray(3) { src.bits })
            ifd.short(259, if (compress) 8 else 1)
            ifd.short(262, 2) // RGB
            info?.description?.takeIf { it.isNotEmpty() }?.let { ifd.ascii(270, it) }
            info?.make?.takeIf { it.isNotEmpty() }?.let { ifd.ascii(271, it) }
            info?.model?.takeIf { it.isNotEmpty() }?.let { ifd.ascii(272, it) }
            ifd.longs(273, offsets)
            ifd.short(274, 1) // upright already
            ifd.short(277, 3)
            ifd.long(278, ROWS_PER_STRIP.toLong())
            ifd.longs(279, counts)
            ifd.rationals(282, longArrayOf(300, 1))
            ifd.rationals(283, longArrayOf(300, 1))
            ifd.short(284, 1) // interleaved
            ifd.short(296, 2) // inches
            ifd.ascii(305, info?.software ?: "Darkhall")
            info?.dateTime?.takeIf { it.isNotEmpty() }?.let { ifd.ascii(306, it) }
            if (compress) ifd.short(317, 2)

            var exif: Ifd? = null
            var gps: Ifd? = null
            if (info != null) {
                exif = Ifd().apply {
                    if (info.exposureTime > 0) rationals(33434, rational(info.exposureTime))
                    if (info.fNumber > 0) rationals(33437, rational(info.fNumber))
                    if (info.iso > 0) short(34855, info.iso.coerceAtMost(65535))
                    undefined(36864, "0231".toByteArray(Charsets.US_ASCII))
                    if (info.dateTime.isNotEmpty()) ascii(36867, info.dateTime)
                    if (info.focalLength > 0) rationals(37386, rational(info.focalLength))
                }
                info.gps?.let { g ->
                    gps = Ifd().apply {
                        bytes(0, byteArrayOf(2, 3, 0, 0))
                        ascii(1, if (g.latitude >= 0) "N" else "S")
                        rationals(2, dms(abs(g.latitude)))
                        ascii(3, if (g.longitude >= 0) "E" else "W")
                        rationals(4, dms(abs(g.longitude)))
                        g.altitude?.let { alt ->
                            bytes(5, byteArrayOf(if (alt < 0) 1 else 0))
                            rationals(6, rational(abs(alt)))
                        }
                        g.timeStamp?.split(':')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }?.let { t ->
                            rationals(7, t.flatMap { v -> rational(v).toList() }.toLongArray())
                        }
                        g.dateStamp?.let { ascii(29, it) }
                    }
                }
                ifd.long(34665, 0) // offsets filled in once the sub-directories are placed
                if (gps != null) ifd.long(34853, 0)
            }

            val mainAt = raf.filePointer
            val exifAt = mainAt + ifd.size()
            val gpsAt = exifAt + (exif?.size() ?: 0)
            if (exif != null) ifd.long(34665, exifAt)
            gps?.let { ifd.long(34853, gpsAt) }
            raf.write(ifd.encode(mainAt))
            exif?.let { raf.write(it.encode(exifAt)) }
            gps?.let { raf.write(it.encode(gpsAt)) }
            raf.seek(4)
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mainAt.toInt()).array())
        }
    }

    /** A value as numerator/denominator with a fixed denominator large enough for its digits. */
    private fun rational(v: Double): LongArray {
        val den = when {
            v >= 1000 -> 1L
            v >= 1 -> 1000L
            else -> 1_000_000L
        }
        return longArrayOf((v * den).roundToLong(), den)
    }

    /** Degrees as EXIF's degrees, minutes, seconds rationals. */
    private fun dms(v: Double): LongArray {
        val d = v.toLong()
        val mFull = (v - d) * 60
        val m = mFull.toLong()
        val s = (mFull - m) * 60
        return longArrayOf(d, 1, m, 1, (s * 10000).roundToLong(), 10000)
    }

    /** One image file directory: entries sorted by tag, values that don't fit inline after it. */
    private class Ifd {
        private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)
        private val entries = sortedMapOf<Int, Entry>()

        private fun put(tag: Int, type: Int, count: Int, fill: (ByteBuffer) -> Unit) {
            val unit = when (type) { 1, 2, 7 -> 1; 3 -> 2; 4 -> 4; else -> 8 }
            val b = ByteBuffer.allocate(unit * count).order(ByteOrder.LITTLE_ENDIAN)
            fill(b)
            entries[tag] = Entry(tag, type, count, b.array())
        }

        fun bytes(tag: Int, v: ByteArray) = put(tag, 1, v.size) { it.put(v) }
        fun ascii(tag: Int, s: String) {
            val b = (s.filter { it.code in 32..126 } + "\u0000").toByteArray(Charsets.US_ASCII)
            put(tag, 2, b.size) { it.put(b) }
        }
        fun short(tag: Int, v: Int) = shorts(tag, intArrayOf(v))
        fun shorts(tag: Int, v: IntArray) = put(tag, 3, v.size) { b -> v.forEach { b.putShort(it.toShort()) } }
        fun long(tag: Int, v: Long) = longs(tag, longArrayOf(v))
        fun longs(tag: Int, v: LongArray) = put(tag, 4, v.size) { b -> v.forEach { b.putInt(it.toInt()) } }
        fun rationals(tag: Int, v: LongArray) = put(tag, 5, v.size / 2) { b -> v.forEach { b.putInt(it.toInt()) } }
        fun undefined(tag: Int, v: ByteArray) = put(tag, 7, v.size) { it.put(v) }

        private fun extra() = entries.values.sumOf { if (it.data.size > 4) it.data.size + (it.data.size and 1) else 0 }
        fun size(): Long = 2L + 12L * entries.size + 4 + extra()

        /** The directory as it sits at file offset [at], with no next directory. */
        fun encode(at: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val head = ByteBuffer.allocate(2 + 12 * entries.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            head.putShort(entries.size.toShort())
            var dataAt = at + head.capacity()
            val blobs = ByteArrayOutputStream()
            for (e in entries.values) {
                head.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
                if (e.data.size <= 4) {
                    head.put(e.data.copyOf(4))
                } else {
                    head.putInt(dataAt.toInt())
                    blobs.write(e.data)
                    if (e.data.size and 1 == 1) blobs.write(0)
                    dataAt += e.data.size + (e.data.size and 1)
                }
            }
            head.putInt(0)
            out.write(head.array())
            out.write(blobs.toByteArray())
            return out.toByteArray()
        }
    }
}
