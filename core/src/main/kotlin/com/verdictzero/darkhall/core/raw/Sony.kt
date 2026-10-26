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

data class SonyLayout(
    override val dataOffset: Long,
    val dataBytes: Long,
    val kind: Kind,
    /** 4096-entry tone curve of compressed ARWs (identity otherwise). */
    val curve: IntArray?,
    val tileWidth: Int = 0,
    val tileLength: Int = 0,
    val tileOffsets: LongArray? = null,
) : RawLayout {
    enum class Kind {
        /** "ARW 2": 128-bit blocks of 16 same-colour pixels, 7-bit deltas over a min/max pair. */
        COMPRESSED,
        /** 14 significant bits in 16-bit little-endian words. */
        UNCOMPRESSED,
        /** Tiles of lossless JPEG, each JPEG pixel a 2x2 Bayer cell (ILCE-1, ILCE-7M4 and later). */
        LOSSLESS,
    }
}

/** Sony Alpha / Cyber-shot ARW (and SR2-era metadata). */
object SonyParser {

    fun parse(data: ByteArray): RawMetadata {
        val t = TiffRaw.read(data)
        val r = t.reader
        val ifd0 = t.ifd0
        val raw = TiffRaw.largestRawIfd(t.all) ?: throw RawFormatException(
            if (t.all.any { it.int(0x0106) == 6 && (it.int(0x0103) == 7 || it.int(0x0103) == 6) }) {
                "Medium and small lossless ARWs are not supported (they are reduced-size YCbCr images); shoot L size"
            } else {
                "ARW without raw data"
            },
        )
        val w = raw.int(0x0100)!!
        val h = raw.int(0x0101)!!
        val bps = raw.int(0x0102) ?: 14
        val comp = raw.int(0x0103) ?: 1
        val offset = raw.long(0x0111) ?: raw.long(0x0144) ?: throw RawFormatException("ARW without raw data")
        val bytes = raw.long(0x0117) ?: (data.size - offset)
        if (offset !in 8 until data.size) throw RawFormatException("Raw data offset outside the file")
        val exif = ExifInfo.read(r, ifd0)

        val tiled = raw[0x0144] != null
        val kind = when {
            (comp == 6 || comp == 7) && tiled -> SonyLayout.Kind.LOSSLESS
            comp == 32767 && bytes == w.toLong() * h -> SonyLayout.Kind.COMPRESSED
            (comp == 32767 || comp == 1) && bytes == w.toLong() * h * 2 -> SonyLayout.Kind.UNCOMPRESSED
            else -> throw RawFormatException("Unsupported ARW variant (compression $comp, $bps bits)")
        }

        // 0x7010: the four knees of the compressed-raw tone curve (any directory, last wins).
        var curve: IntArray? = null
        for (ifd in t.all) ifd[0x7010]?.ints(4)?.let { knees ->
            val k = intArrayOf(0) + knees.map { (it shr 2) and 0xFFF } + intArrayOf(4095)
            val c = IntArray(0x10000) { it }
            for (i in 0 until 5) for (j in k[i] + 1..k[i + 1]) c[j] = c[j - 1] + (1 shl i)
            curve = c
        }

        val cfa = TiffRaw.cfa(raw, exif)
        // Levels and white balance: plain tags in the raw directory on current bodies,
        // otherwise the encrypted SR2 block older ones use.
        var blackRggb = raw[0x7310]?.ints(4)
        var wbRggb = raw[0x7313]?.let { e -> DoubleArray(4) { (e.long(it) ?: 0L).toShort().toDouble() } }
        var white = raw.int(0xC61D)
        val sr2 = sr2(r, ifd0)
        sr2?.let { s ->
            if (blackRggb == null) blackRggb = s.black
            if (wbRggb == null) wbRggb = s.wb
        }
        var black = blackRggb?.let { TiffRaw.blackFromRggb(it, cfa) }
        val (tb, tw) = TiffRaw.tableLevels(ifd0.string(0x010F), ifd0.string(0x0110), black, white)
        black = tb ?: IntArray(4) { if (kind == SonyLayout.Kind.COMPRESSED) 512 else 128 shl (bps - 12) }
        white = tw ?: ((1 shl 14) - 1)
        // SR2's highlight linearity limit (15360 on 14-bit bodies): above it the response bends
        // and channels saturate unevenly, so it is the level to treat as clipped. LibRaw reports
        // it separately (linear_max) and keeps 16383 as the white point.
        sr2?.linearMax?.takeIf { it > white!! / 2 && it < white!! }?.let { white = it }

        val crop = TiffRaw.defaultCrop(raw, w, h) ?: Crop(0, 0, w, h)
        val preview = Previews.largestJpeg(r, t.all)
        return RawMetadata(
            format = RawFormat.ARW,
            make = ifd0.string(0x010F), model = ifd0.string(0x0110),
            rawWidth = w, rawHeight = h,
            cfa = cfa,
            blackLevels = black,
            whiteLevel = white!!,
            cameraWb = wbRggb?.let { TiffRaw.wbFromRggb(it) } ?: floatArrayOf(1f, 1f, 1f),
            crop = crop,
            orientation = ifd0.int(0x0112) ?: 1,
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            dateTime = exif.dateTime,
            previewOffset = preview?.offset ?: 0, previewLength = preview?.length ?: 0,
            gps = exif.gps,
            layout = SonyLayout(
                dataOffset = offset, dataBytes = bytes, kind = kind, curve = curve,
                tileWidth = raw.int(0x0142) ?: 0, tileLength = raw.int(0x0143) ?: 0,
                tileOffsets = raw[0x0144]?.let { e -> LongArray(e.count) { e.long(it) ?: 0L } },
            ),
        )
    }

    private class Sr2(val black: IntArray?, val wb: DoubleArray?, val linearMax: Int?)

    /** The SR2Private block: located from IFD0's DNGPrivateData pointer, encrypted with a per-file key. */
    private fun sr2(r: ByteReader, ifd0: Ifd): Sr2? {
        val ptr = ifd0[0xC634] ?: return null
        val privOff = (if (ptr.type == 1 || ptr.type == 7) r.u32(ptr.offset) else ptr.long() ?: return null).toInt()
        val priv = Tiff.readIfdOrNull(r, privOff) ?: return null
        val off = priv.long(0x7200)?.toInt() ?: return null
        val len = priv.long(0x7201)?.toInt() ?: return null
        // The key is stored as 4 UNDEFINED bytes on most bodies; read it as one word either way.
        val key = priv[0x7221]?.let { r.s32(it.offset) } ?: return null
        if (len <= 0 || len > 10_000_000 || off <= 0 || off + len > r.size) return null
        val buf = r.data.copyOfRange(off, off + len)
        decrypt(buf, len / 4, key)
        val b = ByteReader(buf, r.bigEndian)
        val n = b.u16(0)
        if (n > 1000 || 2 + n * 12 > len) return null
        var black: IntArray? = null
        var wb: DoubleArray? = null
        var linear: Int? = null
        for (i in 0 until n) {
            val e = 2 + i * 12
            val tag = b.u16(e)
            val count = b.u32(e + 4).toInt()
            val p = if (count * 2 <= 4) e + 8 else (b.u32(e + 8) - off).toInt()
            if (p < 0 || p + 2 * minOf(count, 4) > len) continue
            fun s(k: Int) = b.u16(p + 2 * k)
            when (tag) {
                0x7310 -> if (count >= 4) black = IntArray(4) { s(it) }
                0x7300 -> if (count >= 4 && black == null) black = IntArray(4) { s(it) }
                0x7313 -> if (count >= 4) wb = DoubleArray(4) { s(it).toShort().toDouble() }
                // GRBG order in the older tag.
                0x7303 -> if (count >= 4 && wb == null) wb = doubleArrayOf(s(1).toDouble(), s(0).toDouble(), s(3).toDouble(), s(2).toDouble())
                0x787F -> if (count >= 1) linear = (0 until minOf(count, 3)).minOf { s(it) }
            }
        }
        return Sr2(black, wb, linear)
    }

    /** Sony's SR2 stream cipher (dcraw's sony_decrypt), XORing [words] 32-bit words in place. */
    fun decrypt(buf: ByteArray, words: Int, key: Int) {
        val pad = IntArray(128)
        var k = key
        for (p in 0 until 4) {
            k = k * 48828125 + 1
            pad[p] = k
        }
        pad[3] = (pad[3] shl 1) or ((pad[0] xor pad[2]) ushr 31)
        for (p in 4 until 127) pad[p] = ((pad[p - 4] xor pad[p - 2]) shl 1) or ((pad[p - 3] xor pad[p - 1]) ushr 31)
        var p = 127
        for (i in 0 until words) {
            val v = pad[(p + 1) and 127] xor pad[(p + 65) and 127]
            pad[p and 127] = v
            val o = i * 4
            // The pad words are stored big-endian before XORing the data bytes.
            buf[o] = (buf[o].toInt() xor (v ushr 24)).toByte()
            buf[o + 1] = (buf[o + 1].toInt() xor (v ushr 16)).toByte()
            buf[o + 2] = (buf[o + 2].toInt() xor (v ushr 8)).toByte()
            buf[o + 3] = (buf[o + 3].toInt() xor v).toByte()
            p++
        }
    }
}

object SonyDecoder {

    fun decode(data: ByteArray, meta: RawMetadata): RawImage {
        val l = meta.layout as SonyLayout
        val w = meta.rawWidth
        val h = meta.rawHeight
        val out = ShortArray(w * h)
        when (l.kind) {
            SonyLayout.Kind.COMPRESSED -> arw2(data, l, w, h, out)
            SonyLayout.Kind.UNCOMPRESSED -> {
                val start = l.dataOffset.toInt()
                if (start + w.toLong() * h * 2 > data.size) throw RawFormatException("File is truncated")
                var p = start
                for (i in out.indices) {
                    out[i] = ((data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)).toShort()
                    p += 2
                }
            }
            SonyLayout.Kind.LOSSLESS -> lossless(data, l, w, h, out)
        }
        return RawImage(w, h, out, meta)
    }

    private fun arw2(data: ByteArray, l: SonyLayout, w: Int, h: Int, out: ShortArray) {
        val curve = l.curve ?: IntArray(0x10000) { it }
        val start = l.dataOffset.toInt()
        if (start + w.toLong() * h > data.size + w) throw RawFormatException("File is truncated")
        val pix = IntArray(16)
        fun u8(o: Int) = if (o < data.size) data[o].toInt() and 0xFF else 0
        for (row in 0 until h) {
            var dp = start + row * w
            var col = 0
            val base = row * w
            while (col < w - 30) {
                val v = u8(dp) or (u8(dp + 1) shl 8) or (u8(dp + 2) shl 16) or (u8(dp + 3) shl 24)
                val max = v and 0x7FF
                val min = (v ushr 11) and 0x7FF
                val imax = (v ushr 22) and 0x0F
                val imin = (v ushr 26) and 0x0F
                var sh = 0
                while (sh < 4 && (0x80 shl sh) <= max - min) sh++
                var bit = 30
                for (i in 0 until 16) {
                    pix[i] = when (i) {
                        imax -> max
                        imin -> min
                        else -> {
                            val q = dp + (bit shr 3)
                            val word = u8(q) or (u8(q + 1) shl 8)
                            val delta = (word ushr (bit and 7)) and 0x7F
                            bit += 7
                            minOf(0x7FF, (delta shl sh) + min)
                        }
                    }
                }
                for (i in 0 until 16) {
                    out[base + col] = curve[pix[i] shl 1].toShort()
                    col += 2
                }
                col -= if (col and 1 != 0) 1 else 31
                dp += 16
            }
        }
    }

    private fun lossless(data: ByteArray, l: SonyLayout, w: Int, h: Int, out: ShortArray) {
        val offsets = l.tileOffsets ?: throw RawFormatException("ARW without tiles")
        if (l.tileWidth <= 0 || l.tileLength <= 0) throw RawFormatException("Corrupt ARW tile size")
        var t = 0
        var trow = 0
        while (trow < h) {
            var tcol = 0
            while (tcol < w) {
                if (t >= offsets.size) throw RawFormatException("ARW is missing tiles")
                val jh = LjpegStream.open(data, offsets[t].toInt())
                var row = 0
                var jrow = 0
                while (jrow < jh.high && trow + row < h - 1) {
                    val rp = jh.row(jrow)
                    var col = 0
                    var jcol = 0
                    while (jcol < jh.wide && tcol + col < w - 1) {
                        val i = (trow + row) * w + tcol + col
                        out[i] = rp[jcol * 4].toShort()
                        out[i + 1] = rp[jcol * 4 + 1].toShort()
                        out[i + w] = rp[jcol * 4 + 2].toShort()
                        out[i + w + 1] = rp[jcol * 4 + 3].toShort()
                        jcol++; col += 2
                    }
                    jrow++; row += 2
                }
                t++
                tcol += l.tileWidth
            }
            trow += l.tileLength
        }
    }
}
