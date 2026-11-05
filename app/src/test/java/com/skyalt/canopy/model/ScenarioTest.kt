package com.skyalt.canopy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ScenarioTest {
    @Test
    fun wakeIsStrongAtItsCentreAndIgnoresItsOwnSource() {
        val field = WakeField()
        field.emit(7, Vec3(0.0, 100.0, 0.0), WakeProfile.AIRCRAFT, time = 0.0)
        val centre = Vec3(0.0, 100.0, 0.0)
        assertTrue(field.intensity(centre, 0.0, excluding = WakeField.PLAYER_SOURCE) > 0.9)
        assertEquals(0.0, field.intensity(centre, 0.0, excluding = 7), 1e-9)
        assertEquals(0.0, field.intensity(Vec3(100.0, 100.0, 0.0), 0.0, excluding = WakeField.PLAYER_SOURCE), 1e-9)
        field.prune(WakeProfile.AIRCRAFT.lifetime + 1)
        assertTrue(field.samples.isEmpty())
    }

    @Test
    fun wakeEmitsOncePerInterval() {
        val field = WakeField()
        val profile = WakeProfile.canopy(8.0)
        field.emit(3, Vec3(0.0, 50.0, 0.0), profile, time = 0.0)
        field.emit(3, Vec3(0.0, 50.0, 0.0), profile, time = profile.interval / 2)
        field.emit(3, Vec3(0.0, 50.0, 0.0), profile, time = profile.interval * 1.01)
        assertEquals(2, field.samples.size)
    }

    @Test
    fun everySceneFliesTheStudentCanopy() {
        for (scenario in Scenario.entries) {
            val c = scenario.configuration()
            assertEquals(CanopyType.STUDENT, c.canopyType)
            assertEquals(220.0, c.canopyAreaSquareFeet, 1e-9)
            assertEquals(0.8, c.performance.wingLoading, 0.01)
            assertEquals(ReferencePattern.LEFT, c.referencePattern)
        }
        assertEquals(0.0, Scenario.AIRCRAFT.configuration().windSpeed, 1e-9)
        assertTrue(Scenario.OBSTACLES.configuration().startsAtFinalTurn)
    }

    @Test
    fun modeKnowsItsScene() {
        assertNull(SimulatorMode.Free.scenario)
        assertEquals(Scenario.CROSSWIND, SimulatorMode.Scene(Scenario.CROSSWIND).scenario)
    }

    @Test
    fun baseLegWindRoundTrips() {
        for (choice in BaseLegWind.entries) assertEquals(choice, BaseLegWind.of(choice.windDirectionDegrees))
    }

    @Test
    fun sceneAircraftIsFlyingAtTheStart() {
        val state = AircraftFlight.scenario().state(0.0)
        assertTrue(state.isPresent)
        assertFalse(state.onGround)
        assertTrue(state.position.y > 10)
        assertTrue(state.collisionSpheres.isNotEmpty())
    }

    @Test
    fun trafficIsGeneratedAroundTheSpawn() {
        val configuration = Scenario.TRAFFIC.configuration()
        val wind = Compass.wind(configuration.windSpeed, configuration.windDirectionDegrees)
        val spawn = TrafficSpawn(Vec3(0.0, 0.0, 0.0), 0.0..300.0, 600.0..1200.0)
        val traffic = TrafficSimulation.generate(TrafficSettings.MAXIMUM_COUNT, configuration, wind, spawn)
        assertEquals(TrafficSettings.MAXIMUM_COUNT, traffic.canopies.size)
        for (frame in traffic.frames) assertFalse(frame.landed)
        repeat(60) { traffic.step(1.0 / 60, wind) }
        assertEquals(TrafficSettings.MAXIMUM_COUNT, traffic.frames.size)
    }

    @Test
    fun runwayFollowsThePicture() {
        val dz = DropzoneSettings(widthMeters = 1000.0, runway = RunwayPlacement(centreX = 0.1, centreY = 0.0, length = 0.5, width = 0.03, angleDegrees = 0.0))
        val g = assertNotNullAnd(dz.runwayGeometry)
        assertEquals(100.0, g.centre.x, 1e-6)
        assertEquals(0.0, g.centre.z, 1e-6)
        assertEquals(90.0, g.headingDegrees, 1e-6)
        assertEquals(500.0, g.lengthMeters, 1e-6)
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    @Test
    fun cloudLayerContainsItsMiddle() {
        val clouds = CloudSettings(baseAltitudeMeters = 500.0, thicknessMeters = 100.0, density = 0.5)
        assertTrue(clouds.contains(550.0))
        assertFalse(clouds.contains(450.0))
        assertFalse(clouds.copy(density = 0.0).contains(550.0))
    }

    @Test
    fun aircraftTouchesDownPastTheThreshold() {
        assertEquals(Airfield.RUNWAY_WEST_X + 100, AircraftFlight.scenario().touchdown.x, 1e-9)
        assertEquals(Airfield.RUNWAY_WEST_X + 400, AircraftFlight.scenario(400.0).touchdown.x, 1e-9)
        val near = Scenario.AIRCRAFT.configuration(100.0)
        val far = Scenario.AIRCRAFT.configuration(400.0)
        assertEquals(300.0, far.startEastMeters!! - near.startEastMeters!!, 1e-6)
    }

    @Test
    fun swoopPlanStartsHighEnoughToPlaneOut() {
        val still = Vec3(0.0, 0.0, 0.0)
        val performance = CanopyPerformance(96 * 2.2 / CanopyPerformance.POUNDS_PER_KILOGRAM, 96.0, CanopyType.CROSS_BRACED)
        val aero = AeroParameters(performance)
        val ninety = TrafficSimulation.planSwoop(90.0, PatternHand.LEFT, 0.0, performance, aero, still)
        val twoSeventy = TrafficSimulation.planSwoop(270.0, PatternHand.LEFT, 0.0, performance, aero, still)
        assertTrue("90° turn height ${ninety.turnAltitude} m", ninety.turnAltitude in 60.0..220.0)
        assertTrue("270° starts higher: ${twoSeventy.turnAltitude} m", twoSeventy.turnAltitude > ninety.turnAltitude + 50)
        // Flown again from the planned height, the turn ends in a soft landing after a skim along the ground.
        val probe = TrafficCanopy.swoopProbe(90.0, PatternHand.LEFT, ninety.startHeading, 0.0, ninety.turnAltitude, performance, aero, still)
        var clock = TrafficSimulation.CONTROL_INTERVAL
        while (probe.isFlying && probe.state.time < 60) {
            clock += TrafficSimulation.STEP_INTERVAL
            if (clock >= TrafficSimulation.CONTROL_INTERVAL) {
                clock -= TrafficSimulation.CONTROL_INTERVAL
                probe.control(still)
            }
            probe.advance(TrafficSimulation.STEP_INTERVAL, still)
        }
        assertFalse(probe.isFlying)
        assertTrue("soft touchdown: ${-probe.state.velocity.y} m/s", -probe.state.velocity.y <= 2.0)
        val travelled = hypot(probe.state.position.x, probe.state.position.z)
        assertTrue("travelled $travelled m", travelled > 80)
        assertEquals(ninety.offset.x, probe.state.position.x, 2.0)
        assertEquals(ninety.offset.z, probe.state.position.z, 2.0)
    }

    @Test
    fun swooperPatternIsWorkedBackFromTheTurn() {
        val configuration = Scenario.TRAFFIC.configuration()
        val wind = Compass.wind(configuration.windSpeed, configuration.windDirectionDegrees)
        val spawn = TrafficSpawn(Vec3(0.0, 0.0, 0.0), 0.0..300.0, 600.0..1200.0)
        val traffic = TrafficSimulation.generate(3, configuration, wind, spawn, listOf(CanopyType.CROSS_BRACED to 1.0))
        for (canopy in traffic.canopies) {
            assertTrue(canopy.isSwooper)
            val w = canopy.waypoints
            assertEquals(5, w.size)
            assertTrue("entry ${w[0].y} > base ${w[1].y} > run-in ${w[2].y} > set-up ${w[3].y}", w[0].y > w[1].y && w[1].y > w[2].y && w[2].y > w[3].y)
            assertTrue("spawned above the run-in corner", canopy.state.position.y - 1 >= w[2].y + 20)
            assertEquals(w[0].y + 15, canopy.holdAltitude, 1e-9)
        }
        repeat(600) { traffic.step(1.0 / 60, wind) }
        assertEquals(3, traffic.frames.size)
    }

    @Test
    fun aircraftMarkingsAreNormalisedAndClamped() {
        assertEquals("ZK-SKY", AircraftMarkingsSettings.normalizedRegistration("  zk-sky \n"))
        assertEquals("ABCDEFGH", AircraftMarkingsSettings.normalizedRegistration("abcdefghij"))
        assertEquals("A B", AircraftMarkingsSettings.normalizedRegistration("a\nb"))
        assertEquals(AircraftMarkingsSettings.DEFAULT_REGISTRATION, AircraftMarkingsSettings.normalizedRegistration("   "))
        val wild = AircraftMarkingsSettings("", AircraftLogoPlacement(along = 9.0, height = -3.0, width = 0.1)).clamped()
        assertEquals(AircraftMarkingsSettings.DEFAULT_REGISTRATION, wild.registration)
        assertEquals(AircraftLogoPlacement(AircraftLogoPlacement.ALONG_MAX, AircraftLogoPlacement.HEIGHT_MIN, AircraftLogoPlacement.WIDTH_MIN), wild.logo)
        assertEquals(AircraftMarkingsSettings(), AircraftMarkingsSettings().clamped())
    }
}
