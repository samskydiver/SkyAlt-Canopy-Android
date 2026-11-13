package com.skyalt.canopy.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DropzoneSettingsTest {
    /** The point of the picture under the target, as fractions of its width from its centre. */
    private fun underTheTarget(d: DropzoneSettings): Pair<Double, Double> {
        val theta = d.rotationDegrees * DEG
        val dx = -d.eastOffsetMeters / d.widthMeters
        val dy = -d.southOffsetMeters / d.widthMeters
        return (dx * cos(theta) - dy * sin(theta)) to (dx * sin(theta) + dy * cos(theta))
    }

    @Test
    fun resizingKeepsWhatLiesUnderTheTarget() {
        val start = DropzoneSettings(widthMeters = 400.0, eastOffsetMeters = 30.0, southOffsetMeters = -20.0, rotationDegrees = 20.0)
        val (x, y) = underTheTarget(start)
        for (width in listOf(60.0, 400.0, 523.0, 2800.0)) {
            val sized = start.resized(width)
            assertEquals(width, sized.widthMeters, 1e-9)
            assertEquals(start.rotationDegrees, sized.rotationDegrees, 0.0)
            val (u, v) = underTheTarget(sized)
            assertEquals(x, u, 1e-12)
            assertEquals(y, v, 1e-12)
        }
        // Twice as wide: the picture's centre is twice as far from the target.
        assertEquals(60.0, start.resized(800.0).eastOffsetMeters, 1e-9)
        assertEquals(-40.0, start.resized(800.0).southOffsetMeters, 1e-9)
    }

    @Test
    fun resizingStopsAtTheWidthLimits() {
        val start = DropzoneSettings(widthMeters = 400.0, eastOffsetMeters = 30.0, southOffsetMeters = -20.0)
        // The offsets follow the width the picture really took, not the one asked for.
        val widest = start.resized(1e6)
        assertEquals(DropzoneSettings.WIDEST_METERS, widest.widthMeters, 0.0)
        assertEquals(30.0 * DropzoneSettings.WIDEST_METERS / 400.0, widest.eastOffsetMeters, 1e-9)
        val narrowest = start.resized(1.0)
        assertEquals(DropzoneSettings.NARROWEST_METERS, narrowest.widthMeters, 0.0)
        assertEquals(-20.0 * DropzoneSettings.NARROWEST_METERS / 400.0, narrowest.southOffsetMeters, 1e-9)
        assertEquals(widest, widest.clamped())
        assertEquals(narrowest, narrowest.clamped())
    }

    @Test
    fun aQuarterTurnPutsThePicturesRightToTheNorth() {
        // Counter-clockwise seen from above, north up: the runway a quarter of the width to the picture's right lies north of its centre and runs north.
        val turned = DropzoneSettings(
            widthMeters = 400.0, eastOffsetMeters = 10.0, southOffsetMeters = 20.0, rotationDegrees = 90.0,
            runway = RunwayPlacement(centreX = 0.25, centreY = 0.0, angleDegrees = 0.0),
        )
        val runway = turned.runwayGeometry!!
        assertEquals(10.0, runway.centre.x, 1e-9)
        assertEquals(20.0 - 100.0, runway.centre.z, 1e-9)
        assertEquals(0.0, runway.headingDegrees, 1e-9)
    }
}
