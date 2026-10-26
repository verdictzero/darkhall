package com.verdictzero.darkhall.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verdictzero.darkhall.app.data.EditsStore
import com.verdictzero.darkhall.app.data.ExportQueue
import com.verdictzero.darkhall.app.data.LookLibrary
import com.verdictzero.darkhall.app.data.RawRepository
import com.verdictzero.darkhall.app.data.SceneDetector
import com.verdictzero.darkhall.app.data.FactsStore
import com.verdictzero.darkhall.app.data.Preset
import com.verdictzero.darkhall.app.data.PresetStore
import com.verdictzero.darkhall.app.data.RatingsStore
import com.verdictzero.darkhall.core.raw.ShotFacts
import com.verdictzero.darkhall.core.sky.MaskRegion
import com.verdictzero.darkhall.app.data.Prefs
import com.verdictzero.darkhall.app.gpu.GpuRenderer
import com.verdictzero.darkhall.core.gpu.ImageCompare
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.AutoStraighten
import com.verdictzero.darkhall.core.develop.AutoTone
import com.verdictzero.darkhall.core.develop.Demosaic
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.develop.Developer
import com.verdictzero.darkhall.core.develop.Frame
import com.verdictzero.darkhall.core.develop.Geometry
import com.verdictzero.darkhall.core.develop.Crop
import com.verdictzero.darkhall.core.develop.MixBand
import com.verdictzero.darkhall.core.develop.CropAspect
import com.verdictzero.darkhall.core.develop.CropRect
import com.verdictzero.darkhall.core.develop.PixelRect
import com.verdictzero.darkhall.core.develop.LinearImage
import com.verdictzero.darkhall.core.develop.Region
import com.verdictzero.darkhall.core.export.ExportSettings
import com.verdictzero.darkhall.core.develop.RegionRender
import com.verdictzero.darkhall.core.develop.RenderContext
import com.verdictzero.darkhall.core.raw.RawFormats
import com.verdictzero.darkhall.core.raw.RawMetadata
import com.verdictzero.darkhall.core.raw.RawImage
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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
    /** The sliders [preview] was rendered with (it can trail [params] by a frame). */
    val previewParams: DevelopParams? = null,
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
    val autoToneOnOpen: Boolean = false,
    /** Favorite looks by id, in the order they were added. */
    val favoriteLooks: List<String> = emptyList(),
    /** Sliders before the last Auto, for Undo. */
    val beforeAuto: DevelopParams? = null,
    val autoBusy: Boolean = false,
    val straightenBusy: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val scene: SceneStatus = SceneStatus.UNKNOWN,
    /** Share of the picture each region takes, once found. */
    val coverage: Map<MaskRegion, Float> = emptyMap(),
    /** The region whose mask is shown over the preview, if any. */
    val showMask: MaskRegion? = null,
    /** That mask as a tinted overlay matching [preview]. */
    val maskOverlay: Bitmap? = null,
    /** The crop tool is open: the preview shows the whole picture with the crop drawn over it. */
    val cropping: Boolean = false,
    /** This photo's star rating, 0 for none. */
    val rating: Int = 0,
    /** Saved presets, for the Presets dialog. */
    val presets: List<Preset> = emptyList(),
    /** Camera, time, exposure and field-sensor readings for the info panel. */
    val facts: ShotFacts? = null,
    /** Upright size of the full-resolution picture, for crop ratios and sizes. */
    val fullWidth: Int = 0,
    val fullHeight: Int = 0,
)

/** Where finding the photo's sky, water, people and foliage stands. */
enum class SceneStatus { UNKNOWN, FINDING, DONE }

/** True when [region] takes enough of the picture to be worth its sliders. */
fun DevelopState.has(region: MaskRegion) = (coverage[region] ?: 0f) >= MIN_COVERAGE

/** Less than this share of the picture counts as none of a region. */
const val MIN_COVERAGE = 0.005f

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
    private var meta: RawMetadata? = null
    private var raw: RawImage? = null
    private var preview: LinearImage? = null
    private var ctx: RenderContext? = null
    private var frame: Frame? = null
    private var editsKey = ""
    /** The original, uncropped, upright; [DevelopState.compare] is it cut to the crop. */
    private var fullCompare: Bitmap? = null
    /** The last preview before cropping, so moving the crop only cuts it again. */
    private var lastFull: Pair<DevelopParams, IntArray>? = null
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
                _state.update { it.copy(preview = result.bitmap, previewParams = p, histogram = result.histogram, renderMs = result.ms, engine = result.engine) }
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
        _state.value = DevelopState(
            canPaste = prefs.copiedSettings != null, gpuEnabled = prefs.gpuEnabled, presets = PresetStore.all(getApplication()),
            autoToneOnOpen = prefs.autoToneOnOpen, favoriteLooks = prefs.favoriteLooks,
        )
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
        fullCompare = null; lastFull = null
        editsKey = ""
        history.clear()
        historyIndex = -1
        useGpu = false
        thumbBase = null
        thumbCache.evictAll()
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
        val file = RawRepository.describe(app.contentResolver, uri)
        val m: RawMetadata
        val r: RawImage
        run {
            val loaded = RawRepository.load(app.contentResolver, uri)
            m = loaded.meta
            r = RawFormats.decode(loaded.bytes, m)
        }
        val pv = Demosaic.preview(r, prefs.previewLongEdge)
        // Measures this file's sensor noise; looks come from the bundled library.
        val c = RenderContext.forFile(m, pv, r, looks = LookLibrary.source(app))
        val defaults = DevelopParams.defaultsFor(m, c.noise)
        var saved = EditsStore.load(app, file.key, defaults)
        if (saved == null && prefs.autoToneOnOpen) {
            // New photo and auto tone on open: start from the automatic settings.
            saved = AutoTone.solve(pv, c, defaults, Frame(0, 0, pv.width.toFloat() / m.crop.width)).params
        }
        val f = Frame(0, 0, pv.width.toFloat() / m.crop.width)
        // "As shot": the camera's own rendering, without the underwater correction.
        val asShot = defaults.with(Adjustment.WATER, 0f)
        val compare = renderLock.withLock {
            val px = developer.render(pv, c, asShot, f)
            toBitmap(Geometry.apply(px, pv.width, pv.height, asShot, c.lens), pv.width, pv.height, m.orientation)
        }
        if (loadedUri != uri) return // closed or switched while loading
        meta = m; raw = r; preview = pv; ctx = c; frame = f
        fullCompare = compare
        editsKey = file.key
        val knownScene = SceneDetector.cached(app, file.key)?.also { c.scene = it }
        _state.update {
            it.copy(
                fileName = file.name,
                info = m.describe() + if (c.noise.measured) "  noise ≈ ISO ${roundIso(c.noise.effectiveIso)}" else "",
                loading = false,
                defaults = defaults,
                params = saved ?: defaults,
                compare = cutCompare(saved ?: defaults, cropping = false),
                // (The library's cached facts stay as they are, so its names and groups stay consistent.)
                rating = RatingsStore.get(app, file.key),
                facts = ShotFacts.from(m).also { if (FactsStore.load(app, file.key) == null) FactsStore.save(app, file.key, it) },
                hasLensProfile = c.lens != null,
                fullWidth = if (Orientation.swapsAxes(m.orientation)) m.crop.height else m.crop.width,
                fullHeight = if (Orientation.swapsAxes(m.orientation)) m.crop.width else m.crop.height,
                scene = if (knownScene != null) SceneStatus.DONE else SceneStatus.UNKNOWN,
                coverage = knownScene?.let { k -> MaskRegion.entries.associateWith { k.coverage(it) } } ?: emptyMap(),
            )
        }
        setUpGpu(pv, c, f, defaults)
        if (loadedUri != uri) return
        startHistory(saved ?: defaults)
        paramsFlow.value = saved ?: defaults
        if ((saved ?: defaults).usesMasks()) ensureMasks()
    }

    private class PreviewResult(val bitmap: Bitmap, val histogram: IntArray, val ms: Long, val engine: String)

    private suspend fun renderPreview(p: DevelopParams): PreviewResult? = renderLock.withLock {
        val pv = preview ?: return null
        val c = ctx ?: return null
        val f = frame ?: return null
        val m = meta ?: return null
        val t0 = System.nanoTime()
        var engine = lastEngine
        // The crop only cuts the finished picture: a change to the crop alone reuses the last render.
        val uncropped = Crop.copy(p, DevelopParams.NEUTRAL)
        val full = lastFull?.takeIf { it.first == uncropped }?.second ?: run {
            engine = "CPU"
            // The GPU shaders have no backscatter stage: photos using it render on the CPU.
            val px = if (useGpu && !p.needsCpu()) {
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
            lastFull = uncropped to px
            lastEngine = engine
            px
        }
        val cropping = _state.value.cropping
        val rect = if (cropping) null else Crop.of(p).takeIf { !it.isFull }?.let { Crop.sensorPixels(it, m.orientation, pv.width, pv.height) }
        val px = if (rect == null) full else Crop.cut(full, pv.width, pv.height, rect)
        val w = rect?.width ?: pv.width
        val h = rect?.height ?: pv.height
        val hist = Developer.histogram(px)
        val bmp = toBitmap(px, w, h, m.orientation)
        val overlay = _state.value.showMask?.let { maskOverlay(it, pv, c, p, f, m, rect) }
        val compare = cutCompare(p, cropping)
        _state.update { it.copy(maskOverlay = overlay, compare = compare) }
        PreviewResult(bmp, hist, (System.nanoTime() - t0) / 1_000_000, engine)
    }

    private var lastEngine = "CPU"
    private var lastCompare: Pair<CropRect, Bitmap>? = null

    /** The original, cut to [p]'s crop (whole while [cropping]). */
    private fun cutCompare(p: DevelopParams, cropping: Boolean): Bitmap? {
        val full = fullCompare ?: return null
        val crop = Crop.of(p)
        if (cropping || crop.isFull) return full
        lastCompare?.takeIf { it.first == crop }?.let { return it.second }
        val r = crop.pixels(full.width, full.height)
        return Bitmap.createBitmap(full, r.x, r.y, r.width, r.height).also { lastCompare = crop to it }
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
            GpuRenderer.setImage(input, pv)
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
            lastFull = null // the other engine draws it next
            val p = paramsFlow.value ?: return@launch
            val r = withContext(Dispatchers.Default) { renderPreview(p) } ?: return@launch
            _state.update { s -> s.copy(preview = r.bitmap, previewParams = p, histogram = r.histogram, renderMs = r.ms, engine = r.engine) }
        }
    }

    private fun toBitmap(argb: IntArray, w: Int, h: Int, orientation: Int): Bitmap {
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        return RawRepository.applyOrientation(bmp, orientation)
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
        // The request is in the cropped picture; the region render works on the whole frame.
        val crop = if (_state.value.cropping) CropRect.FULL else Crop.of(p)
        // Displayed rect -> sensor-oriented pixel rect.
        val corners = listOf(req.left to req.top, req.right to req.top, req.left to req.bottom, req.right to req.bottom)
            .map { (u, v) -> crop.toFull(u, v) }
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
        ).map { (u, v) -> crop.fromFull(u, v) }
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

    // ---- undo / redo -------------------------------------------------------------------------

    /** Every distinct state of the sliders this session, oldest first; [historyIndex] is the current one. */
    private val history = ArrayList<DevelopParams>()
    private var historyIndex = -1
    /** The slider being dragged, so one drag makes one undo step rather than hundreds. */
    private var dragging: Adjustment? = null
    private var draggedAt = 0L

    private fun startHistory(p: DevelopParams) {
        history.clear()
        history += p
        historyIndex = 0
        dragging = null
        _state.update { it.copy(canUndo = false, canRedo = false) }
    }

    /**
     * Records [p] as a new undo step. Successive changes to the same slider less than a
     * second apart ([slider]) update the step instead, so a whole drag undoes at once.
     */
    private fun record(p: DevelopParams, slider: Adjustment? = null) {
        if (historyIndex < 0) return
        val now = android.os.SystemClock.uptimeMillis()
        val merge = slider != null && slider == dragging && now - draggedAt < 1000 && historyIndex > 0
        dragging = slider
        draggedAt = now
        while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
        if (merge) {
            history[historyIndex] = p
        } else if (history[historyIndex] != p) {
            history += p
            historyIndex++
            if (history.size > MAX_HISTORY) { history.removeAt(0); historyIndex-- }
        }
        _state.update { it.copy(canUndo = historyIndex > 0, canRedo = false) }
    }

    fun undo() = stepHistory(-1)

    fun redo() = stepHistory(+1)

    private fun stepHistory(by: Int) {
        val i = historyIndex + by
        if (i !in history.indices) return
        historyIndex = i
        dragging = null
        val p = history[i]
        _state.update { it.copy(params = p, beforeAuto = null, canUndo = i > 0, canRedo = i < history.size - 1) }
        paramsFlow.value = p
    }

    // ---- sliders ---------------------------------------------------------------------------

    fun set(a: Adjustment, v: Float) {
        if (a.maskKind != null) ensureMasks()
        set(a, v, slider = a)
    }

    private fun set(a: Adjustment, v: Float, slider: Adjustment?) {
        val next = _state.value.params.with(a, v)
        _state.update { it.copy(params = next, beforeAuto = null) }
        paramsFlow.value = next
        record(next, slider)
    }

    fun resetOne(a: Adjustment) = set(a, _state.value.defaults[a], slider = null)

    /** Puts every colour mixer slider back, as one undo step. */
    fun resetMixer() {
        val d = _state.value.defaults
        val next = MixBand.entries.flatMap { Adjustment.mixer(it) }.fold(_state.value.params) { p, a -> p.with(a, d[a]) }
        _state.update { it.copy(params = next) }
        paramsFlow.value = next
        record(next)
    }

    fun resetAll() {
        val d = _state.value.defaults
        _state.update { it.copy(params = d) }
        paramsFlow.value = d
        record(d)
        val key = editsKey
        viewModelScope.launch(Dispatchers.IO) { runCatching { EditsStore.clear(getApplication(), key) } }
    }

    // ---- auto tone ---------------------------------------------------------------------------

    /** Sets Exposure, Contrast, Highlights, Shadows, Whites and Blacks automatically. */
    fun autoTone() {
        val pv = preview ?: return
        val c = ctx ?: return
        val f = frame ?: return
        if (_state.value.autoBusy) return
        val before = _state.value.params
        _state.update { it.copy(autoBusy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { AutoTone.solve(pv, c, before, f) }
            val p = result.params
            _state.update {
                it.copy(params = p, beforeAuto = before, autoBusy = false, message = "Auto: " + describeAuto(before, p))
            }
            paramsFlow.value = p
            record(p)
        }
    }

    fun undoAuto() {
        val before = _state.value.beforeAuto ?: return
        // The step Auto (or Draw level) just made is the latest one: step back over it.
        if (historyIndex > 0 && history[historyIndex - 1] == before) return undo()
        _state.update { it.copy(params = before, beforeAuto = null) }
        paramsFlow.value = before
        record(before)
    }

    // ---- masks ---------------------------------------------------------------------------------

    /**
     * Finds where the sky, water, people and foliage are if that hasn't been done yet: a second
     * or two on the CPU, once per photo (the result is cached on disk). The preview re-renders
     * when they're in.
     */
    fun ensureMasks() {
        if (_state.value.scene != SceneStatus.UNKNOWN) return
        val pv = preview ?: return
        val c = ctx ?: return
        val m = meta ?: return
        val key = editsKey
        val asShot = _state.value.defaults.with(Adjustment.WATER, 0f)
        _state.update { it.copy(scene = SceneStatus.FINDING) }
        viewModelScope.launch {
            val masks = try {
                withContext(Dispatchers.Default) { SceneDetector.detect(getApplication(), key, pv, c, asShot, m) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(scene = SceneStatus.UNKNOWN, message = "Couldn't look for the sky, water and people: ${e.message ?: e.javaClass.simpleName}") }
                return@launch
            }
            if (ctx !== c) return@launch // another photo by now
            c.scene = masks
            lastFull = null // the masked sliders now have masks to work through
            _state.update {
                it.copy(scene = SceneStatus.DONE, coverage = MaskRegion.entries.associateWith { r -> masks.coverage(r) })
            }
            rerender()
        }
    }

    /** Shows [region]'s mask over the preview, or hides it if it is the one showing. */
    fun toggleMask(region: MaskRegion) {
        _state.update { it.copy(showMask = if (it.showMask == region) null else region, maskOverlay = null) }
        ensureMasks()
        rerender()
    }

    private fun rerender() {
        viewModelScope.launch {
            val p = paramsFlow.value ?: return@launch
            val r = withContext(Dispatchers.Default) { renderPreview(p) } ?: return@launch
            _state.update { s -> s.copy(preview = r.bitmap, previewParams = p, histogram = r.histogram, renderMs = r.ms, engine = r.engine) }
        }
    }

    /** [region]'s mask over the preview: magenta, see-through elsewhere, upright, straightened and cropped like the picture. */
    private fun maskOverlay(region: MaskRegion, pv: LinearImage, c: RenderContext, p: DevelopParams, f: Frame, m: RawMetadata, crop: PixelRect?): Bitmap? {
        val mask = c.scene?.get(region)?.forFrame(pv, f, m.crop.width, m.crop.height) ?: return null
        val grey = IntArray(mask.size) { i -> val v = (mask[i] * 255f + 0.5f).toInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        var placed = Geometry.apply(grey, pv.width, pv.height, p, c.lens)
        if (crop != null) placed = Crop.cut(placed, pv.width, pv.height, crop)
        val tinted = IntArray(placed.size) { i -> (((placed[i] and 0xFF) * 150 / 255) shl 24) or 0xFF28C8 }
        return toBitmap(tinted, crop?.width ?: pv.width, crop?.height ?: pv.height, m.orientation)
    }

    // ---- straighten ---------------------------------------------------------------------------

    /** Levels the photo by its horizon or upright lines, measured on the current preview. */
    fun autoStraighten() {
        val bmp = _state.value.preview ?: return
        val shownWith = _state.value.previewParams ?: return
        if (_state.value.straightenBusy) return
        _state.update { it.copy(straightenBusy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                AutoStraighten.detect(px, bmp.width, bmp.height)
            }
            _state.update { it.copy(straightenBusy = false) }
            if (result == null) {
                _state.update { it.copy(message = "No clear horizon or upright lines to level by") }
                return@launch
            }
            val by = if (result.fromVerticals) "the upright lines" else "the horizon"
            straightenBy(result.degrees, shownWith, "Auto", "from $by")
        }
    }

    /**
     * Levels a line drawn on the preview, from (0, 0) to ([dx], [dy]) in screen pixels: it
     * becomes horizontal, or vertical if it is closer to that.
     */
    fun levelLine(dx: Float, dy: Float) {
        var a = atan2(dy, dx) * 180f / PI.toFloat()
        if (a > 90f) a -= 180f
        if (a <= -90f) a += 180f
        val tilt = when {
            abs(a) <= 45f -> a
            a > 0f -> a - 90f
            else -> a + 90f
        }
        val shownWith = _state.value.previewParams ?: return
        straightenBy(tilt, shownWith, "Level", if (abs(a) <= 45f) "to level" else "to upright")
    }

    /**
     * Corrects a [tilt] measured on the preview (degrees, as seen on screen), undoably. The
     * preview was drawn with [shownWith], which may trail the sliders by a frame, so the
     * correction is added to the Straighten that was actually on screen.
     */
    private fun straightenBy(tilt: Float, shownWith: DevelopParams, source: String, how: String) {
        val before = _state.value.params
        // A mirrored orientation turns the other way on screen.
        val sign = if (meta?.orientation in MIRRORED) -1f else 1f
        val wanted = shownWith[Adjustment.STRAIGHTEN] + sign * tilt
        val a = Adjustment.STRAIGHTEN
        val next = (wanted * 10f).roundToInt() / 10f
        val clamped = next.coerceIn(a.min, a.max)
        val msg = when {
            abs(clamped - before[a]) < 0.05f -> "$source: already level"
            clamped != next -> "$source: Straighten ${a.format(clamped)} (as far as it goes; the line is ${"%.1f".format(abs(wanted))}° off)"
            else -> "$source: Straighten ${a.format(clamped)} $how"
        }
        val p = before.with(a, clamped)
        _state.update { it.copy(params = p, beforeAuto = before, message = msg) }
        paramsFlow.value = p
        record(p)
    }

    // ---- crop ----------------------------------------------------------------------------------

    /** Opens or closes the crop tool. While open, the preview shows the whole picture. */
    fun setCropping(on: Boolean) {
        if (_state.value.cropping == on) return
        _state.update { it.copy(cropping = on, loupe = null) }
        dragging = null
        rerender()
    }

    /** Sets the crop rectangle; successive calls during one drag make one undo step. */
    fun setCrop(r: CropRect) {
        val next = Crop.with(_state.value.params, r)
        if (next == _state.value.params) return
        _state.update { it.copy(params = next) }
        paramsFlow.value = next
        record(next, slider = Adjustment.CROP_LEFT)
    }

    /** Picks a crop ratio, fitting the largest crop of that shape around the current one. */
    fun setCropAspect(aspect: CropAspect) {
        val s = _state.value
        val now = Crop.of(s.params)
        val portrait = isPortrait(now, s)
        val ratio = Crop.ratio(aspect, s.fullWidth, s.fullHeight, portrait)
        val rect = if (ratio == null) now else Crop.fit(ratio, now, s.fullWidth, s.fullHeight)
        applyCrop(Crop.with(s.params, rect, aspect))
    }

    /** Turns the crop between landscape and portrait, keeping its ratio. */
    fun rotateCrop() {
        val s = _state.value
        val now = Crop.of(s.params)
        val aspect = Crop.aspect(s.params)
        val pw = now.width * s.fullWidth
        val ph = now.height * s.fullHeight
        val ratio = Crop.ratio(aspect, s.fullWidth, s.fullHeight, !isPortrait(now, s)) ?: (ph / pw)
        applyCrop(Crop.with(s.params, Crop.fit(ratio, now, s.fullWidth, s.fullHeight), aspect))
    }

    fun resetCrop() = applyCrop(Crop.copy(_state.value.params, DevelopParams.NEUTRAL))

    private fun isPortrait(r: CropRect, s: DevelopState) = r.height * s.fullHeight > r.width * s.fullWidth

    private fun applyCrop(next: DevelopParams) {
        _state.update { it.copy(params = next) }
        paramsFlow.value = next
        record(next)
    }

    fun setAutoToneOnOpen(on: Boolean) {
        prefs.autoToneOnOpen = on
        _state.update { it.copy(autoToneOnOpen = on) }
    }

    private fun describeAuto(before: DevelopParams, after: DevelopParams): String {
        val changed = AutoTone.SLIDERS.filter { abs(after[it] - before[it]) > 1e-3f }
        if (changed.isEmpty()) return "already balanced"
        return changed.joinToString(", ") { "${it.label} ${it.format(after[it])}" }
    }

    // ---- looks -------------------------------------------------------------------------------

    fun setLook(id: String?) {
        var next = _state.value.params.withLook(id)
        // Picking a look with the strength at zero would show nothing; start it at full.
        if (id != null && next[Adjustment.LOOK_AMOUNT] < 1f) next = next.with(Adjustment.LOOK_AMOUNT, 100f)
        _state.update { it.copy(params = next) }
        paramsFlow.value = next
        record(next)
    }

    /** Adds look [id] to the favorites, or takes it off. */
    fun toggleFavoriteLook(id: String) {
        val now = prefs.favoriteLooks.let { if (id in it) it - id else it + id }
        prefs.favoriteLooks = now
        _state.update { it.copy(favoriteLooks = now) }
    }

    private class ThumbBase(val key: DevelopParams, val argb: IntArray, val width: Int, val height: Int, val orientation: Int)

    private val thumbDeveloper = Developer()
    private val thumbLock = Mutex()
    @Volatile private var thumbBase: ThumbBase? = null
    private val thumbCache = android.util.LruCache<String, Bitmap>(96)

    /**
     * Small render of the photo with the current sliders but no look, for the look browser.
     * Re-rendered only when the sliders change.
     */
    private suspend fun thumbnailBase(): ThumbBase? = thumbLock.withLock {
        val pv = preview ?: return null
        val c = ctx ?: return null
        val m = meta ?: return null
        val key = _state.value.params.withLook(null)
        thumbBase?.takeIf { it.key == key }?.let { return it }
        val w = 200
        val h = (pv.height.toLong() * w / pv.width).toInt().coerceAtLeast(1)
        val small = pv.resizeArea(w, h)
        val px = withContext(Dispatchers.Default) {
            thumbDeveloper.render(small, c, key, Frame(0, 0, w.toFloat() / m.crop.width))
        }
        thumbCache.evictAll()
        ThumbBase(key, px, w, h, m.orientation).also { thumbBase = it }
    }

    /** The current photo with look [id] (null = no look), for the browser grid. */
    suspend fun lookThumbnail(id: String?): Bitmap? {
        val base = thumbnailBase() ?: return null
        val cacheKey = id ?: "<none>"
        thumbCache.get(cacheKey)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            val out = if (id == null) base.argb else {
                val lut = LookLibrary.lut(getApplication(), id) ?: return@withContext null
                val px = IntArray(base.argb.size)
                val s = FloatArray(3)
                for (i in px.indices) {
                    val v = base.argb[i]
                    lut.sample(((v ushr 16) and 0xFF) / 255f, ((v ushr 8) and 0xFF) / 255f, (v and 0xFF) / 255f, s)
                    px[i] = (0xFF shl 24) or ((s[0] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                        ((s[1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or (s[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                }
                px
            }
            toBitmap(out, base.width, base.height, base.orientation)
        } ?: return null
        thumbCache.put(cacheKey, bmp)
        return bmp
    }

    fun copySettings() {
        prefs.copiedSettings = EditsStore.toJson(_state.value.params)
        _state.update { it.copy(canPaste = true, message = "Settings copied. Paste them onto other photos from the library.") }
    }

    fun pasteSettings() {
        val json = prefs.copiedSettings ?: return
        val copied = EditsStore.fromJson(json, _state.value.defaults) ?: return
        val next = EditsStore.keepPerShot(copied, _state.value.params)
        _state.update { it.copy(params = next, message = "Settings pasted") }
        paramsFlow.value = next
        record(next)
    }

    /** Saves the current settings (all but Straighten and the crop, which belong to the photo) as [name]. */
    fun savePreset(name: String) {
        val clean = name.trim().ifEmpty { return }
        val list = PresetStore.save(getApplication(), clean, _state.value.params)
        _state.update { it.copy(presets = list, message = "Saved preset \"$clean\"") }
    }

    fun applyPreset(preset: Preset) {
        val next = EditsStore.keepPerShot(preset.params, _state.value.params)
        _state.update { it.copy(params = next, message = "Applied \"${preset.name}\"") }
        paramsFlow.value = next
        record(next)
        if (next.usesMasks()) ensureMasks()
    }

    fun deletePreset(preset: Preset) {
        _state.update { it.copy(presets = PresetStore.delete(getApplication(), preset.name)) }
    }

    /** Rates this photo (0 clears it). */
    fun rate(stars: Int) {
        val key = editsKey.ifEmpty { return }
        _state.update { it.copy(rating = stars) }
        viewModelScope.launch(Dispatchers.IO) { RatingsStore.set(getApplication(), listOf(key), stars) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun export(settings: ExportSettings) {
        val uri = loadedUri ?: return
        val p = _state.value.params
        val key = editsKey
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { EditsStore.save(getApplication(), key, p) } }
            ExportQueue.enqueue(getApplication(), listOf(uri), settings)
            _state.update { it.copy(message = "Export started. It keeps running if you leave the app.") }
        }
    }

    /** Rounds to the nearest third-stop ISO value, the way cameras label them. */
    private fun roundIso(iso: Float): Int {
        val steps = intArrayOf(64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
        return steps.minByOrNull { kotlin.math.abs(kotlin.math.ln(it / iso.toDouble())) } ?: iso.toInt()
    }

    private companion object {
        /** EXIF orientations that mirror the picture. */
        val MIRRORED = setOf(2, 4, 5, 7)
        const val MAX_HISTORY = 200
    }
}
