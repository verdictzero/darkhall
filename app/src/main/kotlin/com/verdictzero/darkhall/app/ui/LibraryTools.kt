package com.verdictzero.darkhall.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The library's filters as a row of chips: by date, rating, edited, underwater, camera. */
@Composable
fun LibraryFilterBar(state: LibraryState, onChange: (LibraryFilter) -> Unit) {
    val f = state.filter
    var cameraMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip("By date", f.byDate) { onChange(f.copy(byDate = !f.byDate)) }
        // Cycles: any rating, 1+, 3+, 5 stars.
        val next = when (f.minRating) { 0 -> 1; 1 -> 3; 3 -> 5; else -> 0 }
        Chip(if (f.minRating == 0) "★ Any" else if (f.minRating == 5) "★★★★★" else "★ ${f.minRating}+", f.minRating > 0) {
            onChange(f.copy(minRating = next))
        }
        Chip(
            when (f.edited) { true -> "Edited"; false -> "Not edited"; null -> "Edited or not" },
            f.edited != null,
        ) { onChange(f.copy(edited = when (f.edited) { null -> true; true -> false; false -> null })) }
        if (state.hasUnderwater || f.underwaterOnly) Chip("Underwater", f.underwaterOnly) { onChange(f.copy(underwaterOnly = !f.underwaterOnly)) }
        if (state.cameras.size > 1 || f.camera != null) {
            Box {
                Chip(f.camera ?: "All cameras", f.camera != null) { cameraMenu = true }
                DropdownMenu(expanded = cameraMenu, onDismissRequest = { cameraMenu = false }) {
                    DropdownMenuItem(text = { Text("All cameras") }, onClick = { cameraMenu = false; onChange(f.copy(camera = null)) })
                    for (c in state.cameras) DropdownMenuItem(text = { Text(c) }, onClick = { cameraMenu = false; onChange(f.copy(camera = c)) })
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val strokes = LocalStrokes.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = strokes.action, selectedBorderColor = strokes.selected,
            borderWidth = strokes.width, selectedBorderWidth = strokes.width,
        ),
    )
}

/** Five stars to tap; [rating] 0 is none. */
@Composable
fun StarRow(rating: Int, onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (n in 1..5) {
            Text(
                if (n <= rating) "★" else "☆",
                fontSize = 26.sp,
                color = if (n <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .semantics { contentDescription = "$n ${if (n == 1) "star" else "stars"}" }
                    .testTag("star-$n")
                    // Tapping the current rating again clears it.
                    .clickable { onRate(if (n == rating) 0 else n) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** Rating for a library selection. */
@Composable
fun RateDialog(count: Int, onRate: (Int) -> Unit, onDismiss: () -> Unit) {
    val strokes = LocalStrokes.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(strokes.width, strokes.container, AlertDialogDefaults.shape),
        title = { Text("Rate $count ${if (count == 1) "photo" else "photos"}") },
        text = {
            Column {
                StarRow(0, onRate = onRate)
                Text("Tap a star to rate them all.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { StrokedTextButton("Clear rating", onClick = { onRate(0) }) },
        dismissButton = { StrokedTextButton("Cancel", onClick = onDismiss) },
    )
}

/** A day's heading in the grid. */
@Composable
fun DayHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("  $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

