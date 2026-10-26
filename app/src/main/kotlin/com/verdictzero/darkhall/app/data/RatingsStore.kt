package com.verdictzero.darkhall.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Star ratings (1-5; absent means unrated) per raw file key, in one small JSON file. */
object RatingsStore {
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, "ratings.json")

    fun all(context: Context): Map<String, Int> = synchronized(lock) {
        runCatching {
            val f = file(context)
            if (!f.exists()) return emptyMap()
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { o.getInt(it) }.filterValues { it in 1..5 }
        }.getOrDefault(emptyMap())
    }

    fun get(context: Context, key: String): Int = all(context)[key] ?: 0

    /** Sets [rating] (0 clears it) for every key in [keys]; returns all ratings. */
    fun set(context: Context, keys: Collection<String>, rating: Int): Map<String, Int> = synchronized(lock) {
        val map = all(context).toMutableMap()
        for (k in keys) if (rating in 1..5) map[k] = rating else map.remove(k)
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        file(context).writeText(o.toString())
        map
    }
}
