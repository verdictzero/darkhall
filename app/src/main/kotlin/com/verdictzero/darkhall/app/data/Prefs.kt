package com.verdictzero.darkhall.app.data

import android.content.Context
import android.net.Uri

/** Small persisted settings: the library folder and individually opened files. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("darkhall", Context.MODE_PRIVATE)

    var folderUri: Uri?
        get() = sp.getString("folder", null)?.let { Uri.parse(it) }
        set(v) { sp.edit().putString("folder", v?.toString()).apply() }

    var extraFiles: List<Uri>
        get() = sp.getStringSet("files", emptySet())!!.map { Uri.parse(it) }
        set(v) { sp.edit().putStringSet("files", v.map { it.toString() }.toSet()).apply() }

    var previewLongEdge: Int
        get() = sp.getInt("previewLongEdge", 1280)
        set(v) { sp.edit().putInt("previewLongEdge", v).apply() }
}
