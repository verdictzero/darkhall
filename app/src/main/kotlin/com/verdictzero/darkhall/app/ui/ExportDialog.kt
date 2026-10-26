package com.verdictzero.darkhall.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.verdictzero.darkhall.R
import com.verdictzero.darkhall.app.data.Prefs
import com.verdictzero.darkhall.core.export.ExportFormat
import com.verdictzero.darkhall.core.export.EXPORT_SIZES
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.verdictzero.darkhall.core.export.ExportSettings
import kotlin.math.roundToInt

/** What each format is for, under the format chips. */
@Composable
private fun ExportFormat.purpose() = stringResource(
    when (this) {
        ExportFormat.JPEG -> R.string.export_purpose_jpeg
        ExportFormat.PNG -> R.string.export_purpose_png
        ExportFormat.TIFF -> R.string.export_purpose_tiff
        ExportFormat.WEBP -> R.string.export_purpose_webp
        ExportFormat.TGA -> R.string.export_purpose_tga
    }
)

/**
 * Format and options for an export of [count] photos, starting from the last settings used.
 * [onExport] gets the choice, already saved for next time.
 */
@Composable
fun ExportDialog(count: Int, onDismiss: () -> Unit, title: String? = null, onExport: (ExportSettings) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var s by remember { mutableStateOf(prefs.exportSettings) }
    val strokes = LocalStrokes.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(strokes.width, strokes.container, AlertDialogDefaults.shape),
        title = { Text(title ?: pluralStringResource(R.plurals.export_dialog_title, count, count)) },
        text = {
            Column {
                Label(stringResource(R.string.export_format))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in ExportFormat.entries) Choice(f.label, s.format == f) { s = s.copy(format = f) }
                }
                Text(
                    s.format.purpose(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                when (s.format) {
                    ExportFormat.JPEG -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(stringResource(R.string.export_quality), Modifier.weight(1f))
                            Text("${s.jpegQuality}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        }
                        ShadowedSlider(
                            value = s.jpegQuality.toFloat(),
                            onValueChange = { s = s.copy(jpegQuality = it.roundToInt()) },
                            range = 60f..100f,
                            anchor = 60f,
                        )
                    }
                    ExportFormat.PNG, ExportFormat.TIFF -> {
                        Label(stringResource(R.string.export_bit_depth))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice(stringResource(R.string.export_8_bit), !s.sixteenBit) { s = s.copy(sixteenBit = false) }
                            Choice(stringResource(R.string.export_16_bit), s.sixteenBit) { s = s.copy(sixteenBit = true) }
                        }
                        if (s.format == ExportFormat.TIFF) {
                            Spacer(Modifier.height(8.dp))
                            Label(stringResource(R.string.export_compression))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Choice("ZIP", s.tiffCompressed) { s = s.copy(tiffCompressed = true) }
                                Choice(stringResource(R.string.export_compression_none), !s.tiffCompressed) { s = s.copy(tiffCompressed = false) }
                            }
                        }
                    }
                    else -> {}
                }
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.export_size))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (edge in EXPORT_SIZES) {
                        Choice(if (edge == 0) stringResource(R.string.export_size_full) else stringResource(R.string.export_size_px, edge), s.longEdge == edge) { s = s.copy(longEdge = edge) }
                    }
                }
            }
        },
        confirmButton = {
            StrokedTextButton(stringResource(R.string.export_start), onClick = {
                prefs.exportSettings = s
                onExport(s)
            })
        },
        dismissButton = { StrokedTextButton(stringResource(R.string.export_cancel), onClick = onDismiss) },
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
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
