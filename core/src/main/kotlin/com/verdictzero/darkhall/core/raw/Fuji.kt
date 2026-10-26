/*
 * Copyright 2026 verdictzero
 * Portions Copyright 2008-2025 LibRaw LLC (info@libraw.org)
 * Portions Copyright 2016-2019 Alexey Danilchenko (Fujifilm compressed decoder)
 * Portions Copyright 1997-2018 Dave Coffin (dcraw.c); no RESTRICTED dcraw code is used.
 *
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License, Version 1.0 only (the "License"). You may not use this file except
 * in compliance with the License. A copy is in LICENSES/CDDL-1.0.txt; see it for the
 * specific language governing permissions and limitations under the License.
 *
 * Source code: https://github.com/verdictzero/darkhall. Only the files that carry this
 * header are covered by the CDDL; see NOTICE.md.
 */

package com.verdictzero.darkhall.core.raw

import com.verdictzero.darkhall.core.util.Parallel

data class RafLayout(
    override val dataOffset: Long,
    val dataBytes: Long,
    val encoding: Encoding,
    /** Byte order of unpacked 16-bit samples. */
    val bigEndian: Boolean,
    /** Compressed data only: the stream's parameters. */
    val compressed: RafCompression? = null,
) : RawLayout {
    enum class Encoding { UNPACKED, PACKED14, COMPRESSED }
}

/** Header of Fujifilm's compressed raw data (X-Trans III and later, GFX). */
data class RafCompression(
    val xtrans: Boolean,
    val lossless: Boolean,
    val bits: Int,
    val blockSize: Int,
    val blocks: Int,
    val lines: Int,
)

/**
 * Fujifilm RAF: a small big-endian header pointing at an embedded JPEG (whose EXIF holds the
 * shooting data), Fujifilm's own tag directory (sizes, X-Trans layout, white balance) and the
 * sensor data section, itself a little TIFF. X-Trans bodies record a 6x6 colour pattern; the
 * Bayer ones (GFX, X-A) are RGGB. Only the X and GFX generations are read; the older
 * SuperCCD/EXR bodies' layouts are not.
 */
object RafParser {

    fun isRaf(data: ByteArray): Boolean =
        data.size >= 16 && String(data, 0, 8, Charsets.ISO_8859_1) == "FUJIFILM"

    /** The EXIF TIFF inside the embedded JPEG, copied out (its offsets are relative to its own start). */
    fun exifTiff(data: ByteArray): ByteArray? {
        if (data.size < 100) return null
        val be = ByteReader(data, bigEndian = true)
        val jpg = be.u32(84)
        if (jpg < 100 || jpg + 12 > data.size) return null
        val o = jpg.toInt()
        // FFD8, then an APP1 "Exif\0\0" segment whose TIFF starts 12 bytes in.
        if (be.u16(o) != 0xFFD8 || be.u16(o + 2) != 0xFFE1 || !be.startsWith(o + 6, "Exif")) return null
        val len = be.u16(o + 4) - 8
        if (len < 8) return null
        val end = minOf(data.size, o + 12 + len)
        return data.copyOfRange(o + 12, end)
    }

    /** Embedded JPEG preview: offset, length. */
    fun preview(data: ByteArray): Pair<Long, Int>? {
        if (data.size < 92) return null
        val be = ByteReader(data, bigEndian = true)
        val off = be.u32(84); val len = be.u32(88)
        return if (off > 0 && len > 1024 && len < Int.MAX_VALUE) off to len.toInt() else null
    }

    private class FujiDir(
        var rawHeight: Int = 0, var rawWidth: Int = 0,
        var height: Int = 0, var width: Int = 0,
        var layout: Int = 0, var superCcd: Boolean = false,
        var xtrans: IntArray? = null,
        var wbGrgb: IntArray? = null,
        var black: IntArray? = null,
        /** 0: no RAFData; 1-4: generations with an image size table; 4096: the X-A kind. */
        var generation: Int = 0,
        var sizeTable: IntArray? = null,
    )

    private fun fujiDir(r: ByteReader, off: Long): FujiDir {
        val d = FujiDir()
        if (off < 0 || off + 4 > r.size) throw RawFormatException("Corrupt RAF directory")
        val entries = r.u32(off.toInt())
        if (entries > 255) throw RawFormatException("Corrupt RAF directory")
        var p = off.toInt() + 4
        repeat(entries.toInt()) {
            if (p + 4 > r.size) return d
            val tag = r.u16(p); val len = r.u16(p + 2)
            val v = p + 4
            if (v + len > r.size) return d
            when (tag) {
                0x0100 -> if (len >= 4) { d.rawHeight = r.u16(v); d.rawWidth = r.u16(v + 2) }
                0x0121 -> if (len >= 4) { d.height = r.u16(v); d.width = r.u16(v + 2).let { if (it == 4284) it + 3 else it } }
                0x0130 -> if (len >= 2) { d.layout = r.u8(v) shr 7; d.superCcd = (r.u8(v + 1) and 8) == 0 }
                0x0131 -> if (len >= 36) {
                    // Stored last site first.
                    d.xtrans = IntArray(36) { c -> r.u8(v + 35 - c).coerceIn(0, 2) }
                }
                // G, R, G, B, possibly twice (then averaged, as LibRaw does).
                0x2FF0 -> if (len >= 8) d.wbGrgb = IntArray(4) { c ->
                    val a = r.u16(v + 2 * c)
                    if (len == 16) (a + r.u16(v + 8 + 2 * c)) / 2 else a
                }
                0x4000 -> if (len == 8 || len == 16) {
                    // G R G B order (as the WB), averaged over the two sets when there are two.
                    val grgb = IntArray(4) { c -> if (len == 16) (r.u16(v + 2 * c) + r.u16(v + 8 + 2 * c)) / 2 else r.u16(v + 2 * c) }
                    d.black = intArrayOf(grgb[1], grgb[0], grgb[2], grgb[3])
                }
                0xC000 -> {
                    val le = r.withOrder(false)
                    if (len > 20000) {
                        val b01 = le.u16(v); val b23 = le.u16(v + 2)
                        val wrts = le.u32(v + 4) == 0x53545257L
                        val (gen, at) = when {
                            b01 != 0 && b23 == 0 && b01 < 10000 -> 1 to 0
                            b01 > 10000 && b23 == 0 -> 2 to 4
                            b01 == 0 -> if (wrts) 4 to 8 else 3 to 4
                            else -> 0 to 0
                        }
                        d.generation = gen
                        if (gen != 0) d.sizeTable = IntArray(2) { le.s32(v + at + 4 * it) }
                    } else if (len == 4096) {
                        d.generation = 4096
                    }
                }
            }
            p = v + len
        }
        return d
    }

    fun parse(data: ByteArray): RawMetadata {
        if (data.size < 108) throw RawFormatException("File too small")
        val be = ByteReader(data, bigEndian = true)
        val headerModel = be.ascii(0x1C, 32).trim()
        val dir = fujiDir(be, be.u32(92))
        if (dir.superCcd || dir.generation == 0) {
            throw RawFormatException("Fujifilm RAF files from this camera ($headerModel) are not supported")
        }

        // Shooting data from the embedded JPEG's EXIF.
        val exifBytes = exifTiff(data)
        val er = exifBytes?.let { ByteReader(it, bigEndian = it[0] == 'M'.code.toByte()) }
        val eRoots = er?.let { Tiff.chain(it, it.u32(4)) }.orEmpty()
        val ifd0 = eRoots.firstOrNull()
        val exif = if (er != null && ifd0 != null) ExifInfo.read(er, ifd0, Tiff.tree(er, eRoots)) else ExifInfo()
        val make = ifd0?.string(0x010F)?.ifEmpty { null } ?: "FUJIFILM"
        val model = ifd0?.string(0x0110)?.ifEmpty { null } ?: headerModel

        // The sensor data section: a TIFF whose 0xF000 directory describes the data.
        val cfaOff = be.u32(100)
        if (cfaOff < 108 || cfaOff + 8 > data.size) throw RawFormatException("RAF without raw data")
        val base = cfaOff.toInt()
        val cr = ByteReader(data, bigEndian = data[base] == 'M'.code.toByte())
        if (!cr.startsWith(base, "II*\u0000") && !cr.startsWith(base, "MM\u0000*")) {
            throw RawFormatException("Fujifilm RAF files from this camera ($headerModel) are not supported")
        }
        val root = Tiff.readIfd(cr, (cr.u32(base + 4) + base).toInt(), base)
        val raw = root.long(0xF000)?.let { Tiff.readIfd(cr, (it + base).toInt(), base) } ?: root
        val rawW = raw.int(0xF001) ?: dir.rawWidth
        val rawH = raw.int(0xF002) ?: dir.rawHeight
        val bps = raw.int(0xF003) ?: 14
        val dataOff = (raw.long(0xF007) ?: throw RawFormatException("RAF without raw data")) + base
        val dataBytes = raw.long(0xF008) ?: (data.size - dataOff)
        if (rawW !in 16..TiffRaw.MAX_DIMENSION || rawH !in 16..TiffRaw.MAX_DIMENSION || bps !in 8..16) {
            throw RawFormatException("Corrupt RAF header")
        }
        if (dataOff !in 0 until data.size) throw RawFormatException("Raw data offset outside the file")

        // Visible area, as LibRaw works it out.
        var width = if (dir.sizeTable != null && dir.generation != 4096) dir.sizeTable!![0] else dir.width.takeIf { it > 0 } ?: rawW
        var height = if (dir.sizeTable != null && dir.generation != 4096) dir.sizeTable!![1] else dir.height.takeIf { it > 0 } ?: rawH
        var top = if (rawH >= height) ((rawH - height) shr 2) shl 1 else 0
        var left = if (rawW >= width) ((rawW - width) shr 2) shl 1 else 0
        var cfa = intArrayOf(0, 1, 1, 2)
        var white = (1 shl bps) - 1
        val xtrans = dir.xtrans
        if (xtrans == null && !model.startsWith("X-")) white = 0x3E00
        if (dir.generation != 4096) when (rawW) {
            2944 -> cfa = intArrayOf(2, 1, 1, 0)
            4096, 5120, 6048, 6160 -> left = 0
            4992 -> left = 4
            7872 -> { top = 6; left = 0; width = 7752; height = 5178 }
            6336 -> { top = 6; left = 0; width = if (model.equals("X-S20", ignoreCase = true)) 6252 else 6264; height = 4176 }
            6384 -> { top = 6; left = 0; width = 6246; height = 4170 }
            6912, 9216 -> { left = 0; top = 0 }
            8472 -> { left = 0; top = 0; width = rawW - 192 }
            9696, 11808 -> {
                left = 0; width = rawW - 146; height = rawH - 8; top = 2
                if (bps == 16) white = 0xFFFF
            }
        }
        if (xtrans != null) {
            // X-Trans margins are whole pattern repeats.
            val t6 = (top + 5) / 6 * 6; val l6 = (left + 5) / 6 * 6
            height -= t6 - top; width -= l6 - left
            top = t6; left = l6
        }

        // How the samples are stored; the compressed header gives the real raw size.
        var outW = rawW; var outH = rawH
        var offset = dataOff
        val px = rawW.toLong() * rawH
        val encoding: RafLayout.Encoding
        var compression: RafCompression? = null
        when {
            px * 2 == dataBytes -> encoding = RafLayout.Encoding.UNPACKED
            px * 7 / 4 == dataBytes -> encoding = RafLayout.Encoding.PACKED14
            else -> {
                val c = compressedHeader(data, dataOff.toInt())
                    ?: throw RawFormatException("Unsupported Fujifilm raw data in $model")
                if (c.first.xtrans != (xtrans != null)) throw RawFormatException("Corrupt RAF header (colour layout)")
                encoding = RafLayout.Encoding.COMPRESSED
                compression = c.first
                outW = c.second; outH = c.third
                offset = dataOff + 16
            }
        }
        val crop = Crop(left, top, width, height).takeIf { TiffRaw.valid(it, outW, outH) } ?: Crop(0, 0, outW, outH)

        // Levels and white balance: the data section's tags, else Fujifilm's directory.
        val blacks = raw[0xF00A]?.let { e -> IntArray(minOf(e.count, 36)) { e.int(it) ?: 0 } }
        val black = when {
            blacks != null && blacks.size == 4 -> blacks
            blacks != null && blacks.isNotEmpty() -> IntArray(4) { (blacks.sum() + blacks.size / 2) / blacks.size }
            dir.black != null -> TiffRaw.blackFromRggb(dir.black!!, cfa)
            else -> null
        }
        val (tb, tw) = TiffRaw.tableLevels(make, model, black, white)
        val grb = raw[0xF00E]?.ints(3)
        val wb = when {
            grb != null && grb[0] > 0 && grb[1] > 0 && grb[2] > 0 -> floatArrayOf(grb[1].toFloat() / grb[0], 1f, grb[2].toFloat() / grb[0])
            dir.wbGrgb != null && dir.wbGrgb!![0] > 0 -> dir.wbGrgb!!.let { floatArrayOf(it[1].toFloat() / it[0], 1f, it[3].toFloat() / it[0]) }
            else -> floatArrayOf(1f, 1f, 1f)
        }
        val preview = preview(data)?.takeIf { it.first + it.second <= data.size }
        return RawMetadata(
            format = RawFormat.RAF,
            make = make, model = model,
            rawWidth = outW, rawHeight = outH,
            cfa = if (xtrans != null) intArrayOf(xtrans[0], xtrans[1], xtrans[6], xtrans[7]) else cfa,
            xtrans = xtrans,
            blackLevels = tb ?: IntArray(4),
            whiteLevel = tw ?: white,
            cameraWb = wb,
            crop = crop,
            orientation = ifd0?.int(0x0112) ?: 1,
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            dateTime = exif.dateTime,
            previewOffset = preview?.first ?: 0, previewLength = preview?.second ?: 0,
            ambientTemperature = exif.ambientTemperature, pressureHpa = exif.pressureHpa, waterDepth = exif.waterDepth,
            gps = exif.gps,
            layout = RafLayout(
                dataOffset = offset, dataBytes = dataBytes, encoding = encoding,
                bigEndian = er?.bigEndian ?: cr.bigEndian, compressed = compression,
            ),
        )
    }

    /** The 16-byte header of compressed data, validated as LibRaw does: (params, raw width, raw height). */
    private fun compressedHeader(data: ByteArray, o: Int): Triple<RafCompression, Int, Int>? {
        if (o + 16 > data.size) return null
        val r = ByteReader(data, bigEndian = true)
        val signature = r.u16(o)
        val lossless = r.u8(o + 2)
        val type = r.u8(o + 3)
        val bits = r.u8(o + 4)
        val height = r.u16(o + 5)
        val rounded = r.u16(o + 7)
        val width = r.u16(o + 9)
        val block = r.u16(o + 11)
        val blocks = r.u8(o + 13)
        val lines = r.u16(o + 14)
        val ok = signature == 0x4953 && lossless <= 1 && height in 6..0x4002 && height % 6 == 0 &&
            width in 0x300..0x4200 && width % 24 == 0 && rounded in block..0x4200 && rounded % block == 0 &&
            rounded - width < block && block == 0x300 && blocks in 1..0x10 && blocks == rounded / block &&
            lines in 1..0xAAB && lines == height / 6 && (bits == 12 || bits == 14 || bits == 16) && (type == 16 || type == 0)
        if (!ok) return null
        return Triple(RafCompression(type == 16, lossless == 1, bits, block, blocks, lines), width, height)
    }
}

object RafDecoder {

    fun decode(data: ByteArray, meta: RawMetadata): RawImage {
        val l = meta.layout as RafLayout
        val w = meta.rawWidth; val h = meta.rawHeight
        val out = ShortArray(w * h)
        val o = l.dataOffset.toInt()
        when (l.encoding) {
            RafLayout.Encoding.UNPACKED -> {
                val n = minOf(w.toLong() * h, (data.size - o) / 2L).toInt()
                if (l.bigEndian) for (i in 0 until n) out[i] = (((data[o + 2 * i].toInt() and 0xFF) shl 8) or (data[o + 2 * i + 1].toInt() and 0xFF)).toShort()
                else for (i in 0 until n) out[i] = ((data[o + 2 * i].toInt() and 0xFF) or ((data[o + 2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
            }
            RafLayout.Encoding.PACKED14 -> packed14(data, o, w, h, out)
            RafLayout.Encoding.COMPRESSED -> Compressed(data, o, meta, l.compressed!!, out).decode()
        }
        return RawImage(w, h, out, meta)
    }

    /**
     * 14-bit samples, MSB first, in 32-bit words stored little-endian: each row is
     * `width * 7 / 4` bytes, seven bytes holding four samples.
     */
    private fun packed14(data: ByteArray, o: Int, w: Int, h: Int, out: ShortArray) {
        val lineLen = w * 7 / 4
        Parallel.rows(h) { r0, r1 ->
            val buf = ByteArray(lineLen)
            for (row in r0 until r1) {
                val start = o.toLong() + row.toLong() * lineLen
                val read = (data.size - start).coerceIn(0, lineLen.toLong()).toInt()
                if (read <= 0) continue
                System.arraycopy(data, start.toInt(), buf, 0, read)
                // Whole 32-bit words are byte-swapped; samples are then read MSB first.
                val swapped = read / 4 * 4
                fun b(i: Int) = (if (i < swapped) buf[(i and 3.inv()) + 3 - (i and 3)] else buf[i]).toInt() and 0xFF
                val dest = row * w
                fun four(sp: Int, dp: Int) {
                    out[dest + dp] = ((b(sp) shl 6) or (b(sp + 1) shr 2)).toShort()
                    out[dest + dp + 1] = (((b(sp + 1) and 0x3) shl 12) or (b(sp + 2) shl 4) or (b(sp + 3) shr 4)).toShort()
                    out[dest + dp + 2] = (((b(sp + 3) and 0xF) shl 10) or (b(sp + 4) shl 2) or (b(sp + 5) shr 6)).toShort()
                    out[dest + dp + 3] = (((b(sp + 5) and 0x3F) shl 8) or b(sp + 6)).toShort()
                }
                var sp = 0; var dp = 0
                // LibRaw's two loops: 28-byte groups when the row is made of them, else 7 bytes at a time.
                if (read % 28 == 0) {
                    while (dp < w - 15 && sp < lineLen - 27 && sp < read - 27) {
                        for (k in 0 until 4) four(sp + 7 * k, dp + 4 * k)
                        sp += 28; dp += 16
                    }
                } else {
                    while (dp < w - 3 && sp < lineLen - 6 && sp < read - 6) {
                        four(sp, dp)
                        sp += 7; dp += 4
                    }
                }
            }
        }
    }

    /** Quantisation table of the compressed format. */
    private class QTable(size: Int) {
        val table = ByteArray(size)
        var qBase = 0
        var maxGrad = 0
        var gradMult = 0
        var totalValues = 0
        var rawBits = 0
    }

    private fun log2ceil(value: Int): Int {
        var v = value
        var r = 0
        if (v-- != 0) do { r++; v = v shr 1 } while (v != 0)
        return r
    }

    private fun setupQlut(t: ByteArray, qp: IntArray) {
        var i = 0
        for (v in -qp[4]..qp[4]) {
            t[i++] = when {
                v <= -qp[3] -> -4
                v <= -qp[2] -> -3
                v <= -qp[1] -> -2
                v < -qp[0] -> -1
                v <= qp[0] -> 0
                v < qp[1] -> 1
                v < qp[2] -> 2
                v < qp[3] -> 3
                else -> 4
            }
        }
    }

    /**
     * Fujifilm's compressed format (lossless and lossy, X-Trans and Bayer), a port of LibRaw's
     * fuji_compressed.cpp. The picture is coded in vertical strips ("blocks") of 768 columns,
     * six rows at a time, with each colour of a row group kept in its own line buffer and
     * predicted from the lines above.
     */
    private class Compressed(
        val data: ByteArray,
        private val offset: Int,
        private val meta: RawMetadata,
        private val c: RafCompression,
        private val out: ShortArray,
    ) {
        val lineWidth = if (c.xtrans) c.blockSize * 2 / 3 else c.blockSize / 2
        val maxValue = (1 shl c.bits) - 1
        private val tableSize = 2 * maxValue + 1

        /** Lossy coding's three fixed tables for flat areas (index 1-3; 0 is the per-line main table). */
        val fixed: Array<QTable?> = arrayOfNulls(4)
        /** Lossless coding's main table, the same for every line. */
        val losslessMain: QTable?

        init {
            if ((c.xtrans && c.blockSize % 3 != 0) || (!c.xtrans && c.blockSize % 2 != 0)) throw RawFormatException("Corrupt Fujifilm raw header")
            if (c.lossless) {
                losslessMain = QTable(tableSize).also { mainTable(it, 0) }
            } else {
                losslessMain = null
                val qp = IntArray(5)
                qp[4] = maxValue
                val specs = arrayOf(intArrayOf(0, 5, 0x12, 0x43, 0x114), intArrayOf(1, 6, 0x15, 0x48, 0x11B), intArrayOf(2, 7, 0x18, 0x4D, 0x122))
                for (k in 0 until 3) {
                    val s = specs[k]
                    val t = QTable(tableSize)
                    t.qBase = s[0]; t.maxGrad = s[1]; t.gradMult = 3
                    t.totalValues = (qp[4] + 2 * s[0]) / (2 * s[0] + 1) + 1
                    t.rawBits = log2ceil(t.totalValues)
                    qp[0] = s[0]
                    qp[1] = if (qp[4] >= s[2]) s[2] else qp[0] + 1
                    qp[2] = if (qp[4] >= s[3]) s[3] else qp[1]
                    qp[3] = if (qp[4] >= s[4]) s[4] else qp[2]
                    setupQlut(t.table, qp)
                    fixed[k + 1] = t
                }
            }
        }

        /** Sets up the main table for [qBase]; returns the format's max_bits. */
        fun mainTable(t: QTable, qBase: Int): Int {
            val qp = IntArray(5)
            val maxVal = maxValue + 1
            qp[0] = qBase
            qp[1] = 3 * qBase + 0x12
            qp[2] = 5 * qBase + 0x43
            qp[3] = 7 * qBase + 0x114
            qp[4] = maxValue
            if (qp[1] >= maxVal || qp[1] < qBase + 1) qp[1] = qBase + 1
            if (qp[2] < qp[1] || qp[2] >= maxVal) qp[2] = qp[1]
            if (qp[3] < qp[2] || qp[3] >= maxVal) qp[3] = qp[2]
            setupQlut(t.table, qp)
            t.qBase = qBase
            t.maxGrad = 0
            t.totalValues = (qp[4] + 2 * qBase) / (2 * qBase + 1) + 1
            t.rawBits = log2ceil(t.totalValues)
            t.gradMult = 9
            return 4 * log2ceil(qp[4] + 1)
        }

        fun decode() {
            val r = ByteReader(data, bigEndian = true)
            val blocks = c.blocks
            if (offset + 4L * blocks > data.size) throw RawFormatException("Raw data is truncated")
            val sizes = LongArray(blocks) { r.u32(offset + 4 * it) }
            var rawOffset = ((4L * blocks) + 0xF) and 0xFL.inv()
            val lineStep = (c.lines + 0xF) and 0xF.inv()
            var qBases: Int = -1
            if (!c.lossless) {
                qBases = (offset + rawOffset).toInt()
                rawOffset += blocks.toLong() * lineStep
                if (qBases + blocks.toLong() * lineStep > data.size) throw RawFormatException("Raw data is truncated")
            }
            rawOffset += offset
            val starts = LongArray(blocks)
            starts[0] = rawOffset
            for (b in 1 until blocks) starts[b] = starts[b - 1] + sizes[b - 1]
            Parallel.rows(blocks) { b0, b1 ->
                for (b in b0 until b1) {
                    Strip(this, b, starts[b], sizes[b], if (qBases >= 0) qBases + b * lineStep else -1).decode()
                }
            }
        }

        fun write(index: Int, v: Int) { out[index] = v.toShort() }
        val rawWidth get() = meta.rawWidth
        val xtrans get() = meta.xtrans
        val bayer get() = meta.cfa
        val comp get() = c
    }

    /** One vertical strip of compressed data, decoded top to bottom. */
    private class Strip(
        private val f: Compressed,
        private val block: Int,
        start: Long,
        size: Long,
        private val qBases: Int,
    ) {
        private val data = f.data
        private var pos: Int
        private var bit = 0
        private val end: Int

        init {
            if (start >= data.size) throw RawFormatException("Raw data is truncated")
            pos = start.toInt()
            end = minOf(start + size, data.size.toLong()).toInt()
        }

        private val lw = f.lineWidth
        private val stride = lw + 2
        private val max = f.maxValue
        private val buf = IntArray(LINES * stride)
        /** Gradient statistics: even[0..2] then odd[0..2]; each 41 main entries then 3 x 5 lossy ones. */
        private val v1 = Array(6) { IntArray(56) }
        private val v2 = Array(6) { IntArray(56) }
        private val tables = arrayOfNulls<QTable>(4)
        private var maxBits = 0

        init {
            if (f.comp.lossless) {
                tables[0] = f.losslessMain
                maxBits = 4 * logCeil(max + 1)
                mainGrads()
            } else {
                tables[0] = QTable(2 * max + 1).also { it.qBase = -1 }
                for (k in 1..3) {
                    tables[k] = f.fixed[k]
                    val maxDiff = maxOf(2, (tables[k]!!.totalValues + 0x20) shr 6)
                    for (j in 0 until 6) for (i in 0 until 5) { v1[j][41 + (k - 1) * 5 + i] = maxDiff; v2[j][41 + (k - 1) * 5 + i] = 1 }
                }
            }
        }

        private fun logCeil(v: Int): Int { var x = v - 1; var r = 0; if (v != 0) do { r++; x = x shr 1 } while (x != 0); return r }

        private fun mainGrads() {
            val maxDiff = maxOf(2, (tables[0]!!.totalValues + 0x20) shr 6)
            for (j in 0 until 6) for (i in 0 until 41) { v1[j][i] = maxDiff; v2[j][i] = 1 }
        }

        private fun byteAt(p: Int): Int = if (p < end) data[p].toInt() and 0xFF else 0

        private fun advance() {
            pos++
            // One zero byte may be read past the strip's data, as in LibRaw; more means corrupt data.
            if (pos > end) throw RawFormatException("Compressed raw data is corrupt or truncated")
        }

        private fun zeroBits(): Int {
            var count = 0
            while (true) {
                val b = (byteAt(pos) shl bit) and 0xFF
                if (b == 0) {
                    count += 8 - bit
                    bit = 0
                    advance()
                    continue
                }
                val lz = Integer.numberOfLeadingZeros(b) - 24
                count += lz
                bit += lz + 1
                if (bit == 8) { bit = 0; advance() }
                return count
            }
        }

        private fun readCode(bits: Int): Int {
            var d = 0
            var left = bits
            while (left > 0) {
                val avail = 8 - bit
                val take = if (avail < left) avail else left
                d = (d shl take) or ((byteAt(pos) shr (avail - take)) and ((1 shl take) - 1))
                bit += take
                left -= take
                if (bit == 8) { bit = 0; advance() }
            }
            return d
        }

        private fun bitDiff(a: Int, b: Int): Int {
            var dec = 0
            if (b < a) while (dec <= 14 && (b shl ++dec) < a) { /* count */ }
            return dec
        }

        /** Codes one sample given its prediction; the shared tail of the even and odd samplers. */
        private fun finish(cur: Int, interp: Int, grad: Int, q: QTable, g: Int, gOff: Int) {
            val gradient = (if (grad < 0) -grad else grad) + gOff
            val sample = zeroBits()
            var code: Int
            val g1 = v1[g]; val g2 = v2[g]
            if (sample < maxBits - q.rawBits - 1) {
                val decBits = bitDiff(g1[gradient], g2[gradient])
                code = readCode(decBits)
                code += sample shl decBits
            } else {
                code = readCode(q.rawBits)
                code++
            }
            code = if (code and 1 != 0) -1 - code / 2 else code / 2
            g1[gradient] += if (code < 0) -code else code
            if (g2[gradient] == MIN_VALUE) {
                g1[gradient] = g1[gradient] shr 1
                g2[gradient] = g2[gradient] shr 1
            }
            g2[gradient]++
            val step = 2 * q.qBase + 1
            var v = if (grad < 0) interp - code * step else interp + code * step
            if (v < -q.qBase) v += q.totalValues * step
            else if (v > q.qBase + max) v -= q.totalValues * step
            buf[cur] = if (v >= 0) minOf(v, max) else 0
        }

        private fun sampleEven(line: Int, pos: Int, g: Int) {
            val cur = line * stride + 1 + pos
            val rb = buf[cur - 2 - lw]
            val rc = buf[cur - 3 - lw]
            val rd = buf[cur - 1 - lw]
            val rf = buf[cur - 4 - 2 * lw]
            val dRcRb = abs(rc - rb); val dRfRb = abs(rf - rb); val dRdRb = abs(rd - rb)
            var q = tables[0]!!
            var gOff = 0
            var i = 1
            while (i < 4 && tables[0]!!.qBase >= i) {
                if (dRfRb + dRcRb <= tables[i]!!.maxGrad) { q = tables[i]!!; gOff = 41 + (i - 1) * 5; break }
                i++
            }
            val grad = q.gradMult * q.table[max + rb - rf] + q.table[max + rc - rb]
            val interp = when {
                dRcRb > dRfRb && dRcRb > dRdRb -> rf + rd + 2 * rb
                dRdRb > dRcRb && dRdRb > dRfRb -> rf + rc + 2 * rb
                else -> rd + rc + 2 * rb
            }
            finish(cur, interp shr 2, grad, q, g, gOff)
        }

        private fun sampleOdd(line: Int, pos: Int, g: Int) {
            val cur = line * stride + 1 + pos
            val ra = buf[cur - 1]
            val rb = buf[cur - 2 - lw]
            val rc = buf[cur - 3 - lw]
            val rd = buf[cur - 1 - lw]
            val rg = buf[cur + 1]
            val dRcRa = abs(rc - ra); val dRbRc = abs(rb - rc)
            var q = tables[0]!!
            var gOff = 0
            var i = 1
            while (i < 4 && tables[0]!!.qBase >= i) {
                if (dRbRc + dRcRa <= tables[i]!!.maxGrad) { q = tables[i]!!; gOff = 41 + (i - 1) * 5; break }
                i++
            }
            val grad = q.gradMult * q.table[max + rb - rc] + q.table[max + rc - ra]
            val interp = if ((rb > rc && rb > rd) || (rb < rc && rb < rd)) (rg + ra + 2 * rb) shr 2 else (ra + rg) shr 1
            finish(cur, interp, grad, q, g + 3, gOff)
        }

        private fun interpEven(line: Int, pos: Int) {
            val cur = line * stride + 1 + pos
            val rb = buf[cur - 2 - lw]
            val rc = buf[cur - 3 - lw]
            val rd = buf[cur - 1 - lw]
            val rf = buf[cur - 4 - 2 * lw]
            val dRcRb = abs(rc - rb); val dRfRb = abs(rf - rb); val dRdRb = abs(rd - rb)
            buf[cur] = when {
                dRcRb > dRfRb && dRcRb > dRdRb -> (rf + rd + 2 * rb) shr 2
                dRdRb > dRcRb && dRdRb > dRfRb -> (rf + rc + 2 * rb) shr 2
                else -> (rd + rc + 2 * rb) shr 2
            }
        }

        private fun abs(x: Int) = if (x < 0) -x else x

        /** Even positions of an X-Trans line coded as samples or predicted only. */
        private fun even(line: Int, pos: Int, g: Int, mode: Int) {
            val interp = when (mode) {
                ALWAYS -> true
                AT_0 -> pos and 3 == 0
                AT_2 -> pos and 3 == 2
                else -> false
            }
            if (interp) interpEven(line, pos) else sampleEven(line, pos, g)
        }

        /** One pass over two lines, even positions running ahead of odd ones. */
        private fun pass(a: Int, aMode: Int, b: Int, bMode: Int, g: Int) {
            var e = 0
            var o = 1
            while (e < lw || o < lw) {
                if (e < lw) {
                    even(a, e, g, aMode)
                    even(b, e, g, bMode)
                    e += 2
                }
                if (e > 8) {
                    sampleOdd(a, o, g)
                    sampleOdd(b, o, g)
                    o += 2
                }
            }
        }

        private fun extend(from: Int, to: Int) {
            for (i in from..to) {
                buf[i * stride] = buf[(i - 1) * stride + 1]
                buf[i * stride + lw + 1] = buf[(i - 1) * stride + lw]
            }
        }

        private fun extendRed() = extend(R2, R4)
        private fun extendGreen() = extend(G2, G7)
        private fun extendBlue() = extend(B2, B4)

        private fun decodeLines() {
            val x = f.comp.xtrans
            pass(R2, if (x) ALWAYS else NEVER, G2, NEVER, 0)
            extendRed(); extendGreen()
            pass(G3, NEVER, B2, if (x) ALWAYS else NEVER, 1)
            extendGreen(); extendBlue()
            pass(R3, if (x) AT_0 else NEVER, G4, if (x) ALWAYS else NEVER, 2)
            extendRed(); extendGreen()
            pass(G5, NEVER, B3, if (x) AT_2 else NEVER, 0)
            extendGreen(); extendBlue()
            pass(R4, if (x) AT_2 else NEVER, G6, NEVER, 1)
            extendRed(); extendGreen()
            pass(G7, if (x) ALWAYS else NEVER, B4, if (x) AT_0 else NEVER, 2)
            extendGreen(); extendBlue()
        }

        private fun copyLine(from: Int, to: Int) = System.arraycopy(buf, from * stride, buf, to * stride, stride)

        fun decode() {
            val blockWidth = if (block + 1 == f.comp.blocks) f.rawWidth - f.comp.blockSize * block else f.comp.blockSize
            val rawW = f.rawWidth
            val xt = f.xtrans
            val cfa = f.bayer
            for (line in 0 until f.comp.lines) {
                if (!f.comp.lossless) {
                    val qb = data[qBases + line].toInt() and 0xFF
                    if (line == 0 || qb != tables[0]!!.qBase) {
                        maxBits = f.mainTable(tables[0]!!, qb)
                        mainGrads()
                    }
                }
                decodeLines()
                copyLine(R3, R0); copyLine(R4, R1)
                copyLine(G6, G0); copyLine(G7, G1)
                copyLine(B3, B0); copyLine(B4, B1)

                // Line buffers into the picture.
                var o = f.comp.blockSize * block + 6 * rawW * line
                for (row in 0 until 6) {
                    for (pc in 0 until blockWidth) {
                        val color: Int
                        val index: Int
                        if (xt != null) {
                            color = xt[row * 6 + pc % 6]
                            val m3 = pc % 3
                            index = (((pc * 2 / 3) and 0x7FFFFFFE) or (m3 and 1)) + (m3 shr 1)
                        } else {
                            color = cfa[((row and 1) shl 1) or (pc and 1)]
                            index = pc shr 1
                        }
                        val line = when (color) {
                            0 -> R2 + (row shr 1)
                            2 -> B2 + (row shr 1)
                            else -> G2 + row
                        }
                        f.write(o + pc, buf[line * stride + 1 + index])
                    }
                    o += rawW
                }

                for ((first, n) in ZERO) {
                    java.util.Arrays.fill(buf, first * stride, (first + n) * stride, 0)
                    buf[first * stride] = buf[(first - 1) * stride + 1]
                    buf[first * stride + lw + 1] = buf[(first - 1) * stride + lw]
                }
            }
        }

        companion object {
            const val R0 = 0; const val R1 = 1; const val R2 = 2; const val R3 = 3; const val R4 = 4
            const val G0 = 5; const val G1 = 6; const val G2 = 7; const val G3 = 8; const val G4 = 9
            const val G5 = 10; const val G6 = 11; const val G7 = 12
            const val B0 = 13; const val B1 = 14; const val B2 = 15; const val B3 = 16; const val B4 = 17
            const val LINES = 18
            const val MIN_VALUE = 0x40
            const val NEVER = 0; const val ALWAYS = 1; const val AT_0 = 2; const val AT_2 = 3
            val ZERO = listOf(R2 to 3, G2 to 6, B2 to 3)
        }
    }
}
