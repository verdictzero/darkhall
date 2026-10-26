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

data class PefLayout(
    override val dataOffset: Long,
    val dataBytes: Long,
    val bitsPerSample: Int,
    /** Pentax Huffman coding (compression 65535); otherwise uncompressed. */
    val compressed: Boolean,
    /** Offset of the maker note's Huffman table (tag 0x220). */
    val huffOffset: Int,
    val huffBigEndian: Boolean,
    /** Uncompressed data: 16-bit words in this byte order, or (when null) MSB-first packed samples. */
    val wordsBigEndian: Boolean?,
) : RawLayout

/** Pentax PEF (Ricoh-era Pentax bodies included; Pentax DNGs go through the DNG reader). */
object PefParser {

    fun parse(data: ByteArray): RawMetadata {
        val t = TiffRaw.read(data)
        val r = t.reader
        val ifd0 = t.ifd0
        val raw = TiffRaw.largestRawIfd(t.all) ?: throw RawFormatException("PEF without raw data")
        val w = raw.int(0x0100)!!
        val h = raw.int(0x0101)!!
        val bps = raw.int(0x0102) ?: 12
        val comp = raw.int(0x0103) ?: 1
        val offset = raw.long(0x0111) ?: throw RawFormatException("PEF without raw data")
        val bytes = raw.long(0x0117) ?: (data.size - offset)
        if (offset !in 8 until data.size) throw RawFormatException("Raw data offset outside the file")
        val exif = ExifInfo.read(r, ifd0)
        val mn = exif.makerNote?.let { makerNote(r, it.offset) }

        val px = w.toLong() * h
        val words: Boolean? = when {
            comp == 65535 -> {
                if (mn?.huffOffset == null) throw RawFormatException("Compressed PEF without its Huffman table")
                null
            }
            px * 2 == bytes -> r.bigEndian
            px * bps == bytes * 8 -> null
            else -> throw RawFormatException("Unsupported PEF variant (compression $comp)")
        }
        val cfa = TiffRaw.cfa(raw, exif)
        val (tb, tw) = TiffRaw.tableLevels(ifd0.string(0x010F), ifd0.string(0x0110), mn?.blackRggb?.let { TiffRaw.blackFromRggb(it, cfa) }, null)
        val crop = mn?.crop?.takeIf { TiffRaw.valid(it, w, h) } ?: Crop(0, 0, w, h)
        val preview = Previews.largestJpeg(r, t.all)
        return RawMetadata(
            format = RawFormat.PEF,
            make = ifd0.string(0x010F), model = ifd0.string(0x0110),
            rawWidth = w, rawHeight = h,
            cfa = cfa,
            blackLevels = tb ?: IntArray(4),
            whiteLevel = tw ?: ((1 shl bps) - 1),
            cameraWb = mn?.wbRggb?.let { TiffRaw.wbFromRggb(it) } ?: floatArrayOf(1f, 1f, 1f),
            crop = crop,
            orientation = ifd0.int(0x0112) ?: 1,
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            dateTime = exif.dateTime,
            previewOffset = preview?.offset ?: 0, previewLength = preview?.length ?: 0,
            gps = exif.gps,
            layout = PefLayout(
                dataOffset = offset, dataBytes = bytes, bitsPerSample = bps, compressed = comp == 65535,
                huffOffset = mn?.huffOffset ?: 0, huffBigEndian = mn?.bigEndian ?: r.bigEndian,
                wordsBigEndian = if (comp == 65535) null else words,
            ),
        )
    }

    private class MakerNote(val blackRggb: IntArray?, val wbRggb: DoubleArray?, val crop: Crop?, val huffOffset: Int?, val bigEndian: Boolean)

    /** "PENTAX \0" + byte order (offsets relative to the note) or the older "AOC\0" (absolute offsets). */
    private fun makerNote(file: ByteReader, mnOff: Int): MakerNote? {
        val (r, ifd) = when {
            file.startsWith(mnOff, "PENTAX ") -> {
                val r = file.withOrder(file.startsWith(mnOff + 8, "MM"))
                r to Tiff.readIfdOrNull(r, mnOff + 10, mnOff)
            }
            file.startsWith(mnOff, "AOC\u0000") -> {
                val r = if (file.startsWith(mnOff + 4, "MM")) file.withOrder(true) else if (file.startsWith(mnOff + 4, "II")) file.withOrder(false) else file
                r to Tiff.readIfdOrNull(r, mnOff + 6, 0)
            }
            else -> return null
        }
        ifd ?: return null
        val origin = ifd[0x0038]?.ints(2)
        val size = ifd[0x0039]?.ints(2)
        val crop = if (origin != null && size != null) Crop(origin[0], origin[1], size[0], size[1]) else null
        return MakerNote(
            blackRggb = ifd[0x0200]?.ints(4),
            wbRggb = ifd[0x0201]?.ints(4)?.let { v -> DoubleArray(4) { v[it].toDouble() } },
            crop = crop,
            huffOffset = ifd[0x0220]?.offset,
            bigEndian = r.bigEndian,
        )
    }
}

object PefDecoder {

    fun decode(data: ByteArray, meta: RawMetadata): RawImage {
        val l = meta.layout as PefLayout
        val w = meta.rawWidth
        val h = meta.rawHeight
        val out = ShortArray(w * h)
        val start = l.dataOffset.toInt()
        when {
            l.compressed -> compressed(data, l, w, h, out)
            l.wordsBigEndian != null -> {
                if (start + w.toLong() * h * 2 > data.size) throw RawFormatException("File is truncated")
                var p = start
                for (i in out.indices) {
                    val a = data[p].toInt() and 0xFF; val b = data[p + 1].toInt() and 0xFF
                    out[i] = (if (l.wordsBigEndian) (a shl 8) or b else (b shl 8) or a).toShort()
                    p += 2
                }
            }
            else -> {
                val br = BitReader(data, start)
                for (i in out.indices) out[i] = br.get(l.bitsPerSample).toShort()
            }
        }
        return RawImage(w, h, out, meta)
    }

    /** dcraw's pentax_load_raw: a 12-bit lookup Huffman table from the maker note, two predictors per row parity. */
    private fun compressed(data: ByteArray, l: PefLayout, w: Int, h: Int, out: ShortArray) {
        val m = ByteReader(data, l.huffBigEndian)
        var p = l.huffOffset
        val dep = (m.u16(p) + 12) and 15
        p += 2 + 12
        val codes = IntArray(dep) { m.u16(p + 2 * it) }
        p += 2 * dep
        val lens = IntArray(dep) { m.u8(p + it) }
        val huff = IntArray(4097)
        for (c in 0 until dep) {
            var i = codes[c]
            val last = (codes[c] + (4096 shr lens[c]) - 1) and 4095
            while (i <= last) {
                i++
                huff[i] = (lens[c] shl 8) or c
            }
        }
        val br = BitReader(data, l.dataOffset.toInt())
        val vpred = arrayOf(IntArray(2), IntArray(2))
        val hpred = IntArray(2)
        for (row in 0 until h) {
            val base = row * w
            for (col in 0 until w) {
                val e = huff[br.peek(12) + 1]
                br.skip(e ushr 8)
                val len = e and 0xFF
                var diff = if (len == 16) -32768 else if (len == 0) 0 else br.get(len)
                if (len in 1..15 && diff and (1 shl (len - 1)) == 0) diff -= (1 shl len) - 1
                if (col < 2) {
                    vpred[row and 1][col] = (vpred[row and 1][col] + diff) and 0xFFFF
                    hpred[col] = vpred[row and 1][col]
                } else {
                    hpred[col and 1] = (hpred[col and 1] + diff) and 0xFFFF
                }
                out[base + col] = hpred[col and 1].toShort()
            }
        }
    }
}
