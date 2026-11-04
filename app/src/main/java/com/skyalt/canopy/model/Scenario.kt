package com.skyalt.canopy.model

import com.skyalt.canopy.scene.CanopyMeshShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

// The practice scenes, their airfield, aircraft and people. Port of
// CanopyScenario.swift.

/** The practice scenes, flown at a shared airfield on a student canopy. */
enum class Scenario(val number: Int, val titleKey: String, val summaryKey: String) {
    AIRCRAFT(
        1, "Landing with the aircraft",
        "A P750 is landing on the runway ahead of you, and you are in its wake. Get out of the turbulence and keep clear of the aircraft.",
    ),
    TRAFFIC(
        2, "Landing with other canopies",
        "Twenty canopies open around you at 4,000 ft. Fly the pattern without touching anyone, and stay out of the wake of the canopy ahead.",
    ),
    CROSSWIND(3, "Crosswind landing", "A strong wind blows across the runway. Fly the pattern and land along the runway."),
    DOWNWIND(4, "Downwind landing", "The final leg runs with the wind. Fly the pattern and land along the runway."),
    OBSTACLES(
        5, "Avoiding obstacles",
        "You start at the final turn. Someone stands on your landing point and six more are nearby: pick a clear spot.",
    );

    /** Scenes whose traffic count can be set. */
    val allowsTraffic get() = this == AIRCRAFT || this == CROSSWIND
    /** The aircraft scene is flown in still air; every other scene lets the wind speed be set. */
    val allowsWindSpeed get() = this != AIRCRAFT
    /** Scenes whose wind can blow from any direction. */
    val allowsWindDirection get() = this == TRAFFIC || this == OBSTACLES
    /** The crosswind scene: whether the base leg is flown with the wind or into it. */
    val choosesBaseLegWind get() = this == CROSSWIND
    /** Scenes with the cloud layer and its setting. */
    val showsClouds get() = this != AIRCRAFT && this != CROSSWIND
    val hasAircraft get() = this == AIRCRAFT
    val hasPeople get() = this == OBSTACLES
    /** The obstacle and aircraft scenes are flown without the reference pattern, the trail or the path styles. */
    val showsPaths get() = this != OBSTACLES && this != AIRCRAFT
    /** The aircraft scene keeps the outside view all the way down. */
    val allowsAutomaticFirstPerson get() = this != AIRCRAFT
    /** Every other scene has a switch for an aircraft landing, off to begin with. */
    val choosesAircraft get() = this != AIRCRAFT

    /**
     * The setup the scene starts with: a 220 ft² student canopy at a wing
     * loading of 0.8, a left-hand pattern with the usual altitudes, the
     * windsock 30 m east of the target.
     */
    val configuration: SimulatorConfiguration
        get() {
            var setup = SimulatorConfiguration(
                canopyType = CanopyType.STUDENT,
                canopyAreaSquareFeet = 220.0,
                exitWeightKilograms = 0.8 * 220 / CanopyPerformance.POUNDS_PER_KILOGRAM,
                referencePattern = ReferencePattern.LEFT,
                hand = PatternHand.LEFT,
                windsockEastMeters = 30.0,
                windsockSouthMeters = 0.0,
                windSpeed = 3.0,
                windDirectionDegrees = 0.0,
            )
            setup = when (this) {
                // Still air, inside the aircraft's wake; placeStart puts the start there.
                AIRCRAFT -> setup.copy(windSpeed = 0.0)
                TRAFFIC -> setup.copy(startAltitudeMeters = 4_000 * METERS_PER_FOOT, runInMeters = 2_000 * METERS_PER_FOOT)
                // 500 ft before the downwind entry, on the leg's extension.
                CROSSWIND -> setup.copy(
                    windSpeed = 7.0,
                    windDirectionDegrees = BaseLegWind.WITH_WIND.windDirectionDegrees,
                    finalHeadingDegrees = 270.0,
                    runInMeters = 500 * METERS_PER_FOOT,
                )
                DOWNWIND -> setup.copy(windSpeed = 4.0, windDirectionDegrees = 90.0, finalHeadingDegrees = 270.0)
                OBSTACLES -> setup.copy(startsAtFinalTurn = true, startAltitudeMeters = setup.finalAltitudeMeters)
            }
            return setup.placedStart(this)
        }
}

/**
 * The crosswind scene's choice of wind: across the runway from the south or
 * from the north. With a left-hand pattern onto a westward final the base
 * leg flies north, so a wind from the south is on its tail.
 */
enum class BaseLegWind(val windDirectionDegrees: Double) {
    WITH_WIND(180.0),
    INTO_WIND(0.0);

    companion object {
        /** The choice a wind direction amounts to: from the northern half it is on the nose along base. */
        fun of(windDirectionDegrees: Double) = if (cos(windDirectionDegrees * DEG) > 0) INTO_WIND else WITH_WIND
    }
}

/**
 * Puts a scene's start where the wind of the moment wants it, at each
 * reset: inside the aircraft's wake; on the crosswind scene's run-in high
 * enough to reach the downwind entry at its altitude; or on the downwind
 * scene's leg over the point upwind of the target.
 */
fun SimulatorConfiguration.placedStart(scenario: Scenario): SimulatorConfiguration = when (scenario) {
    Scenario.AIRCRAFT -> startInWake(AircraftFlight.SCENARIO)
    Scenario.CROSSWIND -> copy(startAltitudeMeters = downwindAltitudeMeters + runInAltitudeLoss())
    Scenario.DOWNWIND -> startUpwindOnDownwindLeg()
    else -> this
}

/**
 * The start at 300 ft inside the wake the aircraft left a few seconds ago,
 * where the wind has carried it since: a little south of its centre, so
 * the aircraft is ahead on the left and the way out is to the right.
 */
private fun SimulatorConfiguration.startInWake(flight: AircraftFlight): SimulatorConfiguration {
    val seconds = AircraftFlight.SECONDS_AHEAD
    val wind = Compass.wind(windSpeed, windDirectionDegrees)
    val passed = flight.state(-seconds).position
    val wake = passed + wind * seconds
    val target = SimConstants.TARGET
    return copy(
        startEastMeters = wake.x - target.x,
        startSouthMeters = wake.z - target.z + AircraftFlight.WAKE_START_OFFSET,
        startHeadingDegrees = flight.heading / DEG,
        startAltitudeMeters = AircraftFlight.SCENARIO_START_ALTITUDE,
    )
}

/**
 * The downwind scene's start: on the downwind leg's line, over the point
 * 500 ft upwind of the target, facing the leg's heading, at the height that
 * reaches the entry at its altitude.
 */
private fun SimulatorConfiguration.startUpwindOnDownwindLeg(): SimulatorConfiguration {
    val aero = AeroParameters(performance)
    val wind = Compass.wind(windSpeed, windDirectionDegrees)
    val pattern = patternGeometry(aero)
    val entry = pattern.points[0]
    val target = SimConstants.TARGET
    val along = Compass.direction(pattern.downwindHeading)
    val entryAlong = (entry.x - target.x) * along.x + (entry.z - target.z) * along.z
    // Metres from the entry along the leg; negative is before it.
    val s = 500 * METERS_PER_FOOT - entryAlong
    val groundSpeed = PatternGeometry.groundSpeed(pattern.downwindHeading, aero, wind)
    return copy(
        startEastMeters = entry.x + along.x * s - target.x,
        startSouthMeters = entry.z + along.z * s - target.z,
        startHeadingDegrees = pattern.downwindHeading / DEG,
        startAltitudeMeters = max(50.0, downwindAltitudeMeters - s * aero.trimSinkRate / groundSpeed),
    )
}

/** The altitude lost at trim over the run-in to the downwind entry. */
private fun SimulatorConfiguration.runInAltitudeLoss(): Double {
    val aero = AeroParameters(performance)
    val wind = Compass.wind(windSpeed, windDirectionDegrees)
    val heading = patternGeometry(aero).downwindHeading
    return runInMeters * aero.trimSinkRate / PatternGeometry.groundSpeed(heading, aero, wind)
}

/** Free practice with the saved setup, or one of the scenes. */
sealed class SimulatorMode {
    abstract val scenario: Scenario?

    data object Free : SimulatorMode() {
        override val scenario: Scenario? get() = null
    }

    data class Scene(override val scenario: Scenario) : SimulatorMode()
}

/**
 * The scenes' airfield, metres from the target, x east and z south: a
 * runway east to west, a hangar south of its eastern half joined to it by
 * two taxiways, and the grass landing area south of its western half with
 * the target at its centre.
 */
object Airfield {
    const val RUNWAY_CENTRE_Z = -165.0
    const val RUNWAY_WIDTH = 30.0
    const val RUNWAY_WEST_X = -250.0
    const val RUNWAY_EAST_X = 750.0
    /** The aircraft touches down this far inside the threshold it crosses… */
    const val TOUCHDOWN_FROM_THRESHOLD = 300.0
    /** …except in the aircraft scene, where it lands further along so its approach passes over the grass. */
    const val SCENE_TOUCHDOWN_FROM_THRESHOLD = 700.0
    /** The hangar's footprint and the top of its roof ridge; the walls stop at [HANGAR_WALL_HEIGHT]. */
    const val HANGAR_MIN_X = 350.0
    const val HANGAR_MAX_X = 450.0
    const val HANGAR_MIN_Z = -50.0
    const val HANGAR_MAX_Z = 10.0
    const val HANGAR_HEIGHT = 20.0
    const val HANGAR_WALL_HEIGHT = 12.0
    /** The taxiways run from the apron to the runway, within the hangar's width. */
    val TAXIWAY_XS = listOf(365.0, 435.0)
    const val TAXIWAY_WIDTH = 15.0
    /** The apron in front of the hangar, as wide as it, between it and the taxiways. */
    const val APRON_LENGTH = 30.0
    const val LANDING_MIN_X = -250.0
    const val LANDING_MAX_X = 250.0
    const val LANDING_MIN_Z = -150.0
    const val LANDING_MAX_Z = 150.0

    /** Whether a sphere touches the hangar. */
    fun hangarTouches(centre: Vec3, radius: Double): Boolean {
        val nearest = Vec3(
            centre.x.clamp(HANGAR_MIN_X, HANGAR_MAX_X),
            centre.y.clamp(0.0, HANGAR_HEIGHT),
            centre.z.clamp(HANGAR_MIN_Z, HANGAR_MAX_Z),
        )
        return (centre - nearest).length < radius
    }
}

data class AircraftState(
    val position: Vec3,
    val heading: Double,
    val speed: Double,
    val onGround: Boolean,
    /** Radians, nose up. */
    val pitch: Double,
    /** False while a repeating aircraft has taxied out of sight. */
    val isPresent: Boolean = true,
) {
    /** Spheres along the fuselage and out on the wings, for collisions. */
    val collisionSpheres: List<Pair<Vec3, Double>>
        get() {
            val ahead = Compass.direction(heading)
            val right = Vec3(-ahead.z, 0.0, ahead.x)
            return listOf(
                (position + ahead * 3.5) to 1.6,
                position to 1.8,
                (position - ahead * 3.5) to 1.5,
                (position + right * 4.5) to 1.6,
                (position - right * 4.5) to 1.6,
            )
        }
}

/**
 * The P750 landing on the runway: a straight approach at a fixed angle and
 * speed, then a roll-out, worked out for any moment so the replay can ask
 * for it too.
 */
data class AircraftFlight(
    val touchdown: Vec3,
    /** Radians. */
    val heading: Double,
    /** A steep STOL approach. */
    val approachAngle: Double = 10 * DEG,
    val approachSpeed: Double = 33.0,
    val groundDeceleration: Double = 2.0,
    /** Metres before the touchdown point at the start of the run. */
    val startDistance: Double = 700.0,
    /** Lands again and again: after the roll-out it taxis off (out of sight) and comes round again. */
    val repeats: Boolean = false,
) {
    val direction get() = Compass.direction(heading)
    val flightTime get() = startDistance / approachSpeed
    val stopTime get() = approachSpeed / groundDeceleration
    /** One approach, roll-out, wait and absence. */
    val cycleSeconds get() = flightTime + stopTime + PARKED_SECONDS + AWAY_SECONDS

    fun state(timeIn: Double): AircraftState {
        var time = timeIn
        if (repeats) {
            time %= cycleSeconds
            if (time < 0) time += cycleSeconds
        }
        if (time < flightTime) {
            val remaining = startDistance - approachSpeed * time
            val altitude = remaining * tan(approachAngle)
            return AircraftState(
                touchdown - direction * remaining + Vec3(0.0, altitude + GROUND_HEIGHT, 0.0),
                heading, approachSpeed, onGround = false, pitch = 0.0,
            )
        }
        val t = min(time - flightTime, stopTime)
        val rolled = approachSpeed * t - 0.5 * groundDeceleration * t * t
        return AircraftState(
            touchdown + direction * rolled + Vec3(0.0, GROUND_HEIGHT, 0.0),
            heading,
            max(0.0, approachSpeed - groundDeceleration * t),
            onGround = true,
            pitch = 0.0,
            isPresent = !repeats || time - flightTime < stopTime + PARKED_SECONDS,
        )
    }

    companion object {
        const val PARKED_SECONDS = 10.0
        const val AWAY_SECONDS = 40.0
        /** The fuselage's centre above the ground on its wheels. */
        const val GROUND_HEIGHT = 1.6
        /** The aircraft scene opens with the aircraft this many seconds ahead of the jumper… */
        const val SECONDS_AHEAD = 3.0
        /** …this many metres south of the wake's centre… */
        const val WAKE_START_OFFSET = 10.0
        /** …at this altitude, with the wake's centre this far above the canopy. */
        val SCENARIO_START_ALTITUDE = 300 * METERS_PER_FOOT
        const val WAKE_ABOVE_CANOPY = 3.0
        /** How many seconds of the aircraft's trail are already in the air when the scene opens. */
        const val TRAIL_SECONDS_AT_START = 8.0

        /**
         * The scenes' aircraft: landing eastward, 700 m past the western
         * threshold, and just far enough out for the wake it left
         * [SECONDS_AHEAD] ago to pass over a canopy at the start altitude.
         */
        val SCENARIO: AircraftFlight = run {
            val flight = AircraftFlight(
                touchdown = Vec3(Airfield.RUNWAY_WEST_X + Airfield.SCENE_TOUCHDOWN_FROM_THRESHOLD, 0.0, Airfield.RUNWAY_CENTRE_Z),
                heading = 90 * DEG,
            )
            val wakeHeight = SCENARIO_START_ALTITUDE + 1 + CanopyMeshShape.LINE_LENGTH + WAKE_ABOVE_CANOPY
            val glideHeight = wakeHeight + WakeProfile.AIRCRAFT.sink * SECONDS_AHEAD - GROUND_HEIGHT
            flight.copy(startDistance = glideHeight / tan(flight.approachAngle) - flight.approachSpeed * SECONDS_AHEAD)
        }

        /** The other scenes' aircraft, when switched on: the same approach, flown again and again. */
        val CIRCUIT = SCENARIO.copy(repeats = true)

        /**
         * Free practice's aircraft: landing on the runway drawn on the
         * dropzone picture in the direction nearest the final heading
         * (radians), touching down inside the threshold, again and again.
         */
        fun landing(runway: RunwayGeometry, finalHeading: Double): AircraftFlight {
            var heading = runway.headingDegrees * DEG
            if (cos(heading - finalHeading) < 0) heading += PI
            val direction = Compass.direction(heading)
            val threshold = runway.centre - direction * (runway.lengthMeters / 2)
            val rollIn = min(Airfield.TOUCHDOWN_FROM_THRESHOLD, runway.lengthMeters / 2)
            return SCENARIO.copy(touchdown = threshold + direction * rollIn, heading = heading, repeats = true)
        }
    }
}

/** People standing on the landing area in the obstacle scene. */
object People {
    /** One person on the target and six more within 50 m of it, a few metres apart. */
    fun obstacleCrowd(): List<Vec3> {
        val target = SimConstants.TARGET
        val people = arrayListOf(target)
        var tries = 0
        while (people.size < 7 && tries < 200) {
            tries += 1
            val angle = Random.nextDouble(0.0, 2 * PI)
            val r = 50 * sqrt(Random.nextDouble())
            val point = Vec3(target.x + r * sin(angle), 0.0, target.z - r * cos(angle))
            if (people.all { (it - point).length > 4 }) people += point
        }
        return people
    }

    /** Whether the jumper, whose feet are a metre under [jumper], has come down on someone. */
    fun touches(people: List<Vec3>, jumper: Vec3): Boolean {
        if (jumper.y - 1 >= 2.2) return false
        return people.any { hypot(it.x - jumper.x, it.z - jumper.z) < 1.5 }
    }
}
