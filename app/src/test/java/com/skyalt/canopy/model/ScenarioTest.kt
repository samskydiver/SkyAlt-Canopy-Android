package com.skyalt.canopy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
