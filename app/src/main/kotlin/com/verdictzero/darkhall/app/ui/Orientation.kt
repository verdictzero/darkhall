package com.verdictzero.darkhall.app.ui

/**
 * Converts normalised [0,1] coordinates between the displayed (EXIF-rotated) picture and the
 * sensor-oriented image the pipeline works in. Orientation 6 is shown rotated 90° clockwise,
 * 8 rotated 90° counter-clockwise (the TG-7 writes 8 for a portrait shot held grip-up).
 */
object Orientation {
    fun toSensor(orientation: Int, u: Float, v: Float): Pair<Float, Float> = when (orientation) {
        3 -> (1f - u) to (1f - v)
        6 -> v to (1f - u)
        8 -> (1f - v) to u
        else -> u to v
    }

    fun toDisplayed(orientation: Int, x: Float, y: Float): Pair<Float, Float> = when (orientation) {
        3 -> (1f - x) to (1f - y)
        6 -> (1f - y) to x
        8 -> y to (1f - x)
        else -> x to y
    }

    fun swapsAxes(orientation: Int) = orientation == 6 || orientation == 8 || orientation == 5 || orientation == 7
}
