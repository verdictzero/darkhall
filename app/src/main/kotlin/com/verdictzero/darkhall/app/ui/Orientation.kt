package com.verdictzero.darkhall.app.ui

import com.verdictzero.darkhall.core.develop.Crop

/**
 * Converts normalised [0,1] coordinates between the displayed (EXIF-rotated) picture and the
 * sensor-oriented image the pipeline works in. Orientation 6 is shown rotated 90° clockwise,
 * 8 rotated 90° counter-clockwise (the TG-7 writes 8 for a portrait shot held grip-up).
 */
object Orientation {
    fun toSensor(orientation: Int, u: Float, v: Float): Pair<Float, Float> = Crop.toSensor(orientation, u, v)

    fun toDisplayed(orientation: Int, x: Float, y: Float): Pair<Float, Float> = Crop.toDisplayed(orientation, x, y)

    fun swapsAxes(orientation: Int) = orientation == 6 || orientation == 8 || orientation == 5 || orientation == 7
}
