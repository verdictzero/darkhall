package com.verdictzero.darkhall.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.verdictzero.darkhall.app.data.OpenSourceComponent
import com.verdictzero.darkhall.app.data.OpenSourceLicenses

/** Full-screen list of the third-party components in the app; tap one for its licence text. */
@Composable
fun LicensesScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strokes = LocalStrokes.current
    var open by remember { mutableStateOf<OpenSourceComponent?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().bottomStroke(strokes.container).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StrokedIconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close licences") }
                    Spacer(Modifier.width(10.dp))
                    Text("Open-source licences", style = MaterialTheme.typography.titleLarge)
                }
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            "Darkhall © 2026 verdictzero. It includes the components below, each under its own " +
                                "licence. The raw decoders are covered by the CDDL 1.0; their source code is at " +
                                OpenSourceLicenses.SOURCE_URL + ".",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                    items(OpenSourceLicenses.components, key = { it.name }) { c ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .border(strokes.width, strokes.container, RoundedCornerShape(12.dp))
                                .clickable { open = c }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text(c.use, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(c.copyright, style = MaterialTheme.typography.bodySmall)
                            Text(c.license, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    open?.let { c ->
        AlertDialog(
            onDismissRequest = { open = null },
            modifier = Modifier.border(strokes.width, strokes.container, AlertDialogDefaults.shape),
            confirmButton = { StrokedTextButton("Close", onClick = { open = null }) },
            title = { Text(c.name) },
            text = {
                Text(
                    remember(c) { c.url + "\n\n" + OpenSourceLicenses.text(context, c) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                )
            },
        )
    }
}
