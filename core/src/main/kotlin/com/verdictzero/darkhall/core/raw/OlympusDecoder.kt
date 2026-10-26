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

    fun decode(data: ByteArray, meta: RawMetadata): RawImage = try {
        decodeUnchecked(data, meta)
    } catch (e: RawFormatException) {
        throw e
    } catch (e: RuntimeException) {
        throw RawFormatException("Raw data is corrupt (${e.javaClass.simpleName})", e)
    }

    private fun decodeUnchecked(data: ByteArray, meta: RawMetadata): RawImage {
        if (meta.crop.left + meta.crop.width > meta.rawWidth || meta.crop.top + meta.crop.height > meta.rawHeight) {
            throw RawFormatException("Crop outside sensor area")
        }
        val layout = meta.layout as? OrfLayout ?: throw RawFormatException("Not an ORF layout")
        return when {
            layout.compressed -> decodeCompressed(data, meta, layout)
            layout.dataBytes >= meta.rawWidth.toLong() * meta.rawHeight * 2 -> decodeUnpacked16(data, meta, layout)
            else -> throw RawFormatException("Unsupported ORF raw layout (strip of ${layout.dataBytes} bytes)")
        }
    }

    private fun decodeCompressed(data: ByteArray, meta: RawMetadata, layout: OrfLayout): RawImage {
        val rw = meta.rawWidth
        val rh = meta.rawHeight
        val start = layout.dataOffset.toInt() + 7 // dcraw: fseek(ifp, 7, SEEK_CUR)
        if (start < 0 || start >= data.size) throw RawFormatException("Raw strip offset out of range")
        val end = minOf(data.size.toLong(), layout.dataOffset + layout.dataBytes).toInt()
        // A file cut short (card pulled mid-copy) decodes as long as most of the strip is there;
        // the missing tail reads as zero bits and is caught by the error count below.
        if (end - start < layout.dataBytes / 2) throw RawFormatException("File is truncated")
        // Every compressed sample costs at least 6 bits, so the strip bounds the pixel count.
        // This stops a corrupt width/height from allocating hundreds of megabytes.
        if (rw.toLong() * rh > (end - start).toLong() * 8 / 3) throw RawFormatException("Raw size does not match the data")
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
            throw RawFormatException("Raw data looks corrupt ($errors out-of-range samples)")
        }
        return RawImage(rw, rh, raw, meta)
    }

    private fun decodeUnpacked16(data: ByteArray, meta: RawMetadata, layout: OrfLayout): RawImage {
        val rw = meta.rawWidth
        val rh = meta.rawHeight
        val start = layout.dataOffset.toInt()
        if (start < 0 || start + rw.toLong() * rh * 2 > data.size) throw RawFormatException("Raw strip truncated")
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
