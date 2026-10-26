/*
 * Copyright 2026 verdictzero
 * Portions Copyright 2008-2025 LibRaw LLC (info@libraw.org)
 * Portions Copyright 2018-2019 Alexey Danilchenko (Canon CR3/CRX decoder)
 * Portions Copyright 2019 Alex Tutubalin, LibRaw LLC
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

/** The CMP1 header of a CR3 raw track: how its CRX stream is laid out. */
data class CrxHeader(
    val version: Int,
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val nBits: Int,
    val nPlanes: Int,
    val cfaLayout: Int,
    val encType: Int,
    val levels: Int,
    val mdatHdrSize: Int,
    val medianBits: Int,
)

data class Cr3Layout(
    override val dataOffset: Long,
    val dataSize: Long,
    val header: CrxHeader,
) : RawLayout

/**
 * Canon CR3 (EOS bodies from 2018 on): an ISO media file. Shooting data sits in a Canon box
 * inside `moov` as four little TIFFs (IFD0, EXIF, the maker note, GPS); each picture is a
 * track, the raw one coded with Canon's CRX codec (lossless, or wavelet-based "cRAW").
 */
object Cr3Parser {

    private const val CANON_UUID = "85c0b687820f11e08111f4ce462b6a48"
    private const val PREVIEW_UUID = "eaf42b5e1c984b88b9fbb7dc406e4d16"

    fun isCr3(data: ByteArray): Boolean = data.size >= 12 && String(data, 4, 8, Charsets.ISO_8859_1) == "ftypcrx "

    private class Track {
        var handler = ""
        var format = ""
        var cmp1: ByteArray? = null
        var jpeg = false
        var sampleSize = 0L
        var firstSize = 0L
        var firstOffset = 0L
    }

    private class Boxes {
        val cmt = arrayOfNulls<ByteArray>(4)
        val tracks = ArrayList<Track>()
        var previewOffset = 0L
        var previewLength = 0
    }

    private fun hex(data: ByteArray, o: Int, n: Int) = (0 until n).joinToString("") { "%02x".format(data[o + it]) }

    /** Walks the boxes in [start, end); [path] names the enclosing ones. */
    private fun walk(data: ByteArray, r: ByteReader, start: Long, end: Long, path: String, b: Boxes, depth: Int) {
        if (depth > 12) throw RawFormatException("Corrupt CR3 structure")
        var o = start
        while (o + 8 <= end && o + 8 <= data.size) {
            var size = r.u32(o.toInt())
            val name = r.ascii(o.toInt() + 4, 4)
            var content = o + 8
            if (size == 1L) {
                if (o + 16 > data.size) break
                size = (r.u32(o.toInt() + 8) and 0x7FFFFFFF shl 32) or r.u32(o.toInt() + 12)
                content = o + 16
            } else if (size == 0L) {
                size = end - o
            }
            if (size < content - o || o + size > end) break
            val boxEnd = o + size
            val here = path + name
            val track = b.tracks.lastOrNull()
            when {
                name == "moov" || name == "trak" || name == "mdia" || name == "minf" || name == "stbl" -> {
                    if (name == "trak") b.tracks += Track()
                    walk(data, r, content, boxEnd, here, b, depth + 1)
                }
                here == "moovuuid" && boxEnd - content >= 16 && hex(data, content.toInt(), 16) == CANON_UUID ->
                    walk(data, r, content + 16, boxEnd, here, b, depth + 1)
                path == "moovuuid" && name.startsWith("CMT") && name[3] in '1'..'4' ->
                    b.cmt[name[3] - '1'] = data.copyOfRange(content.toInt(), minOf(boxEnd, data.size.toLong()).toInt())
                here == "uuid" && boxEnd - content >= 48 && hex(data, content.toInt(), 16) == PREVIEW_UUID &&
                    r.ascii(content.toInt() + 16 + 12, 4) == "PRVW" -> {
                    b.previewOffset = content + 48
                    b.previewLength = (boxEnd - b.previewOffset).toInt()
                }
                track != null && here.endsWith("mdiahdlr") && boxEnd - content >= 12 -> track.handler = r.ascii(content.toInt() + 8, 4)
                track != null && here.endsWith("stbl" + "stsd") && boxEnd - content >= 16 -> {
                    track.format = r.ascii(content.toInt() + 12, 4)
                    if (track.format == "CRAW") {
                        // The CRAW sample entry: 82 bytes of fields, then CMP1, CDI1, JPEG...
                        val entry = content + 8
                        val entrySize = r.u32(entry.toInt())
                        if (entry + entrySize <= boxEnd) walk(data, r, entry + 8 + 82, entry + entrySize, here + "CRAW", b, depth + 1)
                    }
                }
                track != null && here.endsWith("CRAWCMP1") && boxEnd - content >= 40 ->
                    track.cmp1 = data.copyOfRange(content.toInt(), minOf(boxEnd, content + 85).toInt())
                track != null && here.endsWith("CRAWJPEG") -> track.jpeg = true
                track != null && here.endsWith("stblstsz") && boxEnd - content >= 12 -> {
                    track.sampleSize = r.u32(content.toInt() + 4)
                    val count = r.u32(content.toInt() + 8)
                    track.firstSize = if (track.sampleSize > 0) track.sampleSize
                    else if (count > 0 && content + 16 <= boxEnd) r.u32(content.toInt() + 12) else 0L
                }
                track != null && here.endsWith("stblco64") && boxEnd - content >= 16 ->
                    track.firstOffset = (r.u32(content.toInt() + 8) shl 32) or r.u32(content.toInt() + 12)
                track != null && here.endsWith("stblstco") && boxEnd - content >= 12 && track.firstOffset == 0L ->
                    track.firstOffset = r.u32(content.toInt() + 8)
            }
            o = boxEnd
        }
    }

    /** The CMP1 header, validated as LibRaw does; null if it doesn't describe a usable stream. */
    private fun header(c: ByteArray): CrxHeader? {
        if (c.size < 32) return null
        val r = ByteReader(c, bigEndian = true)
        val version = r.u16(4)
        val w = r.s32(8); val h = r.s32(12); val tw = r.s32(16); val th = r.s32(20)
        val nBits = r.u8(24)
        val nPlanes = r.u8(25) shr 4
        val cfa = r.u8(25) and 0xF
        val enc = r.u8(26) shr 4
        val levels = r.u8(26) and 0xF
        val tileCols = r.u8(27) shr 7
        val tileRows = (r.u8(27) shr 6) and 1
        val mdatHdrSize = r.s32(28)
        var median = nBits
        val ext = c.size > 32 && r.u8(32) shr 7 != 0
        if (ext && c.size > 56 && nPlanes == 4 && (r.u8(56) shr 6 and 1) != 0 && c.size > 84) median = r.u8(84)
        if ((version != 0x100 && version != 0x200) || mdatHdrSize <= 0) return null
        if (enc == 1) { if (nBits > 15) return null } else { if (enc != 0 && enc != 3) return null; if (nBits > 14) return null }
        if (nPlanes == 1) {
            if (cfa != 0 || enc != 0 || nBits != 8) return null
        } else if (nPlanes != 4 || w and 1 != 0 || h and 1 != 0 || tw and 1 != 0 || th and 1 != 0 || cfa > 3 || nBits == 8) return null
        if (w <= 0 || h <= 0 || tw <= 0 || th <= 0 || tw > w || th > h) return null
        if (levels > 3 || tileCols > 1 || tileRows > 1) return null
        return CrxHeader(version, w, h, tw, th, nBits, nPlanes, cfa, enc, levels, mdatHdrSize, median)
    }

    /**
     * The maker-note TIFFs in the timed-metadata (CTMD) track's first sample: records of type 7
     * and 8 hold ColorData (white balance, black and white levels), which CMT3 lacks.
     */
    private fun ctmdMakerNotes(data: ByteArray, b: Boxes): List<Pair<ByteReader, Ifd>> {
        val t = b.tracks.firstOrNull { it.format == "CTMD" && it.firstOffset > 0 && it.firstSize > 0 } ?: return emptyList()
        val le = ByteReader(data, bigEndian = false)
        val start = t.firstOffset
        val size = minOf(t.firstSize, data.size - start)
        val out = ArrayList<Pair<ByteReader, Ifd>>()
        var dir = 0L
        while (dir + 6 < size) {
            val item = le.u32((start + dir).toInt())
            val type = le.u16((start + dir + 4).toInt())
            if (item < 1 || dir + item > size) break
            if (type == 7 || type == 8) {
                var box = dir + 12
                while (box + 8 < dir + item) {
                    val len = le.u32((start + box).toInt())
                    val tag = le.u32((start + box + 4).toInt())
                    if (len < 8 || box + len > dir + item) break
                    if (tag == 0x927CL) {
                        val from = (start + box + 8).toInt()
                        tiff(data.copyOfRange(from, (start + box + len).toInt()))?.let { out += it }
                    }
                    box += len
                }
            }
            dir += item
        }
        return out
    }

    private fun tiff(bytes: ByteArray?): Pair<ByteReader, Ifd>? {
        if (bytes == null || bytes.size < 8) return null
        val r = ByteReader(bytes, bigEndian = bytes[0] == 'M'.code.toByte())
        if (r.u16(2) != 42) return null
        return Tiff.readIfdOrNull(r, r.u32(4).toInt())?.let { r to it }
    }

    fun parse(data: ByteArray): RawMetadata {
        val r = ByteReader(data, bigEndian = true)
        val b = Boxes()
        walk(data, r, 0, data.size.toLong(), "", b, 0)

        // Shooting data.
        val ifd0 = tiff(b.cmt[0])
        val exifT = tiff(b.cmt[1])
        val mnT = tiff(b.cmt[2])
        val gpsT = tiff(b.cmt[3])
        val exif = exifT?.let { (er, ei) -> ExifInfo.read(er, ei) } ?: ExifInfo()
        gpsT?.let { (_, gi) -> runCatching { ExifInfo.gpsFrom(gi) }.getOrNull() }?.let { exif.gps = it }
        val make = ifd0?.second?.string(0x010F)?.ifEmpty { null } ?: "Canon"
        var model = ifd0?.second?.string(0x0110).orEmpty()
        if (exif.dateTime.isEmpty()) exif.dateTime = ifd0?.second?.string(0x0132).orEmpty()
        val mn = mnT?.second
        mn?.long(0x0010)?.let { id -> Cr2Parser.modelName(id)?.let { model = "Canon $it" } }

        // The raw track: the CRX stream with the most bits (LibRaw's rule), its first frame.
        val raws = b.tracks.mapNotNull { t -> t.cmp1?.let { header(it) }?.let { t to it } }
            .filter { (t, _) -> t.firstOffset > 0 && t.firstSize > 0 }
        val (track, hdr) = raws.maxByOrNull { (_, h) -> h.nBits.toLong() * h.width * h.height }
            ?: throw RawFormatException("CR3 without a supported raw track")
        if (hdr.width > TiffRaw.MAX_DIMENSION || hdr.height > TiffRaw.MAX_DIMENSION) throw RawFormatException("Implausible CR3 size")
        if (track.firstOffset + hdr.mdatHdrSize > data.size) throw RawFormatException("Raw data offset outside the file")
        val w = hdr.width; val h = hdr.height
        val cfa = when (hdr.cfaLayout) {
            1 -> intArrayOf(1, 0, 2, 1)
            2 -> intArrayOf(1, 2, 0, 1)
            3 -> intArrayOf(2, 1, 1, 0)
            else -> intArrayOf(0, 1, 1, 2)
        }

        // SensorInfo gives the crop; ColorData the as-shot levels, black and white.
        val sensor = mn?.get(0x00E0)?.takeIf { it.count >= 17 }?.let { e -> IntArray(17) { e.int(it) ?: 0 } }
        val crop = sensor?.let { s -> Crop(s[5], s[6], s[7] - s[5] + 1, s[8] - s[6] + 1) }?.takeIf { TiffRaw.valid(it, w, h) }
            ?: Crop(0, 0, w, h)
        val bits = if (hdr.encType == 3) hdr.medianBits else hdr.nBits
        val colorData = (listOfNotNull(mnT) + ctmdMakerNotes(data, b)).firstNotNullOfOrNull { (mr, m) ->
            m[0x4001]?.takeIf { it.type == 3 && it.count > 500 }?.let { Cr2Parser.colorData(mr, it, bits) }
        }
        val (tableBlack, tableWhite) = TiffRaw.tableLevels(make, model, colorData?.blackRggb?.let { TiffRaw.blackFromRggb(it, cfa) }, null)
        val fullWhite = tableWhite ?: ((1 shl hdr.nBits) - 1)
        val white = colorData?.specularWhite?.takeIf { it > fullWhite / 2 && it < fullWhite } ?: fullWhite

        // Previews: the full-size JPEG track, else the PRVW box.
        val jpeg = b.tracks.filter { it.jpeg && it.firstOffset > 0 && it.firstSize > 0 && it.firstOffset + it.firstSize <= data.size }
            .maxByOrNull { it.firstSize }
        val (pOff, pLen) = when {
            jpeg != null -> jpeg.firstOffset to jpeg.firstSize.toInt()
            b.previewLength > 0 -> b.previewOffset to b.previewLength
            else -> 0L to 0
        }
        return RawMetadata(
            format = RawFormat.CR3,
            make = make, model = model,
            rawWidth = w, rawHeight = h,
            cfa = cfa,
            blackLevels = tableBlack ?: IntArray(4),
            whiteLevel = white,
            cameraWb = colorData?.wbRggb?.let { TiffRaw.wbFromRggb(it) } ?: floatArrayOf(1f, 1f, 1f),
            crop = crop,
            orientation = ifd0?.second?.int(0x0112) ?: 1,
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            dateTime = exif.dateTime,
            previewOffset = pOff, previewLength = pLen,
            ambientTemperature = exif.ambientTemperature, pressureHpa = exif.pressureHpa, waterDepth = exif.waterDepth,
            gps = exif.gps,
            layout = Cr3Layout(track.firstOffset, track.firstSize, hdr),
        )
    }

    /** Shooting data from the start of a file alone (the boxes before the picture data). */
    fun facts(head: ByteArray): ShotFacts? = runCatching {
        val b = Boxes()
        walk(head, ByteReader(head, bigEndian = true), 0, head.size.toLong(), "", b, 0)
        val ifd0 = tiff(b.cmt[0]) ?: return@runCatching null
        val exif = tiff(b.cmt[1])?.let { (er, ei) -> ExifInfo.read(er, ei) } ?: ExifInfo()
        val gps = tiff(b.cmt[3])?.let { (_, gi) -> runCatching { ExifInfo.gpsFrom(gi) }.getOrNull() }
        var model = ifd0.second.string(0x0110)
        tiff(b.cmt[2])?.second?.long(0x0010)?.let { id -> Cr2Parser.modelName(id)?.let { model = "Canon $it" } }
        ShotFacts(
            make = ifd0.second.string(0x010F), model = model, dateTime = exif.dateTime.ifEmpty { ifd0.second.string(0x0132) },
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            width = 0, height = 0, depth = null, temperature = exif.ambientTemperature, pressureHpa = exif.pressureHpa,
            altitude = gps?.altitude, latitude = gps?.latitude, longitude = gps?.longitude,
        )
    }.getOrNull()

    /** The PRVW preview (about 1620 px wide), found from the start of the file. */
    fun preview(head: ByteArray): Pair<Long, Int>? = runCatching {
        val b = Boxes()
        walk(head, ByteReader(head, bigEndian = true), 0, head.size.toLong(), "", b, 0)
        if (b.previewLength > 1024) b.previewOffset to b.previewLength else null
    }.getOrNull()

    /** EXIF orientation from the start of the file. */
    fun orientation(head: ByteArray): Int = runCatching {
        val b = Boxes()
        walk(head, ByteReader(head, bigEndian = true), 0, head.size.toLong(), "", b, 0)
        tiff(b.cmt[0])?.second?.int(0x0112)
    }.getOrNull() ?: 1
}

/**
 * Canon's CRX codec, a port of LibRaw's crx.cpp: each of the four Bayer planes is coded in
 * tiles; a tile is either one band of adaptive Golomb-Rice coded prediction residuals
 * (lossless) or up to three levels of a 5/3 integer wavelet whose bands are quantised (cRAW).
 */
object Cr3Decoder {

    fun decode(data: ByteArray, meta: RawMetadata): RawImage {
        val l = meta.layout as Cr3Layout
        val out = ShortArray(meta.rawWidth * meta.rawHeight)
        Crx(data, l, out).decode()
        return RawImage(meta.rawWidth, meta.rawHeight, out, meta)
    }

    private const val HAS_RIGHT = 1
    private const val HAS_LEFT = 2
    private const val HAS_BOTTOM = 4
    private const val HAS_TOP = 8

    private val EX_COEF = intArrayOf(
        1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0,
        0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 1, 0,
        0, 0, 1, 2, 2, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 2, 2,
        1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 2, 2, 2, 2, 1, 1, 1,
        1, 2, 2, 1, 1, 1, 1, 2, 2, 1, 1, 0, 1, 1, 1, 1, 1, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    )
    private val Q_STEP = intArrayOf(0x28, 0x2D, 0x33, 0x39, 0x40, 0x48, 0, 0)
    private val JS = intArrayOf(
        1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 8, 8, 8, 8, 0x10, 0x10, 0x20, 0x20, 0x40, 0x40,
        0x80, 0x80, 0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000, 0x8000,
    )
    private val J = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 9, 10, 11, 12, 13, 14, 15)

    private fun corrupt(): Nothing = throw RawFormatException("CR3 raw data is corrupt")

    private fun abs(x: Int) = if (x < 0) -x else x
    private fun constrain(x: Int, lo: Int, hi: Int) = if (x < lo) lo else if (x > hi) hi else x

    private fun predictK(prevK: Int, bitCode: Int, maxVal: Int = 0): Int {
        val k = prevK - (if (Integer.compareUnsigned(bitCode, (1 shl prevK) shr 1) < 0) 1 else 0) +
            (if (Integer.compareUnsigned(bitCode ushr prevK, 2) > 0) 1 else 0) +
            (if (Integer.compareUnsigned(bitCode ushr prevK, 5) > 0) 1 else 0)
        return if (maxVal == 0 || k < maxVal) k else maxVal
    }

    /** MSB-first bits of one band's data; reading past its end gives zeros (until it's clearly corrupt). */
    private class Bits(private val data: ByteArray, start: Long, size: Long) {
        private var pos = start.coerceAtMost(data.size.toLong()).toInt()
        private val end = minOf(start + size, data.size.toLong()).toInt()
        private var acc = 0L
        private var count = 0

        private fun fill() {
            while (count <= 55) {
                val b = if (pos < end) data[pos].toInt() and 0xFF else { if (pos > end + 64) corrupt(); 0 }
                pos++
                acc = (acc shl 8) or b.toLong()
                count += 8
            }
        }

        fun bits(n: Int): Int {
            if (n == 0) return 0
            if (count < n) fill()
            count -= n
            return ((acc ushr count) and ((1L shl n) - 1)).toInt()
        }

        fun zeros(): Int {
            var z = 0
            while (true) {
                if (count == 0) fill()
                val v = acc and ((1L shl count) - 1)
                if (v == 0L) { z += count; count = 0; continue }
                val lead = java.lang.Long.numberOfLeadingZeros(v) - (64 - count)
                z += lead
                count -= lead + 1
                return z
            }
        }

        /** A residual: unary prefix, then [k] bits (or an escape to [escBits] raw bits). */
        fun code(k: Int, escAt: Int = 41, escBits: Int = 21): Int {
            var bc = zeros()
            if (bc >= escAt) bc = bits(escBits) else if (k != 0) bc = bits(k) or (bc shl k)
            return bc
        }
    }

    private fun signed(bc: Int) = -(bc and 1) xor (bc ushr 1)

    /** One band's line decoder (CrxBandParam). */
    private class BandParam(
        val bits: Bits,
        val width: Int,
        val height: Int,
        val roundedBitsMask: Int,
        val supportsPartial: Boolean,
    ) {
        var roundedBits = 0
        var curLine = 0
        val a = IntArray(2 * width + 4)
        val n = IntArray(if (supportsPartial) 0 else width + 2)
        var p0 = 0
        var p1 = 0
        var p2 = 0
        var s = 0
        var k = 0

        private fun run(length: Int): Int {
            var nSyms = 1
            while (bits.bits(1) != 0) {
                nSyms += JS[s]
                if (nSyms > length) { nSyms = length; break }
                if (s < 31) s++
                if (nSyms == length) break
            }
            if (nSyms < length) {
                if (J[s] != 0) nSyms += bits.bits(J[s])
                if (s > 0) s--
                if (nSyms > length) corrupt()
            }
            return nSyms
        }

        private fun median(): Int {
            val delta = a[p0 + 1] - a[p0]
            val idx = ((if ((a[p0] < a[p1]) xor (delta < 0)) 1 else 0) shl 1) + (if ((a[p1] < a[p0 + 1]) xor (delta < 0)) 1 else 0)
            return when (idx) { 0, 1 -> delta + a[p1]; 2 -> a[p1]; else -> a[p0 + 1] }
        }

        private fun symbolL1(doMedian: Boolean, notEOL: Boolean) {
            a[p1 + 1] = if (doMedian) median() else a[p0 + 1]
            var bc = bits.code(k)
            a[p1 + 1] += signed(bc)
            if (notEOL) {
                val nextDelta = (a[p0 + 2] - a[p0 + 1]) shl 1
                bc = (bc + abs(nextDelta)) ushr 1
                p0++
            }
            k = predictK(k, bc, 15)
            p1++
        }

        private fun decodeLine() {
            var length = width
            a[p1] = a[p0 + 1]
            while (length > 1) {
                if (a[p1] != a[p0 + 1] || a[p1] != a[p0 + 2]) {
                    symbolL1(true, true)
                } else {
                    if (bits.bits(1) != 0) {
                        val nSyms = run(length)
                        length -= nSyms
                        p0 += nSyms
                        repeat(nSyms) { a[p1 + 1] = a[p1]; p1++ }
                    }
                    if (length > 0) symbolL1(false, length > 1)
                }
                length--
            }
            if (length == 1) symbolL1(true, false)
            a[p1 + 1] = a[p1] + 1
        }

        private fun symbolL1Rounded(doSym: Boolean = true, doCode: Boolean = true) {
            val sym = if (doSym) median() else a[p0 + 1]
            val bc = bits.code(k)
            var code = signed(bc)
            a[p1 + 1] = roundedBitsMask * 2 * code + (code shr 31) + sym
            if (doCode) {
                code = if (a[p0 + 2] > a[p0 + 1]) (a[p0 + 2] - a[p0 + 1] + roundedBitsMask - 1) shr roundedBits
                else -((a[p0 + 1] - a[p0 + 2] + roundedBitsMask) shr roundedBits)
                k = predictK(k, (bc + 2 * abs(code)) ushr 1, 15)
            } else {
                k = predictK(k, bc, 15)
            }
            p1++
        }

        private fun decodeLineRounded() {
            var valueReached = false
            a[p0] = a[p0 + 1]
            a[p1] = a[p0 + 1]
            var length = width
            while (length > 1) {
                if (abs(a[p0 + 2] - a[p0 + 1]) > roundedBitsMask) {
                    symbolL1Rounded(); p0++; valueReached = true
                } else if (valueReached || abs(a[p0] - a[p1]) > roundedBitsMask) {
                    symbolL1Rounded(); p0++; valueReached = false
                } else {
                    var nSyms = 0
                    if (bits.bits(1) != 0) nSyms = run(length)
                    length -= nSyms
                    p0 += nSyms
                    repeat(nSyms) { a[p1 + 1] = a[p1]; p1++ }
                    if (length > 1) {
                        symbolL1Rounded(doSym = false)
                        p0++
                        valueReached = abs(a[p0 + 1] - a[p0]) > roundedBitsMask
                    } else if (length == 1) {
                        symbolL1Rounded(doSym = false, doCode = false)
                    }
                }
                length--
            }
            if (length == 1) symbolL1Rounded(doSym = true, doCode = false)
            a[p1 + 1] = a[p1] + 1
        }

        private fun decodeLineNoRef() {
            var i = 0
            while (i < width - 1) {
                if ((a[p0 + i + 2] or a[p0 + i + 1] or a[p1 + i]) != 0) {
                    val bc = bits.code(k)
                    a[p1 + i + 1] = signed(bc)
                    k = predictK(k, bc)
                    if (n[p2 + i + 1] - k <= 1) { if (k >= 15) k = 15 } else k++
                } else {
                    var nSyms = 0
                    if (bits.bits(1) != 0) {
                        nSyms = 1
                        if (i != width - 1) {
                            while (bits.bits(1) != 0) {
                                nSyms += JS[s]
                                if (i + nSyms > width) { nSyms = width - i; break }
                                if (s < 31) s++
                                if (i + nSyms == width) break
                            }
                            if (i + nSyms < width) {
                                if (J[s] != 0) nSyms += bits.bits(J[s])
                                if (s > 0) s--
                            }
                            if (i + nSyms > width) corrupt()
                        }
                    } else if (i > width) {
                        corrupt()
                    }
                    if (nSyms > 0) {
                        java.util.Arrays.fill(a, p1 + i + 1, p1 + i + 1 + nSyms, 0)
                        java.util.Arrays.fill(n, p2 + i, p2 + i + nSyms, 0)
                        i += nSyms
                    }
                    if (i >= width - 1) {
                        if (i == width - 1) {
                            val bc = bits.code(k)
                            a[p1 + i + 1] = signed(bc + 1)
                            k = predictK(k, bc, 15)
                            n[p2 + i] = k
                        }
                        i++
                        continue
                    } else {
                        val bc = bits.code(k)
                        a[p1 + i + 1] = signed(bc + 1)
                        k = predictK(k, bc)
                        if (n[p2 + i + 1] - k <= 1) { if (k >= 15) k = 15 } else k++
                    }
                }
                n[p2 + i] = k
                i++
            }
            if (i == width - 1) {
                val bc = bits.code(k)
                a[p1 + i + 1] = signed(bc)
                k = predictK(k, bc, 15)
                n[p2 + i] = k
            }
        }

        private fun decodeTopLine() {
            a[p1] = 0
            var length = width
            while (length > 1) {
                if (a[p1] != 0) {
                    a[p1 + 1] = a[p1]
                } else {
                    if (bits.bits(1) != 0) {
                        val nSyms = run(length)
                        length -= nSyms
                        repeat(nSyms) { a[p1 + 1] = a[p1]; p1++ }
                        if (length <= 0) break
                    }
                    a[p1 + 1] = 0
                }
                val bc = bits.code(k)
                a[p1 + 1] += signed(bc)
                k = predictK(k, bc, 15)
                p1++
                length--
            }
            if (length == 1) {
                a[p1 + 1] = a[p1]
                val bc = bits.code(k)
                a[p1 + 1] += signed(bc)
                k = predictK(k, bc, 15)
                p1++
            }
            a[p1 + 1] = a[p1] + 1
        }

        private fun decodeTopLineRounded() {
            a[p1] = 0
            var length = width
            while (length > 1) {
                if (abs(a[p1]) > roundedBitsMask) {
                    a[p1 + 1] = a[p1]
                } else {
                    var nSyms = 0
                    if (bits.bits(1) != 0) nSyms = run(length)
                    length -= nSyms
                    repeat(nSyms) { a[p1 + 1] = a[p1]; p1++ }
                    if (length <= 0) break
                    a[p1 + 1] = 0
                }
                val bc = bits.code(k)
                val v = signed(bc)
                a[p1 + 1] += roundedBitsMask * 2 * v + (v shr 31)
                k = predictK(k, bc, 15)
                p1++
                length--
            }
            if (length == 1) {
                val bc = bits.code(k)
                val v = signed(bc)
                a[p1 + 1] += roundedBitsMask * 2 * v + (v shr 31)
                k = predictK(k, bc, 15)
                p1++
            }
            a[p1 + 1] = a[p1] + 1
        }

        private fun decodeTopLineNoRef() {
            a[p0] = 0
            a[p1] = 0
            var length = width
            while (length > 1) {
                if (a[p1] != 0) {
                    val bc = bits.code(k)
                    a[p1 + 1] = signed(bc)
                    k = predictK(k, bc, 15)
                } else {
                    var nSyms = 0
                    if (bits.bits(1) != 0) nSyms = run(length)
                    length -= nSyms
                    repeat(nSyms) { n[p2] = 0; a[p1 + 1] = 0; p1++; p2++ }
                    if (length <= 0) break
                    val bc = bits.code(k)
                    a[p1 + 1] = signed(bc + 1)
                    k = predictK(k, bc, 15)
                }
                n[p2] = k
                p2++
                p1++
                length--
            }
            if (length == 1) {
                val bc = bits.code(k)
                a[p1 + 1] = signed(bc)
                k = predictK(k, bc, 15)
                n[p2] = k
                p1++
            }
            a[p1 + 1] = 0
        }

        /** Decodes the next line into [out] (crxDecodeLine). */
        fun line(out: IntArray) {
            if (curLine >= height) corrupt()
            val ll = width + 2
            if (curLine == 0) {
                s = 0; k = 0
                p0 = 0; p1 = ll; p2 = 0
                if (supportsPartial) {
                    if (roundedBitsMask <= 0) {
                        decodeTopLine()
                    } else {
                        roundedBits = 1
                        if (roundedBitsMask and 1.inv() != 0) while ((roundedBitsMask shr roundedBits) != 0) roundedBits++
                        decodeTopLineRounded()
                    }
                } else {
                    decodeTopLineNoRef()
                }
            } else {
                p2 = 0
                if (curLine and 1 != 0) { p1 = 0; p0 = ll } else { p0 = 0; p1 = ll }
                when {
                    !supportsPartial -> decodeLineNoRef()
                    roundedBitsMask <= 0 -> decodeLine()
                    else -> decodeLineRounded()
                }
            }
            // The decoded line starts one after where lineBuf1 did.
            val start = (if (curLine == 0 || curLine and 1 == 0) ll else 0) + 1
            System.arraycopy(a, start, out, 0, width)
            curLine++
        }
    }

    private class Subband {
        var param: BandParam? = null
        var buf = IntArray(0)
        var width = 0
        var height = 0
        var qParam = 0
        var kParam = 0
        var qStepBase = 0
        var qStepMult = 0
        var supportsPartial = false
        var dataSize = 0L
        var dataOffset = 0L
        var rowStartAddOn = 0
        var rowEndAddOn = 0
        var colStartAddOn = 0
        var colEndAddOn = 0
        var levelShift = 0
    }

    private class Wavelet(val width: Int, val height: Int) {
        var sub0 = IntArray(0)
        var sub1 = IntArray(0)
        var sub2 = IntArray(0)
        var sub3 = IntArray(0)
        val lineBuf = Array(8) { IntArray(width) }
        var curLine = 0
        var curH = 0
        var fltTapH = 0
    }

    private class Comp(val number: Int, nBands: Int) {
        val bands = Array(nBands) { Subband() }
        var wavelets: Array<Wavelet> = emptyArray()
        var dataOffset = 0L
        var compSize = 0L
        var supportsPartial = true
        var roundedBitsMask = 0
        var tileFlag = 0
    }

    private class QStep(val table: IntArray, val width: Int, val height: Int)

    private class Tile(nPlanes: Int, nBands: Int) {
        val comps = Array(nPlanes) { Comp(it, nBands) }
        var tileFlag = 0
        var dataOffset = 0L
        var tileSize = 0L
        var width = 0
        var height = 0
        var hasQPData = false
        var qStep: Array<QStep>? = null
        var mdatQPDataSize = 0L
        var mdatExtraSize = 0
    }

    private class Crx(val data: ByteArray, val layout: Cr3Layout, val out: ShortArray) {
        val hdr = layout.header
        val nPlanes = hdr.nPlanes
        val planeWidth = if (nPlanes == 4) hdr.width shr 1 else hdr.width
        val planeHeight = if (nPlanes == 4) hdr.height shr 1 else hdr.height
        val tileWidth = if (nPlanes == 4) hdr.tileWidth shr 1 else hdr.tileWidth
        val tileHeight = if (nPlanes == 4) hdr.tileHeight shr 1 else hdr.tileHeight
        val levels = hdr.levels
        val subbandCount = 3 * levels + 1
        val nBits = hdr.nBits
        val encType = hdr.encType
        val medianBits = hdr.medianBits
        val mdatOffset = layout.dataOffset + hdr.mdatHdrSize
        val tileCols: Int
        val tileRows: Int
        val tiles: Array<Tile>
        /** Where each plane's samples go in the output, by plane. */
        val planeOffset = IntArray(4)
        val planeBuf: ShortArray?

        init {
            if (tileWidth < 0x16 || tileHeight < 0x16 || planeWidth > 0x7FFF || planeHeight > 0x7FFF) corrupt()
            tileCols = (planeWidth + tileWidth - 1) / tileWidth
            tileRows = (planeHeight + tileHeight - 1) / tileHeight
            if (tileCols > 0xFF || tileRows > 0xFF || planeWidth - tileWidth * (tileCols - 1) < 0x16 ||
                planeHeight - tileHeight * (tileRows - 1) < 0x16
            ) corrupt()
            tiles = Array(tileCols * tileRows) { Tile(nPlanes, subbandCount) }
            val rowSize = 2 * planeWidth
            if (nPlanes == 4) {
                val o = intArrayOf(0, 1, rowSize, rowSize + 1)
                val order = when (hdr.cfaLayout) {
                    1 -> intArrayOf(1, 0, 3, 2)
                    2 -> intArrayOf(2, 3, 0, 1)
                    3 -> intArrayOf(3, 2, 1, 0)
                    else -> intArrayOf(0, 1, 2, 3)
                }
                for (p in 0 until 4) planeOffset[p] = o[order[p]]
            }
            planeBuf = if (encType == 3 && nPlanes == 4 && nBits > 8) ShortArray(planeHeight * planeWidth * nPlanes) else null
            readImageHeaders()
        }

        private fun u16(o: Int) = ((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)
        private fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()

        private fun readImageHeaders() {
            val nTiles = tiles.size
            for ((t, tile) in tiles.withIndex()) {
                if ((t + 1) % tileCols != 0) {
                    tile.width = tileWidth
                    if (tileCols > 1) {
                        tile.tileFlag = HAS_RIGHT
                        if (t % tileCols != 0) tile.tileFlag = tile.tileFlag or HAS_LEFT
                    }
                } else {
                    tile.width = planeWidth - tileWidth * (tileCols - 1)
                    if (tileCols > 1) tile.tileFlag = HAS_LEFT
                }
                if (t < nTiles - tileCols) {
                    tile.height = tileHeight
                    if (tileRows > 1) {
                        tile.tileFlag = tile.tileFlag or HAS_BOTTOM
                        if (t >= tileCols) tile.tileFlag = tile.tileFlag or HAS_TOP
                    }
                } else {
                    tile.height = planeHeight - tileHeight * (tileRows - 1)
                    if (tileRows > 1) tile.tileFlag = tile.tileFlag or HAS_TOP
                }
                for (c in tile.comps) {
                    c.tileFlag = tile.tileFlag
                    for (band in c.bands) { band.qParam = 4 }
                }
            }

            var tileOffset = 0L
            var dataSize = hdr.mdatHdrSize
            var p = layout.dataOffset.toInt()
            for ((t, tile) in tiles.withIndex()) {
                if (dataSize < 4) corrupt()
                var sign = u16(p)
                var size = u16(p + 2)
                if ((sign != 0xFF01 || size != 8) && (sign != 0xFF11 || (size != 8 && size != 16))) corrupt()
                if (dataSize < size + 4) corrupt()
                val tail = u16(p + 10)
                if ((size == 8 && tail != 0) || (size == 16 && tail != 0x4000)) corrupt()
                if (u16(p + 8) != t) corrupt()
                dataSize -= size + 4
                tile.tileSize = u32(p + 4)
                tile.dataOffset = tileOffset
                if (size == 16) {
                    if (u16(p + 18) != 0) corrupt()
                    tile.hasQPData = true
                    tile.mdatQPDataSize = u32(p + 12)
                    tile.mdatExtraSize = u16(p + 16)
                }
                p += size + 4
                tileOffset += tile.tileSize

                var compOffset = 0L
                for ((cn, comp) in tile.comps.withIndex()) {
                    if (dataSize < 0xC) corrupt()
                    sign = u16(p)
                    size = u16(p + 2)
                    if ((sign != 0xFF02 && sign != 0xFF12) || size != 8) corrupt()
                    val b8 = data[p + 8].toInt() and 0xFF
                    if (cn != b8 shr 4) corrupt()
                    if (((data[p + 9].toInt() and 0xFF) or (data[p + 10].toInt() and 0xFF) or (data[p + 11].toInt() and 0xFF)) != 0) corrupt()
                    comp.compSize = u32(p + 4)
                    val roundedBits = (b8 shr 1) and 3
                    comp.supportsPartial = (b8 and 8) != 0
                    comp.dataOffset = compOffset
                    compOffset += comp.compSize
                    dataSize -= 0xC
                    p += 0xC
                    comp.roundedBitsMask = 0
                    if (roundedBits != 0) {
                        if (levels != 0 || !comp.supportsPartial) corrupt()
                        comp.roundedBitsMask = 1 shl (roundedBits - 1)
                    }
                    // Subband headers.
                    var subbandOffset = 0L
                    for ((bn, band) in comp.bands.withIndex()) {
                        if (dataSize < 4) corrupt()
                        val hs = u16(p)
                        val hz = u16(p + 2)
                        if (dataSize < hz + 4) corrupt()
                        if ((hs != 0xFF03 || hz != 8) && (hs != 0xFF13 || hz != 16)) corrupt()
                        val subbandSize = u32(p + 4)
                        if (bn != ((data[p + 8].toInt() and 0xF0) shr 4)) corrupt()
                        band.dataOffset = subbandOffset
                        band.kParam = 0
                        if (hs == 0xFF03) {
                            val bitData = u32(p + 8)
                            band.dataSize = subbandSize - (bitData and 0x7FFFF)
                            band.supportsPartial = (bitData and 0x8000000L) != 0L
                            band.qParam = ((bitData shr 19) and 0xFF).toInt()
                            band.qStepBase = 0
                            band.qStepMult = 0
                        } else {
                            if (u16(p + 8) and 0xFFF != 0) corrupt()
                            if (u16(p + 18) != 0) corrupt()
                            band.supportsPartial = false
                            band.qParam = 0
                            band.dataSize = subbandSize - u16(p + 16)
                            band.qStepBase = u32(p + 12).toInt()
                            band.qStepMult = u16(p + 10)
                        }
                        subbandOffset += subbandSize
                        p += hz + 4
                        dataSize -= hz + 4
                    }
                    processSubbands(tile, comp)
                }
            }

            if (hdr.version != 0x200) return
            for (tile in tiles) {
                if (!tile.hasQPData) continue
                val bits = Bits(data, mdatOffset + tile.dataOffset, tile.mdatQPDataSize)
                val qpWidth = (tile.width shr 3) + (if (tile.width and 7 != 0) 1 else 0)
                val qpHeight = (tile.height shr 1) + (tile.height and 1)
                val total = qpWidth * qpHeight
                val qpTable = IntArray(total)
                val lines = IntArray(2 * (qpWidth + 2))
                var cur = 0
                val kp = IntArray(1)
                for (row in 0 until qpHeight) {
                    val l0 = if (row and 1 != 0) qpWidth + 2 else 0
                    val l1 = if (row and 1 != 0) 0 else qpWidth + 2
                    if (row != 0) golombNormal(bits, qpWidth, lines, l0, l1, kp) else golombTop(bits, qpWidth, lines, l1, kp)
                    for (col in 0 until qpWidth) qpTable[cur++] = lines[l1 + col + 1] + 4
                }
                tile.qStep = makeQStep(tile, qpTable)
            }
        }

        private fun readQP(bits: Bits, k: Int): Int = bits.code(k, escAt = 23, escBits = 8)

        private fun golombTop(bits: Bits, width: Int, l: IntArray, start: Int, kp: IntArray) {
            var o = start
            l[o] = 0
            var w = width
            while (w-- > 0) {
                l[o + 1] = l[o]
                val qp = readQP(bits, kp[0])
                l[o + 1] += signed(qp)
                kp[0] = predictK(kp[0], qp, 7)
                o++
            }
            l[o + 1] = l[o] + 1
        }

        private fun prediction(left: Int, top: Int, deltaH: Int, deltaV: Int): Int {
            val idx = ((if ((deltaV < 0) xor (deltaH < 0)) 1 else 0) shl 1) + (if ((left < top) xor (deltaH < 0)) 1 else 0)
            return when (idx) { 0, 1 -> left + deltaH; 2 -> left; else -> top }
        }

        private fun golombNormal(bits: Bits, width: Int, l: IntArray, s0: Int, s1: Int, kp: IntArray) {
            var o0 = s0
            var o1 = s1
            l[o1] = l[o0 + 1]
            var deltaH = l[o0 + 1] - l[o0]
            var w = width
            while (w-- > 0) {
                l[o1 + 1] = prediction(l[o1], l[o0 + 1], deltaH, l[o0] - l[o1])
                val qp = readQP(bits, kp[0])
                l[o1 + 1] += signed(qp)
                if (w != 0) {
                    deltaH = l[o0 + 2] - l[o0 + 1]
                    kp[0] = predictK(kp[0], (qp + 2 * abs(deltaH)) ushr 1, 7)
                    o0++
                } else {
                    kp[0] = predictK(kp[0], qp, 7)
                }
                o1++
            }
            l[o1 + 1] = l[o1] + 1
        }

        private fun qStepOf(v: Int): Int =
            if (v / 6 >= 6) Q_STEP[v % 6] shl ((v / 6 - 6) and 0x1F) else Q_STEP[v % 6] ushr (6 - v / 6)

        private fun makeQStep(tile: Tile, qp: IntArray): Array<QStep> {
            if (levels > 3 || levels < 1) corrupt()
            val qpWidth = (tile.width shr 3) + (if (tile.width and 7 != 0) 1 else 0)
            val qpHeight = (tile.height shr 1) + (tile.height and 1)
            val qpHeight4 = (tile.height shr 2) + (if (tile.height and 3 != 0) 1 else 0)
            val qpHeight8 = (tile.height shr 3) + (if (tile.height and 7 != 0) 1 else 0)
            val out = ArrayList<QStep>()
            if (levels >= 3) {
                val t = IntArray(qpWidth * qpHeight8)
                var o = 0
                for (row in 0 until qpHeight8) {
                    var r0 = qpWidth * minOf(4 * row, qpHeight - 1)
                    var r1 = qpWidth * minOf(4 * row + 1, qpHeight - 1)
                    var r2 = qpWidth * minOf(4 * row + 2, qpHeight - 1)
                    var r3 = qpWidth * minOf(4 * row + 3, qpHeight - 1)
                    for (col in 0 until qpWidth) {
                        var v = qp[r0++] + qp[r1++] + qp[r2++] + qp[r3++]
                        v = ((if (v < 0) 3 else 0) + v) shr 2
                        t[o++] = qStepOf(v)
                    }
                }
                out += QStep(t, qpWidth, qpHeight8)
            }
            if (levels >= 2) {
                val t = IntArray(qpWidth * qpHeight4)
                var o = 0
                for (row in 0 until qpHeight4) {
                    var r0 = qpWidth * minOf(2 * row, qpHeight - 1)
                    var r1 = qpWidth * minOf(2 * row + 1, qpHeight - 1)
                    for (col in 0 until qpWidth) t[o++] = qStepOf((qp[r0++] + qp[r1++]) / 2)
                }
                out += QStep(t, qpWidth, qpHeight4)
            }
            out += QStep(IntArray(qpWidth * qpHeight) { qStepOf(qp[it]) }, qpWidth, qpHeight)
            return out.toTypedArray()
        }

        private fun setupIdx(band: Subband, level: Int, colStart: Int, bandWidthEx: Int, rowStart: Int, bandHeightEx: Int) {
            if (hdr.version == 0x200) {
                band.rowStartAddOn = rowStart
                band.rowEndAddOn = bandHeightEx
                band.colStartAddOn = colStart
                band.colEndAddOn = bandWidthEx
                band.levelShift = 3 - level
            } else {
                band.rowStartAddOn = 0; band.rowEndAddOn = 0; band.colStartAddOn = 0; band.colEndAddOn = 0; band.levelShift = 0
            }
        }

        private fun processSubbands(tile: Tile, comp: Comp) {
            var b = subbandCount - 1
            val bands = comp.bands
            var bandHeight = tile.height
            var bandWidth = tile.width
            var bandWidthEx = 0
            var bandHeightEx = 0
            if (levels != 0) {
                val rowEx = 0x30 * (levels - 1) + 6 * (tile.width and 7)
                val colEx = 0x30 * (levels - 1) + 6 * (tile.height and 7)
                for (level in 0 until levels) {
                    val wOdd = bandWidth and 1
                    val hOdd = bandHeight and 1
                    bandWidth = (wOdd + bandWidth) shr 1
                    bandHeight = (hOdd + bandHeight) shr 1
                    var bw0 = 0; var bw1 = 0; var bh0 = 0; var bh1 = 0
                    var colStart = 0; var rowStart = 0
                    if (tile.tileFlag and HAS_RIGHT != 0) { bw0 = EX_COEF[rowEx + 2 * level]; bw1 = EX_COEF[rowEx + 2 * level + 1] }
                    if (tile.tileFlag and HAS_LEFT != 0) { bw0++; colStart = 1 }
                    if (tile.tileFlag and HAS_BOTTOM != 0) { bh0 = EX_COEF[colEx + 2 * level]; bh1 = EX_COEF[colEx + 2 * level + 1] }
                    if (tile.tileFlag and HAS_TOP != 0) { bh0++; rowStart = 1 }
                    bands[b].width = bandWidth + bw0 - wOdd
                    bands[b].height = bandHeight + bh0 - hOdd
                    setupIdx(bands[b], level + 1, colStart, bw0 - colStart, rowStart, bh0 - rowStart)
                    bands[b - 1].width = bandWidth + bw1
                    bands[b - 1].height = bandHeight + bh0 - hOdd
                    setupIdx(bands[b - 1], level + 1, 0, bw1, rowStart, bh0 - rowStart)
                    bands[b - 2].width = bandWidth + bw0 - wOdd
                    bands[b - 2].height = bandHeight + bh1
                    setupIdx(bands[b - 2], level + 1, colStart, bw0 - colStart, 0, bh1)
                    b -= 3
                }
                if (tile.tileFlag and HAS_RIGHT != 0) bandWidthEx = EX_COEF[rowEx + 2 * levels - 1]
                if (tile.tileFlag and HAS_BOTTOM != 0) bandHeightEx = EX_COEF[colEx + 2 * levels - 1]
            }
            bands[b].width = bandWidthEx + bandWidth
            bands[b].height = bandHeightEx + bandHeight
            if (levels != 0) setupIdx(bands[b], levels, 0, bandWidthEx, 0, bandHeightEx)
        }

        // ---- per plane ----------------------------------------------------------------------

        private fun subbandRow(band: Subband, row: Int): Int = when {
            row < band.rowStartAddOn -> 0
            row < band.height - band.rowEndAddOn -> row - band.rowEndAddOn
            else -> band.height - band.rowEndAddOn - band.rowStartAddOn - 1
        }

        private fun lineWithIQuantization(band: Subband, qStep: QStep?) {
            if (band.dataSize == 0L) { band.buf.fill(0); return }
            val param = band.param!!
            if (band.supportsPartial && qStep == null) {
                val bc = param.bits.code(band.kParam, escAt = 23, escBits = 8)
                band.qParam += signed(bc)
                band.kParam = predictK(band.kParam, bc)
                if (band.kParam > 7) corrupt()
            }
            param.line(band.buf)
            if (band.width <= 0) return
            val buf = band.buf
            if (qStep != null) {
                val base = qStep.width * subbandRow(band, param.curLine - 1)
                val t = qStep.table
                fun q(idx: Int) = constrain(band.qStepBase + ((t[base + idx] * band.qStepMult) ushr 3), 1, 0x168000)
                for (i in 0 until band.colStartAddOn) buf[i] *= q(0)
                for (i in band.colStartAddOn until band.width - band.colEndAddOn) buf[i] *= q((i - band.colStartAddOn) shr band.levelShift)
                val last = (band.width - band.colEndAddOn - band.colStartAddOn - 1) shr band.levelShift
                for (i in band.width - band.colEndAddOn until band.width) buf[i] *= q(last)
            } else {
                var scale = Q_STEP[band.qParam % 6] shr (6 - band.qParam / 6)
                if (band.qParam / 6 >= 6) scale = Q_STEP[band.qParam % 6] * (1 shl (band.qParam / 6 + 26))
                if (scale != 1) for (i in 0 until band.width) buf[i] *= scale
            }
        }

        private fun horizontal53(la: IntArray, lb: IntArray, w: Wavelet, tileFlag: Int) {
            val b0 = w.sub0; val b1 = w.sub1; val b2 = w.sub2; val b3 = w.sub3
            var i0 = 0; var i1 = 0; var i2 = 0; var i3 = 0; var a = 0; var b = 0
            if (w.width <= 1) {
                la[0] = b0[0]
                lb[0] = b2[0]
                return
            }
            if (tileFlag and HAS_LEFT != 0) {
                la[0] = b0[0] - ((b1[0] + b1[1] + 2) shr 2)
                lb[0] = b2[0] - ((b3[0] + b3[1] + 2) shr 2)
                i1++; i3++
            } else {
                la[0] = b0[0] - ((b1[0] + 1) shr 1)
                lb[0] = b2[0] - ((b3[0] + 1) shr 1)
            }
            i0++; i2++
            var i = 0
            while (i < w.width - 3) {
                var delta = b0[i0] - ((b1[i1] + b1[i1 + 1] + 2) shr 2)
                la[a + 1] = b1[i1] + ((delta + la[a]) shr 1)
                la[a + 2] = delta
                delta = b2[i2] - ((b3[i3] + b3[i3 + 1] + 2) shr 2)
                lb[b + 1] = b3[i3] + ((delta + lb[b]) shr 1)
                lb[b + 2] = delta
                i0++; i1++; i2++; i3++; a += 2; b += 2
                i += 2
            }
            if (tileFlag and HAS_RIGHT != 0) {
                val dA = b0[i0] - ((b1[i1] + b1[i1 + 1] + 2) shr 2)
                la[a + 1] = b1[i1] + ((dA + la[a]) shr 1)
                val dB = b2[i2] - ((b3[i3] + b3[i3 + 1] + 2) shr 2)
                lb[b + 1] = b3[i3] + ((dB + lb[b]) shr 1)
                if (w.width and 1 != 0) { la[a + 2] = dA; lb[b + 2] = dB }
            } else if (w.width and 1 != 0) {
                la[a + 1] = b1[i1] + ((la[a] + b0[i0] - ((b1[i1] + 1) shr 1)) shr 1)
                la[a + 2] = b0[i0] - ((b1[i1] + 1) shr 1)
                lb[b + 1] = b3[i3] + ((lb[b] + b2[i2] - ((b3[i3] + 1) shr 1)) shr 1)
                lb[b + 2] = b2[i2] - ((b3[i3] + 1) shr 1)
            } else {
                la[a + 1] = la[a] + b1[i1]
                lb[b + 1] = lb[b] + b3[i3]
            }
        }

        /** One low band and its high band into [l] (the single-row horizontal step). */
        private fun horizontalOne(l: IntArray, lo: IntArray, hi: IntArray, width: Int, tileFlag: Int, rightAlways: Boolean) {
            var i0 = 0; var i1 = 0; var a = 0
            if (width <= 1) { l[0] = lo[0]; return }
            if (tileFlag and HAS_LEFT != 0) {
                l[0] = lo[0] - ((hi[0] + hi[1] + 2) shr 2)
                i1++
            } else {
                l[0] = lo[0] - ((hi[0] + 1) shr 1)
            }
            i0++
            var i = 0
            while (i < width - 3) {
                val delta = lo[i0] - ((hi[i1] + hi[i1 + 1] + 2) shr 2)
                l[a + 1] = hi[i1] + ((l[a] + delta) shr 1)
                l[a + 2] = delta
                i0++; i1++; a += 2
                i += 2
            }
            if (tileFlag and HAS_RIGHT != 0) {
                val delta = lo[i0] - ((hi[i1] + hi[i1 + 1] + 2) shr 2)
                l[a + 1] = hi[i1] + ((l[a] + delta) shr 1)
                if (rightAlways || width and 1 != 0) l[a + 2] = delta
            } else if (width and 1 != 0) {
                val delta = lo[i0] - ((hi[i1] + 1) shr 1)
                l[a + 1] = hi[i1] + ((l[a] + delta) shr 1)
                l[a + 2] = delta
            } else {
                l[a + 1] = hi[i1] + l[a]
            }
        }

        private fun getLine(comp: Comp, level: Int): IntArray {
            val w = comp.wavelets[level]
            val r = w.lineBuf[(w.fltTapH - w.curH + 5) % 5 + 3]
            w.curH--
            return r
        }

        private fun filterDecode(comp: Comp, level: Int, qStep: Array<QStep>?) {
            val w = comp.wavelets[level]
            if (w.curH != 0) return
            val sb = 3 * level
            val q = qStep?.get(level)
            val bands = comp.bands
            if (w.height - 3 <= w.curLine && comp.tileFlag and HAS_BOTTOM == 0) {
                if (w.height and 1 != 0) {
                    if (level != 0) filterDecode(comp, level - 1, qStep) else lineWithIQuantization(bands[sb], q)
                    lineWithIQuantization(bands[sb + 1], q)
                }
            } else {
                if (level != 0) filterDecode(comp, level - 1, qStep) else lineWithIQuantization(bands[sb], q)
                lineWithIQuantization(bands[sb + 1], q)
                lineWithIQuantization(bands[sb + 2], q)
                lineWithIQuantization(bands[sb + 3], q)
            }
        }

        private fun filterTransform(comp: Comp, level: Int) {
            val w = comp.wavelets[level]
            if (w.curH != 0) return
            if (w.curLine >= w.height - 3) {
                if (comp.tileFlag and HAS_BOTTOM == 0) {
                    if (w.height and 1 != 0) {
                        if (level != 0) {
                            if (comp.wavelets[level - 1].curH == 0) filterTransform(comp, level - 1)
                            w.sub0 = getLine(comp, level - 1)
                        }
                        val h0 = w.lineBuf[w.fltTapH + 3]
                        val h1 = w.lineBuf[(w.fltTapH + 1) % 5 + 3]
                        val h2 = w.lineBuf[(w.fltTapH + 2) % 5 + 3]
                        val l0 = w.lineBuf[0]
                        val l1 = w.lineBuf[1]
                        w.lineBuf[1] = w.lineBuf[2]
                        w.lineBuf[2] = l1
                        horizontalOne(l0, w.sub0, w.sub1, w.width, comp.tileFlag, rightAlways = false)
                        val nl0 = w.lineBuf[0]
                        val nl1 = w.lineBuf[1]
                        for (i in 0 until w.width) {
                            val delta = nl0[i] - ((nl1[i] + 1) shr 1)
                            h1[i] = nl1[i] + ((delta + h0[i]) shr 1)
                            h2[i] = delta
                        }
                        w.curH += 3
                        w.curLine += 3
                        w.fltTapH = (w.fltTapH + 3) % 5
                    } else {
                        val l2 = w.lineBuf[2]
                        val h0 = w.lineBuf[w.fltTapH + 3]
                        val h1 = w.lineBuf[(w.fltTapH + 1) % 5 + 3]
                        w.lineBuf[1] = l2
                        w.lineBuf[2] = w.lineBuf[1]
                        for (i in 0 until w.width) h1[i] = h0[i] + l2[i]
                        w.curH += 2
                        w.curLine += 2
                        w.fltTapH = (w.fltTapH + 2) % 5
                    }
                }
            } else {
                if (level != 0) {
                    if (comp.wavelets[level - 1].curH == 0) filterTransform(comp, level - 1)
                    w.sub0 = getLine(comp, level - 1)
                }
                val l0 = w.lineBuf[0]
                val l1 = w.lineBuf[1]
                val h0 = w.lineBuf[w.fltTapH + 3]
                val h1 = w.lineBuf[(w.fltTapH + 1) % 5 + 3]
                val h2 = w.lineBuf[(w.fltTapH + 2) % 5 + 3]
                w.lineBuf[1] = w.lineBuf[2]
                w.lineBuf[2] = l1
                horizontal53(l0, l1, w, comp.tileFlag)
                val nl0 = w.lineBuf[0]
                val nl1 = w.lineBuf[1]
                val nl2 = w.lineBuf[2]
                for (i in 0 until w.width) {
                    val delta = nl0[i] - ((nl2[i] + nl1[i] + 2) shr 2)
                    h1[i] = nl1[i] + ((delta + h0[i]) shr 1)
                    h2[i] = delta
                }
                if (w.curLine >= w.height - 3 && w.height and 1 != 0) {
                    w.curH += 3
                    w.curLine += 3
                    w.fltTapH = (w.fltTapH + 3) % 5
                } else {
                    w.curH += 2
                    w.curLine += 2
                    w.fltTapH = (w.fltTapH + 2) % 5
                }
            }
        }

        private fun filterInitialize(comp: Comp, qStep: Array<QStep>?) {
            if (levels == 0) return
            val bands = comp.bands
            for (curLevel in 0 until levels) {
                val curBand = 3 * curLevel
                val q = qStep?.get(curLevel)
                val w = comp.wavelets[curLevel]
                if (curLevel != 0) w.sub0 = getLine(comp, curLevel - 1)
                else lineWithIQuantization(bands[curBand], q)
                val h0 = w.lineBuf[w.fltTapH + 3]
                if (w.height > 1) {
                    lineWithIQuantization(bands[curBand + 1], q)
                    lineWithIQuantization(bands[curBand + 2], q)
                    lineWithIQuantization(bands[curBand + 3], q)
                    val l0 = w.lineBuf[0]
                    val l1 = w.lineBuf[1]
                    val l2 = w.lineBuf[2]
                    if (comp.tileFlag and HAS_TOP != 0) {
                        horizontal53(l0, w.lineBuf[1], w, comp.tileFlag)
                        lineWithIQuantization(bands[curBand + 3], q)
                        lineWithIQuantization(bands[curBand + 2], q)
                        horizontalOne(l2, w.sub2, w.sub3, w.width, comp.tileFlag, rightAlways = false)
                        for (i in 0 until w.width) h0[i] = l0[i] - ((l1[i] + l2[i] + 2) shr 2)
                    } else {
                        horizontal53(l0, w.lineBuf[2], w, comp.tileFlag)
                        for (i in 0 until w.width) h0[i] = l0[i] - ((l2[i] + 1) shr 1)
                    }
                    filterDecode(comp, curLevel, qStep)
                    filterTransform(comp, curLevel)
                } else {
                    lineWithIQuantization(bands[curBand + 1], q)
                    horizontalOne(h0, w.sub0, w.sub1, w.width, comp.tileFlag, rightAlways = true)
                    w.curLine++
                    w.curH++
                    w.fltTapH = (w.fltTapH + 1) % 5
                }
            }
        }

        private fun setupSubbandData(comp: Comp, tile: Tile, tileMdatOffset: Long) {
            val bands = comp.bands
            for (b in bands) b.buf = IntArray(maxOf(0, b.width))
            if (levels != 0) {
                comp.wavelets = Array(levels) { level ->
                    val band = 3 * level + 1
                    val (h, w) = if (level >= levels - 1) tile.height to tile.width else bands[band + 3].height to bands[band + 4].width
                    Wavelet(w, h).also { wv ->
                        wv.sub1 = bands[band].buf
                        wv.sub2 = bands[band + 1].buf
                        wv.sub3 = bands[band + 2].buf
                    }
                }
                comp.wavelets[0].sub0 = bands[0].buf
            }
            val base = mdatOffset + tileMdatOffset
            for ((n, b) in bands.withIndex()) {
                if (b.dataSize == 0L) continue
                val partial = comp.supportsPartial && n == 0
                b.param = BandParam(
                    Bits(data, base + b.dataOffset, b.dataSize), b.width, b.height,
                    if (partial) comp.roundedBitsMask else 0, partial,
                )
            }
        }

        private fun convertLine(row: Int, col: Int, plane: Int, line: IntArray, length: Int) {
            if (encType == 1) {
                val o = planeOffset[plane] + 4 * planeWidth * row + 2 * col
                val hi = (1 shl (nBits - 1)) - 1
                val lo = -(1 shl (nBits - 1))
                for (i in 0 until length) out[o + 2 * i] = constrain(line[i], lo, hi).toShort()
            } else if (encType == 3) {
                val pb = planeBuf ?: return
                val o = plane * planeWidth * planeHeight + planeWidth * row + col
                for (i in 0 until length) pb[o + i] = line[i].toShort()
            } else if (nPlanes == 4) {
                val o = planeOffset[plane] + 4 * planeWidth * row + 2 * col
                val median = 1 shl (nBits - 1)
                val max = (1 shl nBits) - 1
                for (i in 0 until length) out[o + 2 * i] = constrain(median + line[i], 0, max).toShort()
            } else if (nPlanes == 1) {
                val o = planeWidth * row + col
                val median = 1 shl (nBits - 1)
                val max = (1 shl nBits) - 1
                for (i in 0 until length) out[o + i] = constrain(median + line[i], 0, max).toShort()
            }
        }

        /** Encoding 3: the four planes are a colour transform of R, G1, G2, B. */
        private fun convertE3(row: Int) {
            val pb = planeBuf ?: return
            val size = planeWidth * planeHeight
            val p0 = row * planeWidth
            val median = (1 shl (medianBits - 1)) shl 10
            val max = (1 shl medianBits) - 1
            val rawLine = 4 * planeWidth * row
            for (i in 0 until planeWidth) {
                val v0 = pb[p0 + i].toInt(); val v1 = pb[p0 + size + i].toInt()
                val v2 = pb[p0 + 2 * size + i].toInt(); val v3 = pb[p0 + 3 * size + i].toInt()
                var gr = median + (v0 shl 10) - 168 * v1 - 585 * v3
                gr = if (gr < 0) -(((abs(gr) + 512) shr 9) and 1.inv()) else ((abs(gr) + 512) shr 9) and 1.inv()
                out[planeOffset[0] + rawLine + 2 * i] = constrain((median + (v0 shl 10) + 1510 * v3 + 512) shr 10, 0, max).toShort()
                out[planeOffset[1] + rawLine + 2 * i] = constrain((v2 + gr + 1) shr 1, 0, max).toShort()
                out[planeOffset[2] + rawLine + 2 * i] = constrain((gr - v2 + 1) shr 1, 0, max).toShort()
                out[planeOffset[3] + rawLine + 2 * i] = constrain((median + (v0 shl 10) + 1927 * v1 + 512) shr 10, 0, max).toShort()
            }
        }

        private fun decodePlane(plane: Int) {
            var imageRow = 0
            for (tRow in 0 until tileRows) {
                var imageCol = 0
                for (tCol in 0 until tileCols) {
                    val tile = tiles[tRow * tileCols + tCol]
                    val comp = tile.comps[plane]
                    val tileMdatOffset = tile.dataOffset + tile.mdatQPDataSize + tile.mdatExtraSize + comp.dataOffset
                    setupSubbandData(comp, tile, tileMdatOffset)
                    if (levels != 0) {
                        filterInitialize(comp, tile.qStep)
                        for (i in 0 until tile.height) {
                            filterDecode(comp, levels - 1, tile.qStep)
                            filterTransform(comp, levels - 1)
                            val line = getLine(comp, levels - 1)
                            convertLine(imageRow + i, imageCol, plane, line, tile.width)
                        }
                    } else {
                        val band = comp.bands[0]
                        // (LibRaw stops the whole plane here.)
                        if (band.dataSize == 0L) { band.buf.fill(0); return }
                        for (i in 0 until tile.height) {
                            band.param!!.line(band.buf)
                            convertLine(imageRow + i, imageCol, plane, band.buf, tile.width)
                        }
                    }
                    // Free this tile's buffers before the next.
                    for (b in comp.bands) { b.param = null; b.buf = IntArray(0) }
                    comp.wavelets = emptyArray()
                    imageCol += tile.width
                }
                imageRow += tiles[tRow * tileCols].height
            }
        }

        fun decode() {
            Parallel.rows(nPlanes) { p0, p1 -> for (p in p0 until p1) decodePlane(p) }
            if (encType == 3) Parallel.rows(planeHeight) { r0, r1 -> for (r in r0 until r1) convertE3(r) }
        }
    }
}
