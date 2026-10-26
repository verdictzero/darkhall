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
 * MSB-first bit reader with a 64-bit accumulator. Reads past [end] yield zero bits, so a
 * truncated file decodes to black instead of crashing; the decoders bound the damage.
 *
 * With [jpegStuffing] it follows the JPEG entropy-coded-segment rules: a 0xFF data byte is
 * followed by a 0x00 that is dropped, and any other byte after 0xFF is a marker, which stops
 * the stream (zeros are returned) and leaves [pos] on the 0xFF.
 */
class BitReader(
    private val d: ByteArray,
    start: Int,
    private val end: Int = d.size,
    private val jpegStuffing: Boolean = false,
) {
    var pos = start
        private set
    private var acc = 0L
    private var n = 0
    private var atMarker = false

    private fun fill() {
        while (n <= 56) {
            var b = 0
            if (pos < end && !atMarker) {
                b = d[pos].toInt() and 0xFF
                if (jpegStuffing && b == 0xFF) {
                    val next = if (pos + 1 < end) d[pos + 1].toInt() and 0xFF else 0
                    if (next == 0) pos += 2 else { atMarker = true; b = 0 }
                } else {
                    pos++
                }
            }
            acc = (acc shl 8) or b.toLong()
            n += 8
        }
    }

    fun peek(k: Int): Int {
        if (n < k) fill()
        return ((acc ushr (n - k)) and ((1L shl k) - 1)).toInt()
    }

    fun skip(k: Int) {
        if (n < k) fill()
        n -= k
    }

    fun get(k: Int): Int {
        if (k == 0) return 0
        val v = peek(k)
        n -= k
        return v
    }

    /** Drops buffered bits and continues at byte [p] (used for JPEG restart markers). */
    fun reset(p: Int) {
        pos = p
        acc = 0
        n = 0
        atMarker = false
    }
}

/**
 * Huffman table in dcraw's lookup form: [maxLen] bits are peeked and each table entry
 * holds `codeLength shl 8 or symbol`.
 */
class HuffTable(counts: IntArray, symbols: IntArray) {
    val maxLen: Int
    private val table: IntArray

    init {
        var max = 16
        while (max > 0 && counts[max - 1] == 0) max--
        maxLen = maxOf(max, 1)
        table = IntArray(1 shl maxLen)
        var h = 0
        var s = 0
        for (len in 1..max) {
            for (i in 0 until counts[len - 1]) {
                val sym = if (s < symbols.size) symbols[s] else 0
                s++
                for (j in 0 until (1 shl (max - len))) {
                    if (h < table.size) table[h++] = (len shl 8) or sym
                }
            }
        }
    }

    fun decode(br: BitReader): Int {
        val e = table[br.peek(maxLen)]
        br.skip(e ushr 8)
        return e and 0xFF
    }

    companion object {
        /**
         * Builds a table from dcraw's packed form: 16 code-length counts followed by the symbols
         * (dcraw's tables are zero-padded arrays, so missing trailing symbols are 0).
         */
        fun fromSpec(spec: IntArray): HuffTable {
            val counts = spec.copyOfRange(0, 16)
            return HuffTable(counts, IntArray(counts.sum()) { spec.getOrElse(16 + it) { 0 } })
        }
    }
}

/** A lossless JPEG (ITU T.81 process 14) stream, as used by DNG, CR2 and some Sony ARWs. */
class LjpegStream private constructor(
    private val data: ByteArray,
    val bits: Int,
    val high: Int,
    val wide: Int,
    val clrs: Int,
    private val psv: Int,
    private val restart: Int,
    private val tables: Array<HuffTable>,
    private val scanStart: Int,
    private val end: Int,
    private val legacyDiff16: Boolean,
) {
    private val br = BitReader(data, scanStart, end, jpegStuffing = true)
    private val vpred = IntArray(clrs)
    private var prev = IntArray(wide * clrs)
    private var cur = IntArray(wide * clrs)

    private fun diff(t: HuffTable): Int {
        val len = t.decode(br)
        if (len == 16 && !legacyDiff16) return -32768
        if (len == 0) return 0
        var d = br.get(len)
        if (d and (1 shl (len - 1)) == 0) d -= (1 shl len) - 1
        return d
    }

    /** Decodes row [jrow] (rows must be read in order) and returns its `wide * clrs` samples. */
    fun row(jrow: Int): IntArray {
        val t = prev; prev = cur; cur = t
        val row = cur
        val up = prev
        if (jrow == 0 || (restart > 0 && (jrow.toLong() * wide) % restart == 0L)) {
            vpred.fill(1 shl (bits - 1))
            if (jrow > 0) {
                // Skip to just after the next RSTn marker.
                var p = br.pos
                while (p + 1 < end && !(data[p] == 0xFF.toByte() && (data[p + 1].toInt() and 0xF8) == 0xD0)) p++
                br.reset(minOf(p + 2, end))
            }
        }
        val c = clrs
        for (k in 0 until c) {
            val d = diff(tables[k])
            val pred = vpred[k]
            vpred[k] += d
            row[k] = (pred + d) and 0xFFFF
        }
        var i = c
        val n = wide * c
        if (jrow == 0 || psv == 1) {
            while (i < n) {
                for (k in 0 until c) {
                    row[i] = (row[i - c] + diff(tables[k])) and 0xFFFF
                    i++
                }
            }
        } else {
            while (i < n) {
                for (k in 0 until c) {
                    val d = diff(tables[k])
                    val left = row[i - c]
                    val above = up[i]
                    val diag = up[i - c]
                    val pred = when (psv) {
                        2 -> above
                        3 -> diag
                        4 -> left + above - diag
                        5 -> left + ((above - diag) shr 1)
                        6 -> above + ((left - diag) shr 1)
                        7 -> (left + above) shr 1
                        else -> 0
                    }
                    row[i] = (pred + d) and 0xFFFF
                    i++
                }
            }
        }
        return row
    }

    companion object {
        /**
         * Parses the JPEG headers at [start] up to the start of scan. [quirkSof9] reproduces
         * dcraw skipping a stray byte after an 11-byte SOF segment in non-DNG files.
         */
        fun open(
            data: ByteArray,
            start: Int,
            end: Int = data.size,
            isDng: Boolean = false,
            legacyDiff16: Boolean = false,
        ): LjpegStream {
            fun u8(o: Int): Int {
                if (o >= end) throw RawFormatException("Lossless JPEG stream is truncated")
                return data[o].toInt() and 0xFF
            }
            if (u8(start) != 0xFF || u8(start + 1) != 0xD8) throw RawFormatException("Raw data is not a lossless JPEG stream")
            var p = start + 2
            val huff = arrayOfNulls<HuffTable>(4)
            var bits = 0; var high = 0; var wide = 0; var clrs = 0
            var psv = 1; var restart = 0
            val compIds = IntArray(4)
            var tableOfComp = intArrayOf(0, 1, 2, 3)
            var guard = 0
            while (true) {
                if (guard++ > 1024) throw RawFormatException("Lossless JPEG header is corrupt")
                if (u8(p) != 0xFF) throw RawFormatException("Lossless JPEG header is corrupt")
                val tag = u8(p + 1)
                val len = ((u8(p + 2) shl 8) or u8(p + 3)) - 2
                val seg = p + 4
                if (len < 0 || seg + len > end) throw RawFormatException("Lossless JPEG header is truncated")
                when (tag) {
                    0xC3 -> {
                        bits = u8(seg)
                        high = (u8(seg + 1) shl 8) or u8(seg + 2)
                        wide = (u8(seg + 3) shl 8) or u8(seg + 4)
                        clrs = u8(seg + 5)
                        for (k in 0 until minOf(clrs, 4)) compIds[k] = u8(seg + 6 + 3 * k)
                        // dcraw skips a stray byte after an 11-byte SOF in non-DNG files.
                        p = seg + len + if (len == 9 && !isDng) 1 else 0
                        continue
                    }
                    0xC0, 0xC1, 0xC2 -> throw RawFormatException("Lossy JPEG raw data is not supported")
                    0xC4 -> {
                        var q = seg
                        while (q < seg + len) {
                            val id = u8(q)
                            if (id and 0xEC != 0) break
                            val counts = IntArray(16) { u8(q + 1 + it) }
                            val total = counts.sum()
                            val syms = IntArray(total) { u8(q + 17 + it) }
                            if ((id and 0x10) == 0 && (id and 3) < 4) huff[id and 3] = HuffTable(counts, syms)
                            q += 17 + total
                        }
                    }
                    0xDA -> {
                        val ns = u8(seg)
                        tableOfComp = IntArray(4) { k ->
                            if (k < ns) {
                                val td = u8(seg + 2 + 2 * k) shr 4
                                td and 3
                            } else {
                                k
                            }
                        }
                        psv = u8(seg + 1 + ns * 2)
                        bits -= u8(seg + 3 + ns * 2) and 15
                        p = seg + len
                        break
                    }
                    0xDD -> restart = (u8(seg) shl 8) or u8(seg + 1)
                }
                p = seg + len
            }
            if (bits !in 1..16 || high <= 0 || wide <= 0 || clrs !in 1..4) {
                throw RawFormatException("Unsupported lossless JPEG ($clrs components, $bits bits)")
            }
            // Fill gaps the way dcraw does, then pick each component's table from the scan header.
            for (k in 1 until 4) if (huff[k] == null) huff[k] = huff[k - 1]
            val first = huff.firstOrNull { it != null } ?: throw RawFormatException("Lossless JPEG has no Huffman table")
            for (k in 0 until 4) if (huff[k] == null) huff[k] = first
            val tables = Array(clrs) { k -> huff[tableOfComp[k]]!! }
            return LjpegStream(data, bits, high, wide, clrs, psv, restart, tables, p, end, legacyDiff16)
        }
    }
}
