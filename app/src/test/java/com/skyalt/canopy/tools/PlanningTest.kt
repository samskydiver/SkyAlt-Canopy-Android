package com.skyalt.canopy.tools

import com.skyalt.canopy.app.DistanceUnit
import com.skyalt.canopy.tools.cutaway.CutawayWindLayers
import com.skyalt.canopy.tools.exitspot.ExitRangeMetrics
import com.skyalt.canopy.tools.landing.LandingPatternSession
import com.skyalt.canopy.tools.planning.CheckpointKind
import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.FlightPlanningEngine
import com.skyalt.canopy.tools.planning.LandingPatternPlanningEngine
import com.skyalt.canopy.tools.planning.ManualWindLayer
import com.skyalt.canopy.tools.planning.MapGeometry
import com.skyalt.canopy.tools.planning.PatternDirection
import com.skyalt.canopy.tools.planning.SkillLevel
import com.skyalt.canopy.tools.planning.WindMath
import com.skyalt.canopy.tools.planning.WindProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlanningTest {
    private val landing = Coordinate(13.736_717, 100.523_186)

    private fun profile(speedKnots: Double, fromDegrees: Double) = WindProfile.manual(
        landing, 0.0, Instant.EPOCH,
        listOf(
            ManualWindLayer(altitudeAGLFeet = 0.0, speedKnots = speedKnots, directionFromDegrees = fromDegrees),
            ManualWindLayer(altitudeAGLFeet = 14_000.0, speedKnots = speedKnots, directionFromDegrees = fromDegrees),
        ),
        source = "Manual", model = "User-entered wind layers",
    )

    @Test
    fun calmStandardPatternLegsAreStepTimesGlide() {
        val level = SkillLevel.STUDENT
        val solution = LandingPatternPlanningEngine.standard(landing, 0.0, PatternDirection.LEFT, 300.0, level.airspeedKnots, level.glideRatio, profile(0.0, 0.0))
        assertTrue(solution.issues.isEmpty())
        val leg = 300 * WindMath.METERS_PER_FOOT * level.glideRatio
        val final = MapGeometry.displacement(landing, solution.checkpoint(CheckpointKind.FINAL)!!.coordinate)
        assertEquals(-leg, final.northMeters, 0.5)
        assertEquals(0.0, final.eastMeters, 0.5)
        // Left-hand pattern flying north on final: base is flown east, so it starts west of final.
        val base = MapGeometry.displacement(landing, solution.checkpoint(CheckpointKind.BASE)!!.coordinate)
        assertEquals(-leg, base.eastMeters, 0.5)
        assertEquals(900.0, solution.checkpoint(CheckpointKind.DOWNWIND)!!.altitudeAGLFeet, 0.01)
    }

    @Test
    fun headwindShortensFinalLeg() {
        val level = SkillLevel.EXPERIENCED
        val calm = LandingPatternPlanningEngine.standard(landing, 0.0, PatternDirection.LEFT, 300.0, level.airspeedKnots, level.glideRatio, profile(0.0, 0.0))
        val windy = LandingPatternPlanningEngine.standard(landing, 0.0, PatternDirection.LEFT, 300.0, level.airspeedKnots, level.glideRatio, profile(10.0, 0.0))
        val calmFinal = MapGeometry.displacement(landing, calm.checkpoint(CheckpointKind.FINAL)!!.coordinate).magnitude
        val windyFinal = MapGeometry.displacement(landing, windy.checkpoint(CheckpointKind.FINAL)!!.coordinate).magnitude
        assertTrue(windyFinal < calmFinal)
    }

    @Test
    fun windStrongerThanCanopyMakesFinalUnflyable() {
        val solution = LandingPatternPlanningEngine.standard(landing, 0.0, PatternDirection.LEFT, 300.0, 17.0, 2.5, profile(25.0, 0.0))
        assertTrue(solution.issues.isNotEmpty())
    }

    @Test
    fun windProfileInterpolatesBetweenLayers() {
        val p = WindProfile.manual(
            landing, 0.0, Instant.EPOCH,
            listOf(
                ManualWindLayer(altitudeAGLFeet = 0.0, speedKnots = 0.0, directionFromDegrees = 270.0),
                ManualWindLayer(altitudeAGLFeet = 2_000.0, speedKnots = 20.0, directionFromDegrees = 270.0),
            ),
            source = "Manual", model = "",
        )
        assertEquals(10.0, p.windAtAGL(1_000 * WindMath.METERS_PER_FOOT).speedKnots, 0.01)
        assertEquals(270.0, p.windAtAGL(1_500 * WindMath.METERS_PER_FOOT).directionFromDegrees, 0.01)
    }

    @Test
    fun swoopWaypointLabelsSkipL() {
        val labels = (0 until 12).map { LandingPatternSession.waypointLabel(it) }
        assertEquals(listOf("B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "M", "N"), labels)
        assertEquals("AA", LandingPatternSession.alphabeticLabel(27))
    }

    @Test
    fun calmExitSpotHasRangesForEveryProfile() {
        val north = MapGeometry.coordinate(landing, 5_000.0, 0.0)
        val south = MapGeometry.coordinate(landing, 5_000.0, 180.0)
        val solution = FlightPlanningEngine.exitSpot(
            landing, south, north, 12_000 * WindMath.METERS_PER_FOOT, 5_000 * WindMath.METERS_PER_FOOT, profile(0.0, 0.0),
        )
        assertNotNull(solution)
        assertTrue(solution!!.referenceSolutions.all { it.hasExitRange })
    }

    @Test
    fun exitDistanceFormatting() {
        assertEquals("0", ExitRangeMetrics.formattedDistance(0.2, DistanceUnit.KILOMETERS))
        assertEquals("1.5A", ExitRangeMetrics.formattedDistance(1_500.0, DistanceUnit.KILOMETERS))
        assertEquals("0.25B", ExitRangeMetrics.formattedDistance(-250.0, DistanceUnit.KILOMETERS))
        assertEquals("2", ExitRangeMetrics.fractionText(2.0, 0, 2))
    }

    @Test
    fun calmCutawayLandsUnderTheCircle() {
        val solution = FlightPlanningEngine.cutaway(landing, 300.0, 2_500 * WindMath.METERS_PER_FOOT, 10 * WindMath.METERS_PER_FOOT, profile(0.0, 0.0))
        assertEquals(0.0, MapGeometry.displacement(landing, solution.landingCenter).magnitude, 0.5)
    }

    @Test
    fun cutawayLayersEveryThousandFeet() {
        assertEquals(listOf(0, 1_000, 2_000, 3_000), CutawayWindLayers.altitudes(2_500.0))
        assertEquals(listOf(0, 1_000), CutawayWindLayers.altitudes(400.0))
    }
}
