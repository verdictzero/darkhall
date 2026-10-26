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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verdictzero.darkhall.R
import com.verdictzero.darkhall.app.data.RawRepository
import com.verdictzero.darkhall.app.data.RawFile
import com.verdictzero.darkhall.app.data.PresetStore
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
    var stackDialog by remember { mutableStateOf(false) }
    var presetDialog by remember { mutableStateOf(false) }
    var rateDialog by remember { mutableStateOf(false) }
    if (rateDialog) RateDialog(state.selected.size, onRate = { vm.rateSelection(it); rateDialog = false }, onDismiss = { rateDialog = false })
    val context = LocalContext.current
    if (presetDialog) {
        var presets by remember { mutableStateOf(PresetStore.all(context)) }
        PresetsDialog(
            presets,
            onApply = { vm.applyPresetToSelection(it); presetDialog = false },
            onDelete = { presets = PresetStore.delete(context, it.name) },
            onDismiss = { presetDialog = false },
            title = pluralStringResource(R.plurals.library_apply_preset_title, state.selected.size, state.selected.size),
        )
    }
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
    val stackSelection = rememberExportStarter { vm.stackSelection(exportSettings) }
    if (stackDialog) {
        ExportDialog(state.selected.size, onDismiss = { stackDialog = false }, title = pluralStringResource(R.plurals.library_focus_stack_title, state.selected.size, state.selected.size)) { s ->
            stackDialog = false
            exportSettings = s
            stackSelection()
        }
    }
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
                    title = { Text(pluralStringResource(R.plurals.library_selected_count, state.selected.size, state.selected.size)) },
                    navigationIcon = {
                        StrokedIconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.library_cancel_selection)) }
                    },
                    actions = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StrokedTextButton(stringResource(R.string.library_paste), onClick = { vm.pasteToSelection() }, enabled = state.canPaste)
                            StrokedTextButton(stringResource(R.string.library_auto), onClick = { vm.autoToneSelection() }, enabled = state.autoProgress == null)
                            StrokedTextButton(stringResource(R.string.library_export), onClick = { exportDialog = true })
                        }
                        Box(Modifier.padding(horizontal = 6.dp)) {
                            StrokedIconButton(onClick = { selectionMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more)) }
                            DropdownMenu(
                                expanded = selectionMenu,
                                onDismissRequest = { selectionMenu = false },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(strokes.width, strokes.container),
                            ) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.library_select_all)) }, onClick = { selectionMenu = false; vm.selectAll() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.library_rate_menu)) }, onClick = { selectionMenu = false; rateDialog = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.library_apply_preset_menu)) }, onClick = { selectionMenu = false; presetDialog = true })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_focus_stack_menu)) },
                                    enabled = state.selected.size >= 2,
                                    onClick = { selectionMenu = false; stackDialog = true },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.bottomStroke(strokes.selected),
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            StrokedTextButton(stringResource(R.string.library_folder), onClick = { pickFolder.launch(null) })
                            StrokedTextButton(stringResource(R.string.library_files), onClick = { pickFiles.launch(arrayOf("*/*")) })
                            Box {
                                StrokedIconButton(onClick = { libraryMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_more)) }
                                DropdownMenu(
                                    expanded = libraryMenu,
                                    onDismissRequest = { libraryMenu = false },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(strokes.width, strokes.container),
                                ) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.library_licences)) }, onClick = { libraryMenu = false; licenses = true })
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
                val hint = stringResource(if (state.selecting) R.string.library_hint_selecting else R.string.library_hint_browsing)
                Text(
                    state.folderName?.let { pluralStringResource(R.plurals.library_folder_summary, state.files.size, it, state.files.size, hint) } ?: hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LibraryFilterBar(state, onChange = { vm.setFilter(it) })
                val groups = state.groups
                val shownCount = groups.sumOf { it.files.size }
                if (shownCount < state.files.size) {
                    Text(
                        stringResource(R.string.library_showing, shownCount, state.files.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 128.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    for (group in groups) {
                        group.heading?.let { heading ->
                            item(key = "day-${heading.day}", span = { GridItemSpan(maxLineSpan) }) { DayHeader(heading.title(), group.files.size) }
                        }
                        items(group.files, key = { it.key }) { file ->
                            RawCell(
                                file,
                                selected = file.key in state.selected,
                                edited = file.key in state.edited,
                                rating = state.ratings[file.key] ?: 0,
                                selecting = state.selecting,
                                onClick = { if (state.selecting) vm.toggle(file) else onOpen(file.uri) },
                                onLongClick = { vm.toggle(file) },
                            )
                        }
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
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.library_empty_body),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        val strokes = LocalStrokes.current
        Button(onClick = onPickFolder, border = BorderStroke(strokes.width, strokes.selected)) { Text(stringResource(R.string.library_choose_folder)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFiles, border = BorderStroke(strokes.width, strokes.action)) { Text(stringResource(R.string.library_open_files)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RawCell(
    file: RawFile,
    selected: Boolean,
    edited: Boolean,
    rating: Int,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = file.key) {
        value = withContext(Dispatchers.IO) { RawRepository.thumbnail(context, file) }
    }
    val facts by produceState<ShotFacts?>(initialValue = null, key1 = file.key) {
        value = withContext(Dispatchers.IO) { RawRepository.facts(context, file) }
    }
    val accent = MaterialTheme.colorScheme.primary
    val strokes = LocalStrokes.current
    val stars = pluralStringResource(R.plurals.library_stars, rating, rating)
    val summary = listOfNotNull(
        file.name,
        if (selected) stringResource(R.string.library_cell_selected) else null,
        if (rating > 0) stars else null,
        if (edited) stringResource(R.string.library_cell_edited) else null,
        facts?.badge,
    ).joinToString(", ")
    val select = stringResource(R.string.library_action_select)
    val clickLabel = if (selecting) (if (selected) stringResource(R.string.library_action_deselect) else select) else stringResource(R.string.library_action_open)
    Column(
        Modifier
            .combinedClickable(
                onClick = onClick, onLongClick = onLongClick,
                onClickLabel = clickLabel,
                onLongClickLabel = select,
            )
            .semantics(mergeDescendants = true) { contentDescription = summary },
    ) {
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
                    contentDescription = null,
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
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.library_selected), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
            if (rating > 0) {
                Text(
                    "★".repeat(rating),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFD25A),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB3141416))
                        .padding(horizontal = 4.dp)
                        .semantics { contentDescription = stars },
                )
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
                    stringResource(R.string.library_badge_edited),
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
