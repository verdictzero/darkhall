package com.verdictzero.darkhall.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import kotlin.math.sqrt

/** Tiny RGB histogram overlay. [bins] is 3 x 256 counts. */
@Composable
fun Histogram(bins: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color(0x99000000))
        // Square-root scale against the 99th-percentile bin: shows the shape without letting
        // one spike flatten everything (linear) or every bin reach the top (log).
        val inner = IntArray(3 * 254) { c -> bins[(c / 254) * 256 + 1 + c % 254] }.sorted()
        val ref = maxOf(1, inner[(inner.size * 0.99).toInt().coerceAtMost(inner.size - 1)])
        val norm = sqrt(ref.toFloat())
        val colors = arrayOf(Color(0x99FF5A5A), Color(0x995AFF5A), Color(0x995A8AFF))
        for (c in 0 until 3) {
            val path = Path()
            path.moveTo(0f, size.height)
            for (i in 0 until 256) {
                val v = (sqrt(bins[c * 256 + i].toFloat()) / norm).coerceIn(0f, 1f) * 0.95f
                path.lineTo(i / 255f * size.width, size.height - v * size.height)
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, colors[c], style = Fill, blendMode = BlendMode.Plus)
        }
        drawLine(Color(0x44FFFFFF), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f))
    }
}
