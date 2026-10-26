package com.verdictzero.darkhall.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.verdictzero.darkhall.core.raw.ShotFacts
import java.util.Locale

/** The shot's camera, time, exposure and field-sensor readings, with a map link when it has a location. */
@Composable
fun ShotInfoDialog(name: String, facts: ShotFacts, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strokes = LocalStrokes.current
    val lat = facts.latitude
    val lon = facts.longitude
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(strokes.width, strokes.container, AlertDialogDefaults.shape),
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, value) in facts.rows()) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(128.dp))
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { StrokedTextButton("Close", onClick = onDismiss) },
        dismissButton = if (lat != null && lon != null) {
            {
                StrokedTextButton("Show on map", onClick = {
                    val geo = Uri.parse("geo:%f,%f?q=%f,%f".format(Locale.US, lat, lon, lat, lon))
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
                })
            }
        } else null,
    )
}
