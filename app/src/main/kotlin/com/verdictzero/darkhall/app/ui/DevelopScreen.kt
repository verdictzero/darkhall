package com.verdictzero.darkhall.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verdictzero.darkhall.core.develop.Adjustment
import com.verdictzero.darkhall.core.develop.AdjustmentGroup
import com.verdictzero.darkhall.core.develop.DevelopParams

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevelopScreen(uri: Uri, onBack: () -> Unit) {
    val vm: DevelopViewModel = viewModel(key = uri.toString())
    LaunchedEffect(uri) { vm.open(uri) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.export()
    }
    val startExport = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.export()
        }
    }

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
                    IconButton(onClick = { vm.resetAll() }, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset all sliders")
                    }
                    TextButton(onClick = startExport, enabled = !state.loading && state.exportProgress == null && state.error == null) {
                        Text("Export")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.exportProgress?.let { p ->
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
            when {
                state.error != null -> ErrorPane(state.error!!, onBack)
                state.loading -> LoadingPane()
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val landscape = maxWidth > maxHeight
                    if (landscape) {
                        Row(Modifier.fillMaxSize()) {
                            PreviewPane(state, Modifier.weight(1f).fillMaxHeight())
                            SliderPane(state, vm, Modifier.width(360.dp).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            PreviewPane(state, Modifier.fillMaxWidth().weight(0.48f))
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
private fun PreviewPane(state: DevelopState, modifier: Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var comparing by remember { mutableStateOf(false) }
    val shown = if (comparing) state.compare else state.preview

    Box(modifier.background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (newScale == 1f) Offset.Zero else offset + pan
                        scale = newScale
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                },
            contentAlignment = Alignment.Center,
        ) {
            shown?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        },
                )
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
            if (state.renderMs > 0) {
                Text(
                    "${state.renderMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SliderPane(state: DevelopState, vm: DevelopViewModel, modifier: Modifier) {
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(bottom = 24.dp)) {
        for (group in AdjustmentGroup.entries) {
            item(key = "h-${group.name}") {
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
                )
            }
            items(Adjustment.entries.filter { it.group == group }, key = { it.name }) { adj ->
                SliderRow(adj, state.params, state.defaults, onChange = { vm.set(adj, it) }, onReset = { vm.resetOne(adj) })
            }
        }
    }
}

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
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = adj.min..adj.max,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}
