package com.verdictzero.darkhall.core.raw

/** Helpers shared by the TIFF-based camera formats (ARW, NEF, PEF, CR2). */
internal object TiffRaw {

    /** Larger than any sensor shipped; guards allocations against corrupt headers. */
    const val MAX_DIMENSION = 16384

    class Header(val reader: ByteReader, val roots: List<Ifd>, val all: List<Ifd>) {
        val ifd0 get() = roots[0]
    }

    fun read(data: ByteArray): Header {
        val r = ByteReader(data, bigEndian = data[0] == 'M'.code.toByte())
        val roots = Tiff.chain(r, r.u32(4))
        if (roots.isEmpty()) throw RawFormatException("Corrupt raw header: no directories")
        return Header(r, roots, Tiff.tree(r, roots))
    }

    /**
     * The directory holding the sensor data: the largest image by width x height x bits,
     * skipping RGB/YCbCr previews (LibRaw's rule).
     */
    fun largestRawIfd(ifds: List<Ifd>): Ifd? = ifds.filter { ifd ->
        val w = ifd.long(0x0100) ?: 0L
        val h = ifd.long(0x0101) ?: 0L
        val comp = ifd.int(0x0103) ?: 1
        val spp = ifd.int(0x0115) ?: 1
        val photometric = ifd.int(0x0106)
        w in 16..MAX_DIMENSION && h in 16..MAX_DIMENSION && !(comp == 6 && spp == 3) &&
            photometric != 2 && photometric != 6 && (ifd[0x0111] != null || ifd[0x0144] != null)
    }.maxByOrNull { (it.long(0x0100) ?: 0L) * (it.long(0x0101) ?: 0L) * (it.int(0x0102) ?: 8) }

    /** TIFF/EP CFAPattern of a raw directory, else the EXIF one, else RGGB. */
    fun cfa(ifd: Ifd, exif: ExifInfo): IntArray {
        val dim = ifd[0x828D]?.ints(2)
        if (dim != null && (dim[0] != 2 || dim[1] != 2)) throw RawFormatException("Only 2x2 colour filter arrays are supported")
        ifd[0x828E]?.ints(4)?.let { p -> if (p.all { it in 0..2 }) return p }
        return exif.cfa ?: intArrayOf(0, 1, 1, 2)
    }

    /** DefaultCropOrigin/Size (TIFF/EP, used by ARW and some others), validated against the sensor. */
    fun defaultCrop(ifd: Ifd, w: Int, h: Int): Crop? {
        val o = ifd[0xC61F]?.doubles(2) ?: return null
        val s = ifd[0xC620]?.doubles(2) ?: return null
        val c = Crop(o[0].toInt(), o[1].toInt(), s[0].toInt(), s[1].toInt())
        return c.takeIf { valid(it, w, h) }
    }

    fun valid(c: Crop, w: Int, h: Int) = c.left >= 0 && c.top >= 0 && c.width > 16 && c.height > 16 &&
        c.left + c.width <= w && c.top + c.height <= h

    /**
     * Maps black levels given in R, Gr (green on red rows), Gb, B order onto the four
     * positions of [cfa] (indexed `(row & 1) * 2 + (col & 1)` at the sensor origin).
     */
    fun blackFromRggb(v: IntArray, cfa: IntArray): IntArray = IntArray(4) { p ->
        when (cfa[p]) {
            0 -> v[0]
            2 -> v[3]
            else -> if (cfa[(p and 2) or (1 - (p and 1))] == 0) v[1] else v[2]
        }
    }

    /** As-shot white balance from levels in R, G, G, B order. */
    fun wbFromRggb(v: DoubleArray): FloatArray? {
        val g = (v[1] + v[2]) / 2
        if (v[0] <= 0 || g <= 0 || v[3] <= 0) return null
        return floatArrayOf((v[0] / g).toFloat(), 1f, (v[3] / g).toFloat())
    }

    /** Black and white levels after applying the camera table's overrides the way LibRaw does. */
    fun tableLevels(make: String, model: String, black: IntArray?, white: Int?): Pair<IntArray?, Int?> {
        val e = CameraColors.lookup(make, model) ?: return black to white
        var b = black
        if (e.black > 0 || (e.black < 0 && (b == null || b.all { it == 0 }))) b = IntArray(4) { kotlin.math.abs(e.black) }
        val w = if (e.white > 0) e.white else white
        return b to w
    }
}
