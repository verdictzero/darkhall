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

data class Cr2Layout(
    override val dataOffset: Long,
    val dataEnd: Long,
    /** Canon's vertical slicing of the lossless JPEG: [count, width, last width], or null. */
    val slices: IntArray?,
) : RawLayout

/** Canon CR2 (EOS bodies 2004-2018 and PowerShots of that era). */
object Cr2Parser {

    private val canonModels: Map<Long, String> by lazy {
        val s = Cr2Parser::class.java.getResourceAsStream("/darkhall/canon_models.tsv") ?: return@lazy emptyMap()
        s.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
                val f = l.split('\t')
                f.getOrNull(1)?.let { f[0].toLong(16) to it }
            }.toMap()
        }
    }

    fun parse(data: ByteArray): RawMetadata {
        val t = TiffRaw.read(data)
        val r = t.reader
        val ifd0 = t.ifd0
        // The raw image is the lossless JPEG strip (IFD3 in every CR2).
        var raw: Ifd? = null
        var jh: LjpegStream? = null
        for (ifd in t.roots.reversed()) {
            val off = ifd.long(0x0111)?.toInt() ?: continue
            val len = ifd.long(0x0117) ?: continue
            if (off <= 0 || off + 4 > data.size) continue
            val s = runCatching { LjpegStream.open(data, off, minOf(data.size.toLong(), off + len).toInt()) }.getOrNull() ?: continue
            raw = ifd; jh = s
            break
        }
        if (raw == null || jh == null) throw RawFormatException("CR2 without lossless raw data")
        if (jh.clrs == 3) throw RawFormatException("Canon sRAW and mRAW files are not supported; shoot full RAW")
        val exif = ExifInfo.read(r, ifd0)
        val mn = exif.makerNote?.let { Tiff.readIfdOrNull(r, it.offset) }
        val sensor = mn?.get(0x00E0)?.takeIf { it.count >= 17 }?.let { e -> IntArray(17) { e.int(it) ?: 0 } }
        var w = jh.wide * jh.clrs
        var h = jh.high
        // Four-component streams on some bodies (6D Mark II, 77D...) cover two sensor rows per JPEG row.
        val sensorWidth = sensor?.get(1) ?: 0
        if (jh.clrs == 4 && sensorWidth > 0 && w >= 2 * sensorWidth) { w /= 2; h *= 2 }
        if (w !in 16..TiffRaw.MAX_DIMENSION || h !in 16..TiffRaw.MAX_DIMENSION) throw RawFormatException("Implausible CR2 size ${w}x$h")
        val slices = raw[0xC640]?.ints(3)?.takeIf { it[0] >= 0 && it[1] > 0 && it[2] > 0 }
        val make = ifd0.string(0x010F)
        var model = ifd0.string(0x0110)
        // US and Japanese names (Rebel, Kiss) map to the international one the colour table uses.
        mn?.long(0x0010)?.let { id -> canonModels[id]?.let { model = "Canon $it" } }

        val cfa = exif.cfa ?: intArrayOf(0, 1, 1, 2)
        val colorData = mn?.get(0x4001)?.takeIf { it.type == 3 && it.count > 500 }?.let { colorData(r, it, jh.bits) }
        // SensorInfo: [1] width, [2] height, [5..8] default crop l/t/r/b, [9..12] left optical black.
        val crop = sensor?.let { s -> Crop(s[5], s[6], s[7] - s[5] + 1, s[8] - s[6] + 1) }?.takeIf { TiffRaw.valid(it, w, h) }
            ?: Crop(0, 0, w, h)

        val black = colorData?.blackRggb?.let { TiffRaw.blackFromRggb(it, cfa) }
            ?: maskedBlack(data, raw, jh, slices, w, h, sensor, crop)
        val (_, tableWhite) = TiffRaw.tableLevels(make, model, null, null)
        // Canon's specular white level (it varies with ISO) is where the sensor actually saturates;
        // LibRaw reports it as the linearity limit and keeps the table/full-scale white point.
        val fullWhite = tableWhite ?: ((1 shl jh.bits) - 1)
        val white = colorData?.specularWhite?.takeIf { it > fullWhite / 2 && it < fullWhite } ?: fullWhite
        val preview = Previews.largestJpeg(r, t.roots)
        return RawMetadata(
            format = RawFormat.CR2,
            make = make, model = model,
            rawWidth = w, rawHeight = h,
            cfa = cfa,
            blackLevels = black,
            whiteLevel = white,
            cameraWb = colorData?.wbRggb?.let { TiffRaw.wbFromRggb(it) } ?: floatArrayOf(1f, 1f, 1f),
            crop = crop,
            orientation = ifd0.int(0x0112) ?: 1,
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            dateTime = exif.dateTime,
            previewOffset = preview?.offset ?: 0, previewLength = preview?.length ?: 0,
            gps = exif.gps,
            layout = Cr2Layout(raw.long(0x0111)!!, raw.long(0x0111)!! + raw.long(0x0117)!!, slices),
        )
    }

    private class ColorData(val wbRggb: DoubleArray?, val blackRggb: IntArray?, val specularWhite: Int?)

    /** ColorData (MakerNote 0x4001): as-shot levels and per-channel black, at offsets that depend on its length. */
    private fun colorData(r: ByteReader, e: TiffEntry, bits: Int): ColorData {
        val base = e.offset
        fun s(i: Int) = if (i < e.count) r.u16(base + 2 * i) else 0
        val sub = s(0)
        var wb = -1
        var black = -1
        var black2 = -1
        var white = -1
        when (e.count) {
            582 -> { wb = 0x19; black = 0xA6 }
            653 -> { wb = 0x22; black = 0x11E }
            796 -> { wb = 0x3F; black = 0xC4 }
            674, 692, 702, 1227, 1250, 1251, 1337, 1338, 1346 -> {
                wb = 0x3F
                black = when (sub) { 4, 5 -> 0x2B4; 6, 7 -> 0x2CB; 9 -> 0x2CF; else -> 0xE7 }
            }
            5120 -> { wb = 0x47; black = if (sub == 0xFFFC) 0x14D else 0x108; if (sub == 0xFFFC) white = 0x569 }
            1273, 1275 -> { wb = 0x3F; black = 0x1DF }
            1312, 1313, 1316, 1506 -> { wb = 0x3F; black = if (sub == 11) 0x2D8 else 0x1F8 }
            1560, 1592, 1353, 1602 -> { wb = 0x3F; black = if (sub == 14) 0x22C else 0x30A }
            1820, 1824, 1816 -> { wb = 0x47; black = 0x47 + 0x2D1; black2 = 0x47 + 0x102 }
            1770, 2024, 3656 -> { wb = 0x55; black = 0x55 + 0x2D1; black2 = 0x55 + 0x102 }
            3973, 3778 -> { wb = 0x69; black = 0x69 + 0x213; black2 = 0x69 + 0x102 }
        }
        // Normal and specular white follow the four black levels on every version that has them.
        val hasWhite = e.count !in setOf(582, 653, 796, 5120) &&
            !(e.count in setOf(674, 692, 702, 1227, 1250, 1251, 1337, 1338, 1346) && sub !in setOf(4, 5, 6, 7, 9))
        if (white < 0 && hasWhite && black >= 0) white = black + 4
        var specular = if (white >= 0) s(white + 1) else 0
        val wbV = if (wb >= 0) DoubleArray(4) { s(wb + it).toDouble() } else null
        var bl = if (black >= 0) IntArray(4) { s(black + it) } else null
        if ((bl == null || bl.all { it == 0 }) && black2 >= 0) bl = IntArray(4) { s(black2 + it) }
        // 14-bit metadata on a 12-bit body.
        if (bits < 14 && specular > (1 shl bits) - 1) {
            bl = bl?.let { v -> IntArray(4) { v[it] shr (14 - bits) } }
            specular = specular shr (14 - bits)
        } else if (bl != null && bits < 14 && bl.any { it > (1 shl bits) }) {
            bl = IntArray(4) { bl!![it] shr (14 - bits) }
        }
        return ColorData(wbV, bl?.takeIf { v -> v.any { it > 0 } }, specular.takeIf { it > 0 })
    }

    /** Average of the left optical-black columns per CFA position, when the maker note gives no levels. */
    private fun maskedBlack(data: ByteArray, raw: Ifd, jh: LjpegStream, slices: IntArray?, w: Int, h: Int, sensor: IntArray?, crop: Crop): IntArray {
        val left = sensor?.getOrNull(11)?.takeIf { it in 4 until w } ?: crop.left
        if (left < 6) return IntArray(4)
        val img = Cr2Decoder.decodeRows(data, raw.long(0x0111)!!.toInt(), (raw.long(0x0111)!! + raw.long(0x0117)!!).toInt(), slices, w, h)
        val sum = LongArray(4); val n = LongArray(4)
        for (row in crop.top until crop.top + crop.height) for (col in 2 until left - 2) {
            val p = ((row and 1) shl 1) or (col and 1)
            sum[p] += img[row * w + col].toLong() and 0xFFFF; n[p]++
        }
        return IntArray(4) { if (n[it] > 0) (sum[it] / n[it]).toInt() else 0 }
    }
}

object Cr2Decoder {

    fun decode(data: ByteArray, meta: RawMetadata): RawImage {
        val l = meta.layout as Cr2Layout
        val out = decodeRows(data, l.dataOffset.toInt(), minOf(data.size.toLong(), l.dataEnd).toInt(), l.slices, meta.rawWidth, meta.rawHeight)
        return RawImage(meta.rawWidth, meta.rawHeight, out, meta)
    }

    /** dcraw's lossless_jpeg_load_raw: JPEG samples run down vertical slices of the sensor. */
    internal fun decodeRows(data: ByteArray, start: Int, end: Int, slices: IntArray?, w: Int, h: Int): ShortArray {
        val jh = LjpegStream.open(data, start, end)
        val out = ShortArray(w * h)
        val jwide = jh.wide * jh.clrs
        var row = 0
        var col = 0
        for (jrow in 0 until jh.high) {
            val rp = jh.row(jrow)
            for (jcol in 0 until jwide) {
                if (slices != null) {
                    var jidx = jrow * jwide + jcol
                    var i = jidx / (slices[1] * h)
                    val j = if (i >= slices[0]) 1 else 0
                    if (j == 1) i = slices[0]
                    jidx -= i * (slices[1] * h)
                    val sw = slices[1 + j]
                    row = jidx / sw
                    col = jidx % sw + i * slices[1]
                }
                if (row in 0 until h && col < w) out[row * w + col] = rp[jcol].toShort()
                if (++col >= w) { col = 0; row++ }
            }
        }
        return out
    }
}
