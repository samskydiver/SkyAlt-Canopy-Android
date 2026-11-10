package com.skyalt.canopy.tools

import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.MercatorRect
import com.skyalt.canopy.tools.planning.Path3DFrame
import com.skyalt.canopy.tools.planning.Path3DMeshes
import com.skyalt.canopy.tools.planning.Path3DPath
import com.skyalt.canopy.tools.planning.Path3DPoint
import com.skyalt.canopy.tools.planning.Path3DRing
import com.skyalt.canopy.tools.planning.PlanningPath3DContent
import com.skyalt.canopy.tools.planning.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Path3DTest {
    @Test
    fun mercatorRoundTrips() {
        for (lat in listOf(-60.0, -33.9, 0.0, 13.7, 51.5)) assertEquals(lat, WebMercator.latitude(WebMercator.y(lat)), 1e-9)
        for (lon in listOf(-179.0, 0.0, 100.5)) assertEquals(lon, WebMercator.longitude(WebMercator.x(lon)), 1e-9)
        val rect = MercatorRect.of(Coordinate(13.70, 100.50), Coordinate(13.72, 100.53))
        assertEquals(13.70, rect.southWest.latitude, 1e-9)
        assertEquals(100.53, rect.northEast.longitude, 1e-9)
    }

    @Test
    fun theFrameIsInMetresEastAndSouth() {
        val origin = Coordinate(13.7, 100.5)
        val frame = Path3DFrame(WebMercator.x(origin.longitude), WebMercator.y(origin.latitude))
        // 0.01° of latitude is about 1,112 m; north is −z.
        val north = frame.local(Path3DPoint(Coordinate(13.71, 100.5), 300.0))
        assertEquals(0f, north[0], 0.01f)
        assertEquals(300f, north[1], 0f)
        assertEquals(-1_112f, north[2], 15f)
        val east = frame.local(Path3DPoint(Coordinate(13.7, 100.51), 0.0))
        assertEquals(1_081f, east[0], 15f)
    }

    @Test
    fun theNeededAreaIsAtLeast300MetresAndTheGroundFitsIt() {
        val content = PlanningPath3DContent(rings = listOf(Path3DRing("r", Path3DPoint(Coordinate(13.7, 100.5), 0.0), 20.0, 0)))
        val needed = content.neededRect!!
        val meters = needed.width * WebMercator.metersPerUnit(13.7)
        assertEquals(300.0, meters, 1.0)
        val ground = MercatorRect.squareGround(needed)
        assertTrue(MercatorRect.groundSuits(ground, needed))
        // A plan that grew past the ground's margin needs a new picture.
        val grown = MercatorRect(needed.x, needed.y, needed.width * 2.5, needed.height)
        assertFalse(MercatorRect.groundSuits(ground, grown))
        assertTrue(PlanningPath3DContent().neededRect == null)
    }

    @Test
    fun meshesHaveTheirFaces() {
        val points = listOf(floatArrayOf(0f, 100f, 0f), floatArrayOf(100f, 50f, 0f), floatArrayOf(100f, 0f, 100f))
        val tube = Path3DMeshes.tube(points, 2f)
        assertEquals(2 * 8 * 3, tube.positions.size)
        assertEquals(2 * 4 * 6, tube.indices.size)
        assertEquals(3 * 2 * 3, Path3DMeshes.curtain(points).positions.size)
        assertEquals(2 * 6, Path3DMeshes.groundTrack(points, 3f).indices.size)
        // A vertical drop line still gets a tube.
        assertEquals(4 * 6, Path3DMeshes.tube(listOf(floatArrayOf(5f, 0f, 5f), floatArrayOf(5f, 80f, 5f)), 1f).indices.size)
        val disc = Path3DMeshes.disc(floatArrayOf(0f, 10f, 0f), 50f, segments = 8)
        assertEquals(8 * 3, disc.indices.size)
        assertTrue(Path3DMeshes.tube(listOf(floatArrayOf(0f, 0f, 0f)), 1f).isEmpty)
    }

    @Test
    fun missingSwoopHeightsAreTakenFromTheirNeighbours() {
        assertEquals(listOf(900.0, 750.0, 600.0, 300.0, 0.0), PlanningPath3DContent.filledAltitudes(listOf(900.0, null, 600.0, 300.0, 0.0)))
        assertEquals(listOf(600.0, 600.0, 300.0), PlanningPath3DContent.filledAltitudes(listOf(null, 600.0, 300.0)))
        assertEquals(listOf(0.0, 0.0), PlanningPath3DContent.filledAltitudes(listOf(null, null)))
    }

    @Test
    fun heightTagsRunDownTheDriftWithRoomUnderTheTop() {
        val c = Coordinate(13.7, 100.5)
        val points = (0..250).map { Path3DPoint(c, 2_500.0 - it * 10.0) }
        // 2,500 m with tags every 500 m: 2,500 is the top itself, so 2,000 down to 500.
        assertEquals(listOf(2_000.0, 1_500.0, 1_000.0, 500.0), PlanningPath3DContent.heightTags(points, 2_500.0, 500.0).map { it.altitudeMeters })
        // 2,600 m: 2,500 is too close under the top.
        assertEquals(2_000.0, PlanningPath3DContent.heightTags(points, 2_600.0, 500.0).first().altitudeMeters, 0.0)
        assertTrue(PlanningPath3DContent(paths = listOf(Path3DPath("p", points, 0))).highestAltitudeMeters == 2_500.0)
    }
}
