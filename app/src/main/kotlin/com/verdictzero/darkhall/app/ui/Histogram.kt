package com.verdictzero.darkhall.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import kotlin.math.ln

/** Tiny RGB histogram overlay. [bins] is 3 x 256 counts. */
@Composable
fun Histogram(bins: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color(0x66000000))
        var peak = 1
        for (c in 0 until 3) for (i in 1 until 255) peak = maxOf(peak, bins[c * 256 + i])
        val logPeak = ln(peak.toFloat() + 1f)
        val colors = arrayOf(Color(0xCCFF5A5A), Color(0xCC5AFF5A), Color(0xCC5A8AFF))
        for (c in 0 until 3) {
            val path = Path()
            path.moveTo(0f, size.height)
            for (i in 0 until 256) {
                val v = ln(bins[c * 256 + i].toFloat() + 1f) / logPeak
                val x = i / 255f * size.width
                val y = size.height - v.coerceIn(0f, 1f) * size.height
                path.lineTo(x, y)
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, colors[c], style = Fill, blendMode = BlendMode.Plus)
        }
        drawLine(Color(0x44FFFFFF), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f))
    }
}
