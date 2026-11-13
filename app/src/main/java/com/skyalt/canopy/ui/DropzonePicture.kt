package com.skyalt.canopy.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * The dropzone picture on a north-up map: its centre at [centre], [widthPx]
 * wide and turned counter-clockwise, as the scene lays it on the ground.
 *
 * Drawn, not laid out: a layout node larger than the map is centred in the
 * room it was given, which moved the picture by half of what it overflowed.
 * The rings, the runway and the saved offsets kept their true places, so a
 * picture lined up on screen came out somewhere else in the scene.
 */
internal fun DrawScope.drawDropzonePicture(picture: ImageBitmap, centre: Offset, widthPx: Float, rotationDegrees: Double) {
    if (picture.width <= 0 || picture.height <= 0 || widthPx <= 0f) return
    val pixelSize = widthPx / picture.width
    withTransform({
        translate(centre.x, centre.y)
        rotate(-rotationDegrees.toFloat(), pivot = Offset.Zero)
        scale(pixelSize, pixelSize, pivot = Offset.Zero)
    }) {
        drawImage(picture, topLeft = Offset(-picture.width / 2f, -picture.height / 2f))
    }
}
