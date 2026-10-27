package com.verdictzero.darkhall.app.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.verdictzero.darkhall.R
import com.verdictzero.darkhall.core.raw.RawFormats
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Every raw file extension the app opens, as shown in the format wheel. */
val SUPPORTED_FORMATS: List<String> = RawFormats.EXTENSIONS.map { it.uppercase() }

/**
 * The formats the app opens, as capsules on a slowly turning ring: the near side large and
 * bright, the far side small and faint. With system animations off it is a still row.
 */
@Composable
fun FormatWheel(modifier: Modifier = Modifier, formats: List<String> = SUPPORTED_FORMATS) {
    val description = stringResource(R.string.library_formats, formats.joinToString(", "))
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val semantics = modifier.clearAndSetSemantics { contentDescription = description }
    if (still) {
        StillFormats(formats, semantics)
        return
    }
    val turn = rememberInfiniteTransition(label = "formats").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(formats.size * 1500, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    BoxWithConstraints(semantics.fillMaxWidth().height(64.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val radius = with(density) { maxWidth.toPx() } * 0.40f
        val lift = with(density) { 8.dp.toPx() }
        for ((i, name) in formats.withIndex()) {
            val a = 2.0 * PI * (i.toFloat() / formats.size + turn.value)
            val near = ((cos(a) + 1.0) / 2.0).toFloat() // 1 at the front, 0 at the back
            Box(
                Modifier
                    .zIndex(near)
                    .offset { IntOffset((sin(a) * radius).roundToInt(), ((1f - near) * -lift).roundToInt()) }
                    .graphicsLayer {
                        val s = 0.6f + 0.4f * near
                        scaleX = s
                        scaleY = s
                        alpha = 0.12f + 0.88f * near * near
                    },
            ) { Capsule(name) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StillFormats(formats: List<String>, modifier: Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (name in formats) Capsule(name)
    }
}

@Composable
private fun Capsule(name: String) {
    val strokes = LocalStrokes.current
    val shape = RoundedCornerShape(50)
    Text(
        name,
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(strokes.width, strokes.action, shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
