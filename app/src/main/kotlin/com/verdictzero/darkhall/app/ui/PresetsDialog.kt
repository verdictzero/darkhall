package com.verdictzero.darkhall.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdictzero.darkhall.app.data.Preset

/**
 * Presets: the saved ones (tap to apply, bin to delete) and, when [onSave] is given, a field
 * to save the current settings under a name.
 */
@Composable
fun PresetsDialog(
    presets: List<Preset>,
    onApply: (Preset) -> Unit,
    onDelete: (Preset) -> Unit,
    onDismiss: () -> Unit,
    onSave: ((String) -> Unit)? = null,
    title: String = "Presets",
) {
    val strokes = LocalStrokes.current
    var name by remember { mutableStateOf("") }
    // Laid out by itself, full width: a text field in a dialog of the platform's default width
    // never lets Compose settle (seen under Robolectric).
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().border(strokes.width, strokes.container, RoundedCornerShape(24.dp)),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (presets.isEmpty()) {
                    Text(
                        "No presets yet. Save this photo's settings to use them on others; Straighten and the crop stay with each photo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in presets) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .border(strokes.width, strokes.action, RoundedCornerShape(10.dp))
                                .clickable { onApply(p) }
                                .padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            StrokedIconButton(onClick = { onDelete(p) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete preset ${p.name}") }
                        }
                    }
                }
                if (onSave != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(40) },
                            singleLine = true,
                            placeholder = { Text("Preset name") },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = strokes.action, focusedBorderColor = strokes.selected),
                            modifier = Modifier.weight(1f),
                        )
                        StrokedTextButton("Save", onClick = { onSave(name); name = "" }, enabled = name.isNotBlank(), modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    StrokedTextButton("Close", onClick = onDismiss)
                }
            }
        }
    }
}
