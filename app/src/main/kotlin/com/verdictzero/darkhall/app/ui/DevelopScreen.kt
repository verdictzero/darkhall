package com.verdictzero.darkhall.app.ui

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.Snackbar
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdictzero.darkhall.app.data.LookLibrary
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.AdjustmentGroup
import com.verdictzero.darkhall.core.develop.Crop
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberUpdatedState
import com.verdictzero.darkhall.core.develop.DevelopParams
import com.verdictzero.darkhall.core.export.ExportSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevelopScreen(uri: Uri, vm: DevelopViewModel, onBack: () -> Unit) {
    LaunchedEffect(uri) { vm.open(uri) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    BackHandler(enabled = state.cropping) { vm.setCropping(false) }
    if (licenses) LicensesScreen(onDismiss = { licenses = false })
    val guides = remember { StraightenGuides() }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        // Show first, clear after: clearing changes the key and would cancel this effect.
        if ((msg.startsWith("Auto:") || msg.startsWith("Level:")) && state.beforeAuto != null) {
            val r = snackbar.showSnackbar(msg, actionLabel = "Undo", duration = SnackbarDuration.Long)
            vm.clearMessage()
            if (r == SnackbarResult.ActionPerformed) vm.undoAuto()
        } else {
            snackbar.showSnackbar(msg)
            vm.clearMessage()
        }
    }
    // The dialog picks the format; the starter then asks for any permissions and queues it.
    var exportDialog by remember { mutableStateOf(false) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }
    val startExport = rememberExportStarter { vm.export(exportSettings) }
    if (exportDialog) {
        ExportDialog(1, onDismiss = { exportDialog = false }) { s ->
            exportDialog = false
            exportSettings = s
            startExport()
        }
    }
    val ready = !state.loading && state.error == null
    val strokes = LocalStrokes.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.fileName.ifEmpty { "Develop" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (state.info.isNotEmpty()) {
                            Text(state.info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    StrokedIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    StrokedIconButton(onClick = { vm.undo() }, enabled = ready && state.canUndo) {
                        Icon(Glyphs.Undo, contentDescription = "Undo")
                    }
                    StrokedIconButton(onClick = { vm.redo() }, enabled = ready && state.canRedo) {
                        Icon(Glyphs.Redo, contentDescription = "Redo")
                    }
                    StrokedTextButton("Export", onClick = { exportDialog = true }, enabled = ready)
                    Box {
                        StrokedIconButton(onClick = { menuOpen = true }, enabled = ready) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(strokes.width, strokes.container),
                        ) {
                            DropdownMenuItem(text = { Text("Copy settings") }, onClick = { menuOpen = false; vm.copySettings() })
                            DropdownMenuItem(
                                text = { Text("Paste settings") },
                                enabled = state.canPaste,
                                onClick = { menuOpen = false; vm.pasteSettings() },
                            )
                            DropdownMenuItem(text = { Text("Reset all sliders") }, onClick = { menuOpen = false; vm.resetAll() })
                            DropdownMenuItem(
                                text = { Text("Auto tone new photos") },
                                trailingIcon = { Checkbox(checked = state.autoToneOnOpen, onCheckedChange = null) },
                                onClick = { menuOpen = false; vm.setAutoToneOnOpen(!state.autoToneOnOpen) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("GPU rendering")
                                        if (state.gpuLabel.isNotEmpty()) {
                                            Text(state.gpuLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                },
                                trailingIcon = { Checkbox(checked = state.gpuEnabled, onCheckedChange = null) },
                                onClick = { menuOpen = false; vm.setGpuEnabled(!state.gpuEnabled) },
                            )
                            DropdownMenuItem(text = { Text("Open-source licences") }, onClick = { menuOpen = false; licenses = true })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.bottomStroke(strokes.container),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    data,
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.border(strokes.width, strokes.action, RoundedCornerShape(12.dp)),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.error != null -> ErrorPane(state.error!!, onBack)
                state.loading -> LoadingPane()
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val landscape = maxWidth > maxHeight
                    if (landscape) {
                        Row(Modifier.fillMaxSize()) {
                            PreviewPane(state, vm, guides, Modifier.weight(1f).fillMaxHeight())
                            SliderPane(state, vm, guides, Modifier.width(360.dp).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            PreviewPane(state, vm, guides, Modifier.fillMaxWidth().weight(0.48f))
                            SliderPane(state, vm, guides, Modifier.fillMaxWidth().weight(0.52f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingPane() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Decoding raw…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorPane(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Could not open this file", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack, border = BorderStroke(LocalStrokes.current.width, LocalStrokes.current.selected)) { Text("Back to library") }
    }
}

/** Test tag of the editor's slider list. */
const val SLIDERS_TAG = "sliders"

/** What the straighten tools are doing; shared by the slider panel and the preview. */
@Stable
class StraightenGuides {
    /** A finger is on the Straighten slider. */
    var adjusting by mutableStateOf(false)
    /** Draw level: the next drag on the preview draws a line that is then made level. */
    var leveling by mutableStateOf(false)
}

/** How the preview shows the edit: alone, or beside the original. */
enum class CompareMode { SINGLE, SIDE_BY_SIDE, TOP_BOTTOM }

@Composable
private fun PreviewPane(state: DevelopState, vm: DevelopViewModel, guides: StraightenGuides, modifier: Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var pickedMode by rememberSaveable { mutableStateOf(CompareMode.SINGLE) }
    val cropping = state.cropping
    // The crop tool works on the single, unzoomed view.
    val mode = if (cropping) CompareMode.SINGLE else pickedMode
    val split = mode != CompareMode.SINGLE
    val croppingNow by rememberUpdatedState(cropping)
    // The original shows while the button or the photo itself is held (single view only).
    var holdingButton by remember { mutableStateOf(false) }
    var holdingPhoto by remember { mutableStateOf(false) }
    val original = !split && !cropping && (holdingButton || holdingPhoto)
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(cropping) {
        if (cropping) { scale = 1f; offset = Offset.Zero }
    }

    // The grid shows while straightening: slider held, Draw level on, or just after a change.
    val straighten = state.params[Adjustment.STRAIGHTEN]
    var lastStraighten by remember { mutableFloatStateOf(straighten) }
    var recentlyStraightened by remember { mutableStateOf(false) }
    LaunchedEffect(straighten) {
        if (straighten == lastStraighten) return@LaunchedEffect
        lastStraighten = straighten
        recentlyStraightened = true
        delay(1500)
        recentlyStraightened = false
    }
    val showGrid = !original && (guides.adjusting || guides.leveling || recentlyStraightened)
    // Draw level: the line being drawn, in preview-pane pixels.
    var lineStart by remember { mutableStateOf<Offset?>(null) }
    var lineEnd by remember { mutableStateOf<Offset?>(null) }

    // After the view settles, ask for a full-resolution render of what is on screen. Not in
    // the split views, where the original beside it has only preview resolution to offer.
    val bmpW = state.preview?.width ?: 0
    val bmpH = state.preview?.height ?: 0
    LaunchedEffect(scale, offset, boxSize, bmpW, bmpH, split) {
        if (split || scale <= 1.01f || boxSize.width == 0 || bmpW == 0) {
            vm.requestLoupe(null)
            return@LaunchedEffect
        }
        delay(150)
        val bw = boxSize.width.toFloat()
        val bh = boxSize.height.toFloat()
        val fit = min(bw / bmpW, bh / bmpH)
        val imgW = bmpW * fit
        val imgH = bmpH * fit
        val left0 = (bw - imgW) / 2f
        val top0 = (bh - imgH) / 2f
        fun toImage(sx: Float, sy: Float): Offset {
            val px = (sx - bw / 2f - offset.x) / scale + bw / 2f
            val py = (sy - bh / 2f - offset.y) / scale + bh / 2f
            return Offset(((px - left0) / imgW).coerceIn(0f, 1f), ((py - top0) / imgH).coerceIn(0f, 1f))
        }
        val a = toImage(0f, 0f)
        val b = toImage(bw, bh)
        if (b.x - a.x < 1e-3f || b.y - a.y < 1e-3f) {
            vm.requestLoupe(null)
            return@LaunchedEffect
        }
        vm.requestLoupe(LoupeRequest(a.x, a.y, b.x, b.y, (b.x - a.x) * imgW * scale))
    }

    val strokes = LocalStrokes.current
    Box(
        modifier
            .cautionStripes()
            .onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (croppingNow) return@detectTransformGestures
                        // Moving the picture is not holding it.
                        holdJob?.cancel(); holdingPhoto = false
                        val newScale = (scale * zoom).coerceIn(1f, 12f)
                        offset = if (newScale == 1f) Offset.Zero else offset + pan
                        scale = newScale
                    }
                }
                .pointerInput(split) {
                    detectTapGestures(
                        onPress = {
                            if (split || croppingNow) return@detectTapGestures
                            // Held still for a moment: show the original until released.
                            holdJob = scope.launch { delay(HOLD_MS); holdingPhoto = true }
                            tryAwaitRelease()
                            holdJob?.cancel()
                            holdingPhoto = false
                        },
                        onDoubleTap = { tap ->
                            if (croppingNow) return@detectTapGestures
                            if (scale > 1.01f) {
                                scale = 1f; offset = Offset.Zero
                            } else {
                                // Zoom to roughly 1:1 around the tapped point.
                                val target = 4f
                                val c = Offset(size.width / 2f, size.height / 2f)
                                offset = (c - tap) * (target - 1f)
                                scale = target
                            }
                        },
                    )
                },
        ) {
            val divider = strokes.selected
            val skyOverlay = state.maskOverlay?.takeIf { state.showMask != null }
            when (mode) {
                CompareMode.SINGLE -> PhotoView(
                    bitmap = if (original) state.compare else state.preview,
                    loupe = state.loupe?.takeIf { !original && it.params == state.params },
                    scale = scale, offset = offset,
                    showGrid = showGrid,
                    label = if (original) "Original" else if (skyOverlay != null) "${state.showMask?.title} mask" else null,
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Preview" },
                    overlay = if (original) null else skyOverlay,
                )
                CompareMode.SIDE_BY_SIDE -> Row(Modifier.fillMaxSize().semantics { contentDescription = "Preview" }) {
                    PhotoView(state.compare, null, scale, offset, false, "Original", Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(2.dp).fillMaxHeight().background(divider))
                    PhotoView(state.preview, null, scale, offset, showGrid, "Edited", Modifier.weight(1f).fillMaxHeight(), skyOverlay)
                }
                CompareMode.TOP_BOTTOM -> Column(Modifier.fillMaxSize().semantics { contentDescription = "Preview" }) {
                    PhotoView(state.compare, null, scale, offset, false, "Original", Modifier.weight(1f).fillMaxWidth())
                    Box(Modifier.height(2.dp).fillMaxWidth().background(divider))
                    PhotoView(state.preview, null, scale, offset, showGrid, "Edited", Modifier.weight(1f).fillMaxWidth(), skyOverlay)
                }
            }
        }
        val bmp = state.preview
        if (cropping && bmp != null) {
            val aspect = Crop.aspect(state.params)
            val crop = Crop.of(state.params)
            CropOverlay(
                crop = crop,
                ratio = Crop.ratio(aspect, state.fullWidth, state.fullHeight, crop.height * state.fullHeight > crop.width * state.fullWidth),
                fullWidth = state.fullWidth, fullHeight = state.fullHeight,
                imageWidth = bmp.width, imageHeight = bmp.height,
                onChange = { vm.setCrop(it) },
            )
            OverlayLabel(cropLabel(crop, aspect, state.fullWidth, state.fullHeight), Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
        if (guides.leveling) {
            val accent = MaterialTheme.colorScheme.primary
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Draw a line to level" }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { lineStart = it; lineEnd = it },
                            onDrag = { change, _ -> lineEnd = change.position },
                            onDragEnd = {
                                val a = lineStart; val b = lineEnd
                                if (a != null && b != null && (b - a).getDistance() > 32.dp.toPx()) {
                                    vm.levelLine(b.x - a.x, b.y - a.y)
                                    guides.leveling = false
                                }
                                lineStart = null; lineEnd = null
                            },
                            onDragCancel = { lineStart = null; lineEnd = null },
                        )
                    },
            ) {
                val a = lineStart ?: return@Canvas
                val b = lineEnd ?: return@Canvas
                drawLine(Color.Black.copy(alpha = 0.5f), a, b, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(accent, a, b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                for (p in listOf(a, b)) {
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius = 7.dp.toPx(), center = p)
                    drawCircle(accent, radius = 5.dp.toPx(), center = p)
                }
            }
        }
        if (showGrid || guides.leveling) {
            val a = lineStart; val b = lineEnd
            val label = when {
                guides.leveling && a != null && b != null && (b - a).getDistance() > 1f -> lineLabel(b - a)
                guides.leveling -> "Drag along the horizon or an upright edge"
                else -> "Straighten ${Adjustment.STRAIGHTEN.format(straighten)}"
            }
            OverlayLabel(label, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
        // Out of the way of the guide label while straightening, and of the split view's labels.
        state.histogram?.takeIf { !showGrid && !guides.leveling && !split && !cropping }?.let { bins ->
            Histogram(
                bins,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(width = 112.dp, height = 48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(strokes.width, strokes.overlay, RoundedCornerShape(6.dp)),
            )
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!split && !cropping) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (original) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                        .border(strokes.width, if (original) strokes.selected else strokes.overlay, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                holdingButton = true
                                tryAwaitRelease()
                                holdingButton = false
                            })
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (original) "Original" else "Hold for original",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (original) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            val zoomLabel = when {
                split -> ""
                scale > 1.01f && state.loupeBusy -> "Rendering detail…"
                scale > 1.01f && state.loupe != null && state.loupe.params == state.params -> "Full resolution"
                state.renderMs > 0 -> "${state.renderMs} ms · ${state.engine}"
                else -> ""
            }
            if (zoomLabel.isNotEmpty()) {
                Text(
                    zoomLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier
                        .background(Color(0xB3141416), RoundedCornerShape(10.dp))
                        .border(strokes.width, strokes.overlay, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (!cropping) CompareModePicker(mode, onPick = { pickedMode = it })
        }
    }
}

/** How long the photo must be held, still, before the original shows. */
private const val HOLD_MS = 300L

/**
 * One photo fitted into its box, with the shared zoom and pan, a thin frame, the full-resolution
 * loupe when there is one, the straighten grid, and a corner label.
 */
@Composable
private fun PhotoView(
    bitmap: Bitmap?,
    loupe: Loupe?,
    scale: Float,
    offset: Offset,
    showGrid: Boolean,
    label: String?,
    modifier: Modifier,
    /** Drawn over the photo, same placement (a region's mask). */
    overlay: Bitmap? = null,
) {
    val frameStroke = LocalStrokes.current.overlay
    Box(modifier.clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        ) {
            val bmp = bitmap ?: return@Canvas
            val fit = min(size.width / bmp.width, size.height / bmp.height)
            val w = bmp.width * fit
            val h = bmp.height * fit
            val left = (size.width - w) / 2f
            val top = (size.height - h) / 2f
            drawImage(
                bmp.asImageBitmap(),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            // Thin light frame separating the photo from the stripes; constant on screen at any zoom.
            val sw = 1.dp.toPx() / scale
            drawRect(frameStroke, topLeft = Offset(left - sw / 2f, top - sw / 2f), size = Size(w + sw, h + sw), style = Stroke(sw))
            if (loupe != null) {
                val lx = left + loupe.left * w
                val ly = top + loupe.top * h
                val lw = (loupe.right - loupe.left) * w
                val lh = (loupe.bottom - loupe.top) * h
                drawImage(
                    loupe.bitmap.asImageBitmap(),
                    dstOffset = IntOffset(lx.roundToInt(), ly.roundToInt()),
                    dstSize = IntSize(max(1, lw.roundToInt()), max(1, lh.roundToInt())),
                    filterQuality = FilterQuality.Medium,
                )
            }
            if (overlay != null) {
                drawImage(
                    overlay.asImageBitmap(),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            if (showGrid) drawStraightenGrid(left, top, w, h, 1.dp.toPx() / scale)
        }
        if (label != null) OverlayLabel(label, Modifier.align(Alignment.TopStart).padding(8.dp))
    }
}

/** A small dark label over the photo. */
@Composable
private fun OverlayLabel(text: String, modifier: Modifier) {
    val strokes = LocalStrokes.current
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .background(Color(0xD9141416), RoundedCornerShape(10.dp))
            .border(strokes.width, strokes.overlay, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Single view, side by side, or top and bottom: three small segments drawn as their layouts. */
@Composable
private fun CompareModePicker(mode: CompareMode, onPick: (CompareMode) -> Unit) {
    val strokes = LocalStrokes.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(shape)
            .background(Color(0xD9141416))
            .border(strokes.width, strokes.overlay, shape),
    ) {
        for (m in CompareMode.entries) {
            val selected = m == mode
            val ink = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            val description = when (m) {
                CompareMode.SINGLE -> "Single view"
                CompareMode.SIDE_BY_SIDE -> "Side by side with the original"
                CompareMode.TOP_BOTTOM -> "Original above, edit below"
            }
            Box(
                Modifier
                    .size(width = 40.dp, height = 32.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .semantics { contentDescription = description }
                    .clickable { onPick(m) },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(width = 22.dp, height = 16.dp)) {
                    val sw = 1.5.dp.toPx()
                    drawRect(ink, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw), style = Stroke(sw))
                    when (m) {
                        CompareMode.SINGLE -> {}
                        CompareMode.SIDE_BY_SIDE -> drawLine(ink, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), sw)
                        CompareMode.TOP_BOTTOM -> drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), sw)
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderPane(state: DevelopState, vm: DevelopViewModel, guides: StraightenGuides, modifier: Modifier) {
    val context = LocalContext.current
    var browsing by remember { mutableStateOf(false) }
    // Tells the preview when a finger is on the Straighten slider, so it can show the grid.
    val straightenTouch = remember { MutableInteractionSource() }
    val straightenDragged by straightenTouch.collectIsDraggedAsState()
    val straightenPressed by straightenTouch.collectIsPressedAsState()
    LaunchedEffect(straightenDragged, straightenPressed) { guides.adjusting = straightenDragged || straightenPressed }
    val lookName = remember(state.params.look) {
        state.params.look?.let { id -> LookLibrary.info(context, id)?.name ?: "Unknown look" } ?: "None"
    }
    if (browsing) LookBrowser(vm, state.params.look, onDismiss = { browsing = false })
    LazyColumn(
        modifier.background(MaterialTheme.colorScheme.surface).testTag(SLIDERS_TAG),
        contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp),
    ) {
        for (group in AdjustmentGroup.entries) {
            val absent = group.region?.let { r -> state.scene == SceneStatus.DONE && !state.has(r) && !state.params.uses(r) } ?: false
            val adjustments = Adjustment.entries.filter { it.group == group && group.sliders && !absent }
                .filter { it != Adjustment.LENS_PROFILE || state.hasLensProfile }
                .filter { it != Adjustment.LOOK_AMOUNT || state.params.look != null }
            item(key = "g-${group.name}") {
                SectionCard(
                    group.title,
                    trailing = {
                        if (group == AdjustmentGroup.LIGHT) {
                            StrokedTextButton(if (state.autoBusy) "Auto…" else "Auto", onClick = { vm.autoTone() }, enabled = !state.autoBusy)
                        }
                        val region = group.region
                        if (region != null && (state.scene != SceneStatus.DONE || state.has(region))) {
                            StrokedTextButton("Show mask", onClick = { vm.toggleMask(region) }, selected = state.showMask == region, modifier = Modifier.testTag("mask-${region.name}"))
                        }
                        if (group == AdjustmentGroup.MIXER) {
                            StrokedTextButton("Reset", onClick = { vm.resetMixer() }, enabled = state.mixerUsed())
                        }
                        if (group == AdjustmentGroup.CROP) {
                            StrokedTextButton("Reset", onClick = { vm.resetCrop() }, enabled = !Crop.of(state.params).isFull)
                            StrokedTextButton(if (state.cropping) "Done" else "Crop", onClick = { vm.setCropping(!state.cropping) }, selected = state.cropping)
                        }
                        if (group == AdjustmentGroup.LENS) {
                            StrokedTextButton("Draw level", onClick = { guides.leveling = !guides.leveling }, selected = guides.leveling)
                            StrokedTextButton(
                                if (state.straightenBusy) "Straightening…" else "Auto straighten",
                                onClick = { guides.leveling = false; vm.autoStraighten() },
                                enabled = !state.straightenBusy && state.preview != null,
                            )
                        }
                    },
                ) {
                    val region = group.region
                    if (region != null) {
                        // Scrolling a masked section into view starts looking for the regions (once per photo).
                        LaunchedEffect(Unit) { vm.ensureMasks() }
                        Text(
                            when {
                                state.scene != SceneStatus.DONE -> "Finding the sky, water, people and foliage… (a second or two, once per photo)"
                                state.has(region) -> "${region.title}: ${((state.coverage[region] ?: 0f) * 100).roundToInt().coerceAtLeast(1)}% of the picture. These sliders change only the ${region.noun}."
                                else -> "No ${region.noun} found in this photo."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        )
                    }
                    if (group == AdjustmentGroup.CROP) CropControls(state, vm)
                    if (group == AdjustmentGroup.MIXER) MixerControls(state, vm)
                    if (group == AdjustmentGroup.LOOK) {
                        val info = remember(state.params.look) { state.params.look?.let { LookLibrary.info(context, it) } }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { browsing = true }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    lookName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (info != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    info?.let { "${it.group} · ${it.category}" } ?: "Film simulations and creative looks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                Modifier
                                    .border(LocalStrokes.current.width, LocalStrokes.current.action, RoundedCornerShape(10.dp))
                                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Browse", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Browse looks", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (state.favoriteLooks.isNotEmpty()) FavoriteLookStrip(vm, state.favoriteLooks, state.params.look)
                    }
                    for (adj in adjustments) {
                        SliderRow(
                            adj, state.params, state.defaults,
                            onChange = { vm.set(adj, it) },
                            onReset = { vm.resetOne(adj) },
                            interactionSource = if (adj == Adjustment.STRAIGHTEN) straightenTouch else null,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    adj: Adjustment,
    params: DevelopParams,
    defaults: DevelopParams,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
    interactionSource: MutableInteractionSource? = null,
) {
    val value = params[adj]
    val isDefault = params.isDefault(adj, defaults)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                adj.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(adj) { detectTapGestures(onDoubleTap = { onReset() }) },
            )
            if (adj in HUE_SLIDERS) {
                // The hue a grading slider picks, at full strength.
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.hsv(value.coerceIn(0f, 359.99f), 0.75f, 0.95f))
                        .border(1.dp, LocalStrokes.current.overlay, CircleShape),
                )
            }
            Text(
                adj.format(value),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
        // Two-sided adjustments fill from zero outwards; one-sided ones from the left.
        val anchor = if (adj.min < 0f && adj.max > 0f) 0f else adj.min
        val tag = Modifier.testTag("slider-${adj.name}")
        if (interactionSource != null) {
            ShadowedSlider(value = value, onValueChange = onChange, range = adj.min..adj.max, anchor = anchor, modifier = tag, interactionSource = interactionSource)
        } else {
            ShadowedSlider(value = value, onValueChange = onChange, range = adj.min..adj.max, anchor = anchor, modifier = tag)
        }
    }
}

/** Sliders that pick a hue, shown with a swatch of it. */
private val HUE_SLIDERS = setOf(Adjustment.GRADE_SHADOW_HUE, Adjustment.GRADE_MIDTONE_HUE, Adjustment.GRADE_HIGHLIGHT_HUE)

/** How far a drawn line is from level or upright, whichever it is nearer. */
private fun lineLabel(d: Offset): String {
    var a = atan2(d.y, d.x) * 180f / PI.toFloat()
    if (a > 90f) a -= 180f
    if (a <= -90f) a += 180f
    val upright = abs(a) > 45f
    val off = if (upright) 90f - abs(a) else abs(a)
    return "%.1f° off %s".format(off, if (upright) "upright" else "level")
}

/**
 * Straighten guides over the photo at ([left], [top], [w] x [h]): a fine square grid from the
 * centre, with the thirds drawn brighter. Each line is a light stroke on a dark one, so it
 * reads on bright skies and dark water alike. [px] is one screen pixel in drawing units.
 */
private fun DrawScope.drawStraightenGrid(left: Float, top: Float, w: Float, h: Float, px: Float) {
    val shade = Color.Black.copy(alpha = 0.35f)
    fun line(a: Offset, b: Offset, alpha: Float, width: Float) {
        drawLine(shade, a, b, strokeWidth = (width + 1.5f) * px)
        drawLine(Color.White.copy(alpha = alpha), a, b, strokeWidth = width * px)
    }
    val cell = min(w, h) / 8f
    val cx = left + w / 2f
    val cy = top + h / 2f
    var k = 0
    while (k * cell < w / 2f) {
        for (x in if (k == 0) listOf(cx) else listOf(cx - k * cell, cx + k * cell)) {
            line(Offset(x, top), Offset(x, top + h), 0.28f, 0.8f)
        }
        k++
    }
    k = 0
    while (k * cell < h / 2f) {
        for (y in if (k == 0) listOf(cy) else listOf(cy - k * cell, cy + k * cell)) {
            line(Offset(left, y), Offset(left + w, y), 0.28f, 0.8f)
        }
        k++
    }
    for (t in listOf(1f / 3f, 2f / 3f)) {
        line(Offset(left + w * t, top), Offset(left + w * t, top + h), 0.75f, 1.2f)
        line(Offset(left, top + h * t), Offset(left + w, top + h * t), 0.75f, 1.2f)
    }
}
