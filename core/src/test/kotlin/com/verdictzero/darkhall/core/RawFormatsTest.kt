package com.verdictzero.darkhall.core

import com.verdictzero.darkhall.core.develop.ColorScience
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.RawFormatException
import com.verdictzero.darkhall.core.raw.RawFormats
import com.verdictzero.darkhall.core.raw.RawImage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.fail

/**
 * Decodes a directory of sample raws from many cameras and checks each against LibRaw.
 *
 * `-Pdarkhall.rawsDir=DIR` must contain the samples plus `reference.tsv`, written from
 * LibRaw (rawpy) by tools/raws/reference.py: the raw dimensions, the SHA-256 of LibRaw's
 * decoded sensor data, per-channel black levels, white level, CFA pattern and as-shot white
 * balance. The sensor data must match bit for bit. `-Pdarkhall.rawsFilter=REGEX` limits the
 * run; `-Pdarkhall.outDir` additionally writes a small default render of each file.
 */
class RawFormatsTest {

    private val dir = System.getProperty("darkhall.rawsDir").orEmpty()
    private val filter = System.getProperty("darkhall.rawsFilter").orEmpty().ifEmpty { ".*" }.toRegex()
    private val outDir = System.getProperty("darkhall.outDir").orEmpty()

    private class Ref(
        val file: String, val type: String, val rawW: Int, val rawH: Int, val left: Int, val top: Int,
        val sha: String, val black: IntArray, val white: Int, val pattern: IntArray, val wb: FloatArray,
    )

    private fun refs(): List<Ref> = File(dir, "reference.tsv").readLines().filter { !it.startsWith("#") && it.isNotBlank() }.map { l ->
        val f = l.split('\t')
        Ref(
            f[0], f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[8],
            f[9].split(',').map { it.toInt() }.toIntArray(), f[10].toInt(),
            f[11].split(',').map { it.toInt() }.toIntArray(), f[12].split(',').map { it.toFloat() }.toFloatArray(),
        )
    }

    /** SHA-256 of the sensor data laid out like LibRaw's raw_image (4 channels for linear data). */
    private fun sha(raw: RawImage): String {
        val md = MessageDigest.getInstance("SHA-256")
        val spp = raw.samplesPerPixel
        val outPer = if (spp == 3) 4 else 1
        val buf = ByteArray(raw.width * outPer * 2)
        for (y in 0 until raw.height) {
            var o = 0
            for (x in 0 until raw.width) {
                for (c in 0 until outPer) {
                    val v = if (c < spp) raw.data[(y * raw.width + x) * spp + c].toInt() else 0
                    buf[o++] = v.toByte(); buf[o++] = (v shr 8).toByte()
                }
            }
            md.update(buf)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun decodesLikeLibRaw() {
        assumeTrue("no -Pdarkhall.rawsDir", dir.isNotEmpty() && File(dir, "reference.tsv").exists())
        val failures = ArrayList<String>()
        val report = StringBuilder()
        for (ref in refs()) {
            if (!filter.containsMatchIn(ref.file)) continue
            val file = File(dir, ref.file)
            if (!file.exists()) continue
            val expectUnsupported = ref.type == "Stack" && !ref.file.lowercase().endsWith(".dng")
            val bytes = file.readBytes()
            val problems = ArrayList<String>()
            val t0 = System.nanoTime()
            try {
                val meta = RawFormats.parse(bytes)
                val raw = RawFormats.decode(bytes, meta, corrections = false)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (expectUnsupported) problems += "decoded a file LibRaw treats as non-Bayer"
                if (meta.rawWidth != ref.rawW || meta.rawHeight != ref.rawH) problems += "size ${meta.rawWidth}x${meta.rawHeight} != ${ref.rawW}x${ref.rawH}"
                else if (sha(raw) != ref.sha) problems += "data differs"
                if (!meta.isLinear) {
                    // LibRaw's pattern is relative to its visible area; ours to the sensor origin.
                    for (r in 0..1) for (c in 0..1) {
                        val p = ((r + ref.top) and 1) * 2 + ((c + ref.left) and 1)
                        val lib = ref.pattern[r * 2 + c]
                        if (meta.cfa[p] != (if (lib == 3) 1 else lib)) problems += "cfa ${meta.cfa.toList()} vs ${ref.pattern.toList()}"
                        // Canon black comes from the maker note here and from masked pixels in LibRaw.
                        if (abs(meta.blackLevels[p] - ref.black[lib]) > 2) problems += "black ${meta.blackLevels.toList()} vs ${ref.black.toList()}"
                    }
                } else {
                    for (c in 0..2) if (abs(meta.blackLevels[c] - ref.black[c]) > 1) problems += "black ${meta.blackLevels.toList()} vs ${ref.black.toList()}"
                }
                // Where the camera states a highlight linearity limit (Sony, Canon, Panasonic) Darkhall
                // clips there; LibRaw reports it separately and keeps the full-scale white.
                if (meta.whiteLevel > ref.white || meta.whiteLevel < ref.white / 2) problems += "white ${meta.whiteLevel} vs ${ref.white}"
                val wr = ref.wb[0] / ref.wb[1]; val wbb = ref.wb[2] / ref.wb[1]
                if (abs(meta.cameraWb[0] / wr - 1f) > 0.01f || abs(meta.cameraWb[2] / wbb - 1f) > 0.01f) {
                    problems += "wb %.3f/%.3f vs %.3f/%.3f".format(meta.cameraWb[0], meta.cameraWb[2], wr, wbb)
                }
                if (meta.cameraMatrix == null && meta.xyzToCam == null) problems += "no colour matrix"
                // The library finds the embedded preview from the first 2 MB alone.
                val loc = RawFormats.locatePreview(bytes.copyOf(minOf(bytes.size, 2 shl 20)), bytes.size.toLong())
                val pw = loc?.let { l -> runCatching { ImageIO.read(bytes.inputStream(l.offset.toInt(), l.length))?.width }.getOrNull() }
                if (pw == null || pw < 160) problems += "preview not found ($pw)"
                val distinct = problems.distinct()
                report.append("%-46s %-4s %5d ms %s %s\n".format(ref.file.take(46), meta.format, ms,
                    if (distinct.isEmpty()) "OK" else "!!", distinct.joinToString("; ")))
                // Only data, size, CFA and black are hard requirements; white/wb/matrix are reported.
                if (distinct.any { it.startsWith("size") || it.startsWith("data") || it.startsWith("cfa") || it.startsWith("black") || it.startsWith("decoded") }) {
                    failures += "${ref.file}: ${distinct.joinToString("; ")}"
                }
                if (outDir.isNotEmpty()) {
                    // Rendered with the file's own corrections (gain maps), as the app does.
                    writePreview(RawFormats.decode(bytes, meta), File(outDir, ref.file.substringBeforeLast('.') + ".jpg"))
                }
            } catch (e: RawFormatException) {
                report.append("%-46s unsupported: %s\n".format(ref.file.take(46), e.message))
                if (!expectUnsupported) failures += "${ref.file}: ${e.message}"
            } catch (e: Throwable) {
                report.append("%-46s CRASH %s\n".format(ref.file.take(46), e))
                failures += "${ref.file}: crashed with $e"
                e.printStackTrace()
            }
        }
        println(report)
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * Truncated and corrupted copies of one sample per format must either decode or fail with a
     * [RawFormatException]: anything else (index errors, absurd allocations) would crash the app.
     */
    @Test
    fun damagedFilesFailCleanly() {
        assumeTrue("no -Pdarkhall.rawsDir", dir.isNotEmpty() && File(dir, "reference.tsv").exists())
        val byFormat = refs().filter { filter.containsMatchIn(it.file) && File(dir, it.file).exists() && it.type == "Flat" }
            .groupBy { it.file.substringAfterLast('.').lowercase() }
            .mapValues { (_, v) -> v.minByOrNull { File(dir, it.file).length() }!! }
        val rnd = kotlin.random.Random(7)
        var tried = 0
        for ((ext, ref) in byFormat) {
            val original = File(dir, ref.file).readBytes()
            fun attempt(bytes: ByteArray, label: String) {
                tried++
                try {
                    val meta = RawFormats.parse(bytes)
                    val raw = RawFormats.decode(bytes, meta)
                    check(raw.data.size == meta.rawWidth * meta.rawHeight * meta.samplesPerPixel) { label }
                } catch (e: RawFormatException) {
                    // expected for many corruptions
                } catch (e: Throwable) {
                    fail("$ext ${ref.file} $label: ${e.javaClass.name}: ${e.message}")
                }
            }
            for (n in listOf(0, 7, 16, 300, 4_000, 60_000, original.size / 3, original.size - 5000)) {
                if (n in 0..original.size) attempt(original.copyOf(n), "truncated to $n")
            }
            repeat(12) { i ->
                val b = original.copyOf()
                // Corrupt the directories and maker notes (the first 64 KB) ...
                repeat(1 + rnd.nextInt(16)) { b[8 + rnd.nextInt(minOf(65_536, b.size - 8))] = rnd.nextInt(256).toByte() }
                attempt(b, "header mutation #$i")
            }
            val b = original.copyOf()
            // ... and the sensor data.
            repeat(3000) { b[b.size / 4 + rnd.nextInt(b.size * 3 / 4)] = rnd.nextInt(256).toByte() }
            attempt(b, "data corruption")
        }
        println("$tried damaged files across ${byFormat.size} formats")
    }

    private fun writePreview(raw: RawImage, file: File) {
        val small = Demosaic.preview(raw, 900)
        val w = small.width
        val h = small.height
        val ctx = RenderContext.forFile(raw.meta, small, raw)
        val params = DevelopParams.defaultsFor(raw.meta, ctx.noise)
        val px = Geometry.apply(Developer().render(small, ctx, params, Frame(0, 0, w.toFloat() / raw.meta.crop.width)), w, h, params, ctx.lens)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        file.parentFile.mkdirs()
        ImageIO.write(img, "jpg", file)
        check(ColorScience.cameraToSrgb(raw.meta).all { it.isFinite() })
    }
}
