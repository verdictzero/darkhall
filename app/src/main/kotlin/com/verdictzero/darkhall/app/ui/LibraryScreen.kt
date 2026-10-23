package com.verdictzero.darkhall.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.verdictzero.darkhall.app.data.OrfRepository
import com.verdictzero.darkhall.app.data.RawFile
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
    val exportSelection = rememberExportStarter { vm.exportSelection() }
    var selectionMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshEditMarks() } // returning from the develop screen
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    BackHandler(enabled = state.selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    title = { Text("${state.selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
                    },
                    actions = {
                        TextButton(onClick = { vm.pasteToSelection() }, enabled = state.canPaste) { Text("Paste") }
                        TextButton(onClick = { vm.autoToneSelection() }, enabled = state.autoProgress == null) { Text("Auto") }
                        TextButton(onClick = exportSelection) { Text("Export") }
                        Box {
                            IconButton(onClick = { selectionMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                                DropdownMenuItem(text = { Text("Select all") }, onClick = { selectionMenu = false; vm.selectAll() })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                )
            } else {
                TopAppBar(
                    title = { Text("Darkhall") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        TextButton(onClick = { pickFolder.launch(null) }) { Text("Folder") }
                        TextButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Files") }
                    },
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
            "Pick the folder your TG-7 writes to (DCIM/100OLYMP on the card, or wherever you copied the .ORF files), " +
                "or open individual files.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFolder) { Text("Choose folder") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFiles) { Text("Open files") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RawCell(file: RawFile, selected: Boolean, edited: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = file.key) {
        value = withContext(Dispatchers.IO) { OrfRepository.thumbnail(context, file) }
    }
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (selected) Modifier.border(3.dp, accent, RoundedCornerShape(6.dp)) else Modifier),
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
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                }
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
