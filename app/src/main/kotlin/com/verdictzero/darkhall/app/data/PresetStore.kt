package com.verdictzero.darkhall.app.data

import android.content.Context
import com.verdictzero.darkhall.core.develop.DevelopParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A named set of slider settings and look, to apply to other photos in one go. */
data class Preset(val name: String, val params: DevelopParams)

/** Saved presets, in the order they were made, in one JSON file. */
object PresetStore {
    private fun file(context: Context) = File(context.filesDir, "presets.json")

    fun all(context: Context): List<Preset> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            EditsStore.fromJson(o.getJSONObject("params").toString(), DevelopParams.NEUTRAL)?.let { Preset(o.getString("name"), it) }
        }.filterNotNull()
    }.getOrDefault(emptyList())

    /** Saves [params] as [name], replacing a preset of that name. */
    fun save(context: Context, name: String, params: DevelopParams): List<Preset> {
        val list = all(context).filter { it.name != name } + Preset(name, params)
        write(context, list)
        return list
    }

    fun delete(context: Context, name: String): List<Preset> {
        val list = all(context).filter { it.name != name }
        write(context, list)
        return list
    }

    private fun write(context: Context, list: List<Preset>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("name", p.name).put("params", JSONObject(EditsStore.toJson(p.params))))
        file(context).writeText(arr.toString())
    }
}
