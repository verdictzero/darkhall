package com.verdictzero.darkhall.app

import com.verdictzero.darkhall.app.ui.StrokedTextButton
import com.verdictzero.darkhall.app.ui.LocalStrokes
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.verdictzero.darkhall.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdictzero.darkhall.app.ui.DarkhallTheme
import com.verdictzero.darkhall.app.ui.DevelopScreen
import com.verdictzero.darkhall.app.ui.DevelopViewModel
import com.verdictzero.darkhall.app.ui.ExportBanner
import com.verdictzero.darkhall.app.ui.LibraryScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val externalOpen = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        Splash.install(this, firstLaunch = savedInstanceState == null)
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
    // One editing session for the whole app; it is released when leaving the develop screen.
    val developVm: DevelopViewModel = viewModel()
    LaunchedEffect(requested) {
        requested?.let {
            current = it.toString()
            externalOpen.value = null
        }
    }
    val leave = {
        developVm.close()
        current = null
    }
    Box(Modifier.fillMaxSize()) {
        val uri = current
        if (uri == null) {
            LibraryScreen(onOpen = { current = it.toString() })
        } else {
            BackHandler { leave() }
            DevelopScreen(uri = Uri.parse(uri), vm = developVm, onBack = leave)
        }
        ExportBanner()
    }
}

@Composable
private fun CrashDialog(text: String) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(true) }
    if (!open) return
    val dismiss = { CrashLog.clear(context); open = false }
    val strokes = LocalStrokes.current
    val clipLabel = stringResource(R.string.app_crash_clip_label)
    val shareTitle = stringResource(R.string.app_crash_share_title)
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.border(strokes.width, strokes.container, AlertDialogDefaults.shape),
        title = { Text(stringResource(R.string.app_crash_title)) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            StrokedTextButton(stringResource(R.string.app_crash_copy), onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(clipLabel, text))
                dismiss()
            })
        },
        dismissButton = {
            StrokedTextButton(stringResource(R.string.app_crash_share), onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
                dismiss()
            })
        },
    )
}
