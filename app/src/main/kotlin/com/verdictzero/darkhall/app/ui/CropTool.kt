package com.verdictzero.darkhall.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.verdictzero.darkhall.core.develop.Crop
import com.verdictzero.darkhall.core.develop.CropAspect
import com.verdictzero.darkhall.core.develop.CropRect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The crop rectangle over the whole picture ([imageWidth] x [imageHeight] bitmap pixels, fitted
 * into this box like the preview): the rest is dimmed, thirds are drawn inside, and the
 * corners, edges and middle can be dragged.
 */
@Composable
fun CropOverlay(
    crop: CropRect,
    ratio: Float?,
    fullWidth: Int,
    fullHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    onChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val current by rememberUpdatedState(crop)
    val currentRatio by rememberUpdatedState(ratio)
    var start by remember { mutableStateOf<CropRect?>(null) }
    var handle by remember { mutableStateOf<Crop.Handle?>(null) }
    var moved by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "Crop" }
            .pointerInput(imageWidth, imageHeight, fullWidth, fullHeight) {
                fun image(): Rect {
                    val fit = min(size.width.toFloat() / imageWidth, size.height.toFloat() / imageHeight)
                    val w = imageWidth * fit; val h = imageHeight * fit
                    return Rect(Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, h))
                }
                detectDragGestures(
                    onDragStart = { at ->
                        val img = image()
                        handle = hitTest(screenRect(current, img), at, 28.dp.toPx())
                        start = current
                        moved = Offset.Zero
                    },
                    onDrag = { change, delta ->
                        val h = handle ?: return@detectDragGestures
                        val s = start ?: return@detectDragGestures
                        change.consume()
                        moved += delta
                        val img = image()
                        onChange(Crop.drag(s, h, moved.x / img.width, moved.y / img.height, currentRatio, fullWidth, fullHeight))
                    },
                    onDragEnd = { handle = null; start = null },
                    onDragCancel = { handle = null; start = null },
                )
            },
    ) {
        val fit = min(size.width / imageWidth, size.height / imageHeight)
        val img = Rect(Offset((size.width - imageWidth * fit) / 2f, (size.height - imageHeight * fit) / 2f), Size(imageWidth * fit, imageHeight * fit))
        drawCrop(screenRect(crop, img), img, accent, 1.dp.toPx())
    }
}

/** The crop in screen coordinates, given where the picture sits. */
private fun screenRect(c: CropRect, img: Rect) =
    Rect(img.left + c.left * img.width, img.top + c.top * img.height, img.left + c.right * img.width, img.top + c.bottom * img.height)

/** Which part of crop rectangle [r] a touch at [p] takes hold of; null outside it. */
private fun hitTest(r: Rect, p: Offset, slop: Float): Crop.Handle? {
    val nearL = abs(p.x - r.left) < slop
    val nearR = abs(p.x - r.right) < slop
    val nearT = abs(p.y - r.top) < slop
    val nearB = abs(p.y - r.bottom) < slop
    val withinX = p.x > r.left - slop && p.x < r.right + slop
    val withinY = p.y > r.top - slop && p.y < r.bottom + slop
    return when {
        nearT && nearL -> Crop.Handle.TOP_LEFT
        nearT && nearR -> Crop.Handle.TOP_RIGHT
        nearB && nearL -> Crop.Handle.BOTTOM_LEFT
        nearB && nearR -> Crop.Handle.BOTTOM_RIGHT
        nearL && withinY -> Crop.Handle.LEFT
        nearR && withinY -> Crop.Handle.RIGHT
        nearT && withinX -> Crop.Handle.TOP
        nearB && withinX -> Crop.Handle.BOTTOM
        r.contains(p) -> Crop.Handle.MOVE
        else -> null
    }
}

private fun DrawScope.drawCrop(r: Rect, img: Rect, accent: Color, px: Float) {
    // Dim everything outside the crop.
    val shade = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(img)
        addRect(r)
    }
    drawPath(shade, Color.Black.copy(alpha = 0.6f))
    // Thirds.
    for (t in listOf(1f / 3f, 2f / 3f)) {
        val x = r.left + r.width * t
        val y = r.top + r.height * t
        drawLine(Color.Black.copy(alpha = 0.35f), Offset(x, r.top), Offset(x, r.bottom), 2.5f * px)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(x, r.top), Offset(x, r.bottom), px)
        drawLine(Color.Black.copy(alpha = 0.35f), Offset(r.left, y), Offset(r.right, y), 2.5f * px)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(r.left, y), Offset(r.right, y), px)
    }
    drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5f * px))
    // Corner and edge handles.
    val len = 18f * px
    val w = 4f * px
    fun l(a: Offset, b: Offset) {
        drawLine(Color.Black.copy(alpha = 0.5f), a, b, w + 2f * px, cap = StrokeCap.Square)
        drawLine(accent, a, b, w, cap = StrokeCap.Square)
    }
    l(r.topLeft, r.topLeft + Offset(len, 0f)); l(r.topLeft, r.topLeft + Offset(0f, len))
    l(r.topRight, r.topRight - Offset(len, 0f)); l(r.topRight, r.topRight + Offset(0f, len))
    l(r.bottomLeft, r.bottomLeft + Offset(len, 0f)); l(r.bottomLeft, r.bottomLeft - Offset(0f, len))
    l(r.bottomRight, r.bottomRight - Offset(len, 0f)); l(r.bottomRight, r.bottomRight - Offset(0f, len))
    val c = r.center
    l(Offset(c.x - len / 2f, r.top), Offset(c.x + len / 2f, r.top))
    l(Offset(c.x - len / 2f, r.bottom), Offset(c.x + len / 2f, r.bottom))
    l(Offset(r.left, c.y - len / 2f), Offset(r.left, c.y + len / 2f))
    l(Offset(r.right, c.y - len / 2f), Offset(r.right, c.y + len / 2f))
}

/** "Crop 4:5 · 3,000 × 3,750 px" for the label over the photo. */
fun cropLabel(crop: CropRect, aspect: CropAspect, fullWidth: Int, fullHeight: Int): String {
    val w = (crop.width * fullWidth).roundToInt()
    val h = (crop.height * fullHeight).roundToInt()
    val shape = if (aspect == CropAspect.FREE || aspect == CropAspect.ORIGINAL) aspect.label else aspect.label(h > w)
    return "%s · %,d × %,d px".format(shape, w, h)
}

/** The Crop section: ratio choices, turning the crop, and its size. */
@Composable
fun CropControls(state: DevelopState, vm: DevelopViewModel) {
    val crop = Crop.of(state.params)
    val aspect = Crop.aspect(state.params)
    val portrait = crop.height * state.fullHeight > crop.width * state.fullWidth
    val ink = LocalStrokes.current.action
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (a in CropAspect.entries) {
                StrokedTextButton(
                    a.label(portrait),
                    onClick = { vm.setCropping(true); vm.setCropAspect(a) },
                    selected = a == aspect,
                    modifier = Modifier.testTag("crop-${a.name}"),
                )
            }
            StrokedIconButton(onClick = { vm.setCropping(true); vm.rotateCrop() }) {
                Canvas(Modifier.size(22.dp).semantics { contentDescription = if (portrait) "Make the crop landscape" else "Make the crop portrait" }) {
                    val sw = 1.5.dp.toPx()
                    // A landscape frame and a portrait one, the one it would become drawn bold.
                    val land = Rect(Offset(1.dp.toPx(), 6.dp.toPx()), Size(18.dp.toPx(), 11.dp.toPx()))
                    val port = Rect(Offset(6.dp.toPx(), 1.dp.toPx()), Size(11.dp.toPx(), 18.dp.toPx()))
                    drawRect(ink.copy(alpha = 0.45f), (if (portrait) port else land).topLeft, (if (portrait) port else land).size, style = Stroke(sw))
                    drawRect(ink, (if (portrait) land else port).topLeft, (if (portrait) land else port).size, style = Stroke(sw * 1.4f))
                }
            }
        }
        Text(
            if (crop.isFull) "Not cropped · ${"%,d × %,d px".format(state.fullWidth, state.fullHeight)}"
            else "Cropped to " + cropLabel(crop, aspect, state.fullWidth, state.fullHeight),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        )
    }
}
