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

import kotlin.math.abs
import kotlin.math.roundToInt

/** A DNG GainMap opcode (lens shading per Bayer phase, as phones write in OpcodeList2). */
class GainMap(
    val top: Int, val left: Int, val bottom: Int, val right: Int,
    val rowPitch: Int, val colPitch: Int,
    val pointsV: Int, val pointsH: Int,
    val spacingV: Double, val spacingH: Double,
    val originV: Double, val originH: Double,
    val planes: Int, val firstPlane: Int,
    /** pointsV * pointsH * planes gains, plane-interleaved. */
    val gains: FloatArray,
)

data class DngLayout(
    override val dataOffset: Long,
    /** Offsets and byte counts of the tiles (or strips) in raster order. */
    val offsets: LongArray,
    val byteCounts: LongArray,
    /** Tile size; for strips the width is the image width and the length is RowsPerStrip. */
    val tileWidth: Int,
    val tileLength: Int,
    val compression: Int,
    val bitsPerSample: Int,
    val bigEndian: Boolean,
    val linearization: IntArray?,
    /** Top-left of the active area, which the GainMap coordinates are relative to. */
    val activeTop: Int,
    val activeLeft: Int,
    val activeWidth: Int,
    val activeHeight: Int,
    val gainMaps: List<GainMap>,
    /** FixVignetteRadial: k0..k4 and the optical centre (relative to the active area), or null. */
    val vignette: DoubleArray? = null,
    val legacyLjpeg: Boolean,
    /** For floating-point data: the factor that maps samples to 16-bit integers; 0 for integer data. */
    val floatScale: Float = 0f,
    /** TIFF Predictor (3, 34894, 34895 for floating-point deflate). */
    val predictor: Int = 1,
) : RawLayout

/** Digital Negative files from phones, drones and cameras (Adobe DNG specification 1.0 to 1.6). */
object DngParser {

    private const val MAX_DIMENSION = 32768

    fun isDng(r: ByteReader, ifds: List<Ifd>): Boolean = ifds.any { it[0xC612] != null }

    fun parse(data: ByteArray): RawMetadata {
        val r = ByteReader(data, bigEndian = data[0] == 'M'.code.toByte())
        val roots = Tiff.chain(r, r.u32(4))
        if (roots.isEmpty()) throw RawFormatException("Corrupt DNG: no directories")
        val ifd0 = roots[0]
        val all = Tiff.tree(r, roots)
        val version = ifd0[0xC612]?.let { e -> IntArray(4) { e.int(it) ?: 0 } } ?: intArrayOf(1, 0, 0, 0)

        // The raw image: full resolution (NewSubFileType 0), CFA or LinearRaw.
        val rawIfd = all.filter { (it.long(0x00FE) ?: 0L) == 0L && (it.int(0x0106) == 32803 || it.int(0x0106) == 34892) }
            .maxByOrNull { (it.long(0x0100) ?: 0L) * (it.long(0x0101) ?: 0L) }
            ?: throw RawFormatException("This DNG has no raw image (it may be a converted JPEG or a semi-processed file)")

        val photometric = rawIfd.int(0x0106)!!
        val spp = rawIfd.int(0x0115) ?: 1
        val linear = photometric == 34892
        if (linear && spp != 3) throw RawFormatException("Unsupported linear DNG with $spp channels")
        if (!linear && spp != 1) throw RawFormatException("Unsupported CFA DNG with $spp samples per pixel")
        if ((rawIfd.int(0x011C) ?: 1) != 1) throw RawFormatException("Planar DNGs are not supported")
        val sampleFormat = rawIfd.int(0x0153) ?: 1
        val isFloat = sampleFormat == 3

        val width = rawIfd.int(0x0100) ?: throw RawFormatException("DNG without width")
        val height = rawIfd.int(0x0101) ?: throw RawFormatException("DNG without height")
        if (width !in 16..MAX_DIMENSION || height !in 16..MAX_DIMENSION) throw RawFormatException("Implausible DNG size ${width}x$height")
        val bps = rawIfd.int(0x0102) ?: 16
        val compression = rawIfd.int(0x0103) ?: 1
        when (compression) {
            1 -> {}
            7 -> if (isFloat) throw RawFormatException("Unsupported floating-point DNG compression")
            8 -> if (!isFloat) throw RawFormatException("Deflate-compressed integer DNGs are not supported")
            34892 -> throw RawFormatException("Lossy DNGs are not supported")
            52546 -> throw RawFormatException("JPEG XL compressed DNGs are not supported yet")
            else -> throw RawFormatException("Unsupported DNG compression $compression")
        }
        if (if (isFloat) bps !in setOf(16, 24, 32) else bps !in 8..16) throw RawFormatException("Unsupported DNG bit depth $bps")

        // Tiles or strips.
        val tiled = rawIfd[0x0144] != null
        val offE = if (tiled) rawIfd[0x0144] else rawIfd[0x0111]
        val cntE = if (tiled) rawIfd[0x0145] else rawIfd[0x0117]
        if (offE == null) throw RawFormatException("DNG without image data")
        val offsets = LongArray(offE.count) { offE.long(it) ?: 0L }
        val counts = LongArray(offE.count) { cntE?.long(it) ?: (data.size - offsets[it]) }
        val tileW = if (tiled) rawIfd.int(0x0142) ?: width else width
        val tileL = if (tiled) rawIfd.int(0x0143) ?: height else (rawIfd.long(0x0116)?.coerceAtMost(height.toLong())?.toInt() ?: height)
        if (tileW <= 0 || tileL <= 0) throw RawFormatException("Corrupt DNG tile size")
        val expected = if (tiled) ((width + tileW - 1) / tileW) * ((height + tileL - 1) / tileL) else (height + tileL - 1) / tileL
        if (offsets.size < expected && compression == 1) throw RawFormatException("DNG is missing image data")
        if (offsets.any { it <= 0 || it >= data.size }) throw RawFormatException("DNG image data lies outside the file")

        // CFA pattern.
        var cfa = intArrayOf(0, 1, 1, 2)
        if (!linear) {
            val dim = rawIfd[0x828D]?.ints(2)
            if (dim != null && (dim[0] != 2 || dim[1] != 2)) throw RawFormatException("Only 2x2 colour filter arrays are supported (X-Trans DNGs are not)")
            val planeColor = rawIfd[0xC616]?.ints(3) ?: intArrayOf(0, 1, 2)
            rawIfd[0x828E]?.ints(4)?.let { p ->
                cfa = IntArray(4) { planeColor.getOrElse(p[it]) { -1 } }
                if (cfa.any { it !in 0..2 }) throw RawFormatException("Unsupported CFA colours (only RGB sensors are supported)")
            }
        }

        // Active area and default crop.
        val active = rawIfd[0xC68D]?.ints(4)?.takeIf { it[2] > it[0] && it[3] > it[1] && it[2] <= height && it[3] <= width }
            ?: intArrayOf(0, 0, height, width)
        val aTop = active[0]; val aLeft = active[1]
        val aH = active[2] - active[0]; val aW = active[3] - active[1]
        var crop = Crop(aLeft, aTop, aW, aH)
        val origin = rawIfd[0xC61F]?.doubles(2)
        val size = rawIfd[0xC620]?.doubles(2)
        if (origin != null && size != null) {
            val scale = rawIfd[0xC61E]?.doubles(2) ?: doubleArrayOf(1.0, 1.0)
            val cx = (origin[0] / scale[0]).roundToInt(); val cy = (origin[1] / scale[1]).roundToInt()
            val cw = (size[0] / scale[0]).roundToInt(); val ch = (size[1] / scale[1]).roundToInt()
            if (cx >= 0 && cy >= 0 && cw > 0 && ch > 0 && cx + cw <= aW && cy + ch <= aH) crop = Crop(aLeft + cx, aTop + cy, cw, ch)
        }

        // Linearization, black and white.
        val lin = rawIfd[0xC618]?.let { e -> IntArray(minOf(e.count, 65536)) { e.int(it) ?: 0 } }?.takeIf { it.isNotEmpty() }
        var white = rawIfd[0xC61D]?.double()?.takeIf { it > 0 }
            ?: (if (isFloat) 1.0 else if (lin != null) lin.max().toDouble() else ((1 shl bps) - 1).toDouble())
        var blackLevels = blackLevels(rawIfd, linear, cfa, aTop, aLeft, aW, aH)
        // Floating-point data becomes 16-bit integers the way LibRaw converts it: unchanged when
        // the white level is already in a 12-to-15-bit range, otherwise scaled so white is 16383.
        var floatScale = 0f
        if (isFloat) {
            val tmax = maxOf(white, 1.0)
            floatScale = if (tmax < 4096.0 || tmax > 32767.0) (16383.0 / tmax).toFloat() else 1f
            white *= floatScale
            if (floatScale != 1f) blackLevels = blackFloat(rawIfd, linear, aTop, aLeft, aW, aH, floatScale)
        }

        // Colour: tags live in IFD0 (some writers repeat them in the raw IFD).
        fun colorTag(tag: Int) = rawIfd[tag] ?: ifd0[tag]
        val xyzToCam = colorMatrix(::colorTag)
        val neutral = colorTag(0xC628)?.doubles(3)
        val wb = if (neutral != null && neutral.all { it > 0 }) {
            floatArrayOf((neutral[1] / neutral[0]).toFloat(), 1f, (neutral[1] / neutral[2]).toFloat())
        } else {
            neutralFromWhiteXy(colorTag(0xC629)?.doubles(2), xyzToCam) ?: floatArrayOf(1f, 1f, 1f)
        }
        val baseline = colorTag(0xC62A)?.double()?.toFloat()?.takeIf { it.isFinite() && abs(it) < 8f } ?: 0f

        val make = ifd0.string(0x010F).ifEmpty { ifd0.string(0xC614).substringBefore(' ') }
        val model = ifd0.string(0x0110).ifEmpty { ifd0.string(0xC614) }
        val exif = ExifInfo.read(r, ifd0, listOf(rawIfd))
        val preview = Previews.largestJpeg(r, all)
        val ops2 = opcodes(r, rawIfd[0xC741])
        val ops3 = opcodes(r, rawIfd[0xC74E])
        // A warp that does nothing (Pixel 4a writes one) is left out, so no lens slider appears.
        val warp = ops3.warp?.takeIf { w -> w.any { pl -> kotlin.math.abs(pl[0] - 1) > 1e-6 || (1..3).any { kotlin.math.abs(pl[it]) > 1e-9 } } }

        return RawMetadata(
            format = RawFormat.DNG,
            make = make, model = model,
            rawWidth = width, rawHeight = height,
            samplesPerPixel = spp,
            cfa = cfa,
            blackLevels = blackLevels,
            whiteLevel = white.roundToInt(),
            cameraWb = wb,
            xyzToCam = xyzToCam,
            baselineExposure = baseline,
            crop = crop,
            orientation = ifd0.int(0x0112) ?: rawIfd.int(0x0112) ?: 1,
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            dateTime = exif.dateTime,
            previewOffset = preview?.offset ?: 0, previewLength = preview?.length ?: 0,
            ambientTemperature = exif.ambientTemperature, pressureHpa = exif.pressureHpa, waterDepth = exif.waterDepth,
            gps = exif.gps,
            lensWarp = if (linear) null else warp?.let { LensWarp(it, aW, aH) },
            layout = DngLayout(
                dataOffset = offsets[0],
                offsets = offsets, byteCounts = counts,
                tileWidth = tileW, tileLength = tileL,
                compression = compression, bitsPerSample = bps, bigEndian = r.bigEndian,
                linearization = lin,
                activeTop = aTop, activeLeft = aLeft, activeWidth = aW, activeHeight = aH,
                gainMaps = ops2.gainMaps + ops3.gainMaps,
                vignette = ops3.vignette ?: ops2.vignette,
                legacyLjpeg = version[0] == 1 && version[1] == 0,
                floatScale = floatScale,
                predictor = rawIfd.int(0x013D) ?: 1,
            ),
        )
    }

    /**
     * BlackLevel repeats over the active area with BlackLevelRepeatDim; the row/column deltas
     * are averaged in. Returned per position of the 2x2 cell at the sensor origin (CFA) or
     * per channel (linear).
     */
    private fun blackLevels(ifd: Ifd, linear: Boolean, cfa: IntArray, aTop: Int, aLeft: Int, aW: Int, aH: Int): IntArray {
        val dim = ifd[0xC619]?.ints(2)?.takeIf { it[0] in 1..16 && it[1] in 1..16 } ?: intArrayOf(1, 1)
        val spp = if (linear) 3 else 1
        val e = ifd[0xC61A]
        val values = DoubleArray(dim[0] * dim[1] * spp) { e?.double(minOf(it, (e.count - 1).coerceAtLeast(0))) ?: 0.0 }
        fun mean(tag: Int, n: Int): Double {
            val d = ifd[tag] ?: return 0.0
            var s = 0.0; var k = 0
            for (i in 0 until minOf(d.count, n)) { s += d.double(i) ?: 0.0; k++ }
            return if (k > 0) s / k else 0.0
        }
        val delta = mean(0xC61B, aW) + mean(0xC61C, aH)
        return if (linear) {
            IntArray(4) { c -> if (c < 3) (values[c] + delta).roundToInt() else 0 }
        } else {
            IntArray(4) { p ->
                val row = p shr 1; val col = p and 1
                // Position p of the sensor-origin cell, expressed relative to the active area.
                val br = Math.floorMod(row - aTop, dim[0]); val bc = Math.floorMod(col - aLeft, dim[1])
                // A repeat that is not a divisor of 2 has no per-position meaning; average it.
                if (dim[0] <= 2 && dim[1] <= 2 && 2 % dim[0] == 0 && 2 % dim[1] == 0) {
                    (values[br * dim[1] + bc] + delta).roundToInt()
                } else {
                    (values.average() + delta).roundToInt()
                }
            }
        }
    }

    /** Black levels of floating-point data (fractions of white), scaled like the samples. */
    private fun blackFloat(ifd: Ifd, linear: Boolean, aTop: Int, aLeft: Int, aW: Int, aH: Int, scale: Float): IntArray {
        val e = ifd[0xC61A] ?: return IntArray(4)
        val dim = ifd[0xC619]?.ints(2)?.takeIf { it[0] in 1..2 && it[1] in 1..2 } ?: intArrayOf(1, 1)
        return IntArray(4) { p ->
            val i = if (linear) minOf(p, 2) else Math.floorMod((p shr 1) - aTop, dim[0]) * dim[1] + Math.floorMod((p and 1) - aLeft, dim[1])
            ((e.double(minOf(i, e.count - 1)) ?: 0.0) * scale).toInt()
        }
    }

    private val ILLUMINANT_KELVIN = mapOf(
        1 to 5500, 2 to 4150, 3 to 2850, 4 to 5500, 9 to 5500, 10 to 6500, 11 to 7500, 12 to 6400,
        13 to 5050, 14 to 4150, 15 to 3525, 17 to 2856, 18 to 4874, 19 to 6774, 20 to 5503,
        21 to 6504, 22 to 7504, 23 to 5003, 24 to 3200,
    )

    /**
     * The XYZ -> camera matrix for daylight: the ColorMatrix whose calibration illuminant is
     * closest to D65, with CameraCalibration and AnalogBalance applied as the DNG spec says.
     */
    private fun colorMatrix(tag: (Int) -> TiffEntry?): FloatArray? {
        val candidates = listOf(Triple(0xC621, 0xC623, 0xC65A), Triple(0xC622, 0xC624, 0xC65B))
            .mapNotNull { (cm, cc, ill) ->
                val m = tag(cm)?.doubles(9)?.takeIf { v -> v.any { it != 0.0 } } ?: return@mapNotNull null
                val kelvin = ILLUMINANT_KELVIN[tag(ill)?.int() ?: 21] ?: 5000
                Triple(m, tag(cc)?.doubles(9), kelvin)
            }
        val best = candidates.minByOrNull { abs(1e6 / it.third - 1e6 / 6504) } ?: return null
        var m = best.first
        best.second?.let { cc -> m = mul(cc, m) }
        tag(0xC627)?.doubles(3)?.let { ab -> m = mul(doubleArrayOf(ab[0], 0.0, 0.0, 0.0, ab[1], 0.0, 0.0, 0.0, ab[2]), m) }
        return FloatArray(9) { m[it].toFloat() }
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /** White balance from AsShotWhiteXY: the camera's response to that white, inverted. */
    private fun neutralFromWhiteXy(xy: DoubleArray?, xyzToCam: FloatArray?): FloatArray? {
        if (xy == null || xyzToCam == null || xy[1] <= 0) return null
        val xyz = doubleArrayOf(xy[0] / xy[1], 1.0, (1 - xy[0] - xy[1]) / xy[1])
        val cam = DoubleArray(3) { r -> (0 until 3).sumOf { c -> xyzToCam[r * 3 + c] * xyz[c] } }
        if (cam.any { it <= 0 }) return null
        return floatArrayOf((cam[1] / cam[0]).toFloat(), 1f, (cam[1] / cam[2]).toFloat())
    }

    /** Reads GainMap opcodes (id 9) from an opcode list; others are skipped. Opcode lists are always big-endian. */
    /** The opcodes Darkhall applies: gain maps, radial vignetting and the rectilinear warp. */
    private class Opcodes(val gainMaps: List<GainMap>, val vignette: DoubleArray?, val warp: List<DoubleArray>?)

    /** Reads an opcode list (always big-endian); unknown opcodes are skipped. */
    private fun opcodes(file: ByteReader, e: TiffEntry?): Opcodes {
        val none = Opcodes(emptyList(), null, null)
        e ?: return none
        val r = file.withOrder(bigEndian = true)
        val start = e.offset
        val end = start + e.count
        if (end > r.size || e.count < 4) return none
        val n = r.u32(start)
        var p = start + 4
        val maps = ArrayList<GainMap>()
        var vignette: DoubleArray? = null
        var warp: List<DoubleArray>? = null
        for (k in 0 until minOf(n, 64L).toInt()) {
            if (p + 16 > end) break
            val id = r.s32(p)
            val size = r.u32(p + 12).toInt()
            val q = p + 16
            if (size < 0 || q + size > end) break
            fun u(o: Int) = r.s32(q + o)
            fun d(o: Int) = Double.fromBits((r.u32(q + o) shl 32) or r.u32(q + o + 4))
            when {
                id == 9 && size >= 76 -> {
                    val pv = u(32); val ph = u(36); val planes = u(72)
                    val count = pv.toLong() * ph * planes
                    if (pv in 1..4096 && ph in 1..4096 && planes in 1..4 && 76 + count * 4 <= size) {
                        maps += GainMap(
                            top = u(0), left = u(4), bottom = u(8), right = u(12),
                            firstPlane = u(16), rowPitch = maxOf(1, u(24)), colPitch = maxOf(1, u(28)),
                            pointsV = pv, pointsH = ph,
                            spacingV = d(40), spacingH = d(48), originV = d(56), originH = d(64),
                            planes = planes,
                            gains = FloatArray(count.toInt()) { Float.fromBits(r.s32(q + 76 + 4 * it)) },
                        )
                    }
                }
                // FixVignetteRadial: k0..k4, then the optical centre (relative).
                id == 3 && size >= 56 -> vignette = DoubleArray(7) { d(8 * it) }.takeIf { v -> v.all { it.isFinite() } }
                // WarpRectilinear: planes, then per plane kr0..kr3, kt0, kt1, then the centre.
                id == 1 && size >= 4 -> {
                    val planes = u(0)
                    if (planes in 1..4 && size >= 4 + planes * 48 + 16) {
                        warp = List(planes) { pl -> DoubleArray(4) { d(4 + pl * 48 + 8 * it) } }
                            .takeIf { w -> w.all { pl -> pl.all { it.isFinite() } } }
                    }
                }
            }
            p = q + size
        }
        return Opcodes(maps, vignette, warp)
    }
}

object DngDecoder {

    fun decode(data: ByteArray, meta: RawMetadata, applyGainMaps: Boolean = true): RawImage {
        val l = meta.layout as DngLayout
        val spp = meta.samplesPerPixel
        val w = meta.rawWidth
        val h = meta.rawHeight
        val out = ShortArray(w * h * spp)
        val tilesAcross = (w + l.tileWidth - 1) / l.tileWidth
        var tile = 0
        var trow = 0
        while (trow < h) {
            var tcol = 0
            while (tcol < w) {
                if (tile >= l.offsets.size) throw RawFormatException("DNG is missing image tiles")
                val off = l.offsets[tile].toInt()
                val end = minOf(data.size.toLong(), l.offsets[tile] + l.byteCounts[tile]).toInt()
                when {
                    l.floatScale != 0f -> floatTile(data, off, end, l, out, w, h, spp, trow, tcol)
                    l.compression == 1 -> unpackTile(data, off, end, l, out, w, h, spp, trow, tcol)
                    l.compression == 7 -> ljpegTile(data, off, end, l, out, w, h, spp, trow, tcol, meta.isLinear)
                }
                tile++
                tcol += l.tileWidth
            }
            trow += l.tileLength
            if (tilesAcross == 0) break
        }
        l.linearization?.let { table ->
            val last = table.size - 1
            for (i in out.indices) out[i] = table[minOf(out[i].toInt() and 0xFFFF, last)].toShort()
        }
        if (applyGainMaps && !meta.isLinear) {
            l.gainMaps.forEach { applyGainMap(it, out, meta, l) }
            l.vignette?.let { applyVignette(it, out, meta, l) }
        }
        return RawImage(w, h, out, meta)
    }

    /** Uncompressed data: 16-bit words in file byte order, or MSB-first packed samples with byte-aligned rows. */
    private fun unpackTile(data: ByteArray, off: Int, end: Int, l: DngLayout, out: ShortArray, w: Int, h: Int, spp: Int, trow: Int, tcol: Int) {
        val rowSamples = l.tileWidth * spp
        val rowBytes = (rowSamples * l.bitsPerSample + 7) / 8
        val rows = minOf(l.tileLength, h - trow)
        val cols = minOf(l.tileWidth, w - tcol)
        for (r in 0 until rows) {
            val rowStart = off + r * rowBytes
            if (rowStart >= end) return
            val dst = ((trow + r) * w + tcol) * spp
            if (l.bitsPerSample == 16) {
                var p = rowStart
                for (i in 0 until cols * spp) {
                    if (p + 1 >= data.size) return
                    val a = data[p].toInt() and 0xFF; val b = data[p + 1].toInt() and 0xFF
                    out[dst + i] = (if (l.bigEndian) (a shl 8) or b else (b shl 8) or a).toShort()
                    p += 2
                }
            } else {
                val br = BitReader(data, rowStart, end)
                for (i in 0 until cols * spp) out[dst + i] = br.get(l.bitsPerSample).toShort()
            }
        }
    }

    /**
     * Floating-point samples (16, 24 or 32 bit), uncompressed or deflated with the DNG
     * floating-point predictor, scaled to 16-bit integers by [DngLayout.floatScale].
     */
    private fun floatTile(data: ByteArray, off: Int, end: Int, l: DngLayout, out: ShortArray, w: Int, h: Int, spp: Int, trow: Int, tcol: Int) {
        val bytes = l.bitsPerSample / 8
        val rowSamples = l.tileWidth * spp
        val rowBytes = rowSamples * bytes
        val rows = minOf(l.tileLength, h - trow)
        val cols = minOf(l.tileWidth, w - tcol)
        val buf: ByteArray
        var p0: Int
        if (l.compression == 8) {
            val inf = java.util.zip.Inflater()
            inf.setInput(data, off, end - off)
            buf = ByteArray(rowBytes * l.tileLength)
            var n = 0
            while (n < buf.size && !inf.finished()) {
                val k = inf.inflate(buf, n, buf.size - n)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            inf.end()
            p0 = 0
        } else {
            buf = data
            p0 = off
        }
        val xFactor = when (l.predictor) { 34894 -> 2; 34895 -> 4; else -> 1 }
        val shuffled = l.compression == 8 && (l.predictor == 3 || xFactor > 1)
        val row = ByteArray(rowBytes)
        for (r in 0 until rows) {
            val start = p0 + r * rowBytes
            if (start + rowBytes > buf.size) return
            System.arraycopy(buf, start, row, 0, rowBytes)
            if (shuffled) {
                // Undo the byte-wise horizontal differencing...
                val stride = spp * xFactor
                for (i in stride until rowBytes) row[i] = (row[i] + row[i - stride]).toByte()
            }
            val dst = ((trow + r) * w + tcol) * spp
            for (i in 0 until cols * spp) {
                // ...and the byte-plane shuffle: plane k holds byte k (most significant first) of every sample.
                val bits = if (shuffled) {
                    var v = 0
                    for (k in 0 until bytes) v = (v shl 8) or (row[k * rowSamples + i].toInt() and 0xFF)
                    v
                } else {
                    var v = 0
                    for (k in 0 until bytes) {
                        val b = row[i * bytes + if (l.bigEndian) k else bytes - 1 - k].toInt() and 0xFF
                        v = (v shl 8) or b
                    }
                    v
                }
                val f = when (bytes) {
                    2 -> Float.fromBits(halfToFloatBits(bits))
                    3 -> Float.fromBits(fp24ToFloatBits(bits))
                    else -> Float.fromBits(bits)
                }
                val v = (maxOf(f, 0f) * l.floatScale)
                out[dst + i] = (if (v >= 65535f) 65535 else v.toInt()).toShort()
            }
        }
    }

    /** IEEE half to single precision, as the DNG SDK does it (NaN becomes 0, infinity the largest half). */
    private fun halfToFloatBits(h: Int): Int {
        val sign = (h shr 15) and 1
        var exponent = (h shr 10) and 0x1F
        var mantissa = h and 0x3FF
        if (exponent == 0) {
            if (mantissa == 0) return sign shl 31
            while (mantissa and 0x400 == 0) { mantissa = mantissa shl 1; exponent-- }
            exponent++
            mantissa = mantissa and 0x400.inv()
        } else if (exponent == 31) {
            return if (mantissa == 0) (sign shl 31) or ((0x1E + 127 - 15) shl 23) or (0x3FF shl 13) else 0
        }
        return (sign shl 31) or ((exponent + 127 - 15) shl 23) or (mantissa shl 13)
    }

    private fun fp24ToFloatBits(b: Int): Int {
        val sign = (b shr 23) and 1
        var exponent = (b shr 16) and 0x7F
        var mantissa = b and 0xFFFF
        if (exponent == 0) {
            if (mantissa == 0) return sign shl 31
            while (mantissa and 0x10000 == 0) { mantissa = mantissa shl 1; exponent-- }
            exponent++
            mantissa = mantissa and 0x10000.inv()
        } else if (exponent == 127) {
            return if (mantissa == 0) (sign shl 31) or ((0x7E + 128 - 64) shl 23) or (0xFFFF shl 7) else 0
        }
        return (sign shl 31) or ((exponent + 128 - 64) shl 23) or (mantissa shl 7)
    }

    /** One lossless JPEG tile: samples fill the tile row by row, as in dcraw's lossless_dng_load_raw. */
    private fun ljpegTile(data: ByteArray, off: Int, end: Int, l: DngLayout, out: ShortArray, w: Int, h: Int, spp: Int, trow: Int, tcol: Int, linear: Boolean) {
        val jh = LjpegStream.open(data, off, end, isDng = true, legacyDiff16 = l.legacyLjpeg)
        val wrap = minOf(l.tileWidth, w)
        var row = 0
        var col = 0
        var sample = 0
        for (jrow in 0 until jh.high) {
            val rp = jh.row(jrow)
            val n = jh.wide * jh.clrs
            for (i in 0 until n) {
                val y = trow + row; val x = tcol + col
                if (y < h && x < w) out[(y * w + x) * spp + sample] = rp[i].toShort()
                if (++sample >= spp) {
                    sample = 0
                    if (++col >= wrap) { col = 0; row++ }
                }
            }
            if (row >= l.tileLength) break
        }
    }

    /**
     * FixVignetteRadial: gain 1 + k0 r^2 + k1 r^4 + k2 r^6 + k3 r^8 + k4 r^10, with r = 1 at the
     * active-area corner farthest from the optical centre.
     */
    private fun applyVignette(v: DoubleArray, out: ShortArray, meta: RawMetadata, l: DngLayout) {
        val w = meta.rawWidth
        val cx = v[5] * l.activeWidth; val cy = v[6] * l.activeHeight
        val mx = maxOf(cx, l.activeWidth - cx); val my = maxOf(cy, l.activeHeight - cy)
        val inv = 1.0 / (mx * mx + my * my)
        for (y in 0 until l.activeHeight) {
            val dy = y + 0.5 - cy
            val sy = l.activeTop + y
            for (x in 0 until l.activeWidth) {
                val dx = x + 0.5 - cx
                val r2 = (dx * dx + dy * dy) * inv
                val gain = 1 + r2 * (v[0] + r2 * (v[1] + r2 * (v[2] + r2 * (v[3] + r2 * v[4]))))
                val sx = l.activeLeft + x
                val i = sy * w + sx
                val black = meta.blackAt(sy, sx)
                val s = (out[i].toInt() and 0xFFFF) - black
                if (s > 0) out[i] = (black + s * gain).roundToInt().coerceIn(0, 65535).toShort()
            }
        }
    }

    /**
     * Multiplies the (black-subtracted) samples in the map's rectangle by the bilinearly
     * interpolated gain. Coordinates are relative to the active area, as in the DNG SDK.
     */
    private fun applyGainMap(g: GainMap, out: ShortArray, meta: RawMetadata, l: DngLayout) {
        val w = meta.rawWidth
        val top = maxOf(0, g.top); val left = maxOf(0, g.left)
        val bottom = minOf(l.activeHeight, g.bottom); val right = minOf(l.activeWidth, g.right)
        if (bottom <= top || right <= left) return
        // One map for all of a CFA, or one per colour (written for after demosaicing): pick by site colour.
        val perColour = g.planes > 1 || g.firstPlane > 0
        val gains = g.gains
        val ph = g.pointsH
        var y = top
        while (y < bottom) {
            val ry = y.toDouble() / l.activeHeight
            val my = ((ry - g.originV) / g.spacingV).coerceIn(0.0, (g.pointsV - 1).toDouble())
            val y0 = minOf(my.toInt(), g.pointsV - 1); val y1 = minOf(y0 + 1, g.pointsV - 1)
            val fy = (my - y0).toFloat()
            val sy = l.activeTop + y
            var x = left
            while (x < right) {
                val rx = x.toDouble() / l.activeWidth
                val mx = ((rx - g.originH) / g.spacingH).coerceIn(0.0, (ph - 1).toDouble())
                val x0 = minOf(mx.toInt(), ph - 1); val x1 = minOf(x0 + 1, ph - 1)
                val fx = (mx - x0).toFloat()
                val p = g.planes
                val sx = l.activeLeft + x
                val pl = if (perColour) meta.cfaColor(sy, sx) - g.firstPlane else 0
                if (pl < 0 || pl >= p) { x += g.colPitch; continue }
                val a = gains[(y0 * ph + x0) * p + pl]; val b = gains[(y0 * ph + x1) * p + pl]
                val c = gains[(y1 * ph + x0) * p + pl]; val d = gains[(y1 * ph + x1) * p + pl]
                val gain = (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy
                val i = sy * w + sx
                val black = meta.blackAt(sy, sx)
                val v = (out[i].toInt() and 0xFFFF) - black
                if (v > 0) out[i] = (black + v * gain).roundToInt().coerceIn(0, 65535).toShort()
                x += g.colPitch
            }
            y += g.rowPitch
        }
    }
}
