package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.raw.RawFormats
import com.verdictzero.darkhall.core.raw.ShotFacts
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShotFactsTest {

    private val dive = ShotFacts(
        "OM Digital Solutions", "TG-7", "2025:08:14 10:32:07", 200, 1 / 125.0, 2.8, 4.5, 4000, 3000,
        depth = 8.24, temperature = 23.6, pressureHpa = 1840.0, altitude = null, latitude = -16.92, longitude = 145.78,
    )

    @Test
    fun underwaterShotsGetABadgeAndTheirReadings() {
        assertEquals("8.2 m · 24 °C", dive.badge)
        val rows = dive.rows().toMap()
        assertEquals("OM Digital Solutions TG-7", rows["Camera"])
        assertEquals("2025-08-14 10:32", rows["Taken"])
        assertEquals("1/125 s  ·  f/2.8  ·  ISO 200  ·  4.5 mm", rows["Exposure"])
        assertEquals("8.2 m", rows["Depth"])
        assertEquals("23.6 °C", rows["Water temperature"])
        assertEquals("1,840 hPa", rows["Pressure"])
        assertEquals("16.92000° S, 145.78000° E", rows["Location"])
    }

    @Test
    fun dryShotsHaveNoBadge() {
        val dry = dive.copy(depth = null, pressureHpa = 1013.0)
        assertNull(dry.badge)
        val rows = dry.rows().toMap()
        assertTrue("Depth" !in rows)
        assertEquals("23.6 °C", rows["Temperature"])
    }

    /** The library parses facts from the first 2 MB alone; that must give what the whole file gives. */
    @Test
    fun headerAloneIsEnough() {
        val files = buildList {
            System.getProperty("darkhall.sample").orEmpty().takeIf { it.isNotEmpty() }?.let { add(File(it)) }
            System.getProperty("darkhall.rawsDir").orEmpty().takeIf { it.isNotEmpty() }?.let { d ->
                File(d).listFiles()?.filter { RawFormats.hasRawExtension(it.name) }?.let { addAll(it) }
            }
        }.filter { it.exists() }
        assumeTrue("no samples", files.isNotEmpty())
        var fromHead = 0
        var fromExif = 0
        for (f in files) {
            val bytes = f.readBytes()
            val full = runCatching { ShotFacts.from(RawFormats.parse(bytes)) }.getOrNull() ?: continue
            val headBytes = bytes.copyOf(minOf(bytes.size, 2 shl 20))
            val head = runCatching { ShotFacts.from(RawFormats.parse(headBytes)) }.getOrNull()
            if (head != null) { assertEquals(full, head, f.name); fromHead++; continue }
            // Otherwise the EXIF alone: everything but the picture's size.
            val exif = RawFormats.factsFromHeader(headBytes)
            // (The model is the camera's own: a full parse turns Canon's US names into international ones.)
            assertEquals(full.copy(width = 0, height = 0, model = exif?.model ?: ""), exif, f.name)
            fromExif++
        }
        println("$fromHead of ${files.size} files parse from their first 2 MB, $fromExif more from its EXIF")
    }
}
