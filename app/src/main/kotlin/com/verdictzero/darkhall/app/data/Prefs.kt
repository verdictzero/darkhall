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

    /** Sliders copied with "Copy settings", as JSON; null when nothing has been copied. */
    var copiedSettings: String?
        get() = sp.getString("copied", null)
        set(v) { sp.edit().putString("copied", v).apply() }

    /** User switch for GPU preview rendering (on by default). */
    var gpuEnabled: Boolean
        get() = sp.getBoolean("gpuEnabled", true)
        set(v) { sp.edit().putBoolean("gpuEnabled", v).apply() }

    /** GPU + driver description that passed the CPU comparison, so the check runs once per driver. */
    var gpuVerified: String?
        get() = sp.getString("gpuVerified", null)
        set(v) { sp.edit().putString("gpuVerified", v).apply() }

    /** GPU + driver description that failed the comparison; the GPU stays off for it. */
    var gpuRejected: String?
        get() = sp.getString("gpuRejected", null)
        set(v) { sp.edit().putString("gpuRejected", v).apply() }

    var previewLongEdge: Int
        get() = sp.getInt("previewLongEdge", 1280)
        set(v) { sp.edit().putInt("previewLongEdge", v).apply() }
}
