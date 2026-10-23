package com.verdictzero.darkhall.core.raw

data class Rw2Layout(
    override val dataOffset: Long,
    /**
     * RawFormat tag: 4 (most bodies), 5 (GH5S, G9 high-res), 6 (S1/S5 generation), 7 (S1R
     * high-res), 8 (GH6, G9 II, S5 II and later).
     */
    val encoding: Int,
    val bitsPerSample: Int,
    val pana8: Pana8Tags? = null,
) : RawLayout

/** The coding parameters and stripe layout encoding 8 keeps in tags 0x39-0x48. */
class Pana8Tags(
    val tag39: LongArray, val tag3A: IntArray, val tag3B: Int, val initial: IntArray,
    val tag40a: IntArray, val tag40b: IntArray, val tag41: IntArray,
    val stripeCount: Int, val stripeOffsets: LongArray, val stripeLeft: IntArray,
    /** In bits. */
    val stripeCompressedSize: LongArray,
    val stripeWidth: IntArray, val stripeHeight: IntArray,
)

/** Panasonic (and Leica-rebranded) RW2 / RWL. */
object Rw2Parser {

    fun isRw2(data: ByteArray) = data.size > 8 && data[0] == 'I'.code.toByte() && data[1] == 'I'.code.toByte() &&
        data[2] == 0x55.toByte() && data[3] == 0.toByte()

    fun parse(data: ByteArray): RawMetadata {
        val r = ByteReader(data, bigEndian = false)
        val ifd0 = Tiff.readIfd(r, r.u32(4).toInt())
        val w = ifd0.int(0x0002) ?: throw RawFormatException("RW2 without sensor size")
        val h = ifd0.int(0x0003) ?: throw RawFormatException("RW2 without sensor size")
        if (w !in 16..TiffRaw.MAX_DIMENSION || h !in 16..TiffRaw.MAX_DIMENSION) throw RawFormatException("Implausible RW2 size ${w}x$h")
        val bpp = (ifd0.int(0x000A) ?: 12).coerceIn(8, 16)
        val encoding = ifd0.int(0x002D) ?: 4
        val offset = ifd0.long(0x0118) ?: ifd0.long(0x0111)?.takeIf { it != 0xFFFFFFFFL } ?: throw RawFormatException("RW2 without raw data")
        if (offset !in 8 until data.size) throw RawFormatException("Raw data offset outside the file")
        if (encoding !in 4..8) throw RawFormatException("Unsupported Panasonic raw encoding $encoding")
        val pana8 = if (encoding == 8) pana8Tags(r, ifd0, w, h, data.size.toLong()) else null
        if (encoding == 6 && w % 11 != 0 && w % 14 != 0) throw RawFormatException("Unsupported Panasonic raw layout")

        val cfa = when (ifd0.int(0x0009)) {
            2 -> intArrayOf(1, 0, 2, 1)
            3 -> intArrayOf(1, 2, 0, 1)
            4 -> intArrayOf(2, 1, 1, 0)
            else -> intArrayOf(0, 1, 1, 2)
        }
        // Black per colour; the original RW2 encoding stores it 15 below the real level.
        val add = if (encoding == 4) 15 else 0
        val bR = ifd0.int(0x001C); val bG = ifd0.int(0x001D); val bB = ifd0.int(0x001E)
        val blackRgb = if (bR != null && bG != null && bB != null && bR > 0 && bG > 0 && bB > 0) intArrayOf(bR + add, bG + add, bB + add) else null
        val black = IntArray(4) { p -> blackRgb?.get(cfa[p]) ?: 0 }
        // The highlight linearity limits, pulled in slightly from full scale as LibRaw does; the
        // response bends above them. (LibRaw uses them as the white point only for the newer
        // encodings and reports them separately otherwise.)
        val white = listOf(0x000E, 0x000F, 0x0010).mapNotNull { ifd0.int(it) }.filter { it > 0 }.map {
            when (it) { 16383 -> it - 64; 4095 -> it - 16; else -> it }
        }.let { if (encoding >= 6) it.maxOrNull() else it.minOrNull() } ?: ((1 shl bpp) - 1)
        val wb = run {
            val rr = ifd0.int(0x0024); val gg = ifd0.int(0x0025); val bb = ifd0.int(0x0026)
            if (rr != null && gg != null && bb != null && rr > 0 && gg > 0 && bb > 0) {
                floatArrayOf(rr.toFloat() / gg, 1f, bb.toFloat() / gg)
            } else {
                val rb = ifd0.int(0x0011); val blue = ifd0.int(0x0012)
                if (rb != null && blue != null && rb > 0 && blue > 0) floatArrayOf(rb / 256f, 1f, blue / 256f) else floatArrayOf(1f, 1f, 1f)
            }
        }
        val crop = run {
            val top = ifd0.int(0x002F) ?: ifd0.int(0x0004)
            val left = ifd0.int(0x0030) ?: ifd0.int(0x0005)
            val bottom = ifd0.int(0x0031) ?: ifd0.int(0x0006)
            val right = ifd0.int(0x0032) ?: ifd0.int(0x0007)
            if (top != null && left != null && bottom != null && right != null) Crop(left, top, right - left, bottom - top) else null
        }?.takeIf { TiffRaw.valid(it, w, h) } ?: Crop(0, 0, w, h)

        val exif = ExifInfo.read(r, ifd0)
        val jpg = ifd0[0x002E]?.let { Previews.Found(it.offset.toLong(), it.count) }
        val preview = Previews.largestJpeg(r, listOf(ifd0), listOfNotNull(jpg))
        return RawMetadata(
            format = RawFormat.RW2,
            make = ifd0.string(0x010F), model = ifd0.string(0x0110),
            rawWidth = w, rawHeight = h,
            cfa = cfa,
            blackLevels = black,
            whiteLevel = white,
            cameraWb = wb,
            crop = crop,
            orientation = ifd0.int(0x0112) ?: 1,
            iso = exif.iso.takeIf { it > 0 && it != 65535 } ?: ifd0.int(0x0017) ?: 0,
            exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            dateTime = exif.dateTime,
            previewOffset = preview?.offset ?: 0, previewLength = preview?.length ?: 0,
            gps = exif.gps,
            layout = Rw2Layout(offset, encoding, bpp, pana8),
        )
    }

    private fun pana8Tags(r: ByteReader, ifd: Ifd, w: Int, h: Int, fileSize: Long): Pana8Tags {
        // Counted arrays stored as UNDEFINED: a 16-bit count, then the values.
        fun list(tag: Int, max: Int, wide: Boolean, pairs: Boolean = false): List<Long> {
            val e = ifd[tag] ?: return emptyList()
            if (e.type != 7) return emptyList()
            val n = minOf(r.u16(e.offset), max)
            val unit = if (pairs) 4 else if (wide) 4 else 2
            return List(minOf(n, (e.count - 2) / unit)) { i ->
                val o = e.offset + 2 + i * unit
                when {
                    pairs -> r.u16(o + 2).toLong()
                    wide -> r.u32(o)
                    else -> r.u16(o).toLong()
                }
            }
        }
        fun arr(l: List<Long>, n: Int) = LongArray(n) { l.getOrElse(it) { 0L } }
        val stripes = (ifd.int(0x0042) ?: 0).coerceAtMost(5)
        val t40 = ifd[0x0040]
        val t40a = IntArray(17); val t40b = IntArray(17)
        if (t40 != null && t40.type == 7 && t40.count == 70) {
            val n = minOf(r.u16(t40.offset), 17)
            for (i in 0 until n) {
                t40a[i] = minOf(r.u16(t40.offset + 2 + 4 * i), 16)
                t40b[i] = minOf(r.u16(t40.offset + 4 + 4 * i), 0xFFF)
            }
        }
        val tags = Pana8Tags(
            tag39 = arr(list(0x0039, 6, wide = true), 6),
            tag3A = arr(list(0x003A, 6, wide = false, pairs = true), 6).map { it.toInt() }.toIntArray(),
            tag3B = ifd.int(0x003B) ?: 0,
            initial = IntArray(4) { ifd.int(0x003C + it) ?: 0 },
            tag40a = t40a, tag40b = t40b,
            tag41 = arr(list(0x0041, 17, wide = false), 17).map { minOf(it, 64L).toInt() }.toIntArray(),
            stripeCount = stripes,
            stripeOffsets = arr(list(0x0044, 5, wide = true), 5),
            stripeLeft = arr(list(0x0045, 5, wide = true), 5).map { it.toInt() }.toIntArray(),
            stripeCompressedSize = arr(list(0x0046, 5, wide = true), 5),
            stripeWidth = arr(list(0x0047, 5, wide = false), 5).map { it.toInt() }.toIntArray(),
            stripeHeight = arr(list(0x0048, 5, wide = false), 5).map { it.toInt() }.toIntArray(),
        )
        var total = 0
        if (stripes <= 0) throw RawFormatException("Panasonic raw without stripes")
        for (i in 0 until stripes) {
            if (tags.stripeHeight[i] != h || tags.stripeLeft[i] < 0 || tags.stripeLeft[i] + tags.stripeWidth[i] > w ||
                tags.stripeOffsets[i] <= 0 || tags.stripeOffsets[i] + (tags.stripeCompressedSize[i] + 7) / 8 > fileSize
            ) {
                throw RawFormatException("Corrupt Panasonic raw stripes")
            }
            total += tags.stripeWidth[i]
        }
        if (total != w) throw RawFormatException("Corrupt Panasonic raw stripes")
        return tags
    }
}

object Rw2Decoder {

    fun decode(data: ByteArray, meta: RawMetadata): RawImage {
        val l = meta.layout as Rw2Layout
        val w = meta.rawWidth
        val h = meta.rawHeight
        val out = ShortArray(w * h)
        when (l.encoding) {
            4 -> v4(data, l, w, h, out)
            5 -> v5(data, l, w, h, out)
            6 -> c6(data, l, w, h, out)
            7 -> c7(data, l, w, h, out)
            8 -> {
                val tags = l.pana8 ?: throw RawFormatException("Panasonic raw without stripes")
                val codec = Pana8Codec(tags)
                for (s in 0 until tags.stripeCount) {
                    if (!codec.decodeStripe(data, s, w, out)) throw RawFormatException("Raw data is corrupt")
                }
            }
        }
        return RawImage(w, h, out, meta)
    }

    /**
     * dcraw's pana_data: the file is read in 16 KB chunks, each rotated by 0x2008 bytes, and
     * (for the original encoding) bits are taken from the end of 16-byte groups backwards.
     */
    private class PanaBits(private val data: ByteArray, start: Int) {
        private val buf = ByteArray(0x4002)
        private var vpos = 0
        private var pos = start
        private val loadFlags = 0x2008

        private fun read(dst: Int, n: Int) {
            val k = maxOf(0, minOf(n, data.size - pos))
            if (k > 0) System.arraycopy(data, pos, buf, dst, k)
            pos += n
        }

        private fun refill() {
            read(loadFlags, 0x4000 - loadFlags)
            read(0, loadFlags)
        }

        fun bits(nb: Int): Int {
            if (vpos == 0) refill()
            vpos = (vpos - nb) and 0x1FFFF
            val byte = (vpos ushr 3) xor 0x3FF0
            val v = (buf[byte].toInt() and 0xFF) or ((buf[byte + 1].toInt() and 0xFF) shl 8)
            return (v ushr (vpos and 7)) and ((1 shl nb) - 1)
        }

        fun block(bytes: IntArray) {
            if (vpos == 0) refill()
            for (i in 0 until 16) {
                bytes[i] = buf[vpos++].toInt() and 0xFF
                vpos = vpos and 0x3FFF
            }
        }
    }

    private fun v4(data: ByteArray, l: Rw2Layout, w: Int, h: Int, out: ShortArray) {
        val pb = PanaBits(data, l.dataOffset.toInt())
        val pred = IntArray(2)
        val nonz = IntArray(2)
        var sh = 0
        for (row in 0 until h) {
            for (col in 0 until w) {
                val i = col % 14
                if (i == 0) { pred[0] = 0; pred[1] = 0; nonz[0] = 0; nonz[1] = 0 }
                if (i % 3 == 2) sh = 4 shr (3 - pb.bits(2))
                val k = i and 1
                if (nonz[k] != 0) {
                    val j = pb.bits(8)
                    if (j != 0) {
                        pred[k] -= 0x80 shl sh
                        if (pred[k] < 0 || sh == 4) pred[k] = pred[k] and ((-1 shl sh).inv())
                        pred[k] += j shl sh
                    }
                } else {
                    nonz[k] = pb.bits(8)
                    if (nonz[k] != 0 || i > 11) pred[k] = (nonz[k] shl 4) or pb.bits(4)
                }
                out[row * w + col] = pred[col and 1].toShort()
            }
        }
    }

    private fun v5(data: ByteArray, l: Rw2Layout, w: Int, h: Int, out: ShortArray) {
        val pb = PanaBits(data, l.dataOffset.toInt())
        val b = IntArray(16)
        val step = if (l.bitsPerSample == 12) 10 else 9
        for (row in 0 until h) {
            var col = 0
            val o = row * w
            while (col < w) {
                pb.block(b)
                fun put(k: Int, v: Int) { if (col + k < w) out[o + col + k] = v.toShort() }
                if (l.bitsPerSample == 12) {
                    for (k in 0 until 5) {
                        val q = k * 3
                        put(2 * k, ((b[q + 1] and 0xF) shl 8) + b[q])
                        put(2 * k + 1, 16 * b[q + 2] + (b[q + 1] shr 4))
                    }
                } else {
                    put(0, b[0] + ((b[1] and 0x3F) shl 8))
                    put(1, (b[1] shr 6) + 4 * b[2] + ((b[3] and 0xF) shl 10))
                    put(2, (b[3] shr 4) + 16 * b[4] + ((b[5] and 3) shl 12))
                    put(3, ((b[5] and 0xFC) shr 2) + (b[6] shl 6))
                    put(4, b[7] + ((b[8] and 0x3F) shl 8))
                    put(5, (b[8] shr 6) + 4 * b[9] + ((b[10] and 0xF) shl 10))
                    put(6, (b[10] shr 4) + 16 * b[11] + ((b[12] and 3) shl 12))
                    put(7, ((b[12] and 0xFC) shr 2) + (b[13] shl 6))
                    put(8, b[14] + ((b[15] and 0x3F) shl 8))
                }
                col += step
            }
        }
    }

    /** S1/S5-generation encoding: 16-byte pages of 11 (14-bit) or 14 (12-bit) pixels, rows read 16 at a time. */
    private fun c6(data: ByteArray, l: Rw2Layout, w: Int, h: Int, out: ShortArray) {
        val is12 = l.bitsPerSample == 12
        val perBlock = if (is12) 14 else 11
        val blocks = w / perBlock
        val rowBytes = blocks * 16
        val base0 = if (is12) 0x80 else 0x200
        val baseCompare = if (is12) 0x800 else 0x2000
        val spixCompare = if (is12) 0x3FFF else 0xFFFF
        val mask = if (is12) 0xFFF else 0x3FFF
        val px = IntArray(18)
        var pos = l.dataOffset.toInt()
        fun u(o: Int) = if (o < data.size) data[o].toInt() and 0xFF else 0
        var row = 0
        while (row < h - 16 + 1) {
            for (crow in 0 until 16) {
                var col = 0
                val o = (row + crow) * w
                for (blk in 0 until blocks) {
                    val p = pos
                    fun wb(i: Int) = u(p + 15 - i)
                    if (is12) {
                        px[0] = (wb(0) shl 4) or (wb(1) shr 4)
                        px[1] = (((wb(1) and 0xF) shl 8) or wb(2)) and 0xFFF
                        px[2] = (wb(3) shr 6) and 0x3
                        px[3] = ((wb(3) and 0x3F) shl 2) or (wb(4) shr 6)
                        px[4] = ((wb(4) and 0x3F) shl 2) or (wb(5) shr 6)
                        px[5] = ((wb(5) and 0x3F) shl 2) or (wb(6) shr 6)
                        px[6] = (wb(6) shr 4) and 0x3
                        px[7] = ((wb(6) and 0xF) shl 4) or (wb(7) shr 4)
                        px[8] = ((wb(7) and 0xF) shl 4) or (wb(8) shr 4)
                        px[9] = ((wb(8) and 0xF) shl 4) or (wb(9) shr 4)
                        px[10] = (wb(9) shr 2) and 0x3
                        px[11] = ((wb(9) and 0x3) shl 6) or (wb(10) shr 2)
                        px[12] = ((wb(10) and 0x3) shl 6) or (wb(11) shr 2)
                        px[13] = ((wb(11) and 0x3) shl 6) or (wb(12) shr 2)
                        px[14] = wb(12) and 0x3
                        px[15] = wb(13); px[16] = wb(14); px[17] = wb(15)
                    } else {
                        px[0] = (wb(0) shl 6) or (wb(1) shr 2)
                        px[1] = (((wb(1) and 0x3) shl 12) or (wb(2) shl 4) or (wb(3) shr 4)) and 0x3FFF
                        px[2] = (wb(3) shr 2) and 0x3
                        px[3] = ((wb(3) and 0x3) shl 8) or wb(4)
                        px[4] = (wb(5) shl 2) or (wb(6) shr 6)
                        px[5] = ((wb(6) and 0x3F) shl 4) or (wb(7) shr 4)
                        px[6] = (wb(7) shr 2) and 0x3
                        px[7] = ((wb(7) and 0x3) shl 8) or wb(8)
                        px[8] = ((wb(9) shl 2) and 0x3FC) or (wb(10) shr 6)
                        px[9] = ((wb(10) shl 4) or (wb(11) shr 4)) and 0x3FF
                        px[10] = (wb(11) shr 2) and 0x3
                        px[11] = ((wb(11) and 0x3) shl 8) or wb(12)
                        px[12] = (((wb(13) shl 2) and 0x3FC) or (wb(14) shr 6)) and 0x3FF
                        px[13] = ((wb(14) shl 4) or (wb(15) shr 4)) and 0x3FF
                    }
                    pos += 16
                    var cur = 0
                    val limit = if (is12) 18 else 14
                    fun next() = if (cur < limit) px[cur++] else 0
                    val oddeven = IntArray(2)
                    val nonzero = IntArray(2)
                    var pmul = 0
                    var pixelBase = 0
                    for (pix in 0 until perBlock) {
                        if (pix % 3 == 2) {
                            var b = next()
                            if (b == 3) b = 4
                            pixelBase = base0 shl b
                            pmul = 1 shl b
                        }
                        var e = next()
                        val k = pix % 2
                        if (oddeven[k] != 0) {
                            e *= pmul
                            if (pixelBase < baseCompare && nonzero[k] > pixelBase) e += nonzero[k] - pixelBase
                            nonzero[k] = e
                        } else {
                            oddeven[k] = e
                            if (e != 0) nonzero[k] = e else e = nonzero[k]
                        }
                        val spix = (e - 0xF).toLong() and 0xFFFFFFFFL
                        val v = if (spix <= spixCompare) (spix.toInt() and spixCompare) else ((e + 0x7FFFFFF1) shr 31) and mask
                        if (col < w) out[o + col] = v.toShort()
                        col++
                    }
                }
            }
            row += 16
        }
    }

    /** S1R high-resolution encoding: 16 bytes hold 9 (14-bit) or 10 (12-bit) pixels. */
    private fun c7(data: ByteArray, l: Rw2Layout, w: Int, h: Int, out: ShortArray) {
        val per = if (l.bitsPerSample == 14) 9 else 10
        val rowBytes = w / per * 16
        fun u(o: Int) = if (o < data.size) data[o].toInt() and 0xFF else 0
        var row = 0
        while (row < h - 16 + 1) {
            for (crow in 0 until 16) {
                var p = l.dataOffset.toInt() + (row + crow) * rowBytes
                val o = (row + crow) * w
                var col = 0
                while (col < w - per + 1) {
                    if (per == 9) {
                        out[o + col] = (u(p) + ((u(p + 1) and 0x3F) shl 8)).toShort()
                        out[o + col + 1] = ((u(p + 1) shr 6) + 4 * u(p + 2) + ((u(p + 3) and 0xF) shl 10)).toShort()
                        out[o + col + 2] = ((u(p + 3) shr 4) + 16 * u(p + 4) + ((u(p + 5) and 3) shl 12)).toShort()
                        out[o + col + 3] = (((u(p + 5) and 0xFC) shr 2) + (u(p + 6) shl 6)).toShort()
                        out[o + col + 4] = (u(p + 7) + ((u(p + 8) and 0x3F) shl 8)).toShort()
                        out[o + col + 5] = ((u(p + 8) shr 6) + 4 * u(p + 9) + ((u(p + 10) and 0xF) shl 10)).toShort()
                        out[o + col + 6] = ((u(p + 10) shr 4) + 16 * u(p + 11) + ((u(p + 12) and 3) shl 12)).toShort()
                        out[o + col + 7] = (((u(p + 12) and 0xFC) shr 2) + (u(p + 13) shl 6)).toShort()
                        out[o + col + 8] = (u(p + 14) + ((u(p + 15) and 0x3F) shl 8)).toShort()
                    } else {
                        for (k in 0 until 5) {
                            val q = p + k * 3
                            out[o + col + 2 * k] = (((u(q + 1) and 0xF) shl 8) + u(q)).toShort()
                            out[o + col + 2 * k + 1] = (16 * u(q + 2) + (u(q + 1) shr 4)).toShort()
                        }
                    }
                    col += per
                    p += 16
                }
            }
            row += 16
        }
    }
}

/**
 * Panasonic RawFormat 8 (LibRaw's pana8.cpp, itself a reconstruction of the camera's codec):
 * vertical stripes of bit-reversed 64-bit words, Huffman-coded deltas over four running
 * predictors per 2x2 cell, then an optional gamma table. Integer behaviour follows the C
 * original exactly, including its unsigned wraparounds and masked shift counts.
 */
private class Pana8Codec(private val t: Pana8Tags) {
    private val tag3B = t.tag3B
    private val huffCoeff = IntArray(17) { (t.tag41[it] shl 24) or (t.tag40a[it] shl 16) or t.tag40b[it] }
    private val table1 = LongArray(17)
    private val table2 = LongArray(17)
    private var gamma: IntArray? = null
    private var extra: ByteArray? = null

    init {
        val g = IntArray(0x10000)
        var identity = true
        for (i in 0 until 0x10000) {
            val v = gammaCurve(i)
            g[i] = v and 0xFFFF
            if (v != i) identity = false
        }
        if (!identity) gamma = g

        var maxLen = 0
        for (hi in 0 until 17) {
            val hc = huffCoeff[hi].toLong() and 0xFFFFFFFFL
            val hlow = ((hc ushr 16) and 0x1F).toInt()
            var v8: Short = 0
            if (hc and 0x1F0000L != 0L) {
                var h7 = (((hc ushr 16) and 0xFFFF) and 7).toInt()
                if (((hlow - 1).toLong() and 0xFFFFFFFFL) >= 7) {
                    var hdiff = (h7 - hlow).toLong() and 0xFFFFFFFFL
                    v8 = 0
                    do {
                        v8 = ((v8.toInt() shl 8) or 0xFF).toShort()
                        hdiff = (hdiff + 8) and 0xFFFFFFFFL
                    } while (hdiff != 0L)
                } else {
                    v8 = 0
                }
                while (h7 != 0) { v8 = (2 * v8 + 1).toShort(); h7-- }
            }
            val v9 = (hc and (v8.toLong() and 0xFFFFFFFFL)) and 0xFFFF
            if (maxLen < hlow) maxLen = hlow
            table2[hi] = 0xFFFFL shl (64 - hlow)
            table1[hi] = v9 shl (64 - hlow)
        }
        if (maxLen < 17) {
            val e = ByteArray(0x10000)
            var v = 0L
            for (j in 0 until 0x10000) {
                e[j] = (dBit(v) and 0xFF).toByte()
                v += 0x1000000000000L
            }
            extra = e
        }
    }

    private fun dBit(a: Long): Int {
        for (i in 0 until 16) if (a and table2[i] == table1[i]) return i
        return (if (table2[16] and a == table1[16]) 1 else 0) xor 0x11
    }

    private fun gammaCurve(idx: Int): Int {
        val v2 = if (idx and 0x10000 == 0) idx and 0x1FFFF else idx or 0xFFFF0000.toInt()
        var v4 = minOf(v2, 0xFFFF).toLong() and 0xFFFFFFFFL
        if (v4 and 0x80000000L != 0L) v4 = 0
        fun a(i: Int) = (t.tag3A[i] and 0xFFFF).toLong()
        var v5 = 0
        if (v4 >= a(1)) {
            v5 = 1
            if (v4 >= a(2)) {
                v5 = 2
                if (v4 >= a(3)) {
                    v5 = 3
                    if (v4 >= a(4)) v5 = if (v4 >= a(5)) 5 else 4
                }
            }
        }
        val v6 = t.tag3A[v5].toLong() and 0xFFFFFFFFL
        val v7 = t.tag39[v5].toInt()
        var v8 = (v4 - (v6 and 0xFFFF)) and 0xFFFFFFFFL
        val v9 = v7 and 0x1F
        val b = tag3B.toLong()
        if (v9 == 31) {
            val result = if (v5 == 5) 0xFFFFL else ((t.tag3A[v5 + 1].toLong() ushr 16) and 0xFFFF)
            return minOf(result, b).toInt()
        }
        if (v7 and 0x10 == 0) {
            if (v9 == 15) return minOf((v6 ushr 16) and 0xFFFF, b).toInt()
            if (v9 != 0) v8 = (((v8 + (1L shl (v9 - 1))) and 0xFFFFFFFFL) ushr v9)
        } else {
            v8 = (v8 shl (v7 and 0xF)) and 0xFFFFFFFFL
        }
        val result = (v8 + ((v6 ushr 16) and 0xFFFF)) and 0xFFFFFFFFL
        return minOf(result, b).toInt()
    }

    fun decodeStripe(data: ByteArray, s: Int, rawWidth: Int, out: ShortArray): Boolean {
        val width = t.stripeWidth[s]
        val height = t.stripeHeight[s]
        val left = t.stripeLeft[s]
        val halfWidth = width shr 1
        val halfHeight = height shr 1
        val exactBytes = (t.stripeCompressedSize[s] + 7) / 8
        val jobQwords = ((exactBytes + 7) / 8 * 8) shr 3
        if (halfWidth == 0 || halfHeight == 0 || jobQwords * 8 < 9) return false
        val base = t.stripeOffsets[s]
        fun q(i: Long): Long {
            val o = base + i * 8
            var v = 0L
            for (k in 7 downTo 0) {
                val p = o + k
                v = (v shl 8) or (if (p < data.size) (data[p.toInt()].toLong() and 0xFF) else 0L)
            }
            return java.lang.Long.reverse(v)
        }
        var datamax = tag3B
        val ex = extra
        val doubleWidth = 4 * halfWidth
        val outline = IntArray(doubleWidth)
        val lineBase = IntArray(4) { t.initial[it] and 0xFFFF }
        val cur = IntArray(4)
        var bittail = 0L
        var bitportion = 0
        var inq = 0L
        val g = gamma
        for (row in 0 until halfHeight) {
            System.arraycopy(lineBase, 0, cur, 0, 4)
            for (col in 0 until doubleWidth) {
                val pixbits: Long
                if (bitportion < 0) {
                    val next = inq + 1
                    if (inq + 1 >= jobQwords) return false
                    bitportion += 64
                    pixbits = (q(next) ushr bitportion) or (q(inq) shl (64 - (bitportion and 0xFF)))
                    if (inq < jobQwords) inq = next
                } else {
                    if (inq >= jobQwords) return false
                    pixbits = (q(inq) ushr bitportion) or bittail
                    val step = if (bitportion == 0) 1 else 0
                    if (bitportion == 0) bitportion = 64
                    inq += step
                }
                val hi = if (ex != null) {
                    ex[((pixbits ushr 48) and 0xFFFF).toInt()].toInt() and 0xFF
                } else {
                    datamax = tag3B
                    dBit(pixbits)
                }
                val hc = huffCoeff[hi]
                val v37 = (hc ushr 24) and 0x1F
                val v38 = pixbits shl ((hc ushr 16) and 0x1F)
                var v90 = (hi - v37).toLong() and 0xFFFFFFFFL
                var v39 = ((v38 ushr ((v37 and 0xFF) - (hi and 0xFF))) and 0xFFFF).toInt() shl ((hc ushr 24) and 0xFF)
                if (hi - v37 <= 0) v39 = v39 and 0xFFFF0000.toInt()
                val delta1 = when {
                    v38 < 0 -> v39 and 0xFFFF
                    hi != 0 -> {
                        val v40 = -1 shl hi
                        if (v37 and 0xFF != 0) (v39 and 0xFFFF) + v40 else (v39 and 0xFFFF) + v40 + 1
                    }
                    else -> 0
                }
                val v42 = (bitportion - ((hc ushr 16) and 0x1F)).toLong() and 0xFFFFFFFFL
                val delta2 = if (v37 and 0xFF != 0) 1 shl (v37 - 1) else 0
                val k = when (col and 3) { 2 -> 1; 1 -> 2; 3 -> 3; else -> 0 }
                val cell = (col shr 2) * 4
                val v = cur[k] + delta1 + delta2
                outline[cell + k] = if (v < 0) 0 else if (v > datamax) datamax else v
                if (hi <= v37) v90 = 0L
                bittail = v38 shl v90.toInt()
                bitportion = (v42 - v90).toInt()
                if (col and 3 == 3) System.arraycopy(outline, cell, cur, 0, 4)
                if (col == 3) System.arraycopy(outline, 0, lineBase, 0, 4)
            }
            val r0 = (row * 2) * rawWidth + left
            val r1 = r0 + rawWidth
            var col = 0
            while (col < width - 1) {
                val e = 2 * col
                fun px(v: Int) = (if (g != null) g[v and 0xFFFF] else v and 0xFFFF).toShort()
                out[r0 + col] = px(outline[e])
                out[r0 + col + 1] = px(outline[e + 1])
                out[r1 + col] = px(outline[e + 2])
                out[r1 + col + 1] = px(outline[e + 3])
                col += 2
            }
        }
        return true
    }
}
