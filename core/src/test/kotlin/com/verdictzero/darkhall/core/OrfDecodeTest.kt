package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.orf.OlympusDecoder
import com.verdictzero.darkhall.core.orf.OrfParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes a real TG-series ORF and compares every sample with a LibRaw dump.
 * Supply the files with:
 *   ./gradlew :core:test -Pdarkhall.sample=/path/tg6.orf -Pdarkhall.sampleRef=/path/tg6_raw_u16le.bin
 * The test is skipped when no sample is configured.
 */
class OrfDecodeTest {

    private val samplePath = System.getProperty("darkhall.sample").orEmpty()
    private val refPath = System.getProperty("darkhall.sampleRef").orEmpty()

    @Test
    fun parsesTgMetadata() {
        assumeTrue("no sample configured", samplePath.isNotEmpty() && File(samplePath).exists())
        val bytes = File(samplePath).readBytes()
        val meta = OrfParser.parse(bytes)
        println(meta)
        assertTrue(meta.model.startsWith("TG-"), "model=${meta.model}")
        assertEquals(4040, meta.rawWidth)
        assertEquals(3016, meta.rawHeight)
        assertTrue(meta.isOlympusCompressed)
        assertEquals(listOf(1, 0, 2, 1), meta.cfa.toList()) // GRBG at the sensor origin
        assertEquals(4000, meta.crop.width)
        assertEquals(3000, meta.crop.height)
        assertEquals(8, meta.crop.left)
        assertEquals(8, meta.crop.top)
        assertEquals(4095, meta.whiteLevel)
        assertEquals(1.875f, meta.cameraWb[0], 1e-4f)
        assertEquals(1.7109375f, meta.cameraWb[2], 1e-4f)
        assertEquals(257, meta.blackLevels[0])
        assertNotNull(meta.cameraMatrix)
        assertTrue(meta.previewLength > 1000)
        assertEquals(0xFF, bytes[meta.previewOffset.toInt()].toInt() and 0xFF)
        assertEquals(0xD8, bytes[meta.previewOffset.toInt() + 1].toInt() and 0xFF)
        assertEquals(100, meta.iso)
    }

    @Test
    fun decodesExactlyLikeLibRaw() {
        assumeTrue("no sample configured", samplePath.isNotEmpty() && File(samplePath).exists())
        val bytes = File(samplePath).readBytes()
        val meta = OrfParser.parse(bytes)
        val t0 = System.nanoTime()
        val raw = OlympusDecoder.decode(bytes, meta)
        println("decode took ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(meta.rawWidth * meta.rawHeight, raw.data.size)
        val out = ByteArray(raw.data.size * 2)
        for (i in raw.data.indices) {
            val v = raw.data[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        val crc = CRC32().also { it.update(out) }.value
        println("crc32=$crc first=${(0 until 8).map { raw.get(0, it) }}")
        if (refPath.isNotEmpty() && File(refPath).exists()) {
            val ref = File(refPath).readBytes()
            assertEquals(ref.size, out.size)
            var firstDiff = -1
            for (i in out.indices) if (out[i] != ref[i]) { firstDiff = i; break }
            assertEquals(-1, firstDiff, "first differing byte at $firstDiff (sample ${firstDiff / 2})")
        }
    }
}
