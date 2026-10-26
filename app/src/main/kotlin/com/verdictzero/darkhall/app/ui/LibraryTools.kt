package com.verdictzero.darkhall.app.ui

import androidx.compose.ui.platform.LocalLocale
import android.text.format.DateFormat
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.verdictzero.darkhall.R
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.minimumInteractiveComponentSize
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
        Chip(stringResource(R.string.library_filter_by_date), f.byDate) { onChange(f.copy(byDate = !f.byDate)) }
        // Cycles: any rating, 1+, 3+, 5 stars.
        val next = when (f.minRating) { 0 -> 1; 1 -> 3; 3 -> 5; else -> 0 }
        val ratingLabel = when (f.minRating) {
            0 -> stringResource(R.string.library_filter_rating_any)
            5 -> stringResource(R.string.library_filter_rating_five)
            else -> stringResource(R.string.library_filter_rating_min, f.minRating)
        }
        Chip(ratingLabel, f.minRating > 0) {
            onChange(f.copy(minRating = next))
        }
        Chip(
            when (f.edited) {
                true -> stringResource(R.string.library_filter_edited)
                false -> stringResource(R.string.library_filter_not_edited)
                null -> stringResource(R.string.library_filter_edited_any)
            },
            f.edited != null,
        ) { onChange(f.copy(edited = when (f.edited) { null -> true; true -> false; false -> null })) }
        if (state.hasUnderwater || f.underwaterOnly) Chip(stringResource(R.string.library_filter_underwater), f.underwaterOnly) { onChange(f.copy(underwaterOnly = !f.underwaterOnly)) }
        if (state.cameras.size > 1 || f.camera != null) {
            val allCameras = stringResource(R.string.library_filter_all_cameras)
            val unknownCamera = stringResource(R.string.library_unknown_camera)
            Box {
                Chip(f.camera?.ifEmpty { unknownCamera } ?: allCameras, f.camera != null) { cameraMenu = true }
                DropdownMenu(expanded = cameraMenu, onDismissRequest = { cameraMenu = false }) {
                    DropdownMenuItem(text = { Text(allCameras) }, onClick = { cameraMenu = false; onChange(f.copy(camera = null)) })
                    for (c in state.cameras) DropdownMenuItem(text = { Text(c.ifEmpty { unknownCamera }) }, onClick = { cameraMenu = false; onChange(f.copy(camera = c)) })
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
            val label = pluralStringResource(R.plurals.library_stars, n, n)
            Text(
                if (n <= rating) "★" else "☆",
                fontSize = 26.sp,
                color = if (n <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = label; selected = n == rating }
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
        title = { Text(pluralStringResource(R.plurals.library_rate_title, count, count)) },
        text = {
            Column {
                StarRow(0, onRate = onRate)
                Text(stringResource(R.string.library_rate_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { StrokedTextButton(stringResource(R.string.library_clear_rating), onClick = { onRate(0) }) },
        dismissButton = { StrokedTextButton(stringResource(R.string.library_cancel), onClick = onDismiss) },
    )
}

/** A day heading's text: the date as the device's language writes it, or "Date unknown". */
@Composable
fun LibraryFilter.Heading.title(): String {
    val locale = LocalLocale.current.platformLocale
    // Weekday, day, short month and year, in the order and form the language uses.
    val format = remember(locale) {
        runCatching { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMMyyyy"), locale) }
            .getOrElse { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    }
    return day?.format(format) ?: stringResource(R.string.library_date_unknown)
}

/** A day's heading in the grid. */
@Composable
fun DayHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("  $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

