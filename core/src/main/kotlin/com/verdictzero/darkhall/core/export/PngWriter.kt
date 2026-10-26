package com.verdictzero.darkhall.core.export

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/**
 * PNG encoder for 8- or 16-bit RGB, written row by row. Each row gets whichever of the five
 * PNG filters leaves the smallest residuals (the usual heuristic), then zlib.
 */
object PngWriter {

    fun write(src: RowSource, file: File, text: Map<String, String> = emptyMap(), progress: ((Float) -> Unit)? = null) {
        DataOutputStream(BufferedOutputStream(file.outputStream(), 1 shl 16)).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            val ihdr = ByteArrayOutputStream()
            DataOutputStream(ihdr).apply {
                writeInt(src.width); writeInt(src.height)
                writeByte(src.bits); writeByte(2) // truecolour
                writeByte(0); writeByte(0); writeByte(0) // deflate, adaptive filtering, no interlace
            }
            chunk(out, "IHDR", ihdr.toByteArray())
            for ((k, v) in text) chunk(out, "tEXt", (k + "\u0000" + v).toByteArray(Charsets.ISO_8859_1))

            val bpp = 3 * src.bits / 8 // bytes per pixel, the filters' left neighbour distance
            val stride = src.width * bpp
            var prev = ByteArray(stride)
            var cur = ByteArray(stride)
            val samples = IntArray(3 * src.width)
            val candidates = Array(5) { ByteArray(stride) }
            val idat = IdatStream(out)
            DeflaterOutputStream(idat, Deflater(6), 1 shl 16).use { z ->
                for (y in 0 until src.height) {
                    src.row(y, samples)
                    if (src.bits == 8) {
                        for (i in samples.indices) cur[i] = samples[i].toByte()
                    } else {
                        for (i in samples.indices) { cur[2 * i] = (samples[i] shr 8).toByte(); cur[2 * i + 1] = samples[i].toByte() }
                    }
                    val best = filterRow(cur, prev, bpp, candidates)
                    z.write(best)
                    z.write(candidates[best])
                    val t = prev; prev = cur; cur = t
                    if (progress != null && y % 64 == 0) progress(y.toFloat() / src.height)
                }
            }
            idat.flushChunk()
            chunk(out, "IEND", ByteArray(0))
        }
    }

    /** Fills all five filtered versions of [cur] and returns the type with the least residual. */
    private fun filterRow(cur: ByteArray, prev: ByteArray, bpp: Int, f: Array<ByteArray>): Int {
        var bestType = 0
        var bestSum = Long.MAX_VALUE
        for (type in 0..4) {
            val o = f[type]
            var sum = 0L
            for (i in cur.indices) {
                val x = cur[i].toInt() and 0xFF
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val v = when (type) {
                    0 -> x
                    1 -> x - a
                    2 -> x - b
                    3 -> x - ((a + b) shr 1)
                    else -> x - paeth(a, b, c)
                }
                o[i] = v.toByte()
                sum += abs(o[i].toInt())
            }
            if (sum < bestSum) { bestSum = sum; bestType = type }
        }
        return bestType
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun chunk(out: DataOutputStream, type: String, data: ByteArray, len: Int = data.size) {
        out.writeInt(len)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        out.write(data, 0, len)
        val crc = CRC32()
        crc.update(t); crc.update(data, 0, len)
        out.writeInt(crc.value.toInt())
    }

    /** Collects compressed bytes into IDAT chunks of up to 256 KB. */
    private class IdatStream(val out: DataOutputStream) : OutputStream() {
        private val buf = ByteArray(1 shl 18)
        private var n = 0
        override fun write(b: Int) { if (n == buf.size) flushChunk(); buf[n++] = b.toByte() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var left = len
            while (left > 0) {
                if (n == buf.size) flushChunk()
                val k = minOf(left, buf.size - n)
                System.arraycopy(b, o, buf, n, k)
                n += k; o += k; left -= k
            }
        }
        fun flushChunk() { if (n > 0) { chunk(out, "IDAT", buf, n); n = 0 } }
        override fun close() { /* the PNG stream outlives the deflater */ }
    }
}
