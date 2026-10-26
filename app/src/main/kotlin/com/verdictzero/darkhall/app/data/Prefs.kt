package com.verdictzero.darkhall.app.data

import android.content.Context
import android.net.Uri
import com.verdictzero.darkhall.core.export.ExportFormat
import com.verdictzero.darkhall.core.export.ExportSettings

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

    /** Apply auto tone to photos opened without saved edits. */
    var autoToneOnOpen: Boolean
        get() = sp.getBoolean("autoToneOnOpen", false)
        set(v) { sp.edit().putBoolean("autoToneOnOpen", v).apply() }

    /** Favorite looks by id, in the order they were added. */
    var favoriteLooks: List<String>
        get() = sp.getString("favoriteLooks", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) { sp.edit().putString("favoriteLooks", v.joinToString("\n")).apply() }

    /** The last export settings used, offered again next time. */
    var exportSettings: ExportSettings
        get() = ExportSettings(
            format = ExportFormat.entries.firstOrNull { it.name == sp.getString("exportFormat", null) } ?: ExportFormat.JPEG,
            jpegQuality = sp.getInt("exportQuality", 95),
            sixteenBit = sp.getBoolean("export16", false),
            tiffCompressed = sp.getBoolean("exportTiffZip", true),
            longEdge = sp.getInt("exportLongEdge", 0),
        )
        set(v) {
            sp.edit().putString("exportFormat", v.format.name).putInt("exportQuality", v.jpegQuality)
                .putBoolean("export16", v.sixteenBit).putBoolean("exportTiffZip", v.tiffCompressed)
                .putInt("exportLongEdge", v.longEdge).apply()
        }

    var previewLongEdge: Int
        get() = sp.getInt("previewLongEdge", 1280)
        set(v) { sp.edit().putInt("previewLongEdge", v).apply() }
}
