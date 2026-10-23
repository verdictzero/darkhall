package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.raw.OlympusDecoder
import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.raw.RawFormatException
import com.verdictzero.darkhall.core.raw.OrfParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Feeds truncated and corrupted copies of a real ORF to the parser and decoder.
 * Every outcome must be either a decoded image or an [RawFormatException];
 * anything else (index errors, huge allocations) would crash the app.
 */
class FuzzTest {

    private val samplePath = System.getProperty("darkhall.sample").orEmpty()

    private var baseline: RawMetadata? = null

    /** Parses [bytes]; decodes too unless [decodeOnlyIfLayoutChanged] and the raw layout matches the original. */
    private fun attempt(bytes: ByteArray, label: String, decodeOnlyIfLayoutChanged: Boolean = false) {
        try {
            val meta = OrfParser.parse(bytes)
            val b = baseline
            if (decodeOnlyIfLayoutChanged && b != null && b.rawWidth == meta.rawWidth && b.rawHeight == meta.rawHeight &&
                b.layout == meta.layout && b.crop == meta.crop
            ) return
            val raw = OlympusDecoder.decode(bytes, meta)
            assertEquals(meta.rawWidth * meta.rawHeight, raw.data.size, label)
        } catch (e: RawFormatException) {
            // expected for many corruptions
        } catch (e: Throwable) {
            fail("$label: ${e.javaClass.name}: ${e.message}")
        }
    }

    @Test
    fun truncatedFiles() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val bytes = File(samplePath).readBytes()
        val cuts = listOf(0, 3, 7, 8, 16, 100, 400, 2_000, 4_000, 30_000, 60_000, 1_000_000,
            1_514_496, 1_514_500, 4_000_000, 8_000_000, bytes.size - 1_000_000, bytes.size - 10)
        for (n in cuts) if (n <= bytes.size) attempt(bytes.copyOf(n), "truncated to $n")
    }

    @Test
    fun corruptedHeaders() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val original = File(samplePath).readBytes()
        val rnd = Random(1234)
        val metaEnd = 1_514_496 // everything before the raw strip: IFDs, EXIF, MakerNote
        repeat(400) { i ->
            val b = original.copyOf()
            val flips = 1 + rnd.nextInt(12)
            repeat(flips) {
                // Bias towards the first 20 KB, where the IFD tables live.
                val pos = if (rnd.nextBoolean()) rnd.nextInt(20_000) else rnd.nextInt(metaEnd)
                b[pos] = rnd.nextInt(256).toByte()
            }
            attempt(b, "header corruption #$i")
        }
    }

    /** Byte offsets of every 12-byte IFD entry in IFD0, the EXIF IFD and the MakerNote's IFDs. */
    private fun entryOffsets(b: ByteArray): List<Int> {
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        val out = ArrayList<Int>()
        fun walk(off: Int, base: Int, follow: (Int, Int) -> Unit) {
            val n = u16(off)
            for (i in 0 until n) {
                val e = off + 2 + i * 12
                out += e
                follow(u16(e), e)
            }
        }
        walk(u32(4).toInt(), 0) { tag, e ->
            if (tag == 0x8769) walk(u32(e + 8).toInt(), 0) { t2, e2 ->
                if (t2 == 0x927C) {
                    val mn = u32(e2 + 8).toInt()
                    val head = String(b, mn, 9, Charsets.ISO_8859_1)
                    val ifd = if (head == "OM SYSTEM") mn + 16 else mn + 12
                    walk(ifd, mn) { t3, e3 ->
                        if (t3 in 0x2010..0x2050) walk(mn + u32(e3 + 8).toInt(), mn) { _, _ -> }
                    }
                }
            }
        }
        return out
    }

    @Test
    fun hostileIfdEntries() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val original = File(samplePath).readBytes()
        baseline = OrfParser.parse(original)
        val entries = entryOffsets(original)
        println("mutating ${entries.size} IFD entries")
        val extremes = longArrayOf(0, 1, 0xFFFF, 0x10000, 0x7FFFFFFF, 0xFFFFFFFFL, original.size.toLong() - 2, 60_000)
        fun put32(b: ByteArray, o: Int, v: Long) { for (k in 0 until 4) b[o + k] = (v ushr (8 * k)).toByte() }
        var n = 0
        for (e in entries) {
            for (v in extremes) {
                // value/offset field
                original.copyOf().also { put32(it, e + 8, v); attempt(it, "entry@$e value=$v", true); n++ }
                // count field
                original.copyOf().also { put32(it, e + 4, v); attempt(it, "entry@$e count=$v", true); n++ }
            }
            // type field
            for (t in intArrayOf(0, 2, 5, 7, 10, 12, 200)) {
                original.copyOf().also { it[e + 2] = t.toByte(); it[e + 3] = 0; attempt(it, "entry@$e type=$t", true); n++ }
            }
        }
        println("$n mutated files survived")
    }

    @Test
    fun corruptedRawData() {
        assumeTrue(samplePath.isNotEmpty() && File(samplePath).exists())
        val original = File(samplePath).readBytes()
        val meta = OrfParser.parse(original)
        val rnd = Random(99)
        repeat(6) { i ->
            val b = original.copyOf()
            val start = meta.layout.dataOffset.toInt()
            repeat(2000) { b[start + rnd.nextInt(b.size - start)] = rnd.nextInt(256).toByte() }
            attempt(b, "strip corruption #$i")
        }
    }
}
