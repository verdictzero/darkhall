package com.verdictzero.darkhall.app.ui

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.verdictzero.darkhall.R
import com.verdictzero.darkhall.core.raw.ShotFacts
import java.util.Locale

@get:StringRes
private val ShotFacts.Field.titleRes: Int get() = when (this) {
    ShotFacts.Field.CAMERA -> R.string.editor_info_camera
    ShotFacts.Field.TAKEN -> R.string.editor_info_taken
    ShotFacts.Field.EXPOSURE -> R.string.editor_info_exposure
    ShotFacts.Field.SIZE -> R.string.editor_info_size
    ShotFacts.Field.DEPTH -> R.string.editor_info_depth
    ShotFacts.Field.WATER_TEMPERATURE -> R.string.editor_info_water_temperature
    ShotFacts.Field.TEMPERATURE -> R.string.editor_info_temperature
    ShotFacts.Field.PRESSURE -> R.string.editor_info_pressure
    ShotFacts.Field.ALTITUDE -> R.string.editor_info_altitude
    ShotFacts.Field.LOCATION -> R.string.editor_info_location
}

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
                val unknown = stringResource(R.string.editor_info_unknown_camera)
                for ((field, raw) in facts.fields()) {
                    val label = stringResource(field.titleRes)
                    val value = if (field == ShotFacts.Field.CAMERA) raw.ifBlank { unknown } else raw
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(128.dp))
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { StrokedTextButton(stringResource(R.string.editor_close), onClick = onDismiss) },
        dismissButton = if (lat != null && lon != null) {
            {
                StrokedTextButton(stringResource(R.string.editor_show_on_map), onClick = {
                    val geo = Uri.parse("geo:%f,%f?q=%f,%f".format(Locale.US, lat, lon, lat, lon))
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
                })
            }
        } else null,
    )
}
