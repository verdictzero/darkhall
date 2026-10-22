package com.verdictzero.darkhall.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A darkroom: always dark, warm accent, neutral surfaces so the image is the only colour. */
@Composable
fun DarkhallTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFFE0C097),
        onPrimary = Color(0xFF1E1A14),
        primaryContainer = Color(0xFF3A3020),
        onPrimaryContainer = Color(0xFFF4E4C8),
        secondary = Color(0xFF9FB4C7),
        onSecondary = Color(0xFF101418),
        background = Color(0xFF0E0E10),
        onBackground = Color(0xFFECECEC),
        surface = Color(0xFF141416),
        onSurface = Color(0xFFECECEC),
        surfaceVariant = Color(0xFF1E1E22),
        onSurfaceVariant = Color(0xFFB8B8BE),
        outline = Color(0xFF4A4A50),
        error = Color(0xFFFF8A80),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
