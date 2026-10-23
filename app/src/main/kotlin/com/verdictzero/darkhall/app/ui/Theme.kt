package com.verdictzero.darkhall.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A darkroom: always dark, violet accent from the icon, neutral surfaces so the image is the only colour. */
@Composable
fun DarkhallTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        // Accent from the app icon's chevrons; everything else stays neutral grey so the
        // photo is the only colour that influences your judgement.
        primary = Color(0xFF9D7BFF),
        onPrimary = Color(0xFF1A0F33),
        primaryContainer = Color(0xFF34285A),
        onPrimaryContainer = Color(0xFFE9DDFF),
        secondary = Color(0xFFC6B6FF),
        onSecondary = Color(0xFF221A3D),
        secondaryContainer = Color(0xFF2A2440),
        onSecondaryContainer = Color(0xFFE5DEFF),
        tertiary = Color(0xFFB79CFF),
        background = Color(0xFF0E0E10),
        onBackground = Color(0xFFECECEC),
        surface = Color(0xFF141416),
        onSurface = Color(0xFFECECEC),
        surfaceVariant = Color(0xFF1E1E22),
        onSurfaceVariant = Color(0xFFB8B8BE),
        surfaceContainer = Color(0xFF19191C),
        surfaceContainerHigh = Color(0xFF202024),
        surfaceContainerHighest = Color(0xFF26262B),
        outline = Color(0xFF4A4A50),
        outlineVariant = Color(0xFF34343A),
        error = Color(0xFFFF8A80),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
