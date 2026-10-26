package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.export.ArgbRows
import com.verdictzero.darkhall.core.export.GeometryRows16
import com.verdictzero.darkhall.core.export.PhotoInfo
import com.verdictzero.darkhall.core.export.PngWriter
import com.verdictzero.darkhall.core.export.RowSource
import com.verdictzero.darkhall.core.export.TgaWriter
import com.verdictzero.darkhall.core.export.TiffWriter
import com.verdictzero.darkhall.core.raw.GpsInfo
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every encoder's output, read back (PNG and TIFF by the JDK's own readers), must match. */
class ExportFormatsTest {

    private val w = 173
    private val h = 61

    /** Gradients, flat runs (for RLE) and noise, 8 or 16 bits. */
    private fun picture(bits: Int): Array<IntArray> {
        val max = (1 shl bits) - 1
        val rnd = Random(9)
        return Array(h) { y ->
            IntArray(3 * w) { i ->
                val x = i / 3; val c = i % 3
                when {
                    y < 10 -> max / 2
                    x < 40 -> (x * max / 40 + c * 7) and max
                    else -> rnd.nextInt(max + 1)
                }
            }
        }
    }

    private class ArrayRows(val data: Array<IntArray>, override val bits: Int) : RowSource {
        override val width = data[0].size / 3
        override val height = data.size
        override fun row(y: Int, out: IntArray) { data[y].copyInto(out) }
    }

    private val info = PhotoInfo(
        "OM Digital Solutions", "TG-7", "2026:10:01 12:34:56", 200, 1 / 250.0, 4.9, 4.5,
        GpsInfo(-16.5, 145.75, -8.0, "02:03:04", "2026:10:01"), "Depth 8.0 m, Temp 26.5 C",
    )

    private fun tmp(ext: String) = File.createTempFile("darkhall", ".$ext").apply { deleteOnExit() }

    private fun assertSame(expected: Array<IntArray>, img: java.awt.image.BufferedImage) {
        assertEquals(w, img.width); assertEquals(h, img.height)
        val r = img.raster
        val px = IntArray(r.numBands)
        for (y in 0 until h) for (x in 0 until w) {
            r.getPixel(x, y, px)
            for (c in 0 until 3) assertEquals(expected[y][3 * x + c], px[c], "($x, $y) channel $c")
        }
    }

    @Test
    fun pngRoundTrips() {
        for (bits in listOf(8, 16)) {
            val pic = picture(bits)
            val f = tmp("png")
            PngWriter.write(ArrayRows(pic, bits), f, mapOf("Software" to "Darkhall"))
            val img = ImageIO.read(f)
            assertEquals(bits, img.colorModel.getComponentSize(0))
            assertSame(pic, img)
        }
    }

    @Test
    fun tiffRoundTrips() {
        for (bits in listOf(8, 16)) for (compress in listOf(false, true)) {
            val pic = picture(bits)
            val f = tmp("tif")
            TiffWriter.write(ArrayRows(pic, bits), f, compress, info)
            if (bits == 16 && compress) {
                // The JDK's reader lacks 16-bit predictor support (libtiff, Photoshop and GIMP have it).
                val got = decodeDeflateTiff16(f.readBytes())
                for (y in 0 until h) assertEquals(pic[y].toList(), got[y].toList(), "row $y")
                continue
            }
            val img = ImageIO.read(f)
            assertEquals(bits, img.colorModel.getComponentSize(0), "$bits-bit, compress=$compress")
            assertSame(pic, img)
        }
    }

    /** Minimal reader for what TiffWriter makes at 16 bits with Deflate: strips, predictor 2. */
    private fun decodeDeflateTiff16(bytes: ByteArray): Array<IntArray> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = b.getInt(4)
        val n = b.getShort(ifd).toInt()
        val entries = (0 until n).associate { (b.getShort(ifd + 2 + 12 * it).toInt() and 0xFFFF) to ifd + 2 + 12 * it }
        fun longs(tag: Int): IntArray {
            val e = entries[tag]!!; val count = b.getInt(e + 4)
            val at = if (count == 1) e + 8 else b.getInt(e + 8)
            return IntArray(count) { b.getInt(at + 4 * it) }
        }
        assertEquals(2, b.getShort(entries[317]!! + 8).toInt())
        val offsets = longs(273); val counts = longs(279)
        val rows = ArrayList<IntArray>()
        for (s in offsets.indices) {
            val inf = java.util.zip.Inflater()
            inf.setInput(bytes, offsets[s], counts[s])
            val buf = ByteArray(16 * w * 6)
            val k = inf.inflate(buf)
            val bb = ByteBuffer.wrap(buf, 0, k).order(ByteOrder.LITTLE_ENDIAN)
            repeat(k / (w * 6)) {
                val row = IntArray(3 * w) { bb.getShort().toInt() and 0xFFFF }
                for (i in 3 until row.size) row[i] = (row[i] + row[i - 3]) and 0xFFFF
                rows += row
            }
        }
        return rows.toTypedArray()
    }

    @Test
    fun tiffCarriesTheShootingData() {
        val f = tmp("tif")
        TiffWriter.write(ArrayRows(picture(8), 8), f, true, info)
        val bytes = f.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tags(at: Int): Map<Int, Int> {
            val n = b.getShort(at).toInt()
            return (0 until n).associate { i -> (b.getShort(at + 2 + 12 * i).toInt() and 0xFFFF) to (at + 2 + 12 * i) }
        }
        fun ascii(entry: Int): String {
            val count = b.getInt(entry + 4)
            val at = if (count <= 4) entry + 8 else b.getInt(entry + 8)
            return String(bytes, at, count - 1, Charsets.US_ASCII)
        }
        fun rational(entry: Int, k: Int = 0): Double {
            val at = b.getInt(entry + 8) + 8 * k
            return b.getInt(at).toDouble() / b.getInt(at + 4)
        }
        val main = tags(b.getInt(4))
        assertEquals("TG-7", ascii(main[272]!!))
        assertEquals("2026:10:01 12:34:56", ascii(main[306]!!))
        assertEquals("Depth 8.0 m, Temp 26.5 C", ascii(main[270]!!))
        val exif = tags(b.getInt(main[34665]!! + 8))
        assertEquals(200, b.getShort(exif[34855]!! + 8).toInt())
        assertTrue(abs(rational(exif[33434]!!) - 1 / 250.0) < 1e-6)
        assertTrue(abs(rational(exif[33437]!!) - 4.9) < 1e-6)
        val gps = tags(b.getInt(main[34853]!! + 8))
        assertEquals("S", ascii(gps[1]!!))
        val lat = rational(gps[2]!!, 0) + rational(gps[2]!!, 1) / 60 + rational(gps[2]!!, 2) / 3600
        assertTrue(abs(lat - 16.5) < 1e-6, "latitude $lat")
        assertEquals("E", ascii(gps[3]!!))
        assertEquals(1, bytes[gps[5]!! + 8].toInt()) // below sea level
    }

    @Test
    fun tgaRoundTrips() {
        val pic = picture(8)
        val f = tmp("tga")
        TgaWriter.write(ArrayRows(pic, 8), f)
        val d = f.readBytes()
        assertEquals(10, d[2].toInt())
        assertEquals(w, (d[12].toInt() and 0xFF) or ((d[13].toInt() and 0xFF) shl 8))
        assertEquals(h, (d[14].toInt() and 0xFF) or ((d[15].toInt() and 0xFF) shl 8))
        assertEquals(0x20, d[17].toInt())
        assertEquals("TRUEVISION-XFILE.", String(d, d.size - 18, 17, Charsets.US_ASCII))
        // Decode the run-length packets.
        var p = 18
        val got = IntArray(w * h)
        var n = 0
        while (n < w * h) {
            val head = d[p++].toInt() and 0xFF
            val count = (head and 0x7F) + 1
            if (head and 0x80 != 0) {
                val v = rgb(d, p); p += 3
                repeat(count) { got[n++] = v }
            } else {
                repeat(count) { got[n++] = rgb(d, p); p += 3 }
            }
        }
        assertEquals(d.size - 26, p, "pixel data then the footer")
        for (y in 0 until h) for (x in 0 until w) {
            val e = (pic[y][3 * x] shl 16) or (pic[y][3 * x + 1] shl 8) or pic[y][3 * x + 2]
            assertEquals(e, got[y * w + x], "($x, $y)")
        }
        assertTrue(f.length() < 18 + 3L * w * h, "flat rows compress")
    }

    private fun rgb(d: ByteArray, p: Int) = ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p].toInt() and 0xFF)

    @Test
    fun uprightRowsTurnTheFrame() {
        // 3 x 2 frame, pixel value = its index.
        val argb = IntArray(6) { it }
        fun read(o: Int): List<Int> {
            val s = ArgbRows(argb, 3, 2, o)
            val row = IntArray(3 * s.width)
            return (0 until s.height).flatMap { y -> s.row(y, row); (0 until s.width).map { row[3 * it + 2] } }
        }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), read(1))
        assertEquals(listOf(5, 4, 3, 2, 1, 0), read(3))
        assertEquals(listOf(3, 0, 4, 1, 5, 2), read(6)) // 90 degrees clockwise
        assertEquals(listOf(2, 5, 1, 4, 0, 3), read(8))
    }

    @Test
    fun sixteenBitGeometryMatchesTheEightBitPath() {
        // A smooth picture straightened 4 degrees both ways: the 16-bit rows, scaled to 8 bits,
        // must agree with Geometry.apply on the 8-bit picture to within rounding.
        val gw = 120; val gh = 80
        val rgb16 = ShortArray(3 * gw * gh)
        val argb = IntArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val v = intArrayOf(x * 255 / gw, y * 255 / gh, (x + y) * 255 / (gw + gh))
            for (c in 0 until 3) rgb16[3 * (y * gw + x) + c] = (v[c] * 257).toShort()
            argb[y * gw + x] = (0xFF shl 24) or (v[0] shl 16) or (v[1] shl 8) or v[2]
        }
        val p = DevelopParams.NEUTRAL.with(Adjustment.STRAIGHTEN, 4f)
        val eight = Geometry.apply(argb, gw, gh, p, null)
        val rows = GeometryRows16(rgb16, gw, gh, Geometry.create(gw, gh, p, null), 1)
        val row = IntArray(3 * gw)
        for (y in 0 until gh) {
            rows.row(y, row)
            for (x in 0 until gw) for (c in 0 until 3) {
                val e = (eight[y * gw + x] shr (16 - 8 * c)) and 0xFF
                assertTrue(abs(row[3 * x + c] / 257.0 - e) <= 1.0, "($x, $y) c$c: ${row[3 * x + c] / 257.0} vs $e")
            }
        }
    }

    @Test
    fun sixteenBitRenderMatchesEightBit() {
        val path = System.getProperty("darkhall.sample").orEmpty()
        org.junit.Assume.assumeTrue("no -Pdarkhall.sample", path.isNotEmpty() && File(path).exists())
        val bytes = File(path).readBytes()
        val meta = com.verdictzero.darkhall.core.raw.RawFormats.parse(bytes)
        val raw = com.verdictzero.darkhall.core.raw.RawFormats.decode(bytes, meta)
        val pv = com.verdictzero.darkhall.core.develop.Demosaic.preview(raw, 600)
        val ctx = com.verdictzero.darkhall.core.develop.RenderContext.forFile(meta, pv, raw)
        val p = DevelopParams.defaultsFor(meta, ctx.noise).with(Adjustment.CLARITY, 30f).with(Adjustment.SHADOWS, 40f)
        val frame = com.verdictzero.darkhall.core.develop.Frame(0, 0, pv.width.toFloat() / meta.crop.width)
        val dev = com.verdictzero.darkhall.core.develop.Developer()
        val eight = dev.render(pv, ctx, p, frame)
        val sixteen = dev.render16(pv, ctx, p, frame)
        var maxDiff = 0.0
        for (i in eight.indices) for (c in 0 until 3) {
            val e = (eight[i] shr (16 - 8 * c)) and 0xFF
            val v = (sixteen[3 * i + c].toInt() and 0xFFFF) / 257.0
            maxDiff = maxOf(maxDiff, abs(v - e))
        }
        assertTrue(maxDiff <= 0.51, "16-bit render differs from 8-bit by $maxDiff levels")
        // And it carries real extra precision: many more distinct values than 8 bits allow.
        val distinct = (0 until sixteen.size step 3).map { sixteen[it].toInt() and 0xFFFF }.toSet().size
        assertTrue(distinct > 256, "$distinct distinct red values")
    }
}
