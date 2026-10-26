package com.verdictzero.darkhall.app.data

import android.content.Context
import com.verdictzero.darkhall.core.raw.ShotFacts
import org.json.JSONObject
import java.io.File

/** [ShotFacts] per raw file key, one small JSON file each, so a folder's facts load without opening raws. */
object FactsStore {
    private const val VERSION = 1

    private fun file(context: Context, key: String) = File(File(context.filesDir, "facts").apply { mkdirs() }, "$key.json")

    fun load(context: Context, key: String): ShotFacts? = runCatching {
        val f = file(context, key)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        if (o.optInt("v") != VERSION) return null
        fun d(name: String) = if (o.has(name) && !o.isNull(name)) o.getDouble(name) else null
        ShotFacts(
            make = o.optString("make"), model = o.optString("model"), dateTime = o.optString("dateTime"),
            iso = o.optInt("iso"), exposureTime = o.optDouble("exposure", 0.0), fNumber = o.optDouble("f", 0.0),
            focalLength = o.optDouble("focal", 0.0), width = o.optInt("w"), height = o.optInt("h"),
            depth = d("depth"), temperature = d("temp"), pressureHpa = d("pressure"), altitude = d("alt"),
            latitude = d("lat"), longitude = d("lon"),
        )
    }.getOrNull()

    fun save(context: Context, key: String, f: ShotFacts) {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("make", f.make); o.put("model", f.model); o.put("dateTime", f.dateTime)
        o.put("iso", f.iso); o.put("exposure", f.exposureTime); o.put("f", f.fNumber); o.put("focal", f.focalLength)
        o.put("w", f.width); o.put("h", f.height)
        f.depth?.let { o.put("depth", it) }; f.temperature?.let { o.put("temp", it) }
        f.pressureHpa?.let { o.put("pressure", it) }; f.altitude?.let { o.put("alt", it) }
        f.latitude?.let { o.put("lat", it) }; f.longitude?.let { o.put("lon", it) }
        runCatching { file(context, key).writeText(o.toString()) }
    }
}
