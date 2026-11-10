package com.skyalt.canopy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sin

class FlightModelTest {
    private fun fly(configuration: SimulatorConfiguration, seconds: Double, left: Double, right: Double): FlightState {
        val aero = AeroParameters(configuration.performance)
        val state = FlightState(configuration, aero)
        val wind = Compass.wind(configuration.windSpeed, configuration.windDirectionDegrees)
        var t = 0.0
        while (t < seconds && !state.landed) {
            state.step(SimConstants.PHYSICS_STEP, left, right, aero, wind, configuration.studentMode)
            t += SimConstants.PHYSICS_STEP
        }
        return state
    }

    @Test
    fun fullFlightSettlesOnThePerformanceModel() {
        for (type in CanopyType.entries) {
            val c = SimulatorConfiguration(startAltitudeMeters = 3000.0, windSpeed = 0.0, canopyType = type, canopyModelKey = CanopyModel.fallback(type).key, canopyAreaSquareFeet = 170.0, exitWeightKilograms = 85.0)
            val state = fly(c, 20.0, 0.0, 0.0)
            val v = state.velocity
            val glide = hypot(v.x, v.z) / -v.y
            assertEquals("glide ratio of $type", c.performance.glideRatio, glide, 0.15)
            assertEquals("airspeed of $type", c.performance.airspeed, v.length, 0.4)
        }
    }

    @Test
    fun studentCanopyNeverStalls() {
        val c = SimulatorConfiguration(startAltitudeMeters = 3000.0, windSpeed = 0.0, canopyType = CanopyType.STUDENT)
        val state = fly(c, 10.0, 1.0, 1.0)
        assertEquals(0.0, state.stall, 1e-9)
        assertEquals(0.0, state.collapse, 1e-9)
    }

    @Test
    fun heldFullBrakeCollapsesASportCanopy() {
        val c = SimulatorConfiguration(startAltitudeMeters = 3000.0, windSpeed = 0.0, canopyType = CanopyType.PERFORMANCE, canopyAreaSquareFeet = 170.0)
        val state = fly(c, 9.0, 1.0, 1.0)
        assertTrue(state.collapse > 0.99)
        val sinkMph = -state.velocity.y * Units.MPH_PER_MPS
        assertTrue("collapsed sink $sinkMph mph", sinkMph in 18.0..30.0)
    }

    @Test
    fun fullToggleTurnTakesAFewSeconds() {
        for (type in CanopyType.entries) {
            val c = SimulatorConfiguration(canopyType = type)
            val seconds = FlightState.fullToggleTurnSeconds(c, AeroParameters(c.performance))
            assertTrue("$type 360° in $seconds s", seconds in 2.0..15.0)
        }
    }

    @Test
    fun patternEndsAtTheTargetAndRunsIntoTheWind() {
        val c = SimulatorConfiguration(windSpeed = 4.0, windDirectionDegrees = 270.0)
        val pattern = c.patternGeometry(AeroParameters(c.performance))
        val target = pattern.points.last()
        assertEquals(0.0, target.x, 1e-9)
        assertEquals(0.0, target.y, 1e-9)
        // Final into a westerly wind: flying west, so the final turn lies east of the target.
        assertTrue(pattern.points[2].x > 0)
        assertEquals(c.finalAltitudeMeters, pattern.points[2].y, 1e-9)
    }

    @Test
    fun runLandsAndGradesTheTouchdown() {
        val c = SimulatorConfiguration(startAltitudeMeters = 60.0, windSpeed = 0.0)
        val state = fly(c, 120.0, 0.0, 0.0)
        assertTrue(state.landed)
        val t = Touchdown(-state.velocity.y, hypot(state.velocity.x, state.velocity.z), 0.0)
        assertFalse(t.quality == Touchdown.Quality.STAND_UP)
    }

    @Test
    fun clampingKeepsLegsApart() {
        val c = SimulatorConfiguration(downwindAltitudeMeters = 100.0, baseAltitudeMeters = 100.0, finalAltitudeMeters = 100.0).clamped()
        assertEquals(115.0, c.baseAltitudeMeters, 1e-9)
        assertEquals(130.0, c.downwindAltitudeMeters, 1e-9)
    }

    @Test
    fun strongWakeEmptiesTheCellsAndCleanAirRefillsThem() {
        val configuration = Scenario.AIRCRAFT.configuration()
        val aero = AeroParameters(configuration.performance)
        val state = FlightState(Vec3(0.0, 2_000.0, 0.0), 0.0, aero, Vec3(0.0, 0.0, 0.0))
        val dt = 1.0 / 120
        repeat(360) { state.step(dt, 0.0, 0.0, aero, Vec3(0.0, 0.0, 0.0), true, turbulence = 1.0) }
        assertTrue("cells empty in a strong wake: ${state.inflation}", state.inflation < 0.1)
        val forward = hypot(state.velocity.x, state.velocity.z)
        assertTrue("forward speed bleeds away: $forward", forward < 0.3 * aero.trimForwardSpeed)
        repeat(1_200) { state.step(dt, 0.0, 0.0, aero, Vec3(0.0, 0.0, 0.0), true) }
        assertTrue("cells refill in clean air: ${state.inflation}", state.inflation > 0.95)
    }

    @Test
    fun weakWakeOnlyShakesAtTrimButBrakesDeflate() {
        val configuration = Scenario.AIRCRAFT.configuration()
        val aero = AeroParameters(configuration.performance)
        val trim = FlightState(Vec3(0.0, 2_000.0, 0.0), 0.0, aero, Vec3(0.0, 0.0, 0.0))
        val braked = FlightState(Vec3(0.0, 2_000.0, 0.0), 0.0, aero, Vec3(0.0, 0.0, 0.0))
        val dt = 1.0 / 120
        repeat(600) {
            trim.step(dt, 0.0, 0.0, aero, Vec3(0.0, 0.0, 0.0), true, turbulence = 0.4)
            braked.step(dt, 1.0, 1.0, aero, Vec3(0.0, 0.0, 0.0), true, turbulence = 0.4)
        }
        assertTrue("weak wake at trim keeps the cells: ${trim.inflation}", trim.inflation > 0.9)
        assertTrue("braking in it deflates them: ${braked.inflation}", braked.inflation < trim.inflation - 0.2)
    }

    @Test
    fun frontRisersDiveAndTurnWithoutToggles() {
        val performance = CanopyPerformance(96 * 2.2 / CanopyPerformance.POUNDS_PER_KILOGRAM, 96.0, CanopyType.CROSS_BRACED)
        val aero = AeroParameters(performance)
        val still = Vec3(0.0, 0.0, 0.0)
        val dt = 1.0 / 120
        val both = FlightState(Vec3(0.0, 500.0, 0.0), 0.0, aero, still)
        repeat(360) { both.step(dt, 0.0, 0.0, aero, still, false, leftRiser = 1.0, rightRiser = 1.0) }
        assertTrue("nose down on both fronts: ${both.dive}", both.dive > 0.9)
        val forward = hypot(both.velocity.x, both.velocity.z)
        assertTrue("faster than trim: $forward vs ${aero.trimForwardSpeed}", forward > 1.2 * aero.trimForwardSpeed)
        assertTrue("steeper than trim: ${both.pitch / DEG}°", both.pitch < -22 * DEG)
        assertTrue("no roll on both: ${both.bank}", kotlin.math.abs(both.bank) < 1e-6)
        val one = FlightState(Vec3(0.0, 500.0, 0.0), 0.0, aero, still)
        repeat(360) { one.step(dt, 0.0, 0.0, aero, still, false, leftRiser = 1.0) }
        assertTrue("left front banks left: ${one.bank / DEG}°", one.bank < -30 * DEG)
        assertTrue("and turns left: ${one.heading / DEG}°", sin(one.heading) < -0.5)
        val trim = FlightState(Vec3(0.0, 500.0, 0.0), 0.0, aero, still)
        repeat(360) { trim.step(dt, 0.0, 0.0, aero, still, false) }
        assertEquals(0.0, trim.dive, 1e-9)
        assertEquals(0.0, trim.bank, 1e-9)
    }
}
