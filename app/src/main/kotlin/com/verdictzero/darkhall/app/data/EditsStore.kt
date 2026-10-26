package com.verdictzero.darkhall.app.data

import android.content.Context
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Crop
import com.verdictzero.darkhall.core.develop.DevelopParams
import org.json.JSONObject
import java.io.File

/** Non-destructive edits, one small JSON file per raw file key. */
object EditsStore {

    private fun file(context: Context, key: String) =
        File(File(context.filesDir, "edits").apply { mkdirs() }, "$key.json")

    fun load(context: Context, key: String, defaults: DevelopParams): DevelopParams? {
        val f = file(context, key)
        if (!f.exists()) return null
        return try {
            val obj = JSONObject(f.readText()).getJSONObject("params")
            val map = HashMap<String, Float>()
            for (a in Adjustment.entries) if (obj.has(a.name)) map[a.name] = obj.getDouble(a.name).toFloat()
            DevelopParams.fromMap(map, defaults, look = obj.optString("look").ifEmpty { null })
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, key: String, params: DevelopParams) {
        val obj = JSONObject()
        obj.put("version", 1)
        obj.put("params", JSONObject(toJson(params)))
        file(context, key).writeText(obj.toString())
    }

    fun clear(context: Context, key: String) {
        file(context, key).delete()
    }

    fun exists(context: Context, key: String) = file(context, key).exists()

    fun toJson(params: DevelopParams): String {
        val p = JSONObject()
        for ((k, v) in params.toMap()) p.put(k, v.toDouble())
        params.look?.let { p.put("look", it) }
        return p.toString()
    }

    fun fromJson(json: String, base: DevelopParams): DevelopParams? = try {
        val obj = JSONObject(json)
        val map = HashMap<String, Float>()
        for (a in Adjustment.entries) if (obj.has(a.name)) map[a.name] = obj.getDouble(a.name).toFloat()
        DevelopParams.fromMap(map, base, look = obj.optString("look").ifEmpty { null })
    } catch (e: Exception) {
        null
    }

    /**
     * Applies copied sliders to another file. Straighten and the crop are per-shot, so the
     * target keeps its own; everything else is taken from the copy.
     */
    fun paste(context: Context, key: String, copied: DevelopParams) {
        val existing = load(context, key, DevelopParams.NEUTRAL)
        save(context, key, keepPerShot(copied, existing ?: DevelopParams.NEUTRAL))
    }

    /** [copied] with the per-shot settings (Straighten, the crop) of [target]. */
    fun keepPerShot(copied: DevelopParams, target: DevelopParams): DevelopParams =
        Crop.copy(copied.with(Adjustment.STRAIGHTEN, target[Adjustment.STRAIGHTEN]), target)
}
