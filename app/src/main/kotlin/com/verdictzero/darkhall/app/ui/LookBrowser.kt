package com.verdictzero.darkhall.app.ui

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * Full-screen look browser: every bundled LUT as a thumbnail of the photo being edited,
 * on shelves (Darkhall originals, film colour, film B&W, creative) with search.
 */
@Composable
fun LookBrowser(vm: DevelopViewModel, current: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val all = remember { LookLibrary.all(context) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var showCredits by remember { mutableStateOf(false) }
    val shown = remember(query, group) {
        val q = query.trim().lowercase()
        all.filter { (group == null || it.group == group) && (q.isEmpty() || q in it.name.lowercase() || q in it.category.lowercase()) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close looks") }
                    Text("Looks", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "  ${all.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showCredits = true }) { Text("Credits") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search films and looks") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(selected = group == null, onClick = { group = null }, label = { Text("All") }) }
                    items(LookLibrary.GROUPS) { g ->
                        FilterChip(selected = group == g, onClick = { group = if (group == g) null else g }, label = { Text(g) })
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (query.isBlank()) {
                        item(key = "none") {
                            LookCell(vm, null, "None", selected = current == null) { vm.setLook(null); onDismiss() }
                        }
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
                        item(key = look.id) {
                            LookCell(vm, look, look.name, selected = current == look.id) { vm.setLook(look.id); onDismiss() }
                        }
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
            confirmButton = { TextButton(onClick = { showCredits = false }) { Text("Close") } },
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
private fun LookCell(vm: DevelopViewModel, look: LookInfo?, name: String, selected: Boolean, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = look?.id) {
        value = vm.lookThumbnail(look?.id)
    }
    val accent = MaterialTheme.colorScheme.primary
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
                .then(if (selected) Modifier.border(3.dp, accent, RoundedCornerShape(6.dp)) else Modifier),
        ) {
            thumb?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
