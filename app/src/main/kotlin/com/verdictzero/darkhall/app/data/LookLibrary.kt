package com.verdictzero.darkhall.app.data

import android.content.Context
import android.util.LruCache
import androidx.annotation.StringRes
import com.verdictzero.darkhall.R
import com.verdictzero.darkhall.core.develop.LookSource
import com.verdictzero.darkhall.core.develop.Lut
import org.json.JSONObject

/** One bundled look: [group] is the top-level shelf, [category] the section inside it. */
data class LookInfo(val id: String, val name: String, val group: String, val category: String, val source: String)

/**
 * The bundled look (3D LUT) library in assets/looks, built by tools/looks/build_looks.py:
 * the RawTherapee Film Simulation Collection (CC BY-SA 4.0) and Darkhall's own looks (CC0).
 */
object LookLibrary {
    /** Shelf order in the browser. */
    val GROUPS = listOf("Darkhall", "Film color", "Film B&W", "Creative")

    /**
     * Translations of the shelf and section names in index.json (which stay the English keys).
     * Names not listed here, like the "Darkhall" shelf, are shown as they are.
     */
    private val LABELS = mapOf(
        "Film color" to R.string.looks_group_film_color,
        "Film B&W" to R.string.looks_group_film_bw,
        "Creative" to R.string.looks_group_creative,
        "Instant" to R.string.looks_category_instant,
        "Color negative" to R.string.looks_category_color_negative,
        "Slide" to R.string.looks_category_slide,
        "Cross process" to R.string.looks_category_cross_process,
        "Creative pack" to R.string.looks_category_creative_pack,
        "Modern" to R.string.looks_category_modern,
        "Classic" to R.string.looks_category_classic,
        "Special" to R.string.looks_category_special,
        "Black & white" to R.string.looks_category_black_white,
        "Cinematic" to R.string.looks_category_cinematic,
        "Split tone" to R.string.looks_category_split_tone,
        "Underwater" to R.string.looks_category_underwater,
        "Faded" to R.string.looks_category_faded,
        "Duotone" to R.string.looks_category_duotone,
    )

    /** String resource for a shelf ([LookInfo.group]) or section ([LookInfo.category]) name, or null to show it as is. */
    @StringRes fun labelRes(name: String): Int? = LABELS[name]

    @Volatile private var index: List<LookInfo>? = null
    private val cache = LruCache<String, Lut>(32)

    fun all(context: Context): List<LookInfo> = index ?: synchronized(this) {
        index ?: load(context).also { index = it }
    }

    private fun load(context: Context): List<LookInfo> {
        val text = context.assets.open("looks/index.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("looks")
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            LookInfo(o.getString("id"), o.getString("name"), o.getString("group"), o.getString("category"), o.getString("source"))
        }.sortedWith(compareBy({ GROUPS.indexOf(it.group).let { g -> if (g < 0) GROUPS.size else g } }))
    }

    fun info(context: Context, id: String?): LookInfo? = id?.let { i -> all(context).firstOrNull { it.id == i } }

    /** Decoded table, or null if [id] isn't bundled (e.g. edits from a newer version). */
    fun lut(context: Context, id: String): Lut? {
        cache.get(id)?.let { return it }
        val lut = runCatching {
            context.assets.open("looks/$id.dlut").use { Lut.decode(it.readBytes()) }
        }.getOrNull() ?: return null
        cache.put(id, lut)
        return lut
    }

    fun source(context: Context): LookSource {
        val app = context.applicationContext
        return LookSource { id -> lut(app, id) }
    }

    fun licenses(context: Context): String =
        context.assets.open("looks/LICENSES.txt").bufferedReader().use { it.readText() }
}
