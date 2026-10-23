package com.verdictzero.darkhall.app.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.verdictzero.darkhall.core.develop.DevelopParams
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

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    val startExport = rememberExportStarter { vm.export() }
    val ready = !state.loading && state.error == null

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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = startExport, enabled = ready) { Text("Export") }
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = ready) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Copy settings") }, onClick = { menuOpen = false; vm.copySettings() })
                            DropdownMenuItem(
                                text = { Text("Paste settings") },
                                enabled = state.canPaste,
                                onClick = { menuOpen = false; vm.pasteSettings() },
                            )
                            DropdownMenuItem(text = { Text("Reset all sliders") }, onClick = { menuOpen = false; vm.resetAll() })
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
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                            PreviewPane(state, vm, Modifier.weight(1f).fillMaxHeight())
                            SliderPane(state, vm, Modifier.width(360.dp).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            PreviewPane(state, vm, Modifier.fillMaxWidth().weight(0.48f))
                            SliderPane(state, vm, Modifier.fillMaxWidth().weight(0.52f))
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
        Button(onClick = onBack) { Text("Back to library") }
    }
}

@Composable
private fun PreviewPane(state: DevelopState, vm: DevelopViewModel, modifier: Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var comparing by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val shown = if (comparing) state.compare else state.preview

    // After the view settles, ask for a full-resolution render of what is on screen.
    val bmpW = state.preview?.width ?: 0
    val bmpH = state.preview?.height ?: 0
    LaunchedEffect(scale, offset, boxSize, bmpW, bmpH) {
        if (scale <= 1.01f || boxSize.width == 0 || bmpW == 0) {
            vm.requestLoupe(null)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(150)
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

    Box(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 12f)
                        offset = if (newScale == 1f) Offset.Zero else offset + pan
                        scale = newScale
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            // Zoom to roughly 1:1 around the tapped point.
                            val target = 4f
                            val c = Offset(size.width / 2f, size.height / 2f)
                            offset = (c - tap) * (target - 1f)
                            scale = target
                        }
                    })
                },
        ) {
            val loupe = state.loupe?.takeIf { !comparing && it.params == state.params }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Preview" }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
            ) {
                val bmp = shown ?: return@Canvas
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
            }
        }
        state.histogram?.let { bins ->
            Histogram(
                bins,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(width = 112.dp, height = 48.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (comparing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            comparing = true
                            tryAwaitRelease()
                            comparing = false
                        })
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    if (comparing) "As shot" else "Hold to compare",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (comparing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            val zoomLabel = when {
                scale > 1.01f && state.loupeBusy -> "Rendering detail…"
                scale > 1.01f && state.loupe != null && state.loupe.params == state.params -> "Full resolution"
                state.renderMs > 0 -> "${state.renderMs} ms · ${state.engine}"
                else -> ""
            }
            Text(
                zoomLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SliderPane(state: DevelopState, vm: DevelopViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var browsing by remember { mutableStateOf(false) }
    val lookName = remember(state.params.look) {
        state.params.look?.let { id -> LookLibrary.info(context, id)?.name ?: "Unknown look" } ?: "None"
    }
    if (browsing) LookBrowser(vm, state.params.look, onDismiss = { browsing = false })
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(bottom = 96.dp)) {
        for (group in AdjustmentGroup.entries) {
            item(key = "h-${group.name}") {
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
                )
            }
            if (group == AdjustmentGroup.LOOK) {
                item(key = "look-picker") {
                    val info = remember(state.params.look) { state.params.look?.let { LookLibrary.info(context, it) } }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { browsing = true }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        Text("Browse", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Browse looks", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            val adjustments = Adjustment.entries.filter { it.group == group }
                .filter { it != Adjustment.LENS_PROFILE || state.hasLensProfile }
                .filter { it != Adjustment.LOOK_AMOUNT || state.params.look != null }
            items(adjustments, key = { it.name }) { adj ->
                SliderRow(adj, state.params, state.defaults, onChange = { vm.set(adj, it) }, onReset = { vm.resetOne(adj) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderRow(
    adj: Adjustment,
    params: DevelopParams,
    defaults: DevelopParams,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val value = params[adj]
    val isDefault = params.isDefault(adj, defaults)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                adj.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(adj) { detectTapGestures(onDoubleTap = { onReset() }) },
            )
            Text(
                adj.format(value),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
        // Two-sided adjustments fill from zero outwards; one-sided ones from the left.
        val anchor = if (adj.min < 0f && adj.max > 0f) 0f else adj.min
        val active = MaterialTheme.colorScheme.primary
        val inactive = MaterialTheme.colorScheme.outlineVariant
        val tick = MaterialTheme.colorScheme.outline
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = adj.min..adj.max,
            colors = SliderDefaults.colors(thumbColor = active),
            track = {
                Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                    val span = adj.max - adj.min
                    val y = size.height / 2f
                    val stroke = 4.dp.toPx()
                    fun xOf(v: Float) = (v - adj.min) / span * size.width
                    drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                    if (anchor != adj.min) {
                        val cx = xOf(anchor)
                        drawLine(tick, Offset(cx, y - 6.dp.toPx()), Offset(cx, y + 6.dp.toPx()), strokeWidth = 2.dp.toPx())
                    }
                    val a = xOf(anchor); val b = xOf(value)
                    if (abs(b - a) > 0.5f) {
                        drawLine(active, Offset(minOf(a, b), y), Offset(maxOf(a, b), y), strokeWidth = stroke, cap = StrokeCap.Round)
                    }
                }
            },
        )
    }
}
