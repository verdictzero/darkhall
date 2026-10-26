package com.verdictzero.darkhall.app.ui

import com.verdictzero.darkhall.core.export.ExportSettings
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verdictzero.darkhall.app.data.EditsStore
import com.verdictzero.darkhall.app.data.ExportQueue
import com.verdictzero.darkhall.app.data.LookLibrary
import com.verdictzero.darkhall.app.data.RawRepository
import com.verdictzero.darkhall.app.data.Prefs
import com.verdictzero.darkhall.app.data.RawFile
import com.verdictzero.darkhall.core.develop.AutoTone
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.RawFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryState(
    val folderName: String? = null,
    val files: List<RawFile> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val selected: Set<String> = emptySet(),
    val edited: Set<String> = emptySet(),
    val canPaste: Boolean = false,
    /** "Auto tone 3 of 12" while a batch auto tone runs. */
    val autoProgress: String? = null,
) {
    val selecting get() = selected.isNotEmpty()
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun setFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.folderUri = uri
        refresh()
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        for (u in uris) runCatching { resolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.extraFiles = (prefs.extraFiles + uris).distinct()
        refresh()
    }

    fun forgetFolder() {
        prefs.folderUri = null
        refresh()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---- selection, paste, batch export -------------------------------------------------

    fun toggle(file: RawFile) = _state.update {
        it.copy(selected = if (file.key in it.selected) it.selected - file.key else it.selected + file.key)
    }

    fun selectAll() = _state.update { it.copy(selected = it.files.map { f -> f.key }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun pasteToSelection() {
        val json = prefs.copiedSettings ?: return
        val copied = EditsStore.fromJson(json, DevelopParams.NEUTRAL) ?: return
        val targets = _state.value.files.filter { it.key in _state.value.selected }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (f in targets) runCatching { EditsStore.paste(getApplication(), f.key, copied) }
            }
            _state.update {
                it.copy(
                    selected = emptySet(),
                    edited = it.edited + targets.map { f -> f.key },
                    message = "Pasted settings onto ${targets.size} ${if (targets.size == 1) "photo" else "photos"}",
                )
            }
        }
    }

    fun exportSelection(settings: ExportSettings) {
        val targets = _state.value.files.filter { it.key in _state.value.selected }
        if (targets.isEmpty()) return
        ExportQueue.enqueue(getApplication(), targets.map { it.uri }, settings)
        _state.update { it.copy(selected = emptySet()) }
    }

    /**
     * Merges the selected photos, a focus-bracketed series, into one picture (in name order,
     * developed with the first one's settings) as a background export.
     */
    fun stackSelection(settings: ExportSettings) {
        val targets = _state.value.files.filter { it.key in _state.value.selected }.sortedBy { it.name }
        if (targets.size < 2) return
        ExportQueue.enqueue(getApplication(), targets.map { it.uri }, settings, stack = true)
        _state.update { it.copy(selected = emptySet(), message = "Stacking ${targets.size} photos. It keeps running if you leave the app.") }
    }

    /**
     * Auto tone for every selected photo, one at a time: each file is decoded, measured and its
     * six tone sliders set automatically on top of its saved edits (or the defaults).
     */
    fun autoToneSelection() {
        if (_state.value.autoProgress != null) return
        val targets = _state.value.files.filter { it.key in _state.value.selected }
        if (targets.isEmpty()) return
        _state.update { it.copy(selected = emptySet()) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            var ok = 0
            for ((i, f) in targets.withIndex()) {
                _state.update { it.copy(autoProgress = "Auto tone ${i + 1} of ${targets.size}") }
                val done = withContext(Dispatchers.Default) {
                    runCatching {
                        val loaded = RawRepository.load(app.contentResolver, f.uri)
                        val raw = RawFormats.decode(loaded.bytes, loaded.meta)
                        val pv = Demosaic.preview(raw, 1280)
                        val ctx = RenderContext.forFile(loaded.meta, pv, raw, looks = LookLibrary.source(app))
                        val defaults = DevelopParams.defaultsFor(loaded.meta, ctx.noise)
                        val base = EditsStore.load(app, f.key, defaults) ?: defaults
                        val frame = Frame(0, 0, pv.width.toFloat() / loaded.meta.crop.width)
                        EditsStore.save(app, f.key, AutoTone.solve(pv, ctx, base, frame).params)
                    }.isSuccess
                }
                if (done) ok++
            }
            _state.update {
                it.copy(
                    autoProgress = null,
                    edited = it.edited + targets.map { t -> t.key },
                    message = if (ok == targets.size) "Auto tone applied to $ok ${if (ok == 1) "photo" else "photos"}"
                    else "Auto tone applied to $ok of ${targets.size}; some files couldn't be read",
                )
            }
        }
    }

    /** Re-reads which files have saved edits and whether settings are on the clipboard. */
    fun refreshEditMarks() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val files = _state.value.files
            val edited = withContext(Dispatchers.IO) { files.filter { EditsStore.exists(app, it.key) }.map { it.key }.toSet() }
            _state.update { it.copy(edited = edited, canPaste = prefs.copiedSettings != null) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val result = withContext(Dispatchers.IO) {
                val files = ArrayList<RawFile>()
                var folderName: String? = null
                var message: String? = null
                prefs.folderUri?.let { tree ->
                    try {
                        files += RawRepository.listFolder(resolver, tree)
                        folderName = runCatching {
                            DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
                        }.getOrNull()?.ifEmpty { null } ?: "Folder"
                    } catch (e: Exception) {
                        message = "Folder is no longer accessible; pick it again."
                        prefs.folderUri = null
                    }
                }
                val extras = prefs.extraFiles
                val kept = ArrayList<Uri>()
                for (u in extras) {
                    try {
                        val f = RawRepository.describe(resolver, u)
                        if (RawFile.isRawName(f.name) && files.none { it.key == f.key }) files += f
                        kept += u
                    } catch (e: Exception) { /* permission gone */ }
                }
                if (kept.size != extras.size) prefs.extraFiles = kept
                Triple(folderName, files.toList(), message)
            }
            val keys = result.second.map { f -> f.key }.toSet()
            _state.update {
                it.copy(
                    folderName = result.first, files = result.second, loading = false,
                    message = result.third ?: it.message,
                    selected = it.selected.intersect(keys),
                )
            }
            refreshEditMarks()
        }
    }
}
