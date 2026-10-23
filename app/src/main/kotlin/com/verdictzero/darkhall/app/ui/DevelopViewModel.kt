package com.verdictzero.darkhall.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verdictzero.darkhall.app.data.EditsStore
import com.verdictzero.darkhall.app.data.ExportQueue
import com.verdictzero.darkhall.app.data.OrfRepository
import com.verdictzero.darkhall.app.data.Prefs
import com.verdictzero.darkhall.app.gpu.GpuRenderer
import com.verdictzero.darkhall.core.gpu.ImageCompare
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.develop.Region
import com.verdictzero.darkhall.core.develop.RegionRender
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.orf.OlympusDecoder
import com.verdictzero.darkhall.core.orf.OrfMetadata
import com.verdictzero.darkhall.core.orf.RawImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A full-resolution render of the zoomed-in area, positioned in displayed normalised coordinates. */
class Loupe(
    val bitmap: Bitmap,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val params: DevelopParams,
)

/** What the preview pane is showing: normalised displayed rect plus how many screen pixels it spans. */
data class LoupeRequest(val left: Float, val top: Float, val right: Float, val bottom: Float, val screenWidthPx: Float)

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
    val message: String? = null,
    val renderMs: Long = 0,
    val loupe: Loupe? = null,
    val loupeBusy: Boolean = false,
    val canPaste: Boolean = false,
    val hasLensProfile: Boolean = false,
    /** "GPU" or "CPU": which engine drew the last preview. */
    val engine: String = "CPU",
    /** GPU name for the menu, or why the GPU isn't used. */
    val gpuLabel: String = "",
    val gpuEnabled: Boolean = true,
)

/**
 * One editing session at a time. The activity owns a single instance; [open] swaps files and
 * [close] drops the decoded raw (~25 MB), the preview planes and all scratch buffers, so
 * browsing many photos never accumulates memory.
 */
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
    /** True while the GPU holds this file's preview and passed verification. */
    @Volatile private var useGpu = false
    private val developer = Developer()
    private val loupeDeveloper = Developer()
    private val renderLock = Mutex()
    private val loupeLock = Mutex()
    private val paramsFlow = MutableStateFlow<DevelopParams?>(null)
    private val loupeRequests = MutableStateFlow<LoupeRequest?>(null)
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            paramsFlow.filterNotNull().collectLatest { p ->
                delay(8) // coalesce bursts of slider events
                val result = try {
                    withContext(Dispatchers.Default) { renderPreview(p) } ?: return@collectLatest
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _state.update { it.copy(message = "Render failed: ${e.message ?: e.javaClass.simpleName}") }
                    return@collectLatest
                }
                _state.update { it.copy(preview = result.bitmap, histogram = result.histogram, renderMs = result.ms, engine = result.engine) }
            }
        }
        viewModelScope.launch {
            paramsFlow.filterNotNull().debounce(400).collect { p ->
                val key = editsKey
                if (key.isNotEmpty()) withContext(Dispatchers.IO) { runCatching { EditsStore.save(getApplication(), key, p) } }
            }
        }
        viewModelScope.launch {
            combine(paramsFlow, loupeRequests) { p, r -> p to r }
                .debounce(220)
                .collectLatest { (p, r) -> updateLoupe(p, r) }
        }
    }

    fun open(uri: Uri) {
        if (loadedUri == uri && _state.value.error == null) return
        close()
        loadedUri = uri
        _state.value = DevelopState(canPaste = prefs.copiedSettings != null, gpuEnabled = prefs.gpuEnabled)
        loadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { loadFile(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                close()
                _state.update { it.copy(loading = false, error = "Not enough memory to open this file. Close other apps and try again.") }
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Releases everything held for the current file. Safe to call repeatedly. */
    fun close() {
        loadJob?.cancel()
        loadJob = null
        // Persist the latest sliders immediately; the debounced save may not have fired yet.
        val key = editsKey
        val p = paramsFlow.value
        if (key.isNotEmpty() && p != null && !_state.value.loading) runCatching { EditsStore.save(getApplication(), key, p) }
        loadedUri = null
        paramsFlow.value = null
        loupeRequests.value = null
        meta = null; raw = null; preview = null; ctx = null; frame = null
        editsKey = ""
        useGpu = false
        viewModelScope.launch(Dispatchers.Default) {
            renderLock.withLock {
                developer.release()
                if (GpuRenderer.available) GpuRenderer.releaseImage()
            }
            loupeLock.withLock { loupeDeveloper.release() }
        }
        _state.value = DevelopState()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    private suspend fun loadFile(uri: Uri) {
        val app = getApplication<Application>()
        val file = OrfRepository.describe(app.contentResolver, uri)
        val m: OrfMetadata
        val r: RawImage
        run {
            val loaded = OrfRepository.load(app.contentResolver, uri)
            m = loaded.meta
            r = OlympusDecoder.decode(loaded.bytes, m)
        }
        val half = Demosaic.superpixel(r)
        val longEdge = prefs.previewLongEdge
        val pv = if (half.width > longEdge) {
            half.resizeArea(longEdge, (half.height.toLong() * longEdge / half.width).toInt())
        } else half
        val c = RenderContext.forFile(m, pv, r) // measures this file's sensor noise
        val defaults = DevelopParams.defaultsFor(m, c.noise)
        val saved = EditsStore.load(app, file.key, defaults)
        val f = Frame(0, 0, pv.width.toFloat() / m.crop.width)
        // "As shot": the camera's own rendering, without the underwater correction.
        val asShot = defaults.with(Adjustment.WATER, 0f)
        val compare = renderLock.withLock {
            val px = developer.render(pv, c, asShot, f)
            toBitmap(Geometry.apply(px, pv.width, pv.height, asShot, c.lens), pv.width, pv.height, m.orientation)
        }
        if (loadedUri != uri) return // closed or switched while loading
        meta = m; raw = r; preview = pv; ctx = c; frame = f
        editsKey = file.key
        _state.update {
            it.copy(
                fileName = file.name,
                info = m.describe() + if (c.noise.measured) "  noise ≈ ISO ${roundIso(c.noise.effectiveIso)}" else "",
                loading = false,
                defaults = defaults,
                params = saved ?: defaults,
                compare = compare,
                hasLensProfile = c.lens != null,
            )
        }
        setUpGpu(pv, c, f, defaults)
        if (loadedUri != uri) return
        paramsFlow.value = saved ?: defaults
    }

    private class PreviewResult(val bitmap: Bitmap, val histogram: IntArray, val ms: Long, val engine: String)

    private suspend fun renderPreview(p: DevelopParams): PreviewResult? = renderLock.withLock {
        val pv = preview ?: return null
        val c = ctx ?: return null
        val f = frame ?: return null
        val m = meta ?: return null
        val t0 = System.nanoTime()
        var engine = "CPU"
        val px = if (useGpu) {
            try {
                GpuRenderer.render(c, p, f).also { engine = "GPU" }
            } catch (e: Throwable) {
                // The renderer has already switched itself off; finish this frame on the CPU.
                useGpu = false
                _state.update { it.copy(gpuLabel = GpuRenderer.unavailableReason.orEmpty(), message = "GPU rendering stopped; using the CPU") }
                Geometry.apply(developer.render(pv, c, p, f), pv.width, pv.height, p, c.lens)
            }
        } else {
            Geometry.apply(developer.render(pv, c, p, f), pv.width, pv.height, p, c.lens)
        }
        val hist = Developer.histogram(px)
        val bmp = toBitmap(px, pv.width, pv.height, m.orientation)
        PreviewResult(bmp, hist, (System.nanoTime() - t0) / 1_000_000, engine)
    }

    /**
     * Puts this file's preview on the GPU. The first time a GPU/driver combination is seen,
     * GPU and CPU renders of the same picture are compared; a driver that doesn't reproduce
     * the CPU result is remembered and left off.
     */
    private suspend fun setUpGpu(pv: LinearImage, c: RenderContext, f: Frame, defaults: DevelopParams) {
        useGpu = false
        if (!prefs.gpuEnabled) {
            _state.update { it.copy(gpuEnabled = false, gpuLabel = "Off") }
            return
        }
        if (!GpuRenderer.ensureReady()) {
            _state.update { it.copy(gpuLabel = GpuRenderer.unavailableReason ?: "Unavailable") }
            return
        }
        val name = GpuRenderer.description.orEmpty()
        if (prefs.gpuRejected == name) {
            _state.update { it.copy(gpuLabel = "Off: did not match the CPU on this driver") }
            return
        }
        try {
            val input = renderLock.withLock { developer.withReconstructedHighlights(pv, c, f) }
            GpuRenderer.setImage(input)
            if (prefs.gpuVerified != name) {
                val checks = listOf(
                    defaults,
                    defaults.with(Adjustment.DEHAZE, 40f).with(Adjustment.CLARITY, 40f).with(Adjustment.EXPOSURE, 1f)
                        .with(Adjustment.LUMA_NR, 60f).with(Adjustment.STRAIGHTEN, 2f),
                )
                for (p in checks) {
                    val gpu = GpuRenderer.render(c, p, f)
                    val cpu = renderLock.withLock {
                        withContext(Dispatchers.Default) { Geometry.apply(developer.render(pv, c, p, f), pv.width, pv.height, p, c.lens) }
                    }
                    val cmp = ImageCompare.of(cpu, gpu)
                    if (!cmp.acceptable) {
                        prefs.gpuRejected = name
                        _state.update { it.copy(gpuLabel = "Off: did not match the CPU ($cmp)", message = "This GPU's output didn't match; using the CPU") }
                        return
                    }
                }
                prefs.gpuVerified = name
            }
            useGpu = true
            _state.update { it.copy(gpuEnabled = true, gpuLabel = shortGpuName(name)) }
        } catch (e: Throwable) {
            _state.update { it.copy(gpuLabel = GpuRenderer.unavailableReason ?: (e.message ?: "GPU error")) }
        }
    }

    private fun shortGpuName(description: String) = description.substringBefore(" · ")

    fun setGpuEnabled(enabled: Boolean) {
        prefs.gpuEnabled = enabled
        if (enabled) prefs.gpuRejected = null // let the user retry after a driver update
        val pv = preview; val c = ctx; val f = frame
        viewModelScope.launch {
            if (enabled && pv != null && c != null && f != null) {
                setUpGpu(pv, c, f, _state.value.defaults)
            } else {
                useGpu = false
                _state.update { it.copy(gpuEnabled = enabled, gpuLabel = "Off") }
            }
            _state.update { it.copy(gpuEnabled = enabled) }
            val p = paramsFlow.value ?: return@launch
            val r = withContext(Dispatchers.Default) { renderPreview(p) } ?: return@launch
            _state.update { s -> s.copy(preview = r.bitmap, histogram = r.histogram, renderMs = r.ms, engine = r.engine) }
        }
    }

    private fun toBitmap(argb: IntArray, w: Int, h: Int, orientation: Int): Bitmap {
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        return OrfRepository.applyOrientation(bmp, orientation)
    }

    // ---- full-resolution zoom ------------------------------------------------------------

    fun requestLoupe(request: LoupeRequest?) {
        loupeRequests.value = request
        if (request == null) _state.update { it.copy(loupe = null, loupeBusy = false) }
    }

    private suspend fun updateLoupe(p: DevelopParams?, req: LoupeRequest?) {
        val m = meta
        val r = raw
        val c = ctx
        val pv = preview
        if (p == null || req == null || m == null || r == null || c == null || pv == null) {
            if (req == null) loupeLock.withLock { loupeDeveloper.release() }
            return
        }
        val w = m.crop.width
        val h = m.crop.height
        // Displayed rect -> sensor-oriented pixel rect.
        val corners = listOf(req.left to req.top, req.right to req.top, req.left to req.bottom, req.right to req.bottom)
            .map { (u, v) -> Orientation.toSensor(m.orientation, u, v) }
        val nx0 = corners.minOf { it.first }.coerceIn(0f, 1f)
        val nx1 = corners.maxOf { it.first }.coerceIn(0f, 1f)
        val ny0 = corners.minOf { it.second }.coerceIn(0f, 1f)
        val ny1 = corners.maxOf { it.second }.coerceIn(0f, 1f)
        val x0 = (nx0 * w).toInt()
        val y0 = (ny0 * h).toInt()
        val rw = ((nx1 * w).roundToInt() - x0).coerceAtLeast(1)
        val rh = ((ny1 * h).roundToInt() - y0).coerceAtLeast(1)
        // Full-res pixels across the displayed width of the region.
        val displayedFullWidth = if (Orientation.swapsAxes(m.orientation)) rh else rw
        var scale = min(1f, req.screenWidthPx / displayedFullWidth)
        val previewScale = pv.width.toFloat() / w
        if (scale < previewScale * 1.2f) {
            // The proxy already has enough pixels for this zoom level.
            _state.update { it.copy(loupe = null, loupeBusy = false) }
            return
        }
        // Keep the region render within ~4 MP so it stays fast and small on a phone.
        val budget = 4_000_000f
        val px = rw * scale * rh * scale
        if (px > budget) scale *= sqrt(budget / px)
        _state.update { it.copy(loupeBusy = true) }
        val bmp = try {
            withContext(Dispatchers.Default) {
                loupeLock.withLock {
                    val out = RegionRender.render(r, c, p, Region(x0, y0, rw, rh), scale, loupeDeveloper)
                    toBitmap(out.argb, out.width, out.height, m.orientation)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(loupeBusy = false) }
            return
        }
        val dc = listOf(
            Orientation.toDisplayed(m.orientation, x0.toFloat() / w, y0.toFloat() / h),
            Orientation.toDisplayed(m.orientation, (x0 + rw).toFloat() / w, (y0 + rh).toFloat() / h),
        )
        _state.update {
            it.copy(
                loupe = Loupe(
                    bmp,
                    min(dc[0].first, dc[1].first), min(dc[0].second, dc[1].second),
                    max(dc[0].first, dc[1].first), max(dc[0].second, dc[1].second),
                    p,
                ),
                loupeBusy = false,
            )
        }
    }

    // ---- sliders ---------------------------------------------------------------------------

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
        val key = editsKey
        viewModelScope.launch(Dispatchers.IO) { runCatching { EditsStore.clear(getApplication(), key) } }
    }

    fun copySettings() {
        prefs.copiedSettings = EditsStore.toJson(_state.value.params)
        _state.update { it.copy(canPaste = true, message = "Settings copied. Paste them onto other photos from the library.") }
    }

    fun pasteSettings() {
        val json = prefs.copiedSettings ?: return
        val copied = EditsStore.fromJson(json, _state.value.defaults) ?: return
        val next = copied.with(Adjustment.STRAIGHTEN, _state.value.params[Adjustment.STRAIGHTEN])
        _state.update { it.copy(params = next, message = "Settings pasted") }
        paramsFlow.value = next
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun export() {
        val uri = loadedUri ?: return
        val p = _state.value.params
        val key = editsKey
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { EditsStore.save(getApplication(), key, p) } }
            ExportQueue.enqueue(getApplication(), listOf(uri))
            _state.update { it.copy(message = "Export started. It keeps running if you leave the app.") }
        }
    }

    /** Rounds to the nearest third-stop ISO value, the way cameras label them. */
    private fun roundIso(iso: Float): Int {
        val steps = intArrayOf(64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
        return steps.minByOrNull { kotlin.math.abs(kotlin.math.ln(it / iso.toDouble())) } ?: iso.toInt()
    }
}
