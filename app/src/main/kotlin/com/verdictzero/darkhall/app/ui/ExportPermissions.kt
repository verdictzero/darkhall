package com.verdictzero.darkhall.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns a function that starts an export after asking for what it needs: storage on
 * Android 8/9, and (optionally) notifications on 13+ so the progress notification shows.
 * The export runs whatever the notification answer is.
 */
@Composable
fun rememberExportStarter(onExport: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val current = rememberUpdatedState(onExport)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val storageOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (storageOk) current.value()
    }
    return remember(context) {
        {
            val needed = buildList {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needed.isEmpty()) current.value() else launcher.launch(needed.toTypedArray())
        }
    }
}
