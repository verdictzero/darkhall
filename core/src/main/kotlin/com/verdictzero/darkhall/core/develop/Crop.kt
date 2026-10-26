package com.verdictzero.darkhall.core.develop

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Crop ratios offered in the editor: [long] side to [short] side (0 for Free and Original). */
enum class CropAspect(val label: String, val long: Int, val short: Int) {
    FREE("Free", 0, 0),
    ORIGINAL("Original", 0, 0),
    SQUARE("1:1", 1, 1),
    R5_4("5:4", 5, 4),
    R3_2("3:2", 3, 2),
    R16_9("16:9", 16, 9),
    ;

    /** The label for a crop that is [portrait] (taller than wide), e.g. "4:5" rather than "5:4". */
    fun label(portrait: Boolean): String =
        if (long == 0 || long == short) label else if (portrait) "$short:$long" else "$long:$short"
}

/**
 * The crop, kept as a rectangle in the upright picture after straightening and lens
 * corrections, in 0..1 of its width and height (the CROP_* adjustments). Coordinates are
 * normalised so the same crop fits the preview, a zoomed region and the full-size export.
 */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val isFull get() = left <= EPS && top <= EPS && right >= 1f - EPS && bottom >= 1f - EPS

    /** Pixel rectangle of this crop in an upright picture of [w] x [h]. */
    fun pixels(w: Int, h: Int): PixelRect {
        val x0 = (left * w).roundToInt().coerceIn(0, w - 1)
        val y0 = (top * h).roundToInt().coerceIn(0, h - 1)
        val x1 = (right * w).roundToInt().coerceIn(x0 + 1, w)
        val y1 = (bottom * h).roundToInt().coerceIn(y0 + 1, h)
        return PixelRect(x0, y0, x1 - x0, y1 - y0)
    }

    /** Point (u, v) of the cropped picture, in the uncropped picture's 0..1 coordinates. */
    fun toFull(u: Float, v: Float) = (left + u * width) to (top + v * height)

    /** Point (u, v) of the uncropped picture, in the cropped picture's 0..1 coordinates. */
    fun fromFull(u: Float, v: Float) = ((u - left) / width) to ((v - top) / height)

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)
        /** Smallest crop side, as a share of the picture's side. */
        const val MIN_SIDE = 0.05f
        private const val EPS = 1e-4f
    }
}

data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

object Crop {
    val ADJUSTMENTS = listOf(Adjustment.CROP_LEFT, Adjustment.CROP_TOP, Adjustment.CROP_RIGHT, Adjustment.CROP_BOTTOM, Adjustment.CROP_ASPECT)

    /** The crop set in [p]; [CropRect.FULL] when there is none (or it is degenerate). */
    fun of(p: DevelopParams): CropRect {
        val r = CropRect(p[Adjustment.CROP_LEFT], p[Adjustment.CROP_TOP], p[Adjustment.CROP_RIGHT], p[Adjustment.CROP_BOTTOM])
        return if (r.width < CropRect.MIN_SIDE * 0.5f || r.height < CropRect.MIN_SIDE * 0.5f) CropRect.FULL else r
    }

    fun aspect(p: DevelopParams): CropAspect =
        CropAspect.entries.getOrElse(p[Adjustment.CROP_ASPECT].roundToInt()) { CropAspect.FREE }

    fun with(p: DevelopParams, r: CropRect, aspect: CropAspect = aspect(p)): DevelopParams =
        p.with(Adjustment.CROP_LEFT, r.left).with(Adjustment.CROP_TOP, r.top)
            .with(Adjustment.CROP_RIGHT, r.right).with(Adjustment.CROP_BOTTOM, r.bottom)
            .with(Adjustment.CROP_ASPECT, aspect.ordinal.toFloat())

    /** [p] with its crop settings taken from [from], everything else kept. */
    fun copy(p: DevelopParams, from: DevelopParams): DevelopParams =
        ADJUSTMENTS.fold(p) { acc, a -> acc.with(a, from[a]) }

    /** Width over height that [aspect] asks for, in a picture of [w] x [h] upright pixels; null for free. */
    fun ratio(aspect: CropAspect, w: Int, h: Int, portrait: Boolean): Float? = when (aspect) {
        CropAspect.FREE -> null
        CropAspect.ORIGINAL -> if (portrait == (h > w)) w.toFloat() / h else h.toFloat() / w
        else -> if (portrait) aspect.short.toFloat() / aspect.long else aspect.long.toFloat() / aspect.short
    }

    /**
     * The largest rectangle of picture-pixel ratio [ratio] (width over height) that fits in
     * the frame, centred as near [around]'s centre as it can be. [w] x [h] is the upright
     * picture's size, which turns normalised units into pixels.
     */
    fun fit(ratio: Float, around: CropRect, w: Int, h: Int): CropRect {
        // In normalised units a rectangle of pixel ratio `ratio` has nw / nh = ratio * h / w.
        val k = ratio * h / w
        val nw = if (k >= 1f) 1f else k
        val nh = if (k >= 1f) 1f / k else 1f
        val cx = ((around.left + around.right) / 2f).coerceIn(nw / 2f, 1f - nw / 2f)
        val cy = ((around.top + around.bottom) / 2f).coerceIn(nh / 2f, 1f - nh / 2f)
        return CropRect(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f)
    }

    /** Which handle of the crop rectangle a drag holds. */
    enum class Handle { MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * Moves [handle] of [r] by ([du], [dv]) in normalised units, keeping the rectangle inside
     * the frame, at least [CropRect.MIN_SIDE] on each side, and at pixel ratio [ratio] when
     * that is set ([w] x [h] being the upright picture's size).
     */
    fun drag(r: CropRect, handle: Handle, du: Float, dv: Float, ratio: Float?, w: Int, h: Int): CropRect {
        val min = CropRect.MIN_SIDE
        if (handle == Handle.MOVE) {
            val dx = du.within(-r.left, 1f - r.right)
            val dy = dv.within(-r.top, 1f - r.bottom)
            return CropRect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
        }
        var l = r.left; var t = r.top; var rt = r.right; var b = r.bottom
        val moveL = handle in setOf(Handle.LEFT, Handle.TOP_LEFT, Handle.BOTTOM_LEFT)
        val moveR = handle in setOf(Handle.RIGHT, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT)
        val moveT = handle in setOf(Handle.TOP, Handle.TOP_LEFT, Handle.TOP_RIGHT)
        val moveB = handle in setOf(Handle.BOTTOM, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT)
        if (moveL) l = (l + du).within(0f, rt - min)
        if (moveR) rt = (rt + du).within(l + min, 1f)
        if (moveT) t = (t + dv).within(0f, b - min)
        if (moveB) b = (b + dv).within(t + min, 1f)
        if (ratio == null) return CropRect(l, t, rt, b)

        // Keep the ratio: the dragged side decides the size, the opposite side or corner stays put.
        val k = ratio * h / w // normalised width over normalised height
        val horizontalLead = when {
            (moveL || moveR) && !(moveT || moveB) -> true
            (moveT || moveB) && !(moveL || moveR) -> false
            else -> abs(du) * h >= abs(dv) * w // a corner: whichever way the finger moved more
        }
        var nw = rt - l
        var nh = b - t
        if (horizontalLead) nh = nw / k else nw = nh * k
        // Anchors: the edges that don't move (the centre line for a single edge drag).
        val ax = when { moveL -> r.right; moveR -> r.left; else -> (r.left + r.right) / 2f }
        val ay = when { moveT -> r.bottom; moveB -> r.top; else -> (r.top + r.bottom) / 2f }
        // Largest size that still fits from the anchors.
        val maxW = when { moveL -> ax; moveR -> 1f - ax; else -> 2f * min(ax, 1f - ax) }
        val maxH = when { moveT -> ay; moveB -> 1f - ay; else -> 2f * min(ay, 1f - ay) }
        val shrink = min(1f, min(maxW / nw, maxH / nh))
        nw *= shrink; nh *= shrink
        val minScale = max(min / nw, min / nh)
        if (minScale > 1f) { nw *= minScale; nh *= minScale }
        l = when { moveL -> ax - nw; moveR -> ax; else -> ax - nw / 2f }
        t = when { moveT -> ay - nh; moveB -> ay; else -> ay - nh / 2f }
        return clampInside(CropRect(l, t, l + nw, t + nh))
    }

    /** Like coerceIn, but tolerates a range that rounding has made empty (it then gives [lo]). */
    private fun Float.within(lo: Float, hi: Float) = if (hi < lo) lo else coerceIn(lo, hi)

    /** Slides [r] back inside the frame, shrinking it only if it is larger than the frame. */
    private fun clampInside(r: CropRect): CropRect {
        val w = min(r.width, 1f); val h = min(r.height, 1f)
        val l = r.left.within(0f, 1f - w); val t = r.top.within(0f, 1f - h)
        return CropRect(l, t, l + w, t + h)
    }

    /**
     * The crop as a pixel rectangle of the camera-oriented (not yet upright) picture of [w] x
     * [h] that EXIF [orientation] turns upright, the frame the develop stage works in.
     */
    fun sensorPixels(r: CropRect, orientation: Int, w: Int, h: Int): PixelRect {
        val a = toSensor(orientation, r.left, r.top)
        val b = toSensor(orientation, r.right, r.bottom)
        return CropRect(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second)).pixels(w, h)
    }

    /** Copies [rect] out of packed pixels [px] (w wide); [px] itself when the rect is the whole picture. */
    fun cut(px: IntArray, w: Int, h: Int, rect: PixelRect): IntArray {
        if (rect.x == 0 && rect.y == 0 && rect.width == w && rect.height == h) return px
        val out = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) System.arraycopy(px, (rect.y + y) * w + rect.x, out, y * rect.width, rect.width)
        return out
    }

    /** Upright (displayed) 0..1 coordinates to the camera frame's, for EXIF [orientation] 1, 3, 6 or 8. */
    fun toSensor(orientation: Int, u: Float, v: Float): Pair<Float, Float> = when (orientation) {
        3 -> (1f - u) to (1f - v)
        6 -> v to (1f - u)
        8 -> (1f - v) to u
        else -> u to v
    }

    /** The camera frame's 0..1 coordinates to upright (displayed) ones. */
    fun toDisplayed(orientation: Int, x: Float, y: Float): Pair<Float, Float> = when (orientation) {
        3 -> (1f - x) to (1f - y)
        6 -> (1f - y) to x
        8 -> y to (1f - x)
        else -> x to y
    }
}
