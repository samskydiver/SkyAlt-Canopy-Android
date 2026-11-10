package com.skyalt.canopy.model

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The rig's proportions the flight model and the scene share: the jumper
 * hangs [LINE_LENGTH] below the canopy's centre, and a canopy's size grows
 * or shrinks its span alike with the square root of the area against a
 * 150 ft² one. Port of CanopyRigGeometry.swift.
 */
object CanopyRigGeometry {
    const val LINE_LENGTH = 4.4
    const val REFERENCE_AREA_SQUARE_FEET = 150.0

    fun scale(areaSquareFeet: Double) = sqrt(max(1.0, areaSquareFeet) / REFERENCE_AREA_SQUARE_FEET)
}
