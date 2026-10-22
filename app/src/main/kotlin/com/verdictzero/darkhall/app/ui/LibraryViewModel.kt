package com.verdictzero.darkhall.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verdictzero.darkhall.app.data.OrfRepository
import com.verdictzero.darkhall.app.data.Prefs
import com.verdictzero.darkhall.app.data.RawFile
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
)

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
                        files += OrfRepository.listFolder(resolver, tree)
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
                        val f = OrfRepository.describe(resolver, u)
                        if (RawFile.isRawName(f.name) && files.none { it.key == f.key }) files += f
                        kept += u
                    } catch (e: Exception) { /* permission gone */ }
                }
                if (kept.size != extras.size) prefs.extraFiles = kept
                Triple(folderName, files.toList(), message)
            }
            _state.update {
                it.copy(folderName = result.first, files = result.second, loading = false, message = result.third ?: it.message)
            }
        }
    }
}
