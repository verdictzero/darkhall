package com.verdictzero.darkhall.core.raw

/**
 * Entry point for reading any supported raw file: identifies the container, parses its
 * metadata and decodes the sensor data.
 */
object RawFormats {

    /** File extensions Darkhall lists and opens. */
    val EXTENSIONS = setOf("orf", "ori", "dng", "arw", "nef", "nrw", "cr2", "rw2", "rwl", "pef", "raf")

    fun hasRawExtension(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Quick check of the first bytes: a TIFF-style header any supported format starts with. */
    fun looksLikeRaw(head: ByteArray): Boolean {
        if (head.size < 8) return false
        if (RafParser.isRaf(head)) return true
        val sig = String(head, 0, 4, Charsets.ISO_8859_1)
        return sig == "II*\u0000" || sig == "MM\u0000*" || sig == "IIRO" || sig == "IIRS" || sig == "MMOR" || sig == "IIU\u0000"
    }

    fun parse(data: ByteArray): RawMetadata {
        val meta = try {
            identifyAndParse(data)
        } catch (e: RawFormatException) {
            throw e
        } catch (e: RuntimeException) {
            throw RawFormatException("Corrupt raw header (${e.javaClass.simpleName})", e)
        }
        return withTableColors(meta)
    }

    /**
     * Decodes the sensor data. [corrections] applies the file's own fix-ups that LibRaw leaves
     * out of its raw data (DNG lens-shading gain maps); tests comparing against LibRaw turn it off.
     */
    fun decode(data: ByteArray, meta: RawMetadata, corrections: Boolean = true): RawImage = try {
        // No raw codec gets below a bit per sample: a corrupt size that would need a huge
        // buffer for a small file is rejected before anything is allocated.
        val samples = meta.rawWidth.toLong() * meta.rawHeight * meta.samplesPerPixel
        if (meta.layout.dataOffset !in 0 until data.size || samples > (data.size - meta.layout.dataOffset) * 8) {
            throw RawFormatException("Raw size does not match the data (file truncated or damaged)")
        }
        when (meta.layout) {
            is OrfLayout -> OlympusDecoder.decode(data, meta)
            is DngLayout -> DngDecoder.decode(data, meta, applyGainMaps = corrections)
            is SonyLayout -> SonyDecoder.decode(data, meta)
            is NikonLayout -> NikonDecoder.decode(data, meta)
            is Cr2Layout -> Cr2Decoder.decode(data, meta)
            is Rw2Layout -> Rw2Decoder.decode(data, meta)
            is PefLayout -> PefDecoder.decode(data, meta)
            is RafLayout -> RafDecoder.decode(data, meta)
        }
    } catch (e: RawFormatException) {
        throw e
    } catch (e: RuntimeException) {
        throw RawFormatException("Raw data is corrupt (${e.javaClass.simpleName})", e)
    }

    private fun identifyAndParse(data: ByteArray): RawMetadata {
        if (data.size < 16) throw RawFormatException("File too small")
        val sig = String(data, 0, 16, Charsets.ISO_8859_1)
        when {
            sig.startsWith("FUJIFILM") -> return RafParser.parse(data)
            sig.substring(4, 12) == "ftypcrx " -> throw RawFormatException("Canon CR3 files are not supported")
            sig.substring(6, 14) == "HEAPCCDR" -> throw RawFormatException("Canon CRW files are not supported")
        }
        if (OrfParser.isOrf(data)) return OrfParser.parse(data)
        if (Rw2Parser.isRw2(data)) return Rw2Parser.parse(data)
        if (!looksLikeRaw(data)) throw RawFormatException("Not a raw file")
        val r = ByteReader(data, bigEndian = data[0] == 'M'.code.toByte())
        val roots = Tiff.chain(r, r.u32(4))
        val ifd0 = roots.firstOrNull() ?: throw RawFormatException("Corrupt raw header")
        if (DngParser.isDng(r, Tiff.tree(r, roots))) return DngParser.parse(data)
        val make = ifd0.string(0x010F).uppercase()
        return when {
            make.startsWith("OLYMPUS") || make.startsWith("OM DIGITAL") -> OrfParser.parse(data)
            make.startsWith("SONY") -> SonyParser.parse(data)
            make.startsWith("NIKON") -> NikonParser.parse(data)
            make.startsWith("CANON") -> Cr2Parser.parse(data)
            make.startsWith("PENTAX") || make.startsWith("RICOH") || make.startsWith("ASAHI") -> PefParser.parse(data)
            else -> throw RawFormatException("Unsupported camera (${ifd0.string(0x010F)} ${ifd0.string(0x0110)})".trim())
        }
    }

    /**
     * The shot's facts from the first part of a file alone, by reading only its EXIF, for
     * files whose full parse needs the raw data (the picture's size is then left at 0).
     */
    fun factsFromHeader(head: ByteArray): ShotFacts? = runCatching {
        if (!looksLikeRaw(head)) return@runCatching null
        // A RAF's EXIF is in its embedded JPEG.
        val tiff = if (RafParser.isRaf(head)) RafParser.exifTiff(head) ?: return@runCatching null else head
        val r = ByteReader(tiff, bigEndian = tiff[0] == 'M'.code.toByte())
        val roots = Tiff.chain(r, r.u32(4))
        val ifd0 = roots.firstOrNull() ?: return@runCatching null
        val exif = ExifInfo.read(r, ifd0, Tiff.tree(r, roots))
        ShotFacts(
            make = ifd0.string(0x010F), model = ifd0.string(0x0110), dateTime = exif.dateTime,
            iso = exif.iso, exposureTime = exif.exposureTime, fNumber = exif.fNumber, focalLength = exif.focalLength,
            width = 0, height = 0,
            depth = RawMetadata.depthFrom(exif.waterDepth, exif.pressureHpa)?.takeIf { it >= 0.3 },
            temperature = exif.ambientTemperature, pressureHpa = exif.pressureHpa,
            altitude = exif.gps?.altitude, latitude = exif.gps?.latitude, longitude = exif.gps?.longitude,
        ).takeIf { it.make.isNotBlank() || it.model.isNotBlank() }
    }.getOrNull()

    /** Where an embedded JPEG preview sits, possibly beyond the bytes read so far. */
    class PreviewLocation(val offset: Long, val length: Int, val orientation: Int)

    /**
     * Finds an embedded JPEG preview from the first part of a file ([head]) without parsing
     * the raw data, so a library grid only reads the preview's own bytes. Prefers the smallest
     * preview that is still comfortably larger than a thumbnail.
     */
    fun locatePreview(head: ByteArray, fileSize: Long): PreviewLocation? = runCatching {
        if (OrfParser.isOrf(head)) {
            val m = OrfParser.parse(head)
            return@runCatching if (m.previewLength > 0) PreviewLocation(m.previewOffset, m.previewLength, m.orientation) else null
        }
        if (RafParser.isRaf(head)) {
            val (off, len) = RafParser.preview(head) ?: return@runCatching null
            if (off + len > fileSize) return@runCatching null
            val orientation = RafParser.exifTiff(head)?.let { t ->
                val tr = ByteReader(t, bigEndian = t[0] == 'M'.code.toByte())
                Tiff.chain(tr, tr.u32(4)).firstOrNull()?.int(0x0112)
            } ?: 1
            return@runCatching PreviewLocation(off, len, orientation)
        }
        if (!looksLikeRaw(head)) return@runCatching null
        val r = ByteReader(head, bigEndian = head[0] == 'M'.code.toByte())
        val roots = Tiff.chain(r, r.u32(4))
        val ifd0 = roots.firstOrNull() ?: return@runCatching null
        val orientation = ifd0.int(0x0112) ?: 1
        class Cand(val off: Long, val len: Long, val width: Int?)
        val cands = ArrayList<Cand>()
        if (Rw2Parser.isRw2(head)) ifd0[0x002E]?.let { cands += Cand(it.offset.toLong(), it.count.toLong(), null) }
        for (ifd in Tiff.tree(r, roots)) {
            val o = ifd.long(0x0201); val n = ifd.long(0x0202)
            if (o != null && n != null) cands += Cand(o, n, ifd.int(0x0100))
            val comp = ifd.int(0x0103)
            val photometric = ifd.int(0x0106)
            // Baseline JPEG strips with a stated size (CR2's raw strip has none; DNG raws are CFA/linear).
            if ((comp == 6 || comp == 7) && ifd[0x0100] != null && (photometric == null || photometric == 6 || photometric == 2) &&
                (ifd.int(0x0115) ?: 3) == 3
            ) {
                val so = ifd[0x0111]; val sb = ifd[0x0117]
                if (so != null && sb != null && so.count == 1) cands += Cand(so.long()!!, sb.long()!!, ifd.int(0x0100))
            }
        }
        fun isJpegStart(c: Cand) = c.off + 2 > head.size || (r.u8(c.off.toInt()) == 0xFF && r.u8(c.off.toInt() + 1) == 0xD8)
        val valid = cands.filter { it.len > 1024 && it.off > 0 && it.off + it.len <= fileSize && isJpegStart(it) }
        val good = valid.filter { (it.width ?: 1000) >= 640 && it.len < 8_000_000 }
        val pick = good.minByOrNull { it.len } ?: valid.maxByOrNull { it.len } ?: return@runCatching null
        PreviewLocation(pick.off, pick.len.toInt(), orientation)
    }.getOrNull()

    /** Fills in the colour matrix from the camera table when the file carries none. */
    private fun withTableColors(meta: RawMetadata): RawMetadata {
        if (meta.cameraMatrix != null || meta.xyzToCam != null) return meta
        val entry = CameraColors.lookup(meta.make, meta.model) ?: return meta
        return meta.copy(xyzToCam = entry.xyzToCam)
    }
}
