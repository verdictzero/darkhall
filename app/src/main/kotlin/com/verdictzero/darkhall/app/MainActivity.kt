package com.verdictzero.darkhall.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdictzero.darkhall.app.ui.DarkhallTheme
import com.verdictzero.darkhall.app.ui.DevelopScreen
import com.verdictzero.darkhall.app.ui.LibraryScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val externalOpen = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) externalOpen.value = uriFrom(intent)
        val crash = CrashLog.pending(this)
        setContent {
            DarkhallTheme {
                DarkhallApp(externalOpen)
                crash?.let { CrashDialog(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        uriFrom(intent)?.let { externalOpen.value = it }
    }

    private fun uriFrom(intent: Intent?): Uri? {
        if (intent == null) return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return null
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return uri
    }
}

@Composable
private fun DarkhallApp(externalOpen: MutableStateFlow<Uri?>) {
    var current by rememberSaveable { mutableStateOf<String?>(null) }
    val requested by externalOpen.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let {
            current = it.toString()
            externalOpen.value = null
        }
    }
    val uri = current
    if (uri == null) {
        LibraryScreen(onOpen = { current = it.toString() })
    } else {
        BackHandler { current = null }
        DevelopScreen(uri = Uri.parse(uri), onBack = { current = null })
    }
}

@Composable
private fun CrashDialog(text: String) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(true) }
    if (!open) return
    val dismiss = { CrashLog.clear(context); open = false }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Darkhall crashed last time") },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Darkhall crash", text))
                dismiss()
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, "Share crash report")) }
                dismiss()
            }) { Text("Share") }
        },
    )
}
