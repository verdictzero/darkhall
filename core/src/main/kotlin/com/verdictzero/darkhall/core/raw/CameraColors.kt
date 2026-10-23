package com.verdictzero.darkhall.core.raw

/**
 * Per-camera colour data for formats that do not embed a usable matrix: the XYZ (D65) to
 * camera matrix Adobe DNG Converter uses, plus black/white overrides for a few bodies.
 *
 * The table (`camera_colors.tsv`, generated from LibRaw's colordata.cpp by
 * tools/colors/extract_colors.py) is matched like LibRaw does it: by maker, then by the first
 * row whose model prefix matches, so more specific rows come first.
 */
object CameraColors {

    class Entry(
        val maker: String,
        val prefix: String,
        /** Positive: black level to use; negative: black to use only when the file gives none; 0: none. */
        val black: Int,
        /** White level override, or 0. */
        val white: Int,
        /** XYZ -> camera, row-major 3x3, or null when the row only carries levels. */
        val xyzToCam: FloatArray?,
    )

    private val table: List<Entry> by lazy { load() }

    private fun load(): List<Entry> {
        val stream = CameraColors::class.java.getResourceAsStream("/darkhall/camera_colors.tsv") ?: return emptyList()
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 5) return@mapNotNull null
                val m = f[4].split(',').map { it.trim().toFloat() / 10000f }
                Entry(f[0], f[1], f[2].toInt(), f[3].toInt(), if (m.size >= 9 && m.any { it != 0f }) m.take(9).toFloatArray() else null)
            }.toList()
        }
    }

    /** Maps an EXIF make string onto the maker names the table uses. */
    fun makerOf(make: String, model: String = ""): String {
        val m = make.trim().uppercase()
        return when {
            // Pentax bodies made under Ricoh ownership say "RICOH IMAGING COMPANY" but "PENTAX K-..".
            m.startsWith("RICOH") && model.trim().uppercase().startsWith("PENTAX") -> "Pentax"
            m.startsWith("OLYMPUS") || m.startsWith("OM DIGITAL") || m.startsWith("OM SYSTEM") -> "Olympus"
            m.startsWith("NIKON") -> "Nikon"
            m.startsWith("CANON") -> "Canon"
            m.startsWith("SONY") -> "Sony"
            m.startsWith("PANASONIC") -> "Panasonic"
            m.startsWith("LEICA") -> "Leica"
            m.startsWith("PENTAX") || m.startsWith("ASAHI") -> "Pentax"
            m.startsWith("RICOH") -> "Ricoh"
            m.startsWith("SAMSUNG") -> "Samsung"
            m.startsWith("FUJI") -> "Fujifilm"
            m.startsWith("HASSELBLAD") -> "Hasselblad"
            m.startsWith("PHASE ONE") -> "PhaseOne"
            m.startsWith("SIGMA") -> "Sigma"
            m.startsWith("KONICA") || m.startsWith("MINOLTA") -> "Minolta"
            else -> make.trim().substringBefore(' ')
        }
    }

    /** Strips the maker's name from the front of a model string ("NIKON D750" -> "D750"). */
    fun normalizeModel(make: String, model: String): String {
        var m = model.trim()
        val words = listOf(make.trim(), make.trim().substringBefore(' '), makerOf(make, model))
        for (w in words) {
            if (w.isNotEmpty() && m.length > w.length && m.startsWith(w, ignoreCase = true)) {
                m = m.substring(w.length).trim()
                break
            }
        }
        return m
    }

    private fun squash(s: String) = s.filterNot { it == ' ' || it == '_' }.lowercase()

    fun lookup(make: String, model: String): Entry? {
        val maker = makerOf(make, model)
        val m = squash(normalizeModel(make, model))
        return table.firstOrNull { it.maker.equals(maker, ignoreCase = true) && m.startsWith(squash(it.prefix)) }
    }
}
