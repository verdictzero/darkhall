package com.verdictzero.darkhall.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stroke colours by role, so every outlined element in the app follows one rule:
 * actions are violet, containers a muted violet-grey, the selected thing bright and thicker,
 * and anything floating over the photo a translucent white that reads on any picture.
 */
@Immutable
data class Strokes(
    val action: Color,
    val actionDisabled: Color,
    val container: Color,
    val selected: Color,
    val overlay: Color,
    val width: Dp = 1.dp,
    val selectedWidth: Dp = 2.dp,
)

val DarkhallStrokes = Strokes(
    action = Color(0xB39D7BFF),
    actionDisabled = Color(0x403A3350),
    container = Color(0xFF3A3350),
    selected = Color(0xFFB79CFF),
    overlay = Color(0x59FFFFFF),
)

val LocalStrokes = staticCompositionLocalOf { DarkhallStrokes }

/** Caution-stripe colours: purple bands on dark purple, 45°. */
object Stripes {
    val ground = Color(0xFF160E2A)
    val band = Color(0xFF4B3290)
    val bandWidth = 14.dp
}

/** Paints 45° caution stripes behind the content and clips them to the bounds. */
fun Modifier.cautionStripes(): Modifier = this
    .clipToBounds()
    .drawBehind {
        drawRect(Stripes.ground)
        val band = Stripes.bandWidth.toPx()
        val period = band * 2f
        // Rotate around the centre and draw vertical bars long enough to cover the diagonal.
        val diag = sqrt(size.width * size.width + size.height * size.height)
        rotate(45f) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            var x = cx - diag
            while (x < cx + diag) {
                drawRect(Stripes.band, topLeft = Offset(x, cy - diag), size = Size(band, diag * 2f))
                x += period
            }
        }
    }

/** A text action with a violet outline. */
@Composable
fun StrokedTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val s = LocalStrokes.current
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.padding(horizontal = 2.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(s.width, if (enabled) s.action else s.actionDisabled),
    ) { Text(text) }
}

/** An icon action inside a violet ring. */
@Composable
fun StrokedIconButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val s = LocalStrokes.current
    val ring = if (enabled) s.action else s.actionDisabled
    // The ring is drawn at 40dp inside the full 48dp touch target.
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.drawBehind {
            val w = s.width.toPx()
            drawCircle(ring, radius = 20.dp.toPx() - w / 2f, style = Stroke(w))
        },
    ) { content() }
}

/** A titled group of controls on an outlined card. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalStrokes.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(s.width, s.container, shape)
            .padding(bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** Draws a 1-px line along the bottom edge (for bars). */
fun Modifier.bottomStroke(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
}

/**
 * The editor's slider: a recessed outlined groove that fills from [anchor] to the value, a
 * round knob with a light rim, and a soft drop shadow under both.
 */
/** Per-layer alphas of the hand-drawn slider shadows, innermost first; they stack to roughly 70% black. */
private val SHADOW_ALPHAS = floatArrayOf(0.32f, 0.22f, 0.14f, 0.08f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    anchor: Float,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrokes.current
    val active = MaterialTheme.colorScheme.primary
    val groove = Color(0xFF221D30)
    val tick = MaterialTheme.colorScheme.outline
    val rim = Color(0xFFE9DDFF)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        modifier = modifier,
        thumb = {
            Box(
                Modifier
                    .size(22.dp)
                    .drawBehind {
                        // Soft drop shadow, drawn by hand so it shows the same on every renderer.
                        for ((i, a) in SHADOW_ALPHAS.withIndex()) {
                            drawCircle(
                                Color.Black.copy(alpha = a),
                                radius = size.minDimension / 2f + (i + 1) * 1.5f.dp.toPx(),
                                center = center + Offset(0f, 3.dp.toPx()),
                            )
                        }
                    }
                    .background(active, CircleShape)
                    .border(2.dp, rim, CircleShape),
            )
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val span = range.endInclusive - range.start
                val h = 6.dp.toPx()
                val y = size.height / 2f - h / 2f
                val r = CornerRadius(h / 2f)
                fun xOf(v: Float) = (v - range.start) / span * size.width
                // Drop shadow: a few offset, widening, fading copies read as a soft blur on every API level.
                for ((i, a) in SHADOW_ALPHAS.withIndex()) {
                    val grow = (i + 1) * 3.dp.toPx()
                    drawRoundRect(
                        Color.Black.copy(alpha = a),
                        topLeft = Offset(-grow / 2f, y + 3.dp.toPx() - grow / 2f),
                        size = Size(size.width + grow, h + grow),
                        cornerRadius = CornerRadius((h + grow) / 2f),
                    )
                }
                drawRoundRect(groove, topLeft = Offset(0f, y), size = Size(size.width, h), cornerRadius = r)
                drawRoundRect(s.container, topLeft = Offset(0f, y), size = Size(size.width, h), cornerRadius = r, style = Stroke(s.width.toPx()))
                if (anchor != range.start) {
                    val cx = xOf(anchor)
                    drawLine(tick, Offset(cx, y - 4.dp.toPx()), Offset(cx, y + h + 4.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
                val a = xOf(anchor); val b = xOf(value)
                if (abs(b - a) > 0.5f) {
                    drawRoundRect(active, topLeft = Offset(minOf(a, b), y), size = Size(abs(b - a), h), cornerRadius = r)
                }
            }
        },
    )
}
