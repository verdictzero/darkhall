/*
 * Copyright 2026 verdictzero
 * Portions Copyright 2008-2025 LibRaw LLC (info@libraw.org)
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

data class NikonLayout(
    override val dataOffset: Long,
    val dataBytes: Long,
    val kind: Kind,
    val bitsPerSample: Int,
    /** Offset of the maker note's NEF linearization/predictor block (tag 0x96 or 0x8C). */
    val metaOffset: Int,
    /** Byte order of the maker note, which the predictor block uses. */
    val metaBigEndian: Boolean,
    /** Byte order of 16-bit sample words (unpacked data). */
    val wordsBigEndian: Boolean,
    /** Right shift applied to unpacked 16-bit words. */
    val shift: Int = 0,
    /** Row stride in bytes for padded packed data. */
    val rowBytes: Int = 0,
) : RawLayout {
    enum class Kind { COMPRESSED, UNPACKED, PACKED12, PADDED12, PACKED14 }
}

/** Nikon NEF / NRW. */
object NikonParser {

    fun parse(data: ByteArray): RawMetadata {
        val t = TiffRaw.read(data)
        val r = t.reader
        val ifd0 = t.ifd0
        val raw = TiffRaw.largestRawIfd(t.all) ?: throw RawFormatException("NEF without raw data")
        val w = raw.int(0x0100)!!
        val h = raw.int(0x0101)!!
        val bps = raw.int(0x0102) ?: 12
        val comp = raw.int(0x0103) ?: 1
        val offset = raw.long(0x0111) ?: throw RawFormatException("NEF without raw data")
        val bytes = raw.long(0x0117) ?: (data.size - offset)
        if (offset !in 8 until data.size) throw RawFormatException("Raw data offset outside the file")
        val model = ifd0.string(0x0110)
        val exif = ExifInfo.read(r, ifd0)
        val mn = exif.makerNote?.let { makerNote(r, it.offset) }
        val px = w.toLong() * h

        val z14Size = ((w * 7 / 4 + 15) / 16) * 16L * h
        val padded12 = ((w * 3L / 2 + 15) / 16) * 16
        val kind: NikonLayout.Kind
        var wordsBigEndian = r.bigEndian
        var shift = 0
        val zSeries = model.uppercase().let { it.startsWith("NIKON Z") || it.startsWith("Z ") || it == "NIKON D6" }
        when {
            // Z-series and D6 uncompressed 14-bit: 4 samples in 7 bytes, rows padded to 16 bytes,
            // whatever the compression tag says.
            zSeries && bps == 14 && z14Size == bytes -> kind = NikonLayout.Kind.PACKED14
            comp == 34713 && (w + 9L) / 10 * 16 * h == bytes ->
                throw RawFormatException("Unsupported NEF variant (Coolpix packed)")
            comp == 34713 && px * 3 == bytes * 2 -> kind = NikonLayout.Kind.PACKED12
            comp == 34713 && px * 3 == bytes -> throw RawFormatException("Nikon small/medium RAW (YUV) files are not supported")
            comp == 34713 && px * 2 == bytes -> { kind = NikonLayout.Kind.UNPACKED; wordsBigEndian = true; shift = 4 }
            comp == 34713 && padded12 * h == bytes -> kind = NikonLayout.Kind.PADDED12
            comp == 34713 -> {
                if (mn?.metaOffset == null) throw RawFormatException("Compressed NEF without its decoding table")
                kind = NikonLayout.Kind.COMPRESSED
            }
            comp == 1 && px * 2 == bytes -> kind = NikonLayout.Kind.UNPACKED
            comp == 1 && bps == 12 && px * 3 == bytes * 2 -> kind = NikonLayout.Kind.PACKED12
            else -> throw RawFormatException(
                if (comp == 34892 || comp == 0x8799) "Nikon High Efficiency RAW is not supported" else "Unsupported NEF variant (compression $comp)",
            )
        }

        val cfa = TiffRaw.cfa(raw, exif)
        // The black level tag is in 14-bit units; LibRaw scales it for 12-bit files.
        var black = mn?.blackRggb?.let { b -> TiffRaw.blackFromRggb(if (bps == 12) IntArray(4) { b[it] / 4 } else b, cfa) }
        var white: Int? = (1 shl bps) - 1
        val (tb, tw) = TiffRaw.tableLevels(ifd0.string(0x010F), model, black, null)
        if (tb != null && black == null) black = if (bps == 12) IntArray(4) { tb[it] / 4 } else tb
        if (tw != null) white = tw
        val crop = mn?.crop?.takeIf { TiffRaw.valid(it, w, h) } ?: Crop(0, 0, w, h)
        val preview = Previews.largestJpeg(r, t.all)

        return RawMetadata(
            format = RawFormat.NEF,
            make = ifd0.string(0x010F), model = model,
            rawWidth = w, rawHeight = h,
            cfa = cfa,
            blackLevels = black ?: IntArray(4),
            whiteLevel = white!!,
            cameraWb = mn?.wb ?: floatArrayOf(1f, 1f, 1f),
            crop = crop,
            orientation = ifd0.int(0x0112) ?: 1,
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            dateTime = exif.dateTime,
            previewOffset = preview?.offset ?: 0, previewLength = preview?.length ?: 0,
            gps = exif.gps,
            layout = NikonLayout(
                dataOffset = offset, dataBytes = bytes, kind = kind, bitsPerSample = bps,
                metaOffset = mn?.metaOffset ?: 0, metaBigEndian = mn?.bigEndian ?: r.bigEndian,
                wordsBigEndian = wordsBigEndian, shift = shift,
                rowBytes = if (kind == NikonLayout.Kind.PADDED12) padded12.toInt() else 0,
            ),
        )
    }

    private class MakerNote(val wb: FloatArray?, val blackRggb: IntArray?, val crop: Crop?, val metaOffset: Int?, val bigEndian: Boolean)

    /** "Nikon\0" + version, then a TIFF header whose offsets are relative to it. */
    private fun makerNote(file: ByteReader, mnOff: Int): MakerNote? {
        if (!file.startsWith(mnOff, "Nikon\u0000")) return null
        val base = mnOff + 10
        val r = file.withOrder(file.startsWith(base, "MM"))
        if (r.u16(base + 2) != 42) return null
        val ifd = Tiff.readIfdOrNull(r, base + r.u32(base + 4).toInt(), base) ?: return null
        val wb = ifd[0x000C]?.takeIf { it.type == 5 && it.count >= 4 }?.doubles(4)?.let { v ->
            // R, B, G, G
            if (v[0] > 0 && v[1] > 0 && v[2] > 0) floatArrayOf((v[0] / v[2]).toFloat(), 1f, (v[1] / v[2]).toFloat()) else null
        }
        val black = ifd[0x003D]?.ints(4)
        val crop = ifd[0x0045]?.ints(4)?.let { Crop(it[0], it[1], it[2], it[3]) }
        val meta = (ifd[0x0096] ?: ifd[0x008C])?.offset
        return MakerNote(wb, black, crop, meta, r.bigEndian)
    }
}

object NikonDecoder {

    /** dcraw's nikon_tree: 12-bit lossy, lossy after split, 12-bit lossless, 14-bit lossy, after split, 14-bit lossless. */
    private val TREES = arrayOf(
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 5, 4, 3, 6, 2, 7, 1, 0, 8, 9, 11, 10, 12),
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0x39, 0x5a, 0x38, 0x27, 0x16, 5, 4, 3, 2, 1, 0, 11, 12, 12),
        intArrayOf(0, 1, 4, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11, 12),
        intArrayOf(0, 1, 4, 3, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 5, 6, 4, 7, 8, 3, 9, 2, 1, 0, 10, 11, 12, 13, 14),
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 8, 0x5c, 0x4b, 0x3a, 0x29, 7, 6, 5, 4, 3, 2, 1, 0, 13, 14),
        intArrayOf(0, 1, 4, 2, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 7, 6, 8, 5, 9, 4, 10, 3, 11, 12, 2, 0, 1, 13, 14),
    )

    fun decode(data: ByteArray, meta: RawMetadata): RawImage {
        val l = meta.layout as NikonLayout
        val w = meta.rawWidth
        val h = meta.rawHeight
        val out = ShortArray(w * h)
        val start = l.dataOffset.toInt()
        fun u8(o: Int) = if (o < data.size) data[o].toInt() and 0xFF else 0
        when (l.kind) {
            NikonLayout.Kind.COMPRESSED -> compressed(data, l, w, h, out)
            NikonLayout.Kind.UNPACKED -> {
                var p = start
                for (i in out.indices) {
                    val a = u8(p); val b = u8(p + 1)
                    out[i] = ((if (l.wordsBigEndian) (a shl 8) or b else (b shl 8) or a) ushr l.shift).toShort()
                    p += 2
                }
            }
            NikonLayout.Kind.PACKED12 -> {
                val br = BitReader(data, start)
                for (i in out.indices) out[i] = br.get(12).toShort()
            }
            NikonLayout.Kind.PADDED12 -> for (row in 0 until h) {
                val p = start + row * l.rowBytes
                for (ic in 0 until w / 2) {
                    val q = p + ic * 3
                    out[row * w + 2 * ic] = (((u8(q + 1) and 0xF) shl 8) or u8(q)).toShort()
                    out[row * w + 2 * ic + 1] = ((u8(q + 2) shl 4) or (u8(q + 1) ushr 4)).toShort()
                }
            }
            NikonLayout.Kind.PACKED14 -> {
                val lineLen = ((w * 7 / 4 + 15) / 16) * 16
                for (row in 0 until h) {
                    var sp = start + row * lineLen
                    var dp = row * w
                    val end = row * w + w - 3
                    while (dp < end) {
                        out[dp] = (((u8(sp + 1) and 0x3F) shl 8) or u8(sp)).toShort()
                        out[dp + 1] = (((u8(sp + 3) and 0xF) shl 10) or (u8(sp + 2) shl 2) or (u8(sp + 1) ushr 6)).toShort()
                        out[dp + 2] = (((u8(sp + 5) and 0x3) shl 12) or (u8(sp + 4) shl 4) or (u8(sp + 3) ushr 4)).toShort()
                        out[dp + 3] = ((u8(sp + 6) shl 6) or (u8(sp + 5) ushr 2)).toShort()
                        sp += 7; dp += 4
                    }
                }
            }
        }
        return RawImage(w, h, out, meta)
    }

    /** dcraw's nikon_load_raw with its linearization curve (nikon_read_curve). */
    private fun compressed(data: ByteArray, l: NikonLayout, w: Int, h: Int, out: ShortArray) {
        val m = ByteReader(data, l.metaBigEndian)
        var p = l.metaOffset
        val ver0 = m.u8(p); val ver1 = m.u8(p + 1)
        p += 2
        if (ver0 == 0x49 || ver1 == 0x58) p += 2110
        val vpred = arrayOf(intArrayOf(m.u16(p), m.u16(p + 2)), intArrayOf(m.u16(p + 4), m.u16(p + 6)))
        p += 8
        val bps = l.bitsPerSample
        val curve = IntArray(0x10000) { it }
        var max = (1 shl bps) and 0x7FFF
        var step = max
        val csize = m.u16(p)
        p += 2
        if (csize > 1) step = max / (csize - 1)
        if (ver0 == 0x44 && (ver1 == 0x20 || (ver1 == 0x40 && step > 3)) && step > 0) {
            if (ver1 == 0x40) { step /= 4; max /= 4 }
            for (i in 0 until csize) curve[i * step] = m.u16(p + 2 * i)
            for (i in 0 until max) {
                val k = i % step
                curve[i] = (curve[i - k] * (step - k) + curve[i - k + step] * k) / step
            }
        } else if (ver0 != 0x46 && csize <= 0x4001) {
            for (i in 0 until csize) curve[i] = m.u16(p + 2 * i)
        }

        var tree = if (ver0 == 0x46) 2 else 0
        if (bps == 14) tree += 3
        var split = 0
        if (ver0 == 0x44 && (ver1 == 0x20 || ver1 == 0x40)) split = m.u16(l.metaOffset + 562)
        var huff = HuffTable.fromSpec(TREES[tree])
        val br = BitReader(data, l.dataOffset.toInt())
        val hpred = IntArray(2)
        for (row in 0 until h) {
            if (split != 0 && row == split) huff = HuffTable.fromSpec(TREES[tree + 1])
            val base = row * w
            for (col in 0 until w) {
                val i = huff.decode(br)
                val len = i and 15
                val shl = i ushr 4
                var diff = (((br.get(len - shl) shl 1) + 1) shl shl) shr 1
                if (len > 0 && diff and (1 shl (len - 1)) == 0) diff -= (1 shl len) - (if (shl == 0) 1 else 0)
                if (col < 2) {
                    vpred[row and 1][col] = (vpred[row and 1][col] + diff) and 0xFFFF
                    hpred[col] = vpred[row and 1][col]
                } else {
                    hpred[col and 1] = (hpred[col and 1] + diff) and 0xFFFF
                }
                val v = hpred[col and 1].toShort().toInt().coerceIn(0, 0x3FFF)
                out[base + col] = curve[v].toShort()
            }
        }
    }
}
