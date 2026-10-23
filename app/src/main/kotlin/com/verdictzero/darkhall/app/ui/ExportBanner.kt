package com.verdictzero.darkhall.app.ui

import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import com.verdictzero.darkhall.app.data.ExportService
import com.verdictzero.darkhall.app.data.ExportQueue
import kotlinx.coroutines.delay
import java.util.UUID

/** App-wide pill showing background export progress, then a short summary when a job ends. */
@Composable
fun ExportBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val flow = remember(context) { ExportQueue.status(context) }
    val status by flow.collectAsState(initial = null)
    var reported by remember { mutableStateOf<Set<UUID>?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

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
            summary = when {
                failed == 0 -> "Saved $ok ${if (ok == 1) "photo" else "photos"} to Pictures/${ExportService.ALBUM}"
                ok == 0 -> "Export failed" + if (names.isNotEmpty()) ": $names" else ""
                else -> "Saved $ok, failed $failed" + if (names.isNotEmpty()) ": $names" else ""
            }
        }
    }
    LaunchedEffect(summary) {
        if (summary != null) { delay(5000); summary = null }
    }

    val s = status
    val text = when {
        s != null && s.running -> {
            val total = s.total + s.queued
            val pct = (s.fraction * 100).toInt()
            if (total > 1) "Exporting ${s.done + 1} of $total · $pct%" else "Exporting · $pct%"
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
                Text(text.orEmpty(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                if (s != null && s.running) {
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.padding(top = 6.dp).width(180.dp))
                }
            }
        }
    }
}
