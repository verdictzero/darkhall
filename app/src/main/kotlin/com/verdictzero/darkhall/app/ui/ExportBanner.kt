package com.verdictzero.darkhall.app.ui

import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.verdictzero.darkhall.R
import com.verdictzero.darkhall.app.data.ExportQueue
import kotlinx.coroutines.delay
import java.util.UUID

/** App-wide pill showing background export progress, then a short summary when a job ends. */
@Composable
fun ExportBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val flow = remember(context) { ExportQueue.status(context) }
    val status by flow.collectAsState(initial = null)
    var reported by remember { mutableStateOf<Set<UUID>?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var shareable by remember { mutableStateOf<List<Uri>>(emptyList()) }

    LaunchedEffect(status) {
        val s = status ?: return@LaunchedEffect
        val ids = s.finished.map { it.id }.toSet()
        val known = reported
        if (known == null) {
            reported = ids // jobs that finished before this launch are old news
            return@LaunchedEffect
        }
        val fresh = s.finished.filter { it.id !in known }
        if (fresh.isNotEmpty()) {
            reported = known + ids
            val ok = fresh.sumOf { it.ok }
            val failed = fresh.sumOf { it.failed }
            val names = fresh.map { it.failedNames }.filter { it.isNotEmpty() }.joinToString(", ")
            shareable = fresh.flatMap { it.uris }
            summary = when {
                failed == 0 -> resources.getQuantityString(R.plurals.export_saved_photos, ok, ok, fresh.last().folder)
                ok == 0 -> if (names.isNotEmpty()) resources.getString(R.string.export_failed_names, names) else resources.getString(R.string.export_failed)
                else -> if (names.isNotEmpty()) resources.getString(R.string.export_saved_failed_names, ok, failed, names)
                    else resources.getString(R.string.export_saved_failed, ok, failed)
            }
        }
    }
    LaunchedEffect(summary) {
        if (summary != null) { delay(if (shareable.isNotEmpty()) 9000 else 5000); summary = null; shareable = emptyList() }
    }

    val s = status
    val text = when {
        s != null && s.running -> {
            val total = s.total + s.queued
            val pct = (s.fraction * 100).toInt()
            if (total > 1) stringResource(R.string.export_progress_of, s.done + 1, total, pct) else stringResource(R.string.export_progress, pct)
        }
        summary != null -> summary
        else -> null
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(LocalStrokes.current.width, LocalStrokes.current.action, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text.orEmpty(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                    if (summary != null && shareable.isNotEmpty() && !(s?.running ?: false)) {
                        Spacer(Modifier.width(10.dp))
                        StrokedTextButton(stringResource(R.string.export_share), onClick = { share(context, shareable) })
                    }
                }
                if (s != null && s.running) {
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.padding(top = 6.dp).width(180.dp))
                }
            }
        }
    }
}

/** Opens the share sheet for exported files. */
private fun share(context: Context, uris: List<Uri>) {
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = context.contentResolver.getType(uris[0]) ?: "image/*"
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(send, context.resources.getQuantityString(R.plurals.export_share_chooser, uris.size, uris.size)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
