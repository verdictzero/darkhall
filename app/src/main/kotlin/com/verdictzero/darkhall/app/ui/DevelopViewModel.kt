package com.verdictzero.darkhall.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verdictzero.darkhall.app.data.EditsStore
import com.verdictzero.darkhall.app.data.ExportService
import com.verdictzero.darkhall.app.data.OrfRepository
import com.verdictzero.darkhall.app.data.Prefs
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.FullRender
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.orf.OlympusDecoder
import com.verdictzero.darkhall.core.orf.OrfMetadata
import com.verdictzero.darkhall.core.orf.RawImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DevelopState(
    val fileName: String = "",
    val info: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val preview: Bitmap? = null,
    val compare: Bitmap? = null,
    val histogram: IntArray? = null,
    val params: DevelopParams = DevelopParams.NEUTRAL,
    val defaults: DevelopParams = DevelopParams.NEUTRAL,
    val exportProgress: Float? = null,
    val message: String? = null,
    val renderMs: Long = 0,
)

@OptIn(FlowPreview::class)
class DevelopViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DevelopState())
    val state = _state.asStateFlow()

    private val prefs = Prefs(app)
    private var loadedUri: Uri? = null
    private var meta: OrfMetadata? = null
    private var raw: RawImage? = null
    private var preview: LinearImage? = null
    private var ctx: RenderContext? = null
    private var frame: Frame? = null
    private var editsKey = ""
    private var baseName = "image"
    private val developer = Developer()
    private val renderLock = Mutex()
    private val paramsFlow = MutableStateFlow<DevelopParams?>(null)
    private var exportJob: Job? = null

    init {
        viewModelScope.launch {
            paramsFlow.filterNotNull().collectLatest { p ->
                delay(8) // coalesce bursts of slider events
                val result = withContext(Dispatchers.Default) { renderPreview(p) } ?: return@collectLatest
                _state.update { it.copy(preview = result.bitmap, histogram = result.histogram, renderMs = result.ms) }
            }
        }
        viewModelScope.launch {
            paramsFlow.filterNotNull().debounce(500).collect { p ->
                withContext(Dispatchers.IO) { runCatching { EditsStore.save(getApplication(), editsKey, p) } }
            }
        }
    }

    fun open(uri: Uri) {
        if (loadedUri == uri) return
        loadedUri = uri
        _state.value = DevelopState()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { loadFile(uri) }
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun loadFile(uri: Uri) {
        val app = getApplication<Application>()
        val file = OrfRepository.describe(app.contentResolver, uri)
        baseName = file.baseName
        editsKey = file.key
        val loaded = OrfRepository.load(app.contentResolver, uri)
        val m = loaded.meta
        val r = OlympusDecoder.decode(loaded.bytes, m)
        val half = Demosaic.superpixel(r)
        val longEdge = prefs.previewLongEdge
        val pv = if (half.width > longEdge) {
            half.resizeArea(longEdge, (half.height.toLong() * longEdge / half.width).toInt())
        } else half
        val c = RenderContext.forFile(m, pv)
        val defaults = DevelopParams.defaultsFor(m)
        val saved = EditsStore.load(app, editsKey, defaults)
        meta = m; raw = r; preview = pv; ctx = c
        frame = Frame(0, 0, pv.width.toFloat() / m.crop.width)
        val compare = renderLock.withLock { toBitmap(developer.render(pv, c, defaults, frame!!), pv.width, pv.height, m.orientation) }
        _state.update {
            it.copy(
                fileName = file.name,
                info = m.describe(),
                loading = false,
                defaults = defaults,
                params = saved ?: defaults,
                compare = compare,
            )
        }
        paramsFlow.value = saved ?: defaults
    }

    private class PreviewResult(val bitmap: Bitmap, val histogram: IntArray, val ms: Long)

    private suspend fun renderPreview(p: DevelopParams): PreviewResult? {
        val pv = preview ?: return null
        val c = ctx ?: return null
        val f = frame ?: return null
        val m = meta ?: return null
        return renderLock.withLock {
            val t0 = System.nanoTime()
            val px = Developer.applyGeometry(developer.render(pv, c, p, f), pv.width, pv.height, p)
            val hist = Developer.histogram(px)
            val bmp = toBitmap(px, pv.width, pv.height, m.orientation)
            PreviewResult(bmp, hist, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private fun toBitmap(argb: IntArray, w: Int, h: Int, orientation: Int): Bitmap {
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        return OrfRepository.applyOrientation(bmp, orientation)
    }

    fun set(a: Adjustment, v: Float) {
        val next = _state.value.params.with(a, v)
        _state.update { it.copy(params = next) }
        paramsFlow.value = next
    }

    fun resetOne(a: Adjustment) = set(a, _state.value.defaults[a])

    fun resetAll() {
        val d = _state.value.defaults
        _state.update { it.copy(params = d) }
        paramsFlow.value = d
        viewModelScope.launch(Dispatchers.IO) { runCatching { EditsStore.clear(getApplication(), editsKey) } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun export() {
        if (exportJob?.isActive == true) return
        val r = raw ?: return
        val c = ctx ?: return
        val m = meta ?: return
        val p = _state.value.params
        exportJob = viewModelScope.launch {
            _state.update { it.copy(exportProgress = 0f) }
            try {
                val result = withContext(Dispatchers.Default) {
                    val rendered = FullRender.render(r, c, p, progress = { f ->
                        _state.update { it.copy(exportProgress = f * 0.85f) }
                    }) ?: throw IllegalStateException("cancelled")
                    var bmp = Bitmap.createBitmap(rendered.argb, rendered.width, rendered.height, Bitmap.Config.ARGB_8888)
                    bmp = OrfRepository.applyOrientation(bmp, m.orientation)
                    _state.update { it.copy(exportProgress = 0.9f) }
                    val saved = ExportService.saveJpeg(getApplication(), bmp, m, baseName)
                    bmp.recycle()
                    saved
                }
                _state.update { it.copy(message = "Saved ${result.displayPath}") }
            } catch (e: Throwable) {
                _state.update { it.copy(message = "Export failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(exportProgress = null) }
            }
        }
    }
}
