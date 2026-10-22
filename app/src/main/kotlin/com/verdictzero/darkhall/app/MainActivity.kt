package com.verdictzero.darkhall.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
        setContent {
            DarkhallTheme {
                DarkhallApp(externalOpen)
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
