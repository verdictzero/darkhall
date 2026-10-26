package com.verdictzero.darkhall.app.ui

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AlertDialogDefaults
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.verdictzero.darkhall.app.data.LookInfo
import com.verdictzero.darkhall.app.data.LookLibrary

/** The chip that shows only favorites; not a real shelf name. */
private const val FAVORITES = "Favorites"

/**
 * Full-screen look browser: every bundled LUT as a thumbnail of the photo being edited,
 * on shelves (Darkhall originals, film colour, film B&W, creative) with search. The heart on
 * a thumbnail makes it a favorite: favorites get their own shelf at the top and a chip.
 */
@Composable
fun LookBrowser(vm: DevelopViewModel, current: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val all = remember { LookLibrary.all(context) }
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites = state.favoriteLooks
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var showCredits by remember { mutableStateOf(false) }
    val q = query.trim().lowercase()
    fun matches(it: LookInfo) = q.isEmpty() || q in it.name.lowercase() || q in it.category.lowercase()
    val shown = remember(query, group) {
        all.filter { (group == null || it.group == group) && matches(it) }
    }
    val favoriteLooks = remember(favorites, query) {
        val byId = all.associateBy { it.id }
        favorites.mapNotNull { byId[it] }.filter { matches(it) }
    }
    val favoriteSet = favorites.toSet()

    val strokes = LocalStrokes.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().bottomStroke(strokes.container).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StrokedIconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close looks") }
                    Spacer(Modifier.width(10.dp))
                    Text("Looks", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "  ${all.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.weight(1f))
                    StrokedTextButton("Credits", onClick = { showCredits = true })
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search films and looks") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = strokes.action,
                        focusedBorderColor = strokes.selected,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { GroupChip("All", selected = group == null) { group = null } }
                    item { GroupChip("♥ $FAVORITES", selected = group == FAVORITES) { group = if (group == FAVORITES) null else FAVORITES } }
                    items(LookLibrary.GROUPS) { g ->
                        GroupChip(g, selected = group == g) { group = if (group == g) null else g }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    @Composable
                    fun header(text: String) = Text(
                        text,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    val cell: @Composable (LookInfo) -> Unit = { look ->
                        LookCell(
                            vm, look, look.name, selected = current == look.id,
                            favorite = look.id in favoriteSet, onFavorite = { vm.toggleFavoriteLook(look.id) },
                        ) { vm.setLook(look.id); onDismiss() }
                    }
                    if (group == FAVORITES) {
                        item(key = "h:fav", span = { GridItemSpan(maxLineSpan) }) { header(FAVORITES) }
                        items(favoriteLooks, key = { "f:${it.id}" }) { cell(it) }
                        if (favoriteLooks.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    if (favorites.isEmpty()) "No favorites yet. Tap the heart on any look to keep it here." else "No favorites match “$query”.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        return@LazyVerticalGrid
                    }
                    if (query.isBlank()) {
                        item(key = "none") {
                            LookCell(vm, null, "None", selected = current == null) { vm.setLook(null); onDismiss() }
                        }
                    }
                    // Favorites first, on the full shelf view (they also stay on their own shelves).
                    if (group == null && favoriteLooks.isNotEmpty()) {
                        item(key = "h:fav", span = { GridItemSpan(maxLineSpan) }) { header(FAVORITES) }
                        items(favoriteLooks, key = { "f:${it.id}" }) { cell(it) }
                    }
                    var lastHeader = ""
                    for (look in shown) {
                        val header = "${look.group} · ${look.category}"
                        if (header != lastHeader) {
                            lastHeader = header
                            item(key = "h:$header", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    header,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                )
                            }
                        }
                        item(key = look.id) { cell(look) }
                    }
                    if (shown.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("No looks match “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showCredits) {
        AlertDialog(
            onDismissRequest = { showCredits = false },
            modifier = Modifier.border(strokes.width, strokes.container, AlertDialogDefaults.shape),
            confirmButton = { StrokedTextButton("Close", onClick = { showCredits = false }) },
            title = { Text("Look credits") },
            text = {
                Text(
                    remember { LookLibrary.licenses(context) },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
        )
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val strokes = LocalStrokes.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = strokes.action,
            selectedBorderColor = strokes.selected,
            borderWidth = strokes.width,
            selectedBorderWidth = strokes.width,
        ),
    )
}

@Composable
private fun LookCell(
    vm: DevelopViewModel,
    look: LookInfo?,
    name: String,
    selected: Boolean,
    favorite: Boolean = false,
    /** Null for cells that can't be favorites ("None"). */
    onFavorite: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = look?.id) {
        value = vm.lookThumbnail(look?.id)
    }
    val accent = MaterialTheme.colorScheme.primary
    val strokes = LocalStrokes.current
    Column(
        Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Look $name" },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    if (selected) strokes.selectedWidth else strokes.width,
                    if (selected) strokes.selected else strokes.container,
                    RoundedCornerShape(6.dp),
                ),
        ) {
            thumb?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (onFavorite != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xA6141416))
                        .clickable(onClick = onFavorite)
                        .semantics { contentDescription = if (favorite) "Remove $name from favorites" else "Add $name to favorites" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = if (favorite) accent else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 3.dp).width(120.dp),
        )
    }
}

/**
 * The favorite looks as a row of small thumbnails, for the editor's Look section: one tap
 * applies a look; tapping the one in use takes it off again.
 */
@Composable
fun FavoriteLookStrip(vm: DevelopViewModel, favorites: List<String>, current: String?) {
    val context = LocalContext.current
    val strokes = LocalStrokes.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        items(favorites, key = { it }) { id ->
            val info = remember(id) { LookLibrary.info(context, id) } ?: return@items
            val selected = id == current
            val thumb by produceState<Bitmap?>(initialValue = null, key1 = id) { value = vm.lookThumbnail(id) }
            Column(
                Modifier
                    .width(64.dp)
                    .clickable { vm.setLook(if (selected) null else id) }
                    .semantics { contentDescription = "Favorite look ${info.name}" },
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            if (selected) strokes.selectedWidth else strokes.width,
                            if (selected) strokes.selected else strokes.container,
                            RoundedCornerShape(6.dp),
                        ),
                ) {
                    thumb?.let {
                        Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(
                    info.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
