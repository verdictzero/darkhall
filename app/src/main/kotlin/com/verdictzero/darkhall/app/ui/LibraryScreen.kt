package com.verdictzero.darkhall.app.ui

import com.verdictzero.darkhall.core.export.ExportSettings
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verdictzero.darkhall.app.data.RawRepository
import com.verdictzero.darkhall.app.data.RawFile
import com.verdictzero.darkhall.core.raw.ShotFacts
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (Uri) -> Unit, vm: LibraryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.setFolder(it) }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addFiles(uris)
    }
    var exportDialog by remember { mutableStateOf(false) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }
    val exportSelection = rememberExportStarter { vm.exportSelection(exportSettings) }
    if (exportDialog) {
        ExportDialog(state.selected.size, onDismiss = { exportDialog = false }) { s ->
            exportDialog = false
            exportSettings = s
            exportSelection()
        }
    }
    var selectionMenu by remember { mutableStateOf(false) }
    var libraryMenu by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    if (licenses) LicensesScreen(onDismiss = { licenses = false })

    LaunchedEffect(Unit) { vm.refreshEditMarks() } // returning from the develop screen
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    BackHandler(enabled = state.selecting) { vm.clearSelection() }
    val strokes = LocalStrokes.current

    Scaffold(
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    title = { Text("${state.selected.size} selected") },
                    navigationIcon = {
                        StrokedIconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
                    },
                    actions = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StrokedTextButton("Paste", onClick = { vm.pasteToSelection() }, enabled = state.canPaste)
                            StrokedTextButton("Auto", onClick = { vm.autoToneSelection() }, enabled = state.autoProgress == null)
                            StrokedTextButton("Export", onClick = { exportDialog = true })
                        }
                        Box(Modifier.padding(horizontal = 6.dp)) {
                            StrokedIconButton(onClick = { selectionMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(
                                expanded = selectionMenu,
                                onDismissRequest = { selectionMenu = false },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(strokes.width, strokes.container),
                            ) {
                                DropdownMenuItem(text = { Text("Select all") }, onClick = { selectionMenu = false; vm.selectAll() })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.bottomStroke(strokes.selected),
                )
            } else {
                TopAppBar(
                    title = { Text("Darkhall") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            StrokedTextButton("Folder", onClick = { pickFolder.launch(null) })
                            StrokedTextButton("Files", onClick = { pickFiles.launch(arrayOf("*/*")) })
                            Box {
                                StrokedIconButton(onClick = { libraryMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                                DropdownMenu(
                                    expanded = libraryMenu,
                                    onDismissRequest = { libraryMenu = false },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(strokes.width, strokes.container),
                                ) {
                                    DropdownMenuItem(text = { Text("Open-source licences") }, onClick = { libraryMenu = false; licenses = true })
                                }
                            }
                        }
                    },
                    modifier = Modifier.bottomStroke(strokes.container),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.autoProgress != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.autoProgress?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (state.files.isEmpty() && !state.loading) {
                EmptyLibrary(
                    onPickFolder = { pickFolder.launch(null) },
                    onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                )
            } else {
                val hint = if (state.selecting) "Tap to add or remove photos" else "Long-press to select photos for paste, auto tone or batch export"
                Text(
                    (state.folderName?.let { "$it  ·  ${state.files.size} raw files  ·  " } ?: "") + hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 128.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.files, key = { it.key }) { file ->
                        RawCell(
                            file,
                            selected = file.key in state.selected,
                            edited = file.key in state.edited,
                            onClick = { if (state.selecting) vm.toggle(file) else onOpen(file.uri) },
                            onLongClick = { vm.toggle(file) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onPickFolder: () -> Unit, onPickFiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No raw files yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Pick a folder of raw files (your TG-7's DCIM/100OLYMP, another camera's card, or your phone's DNGs), " +
                "or open individual files. Reads ORF, DNG, ARW, NEF, CR2, RW2 and PEF.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        val strokes = LocalStrokes.current
        Button(onClick = onPickFolder, border = BorderStroke(strokes.width, strokes.selected)) { Text("Choose folder") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFiles, border = BorderStroke(strokes.width, strokes.action)) { Text("Open files") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RawCell(file: RawFile, selected: Boolean, edited: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = file.key) {
        value = withContext(Dispatchers.IO) { RawRepository.thumbnail(context, file) }
    }
    val facts by produceState<ShotFacts?>(initialValue = null, key1 = file.key) {
        value = withContext(Dispatchers.IO) { RawRepository.facts(context, file) }
    }
    val accent = MaterialTheme.colorScheme.primary
    val strokes = LocalStrokes.current
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    if (selected) strokes.selectedWidth else strokes.width,
                    if (selected) strokes.selected else strokes.container,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            val b = thumb
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(Modifier.padding(24.dp), strokeWidth = 2.dp)
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(accent)
                        .border(strokes.width, strokes.overlay, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
            facts?.badge?.let { badge ->
                // Depth and water temperature from the TG series' field sensors.
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC0B2A3A))
                        .border(strokes.width, strokes.overlay, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (edited) {
                Text(
                    "EDITED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f))
                        .border(strokes.width, strokes.action, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            file.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
    }
}
