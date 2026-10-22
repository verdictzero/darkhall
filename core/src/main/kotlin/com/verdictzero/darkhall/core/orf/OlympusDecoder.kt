package com.verdictzero.darkhall.core.orf

/**
 * Decoder for the raw strip of an Olympus ORF.
 *
 * The TG-7 (like every Olympus body since the E-510) writes its 12-bit sensor data
 * with Olympus' lossless adaptive predictive coding: each sample is a prediction
 * from the two previously decoded neighbours of the same colour plus a variable
 * length residual whose width adapts to recent activity. This is a direct port of
 * the public-domain algorithm in dcraw's `olympus_load_raw`.
 */
object OlympusDecoder {

    fun decode(data: ByteArray, meta: OrfMetadata): RawImage {
        return when {
            meta.isOlympusCompressed -> decodeCompressed(data, meta)
            meta.isUnpacked16 -> decodeUnpacked16(data, meta)
            else -> throw OrfParseException(
                "Unsupported ORF raw layout (bps=${meta.bitsPerSample}, compression=${meta.compression}, strip=${meta.stripBytes} bytes)"
            )
        }
    }

    /** MSB-first bit reader with a 64-bit accumulator. Reads past the end yield zero bits. */
    private class BitReader(private val d: ByteArray, private var pos: Int, private val end: Int) {
        private var acc = 0L
        private var n = 0

        private fun fill() {
            while (n <= 56) {
                val b = if (pos < end) d[pos++].toInt() and 0xFF else 0
                acc = (acc shl 8) or b.toLong()
                n += 8
            }
        }

        fun peek(k: Int): Int {
            if (n < k) fill()
            return ((acc ushr (n - k)) and ((1L shl k) - 1)).toInt()
        }

        fun skip(k: Int) { n -= k }

        fun get(k: Int): Int {
            if (k == 0) return 0
            val v = peek(k)
            n -= k
            return v
        }
    }

    private fun decodeCompressed(data: ByteArray, meta: OrfMetadata): RawImage {
        val rw = meta.rawWidth
        val rh = meta.rawHeight
        val start = meta.stripOffset.toInt() + 7 // dcraw: fseek(ifp, 7, SEEK_CUR)
        if (start < 0 || start >= data.size) throw OrfParseException("Raw strip offset out of range")
        val end = minOf(data.size.toLong(), meta.stripOffset + meta.stripBytes).toInt()
        val br = BitReader(data, start, end)
        val raw = ShortArray(rw * rh)
        val acarry = IntArray(6)
        var errors = 0
        var rowBase = 0
        for (row in 0 until rh) {
            acarry.fill(0)
            for (col in 0 until rw) {
                val ci = (col and 1) * 3
                val i = if (acarry[ci + 2] < 3) 2 else 0
                var nbits = 2 + i
                while (((acarry[ci] and 0xFFFF) ushr (nbits + i)) != 0) nbits++
                val s3 = br.get(3)
                val low = s3 and 3
                val sign = if ((s3 and 4) != 0) -1 else 0
                // Unary-coded "high" part: number of leading zeros in the next 12 bits.
                val window = br.peek(12)
                var high: Int
                if (window == 0) {
                    br.skip(12)
                    high = 12
                } else {
                    val zeros = Integer.numberOfLeadingZeros(window) - 20 // leading zeros within 12 bits
                    br.skip(zeros + 1)
                    high = zeros
                }
                if (high == 12) high = br.get(16 - nbits) ushr 1
                val c0 = (high shl nbits) or br.get(nbits)
                acarry[ci] = c0
                val diff = (c0 xor sign) + acarry[ci + 1]
                acarry[ci + 1] = (diff * 3 + acarry[ci + 1]) shr 5
                acarry[ci + 2] = if (c0 > 16) 0 else acarry[ci + 2] + 1

                val pred: Int
                if (row < 2 && col < 2) {
                    pred = 0
                } else if (row < 2) {
                    pred = raw[rowBase + col - 2].toInt() and 0xFFFF
                } else if (col < 2) {
                    pred = raw[rowBase - 2 * rw + col].toInt() and 0xFFFF
                } else {
                    val w = raw[rowBase + col - 2].toInt() and 0xFFFF
                    val nn = raw[rowBase - 2 * rw + col].toInt() and 0xFFFF
                    val nw = raw[rowBase - 2 * rw + col - 2].toInt() and 0xFFFF
                    pred = if ((w < nw && nw < nn) || (nn < nw && nw < w)) {
                        if (abs(w - nw) > 32 || abs(nn - nw) > 32) w + nn - nw else (w + nn) shr 1
                    } else {
                        if (abs(w - nw) > abs(nn - nw)) w else nn
                    }
                }
                var v = pred + ((diff shl 2) or low)
                if ((v ushr 12) != 0) {
                    errors++
                    v = v.coerceIn(0, 4095)
                }
                raw[rowBase + col] = v.toShort()
            }
            rowBase += rw
        }
        if (errors > rw * rh / 100) {
            throw OrfParseException("Raw data looks corrupt ($errors out-of-range samples)")
        }
        return RawImage(rw, rh, raw, meta)
    }

    private fun decodeUnpacked16(data: ByteArray, meta: OrfMetadata): RawImage {
        val rw = meta.rawWidth
        val rh = meta.rawHeight
        val start = meta.stripOffset.toInt()
        if (start < 0 || start + rw.toLong() * rh * 2 > data.size) throw OrfParseException("Raw strip truncated")
        val little = data[0] == 'I'.code.toByte()
        val raw = ShortArray(rw * rh)
        var p = start
        // Olympus stores 12 significant bits left-aligned in 16 (dcraw load_flags = 4).
        for (i in 0 until rw * rh) {
            val a = data[p].toInt() and 0xFF
            val b = data[p + 1].toInt() and 0xFF
            p += 2
            val v = if (little) (b shl 8) or a else (a shl 8) or b
            raw[i] = (v ushr 4).toShort()
        }
        return RawImage(rw, rh, raw, meta)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun abs(x: Int) = if (x < 0) -x else x
}
