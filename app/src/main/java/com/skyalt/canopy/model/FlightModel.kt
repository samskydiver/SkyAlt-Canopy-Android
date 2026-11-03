package com.skyalt.canopy.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// A point-mass ram-air canopy with lift and drag from the common brake
// stroke, a banked diving turn from the differential, and a stall that
// develops over the seconds the toggles are held deep. A line-for-line port
// of the iOS simulator's CanopyFlightModel.swift.

object SimConstants {
    val TARGET = Vec3.ZERO
    const val PHYSICS_STEP = 1.0 / 240
    const val RECORDING_INTERVAL = 1.0 / 30
    const val PROFILE_INTERVAL = 0.1
    const val MINIMUM_REPLAY_FRAMES = 15
    const val MAXIMUM_STORED_RUNS = 3
    /** Deep brakes, stall onset and the developed stall each last this long. */
    const val STALL_PHASE_SECONDS = 2.0
    /** With the toggles up the stall unwinds this many times faster. */
    const val STALL_RECOVERY_RATE = 2.0
}

/** Hermite step from 0 at [a] to 1 at [b]. */
fun smoothStep(a: Double, b: Double, x: Double): Double {
    val t = ((x - a) / (b - a)).clamp(0.0, 1.0)
    return t * t * (3 - 2 * t)
}

/** Compass headings in radians: 0 = north (−z), 90° = east (+x). */
object Compass {
    fun direction(heading: Double) = Vec3(sin(heading), 0.0, -cos(heading))

    fun heading(x: Double, z: Double): Double {
        var h = atan2(x, -z)
        if (h < 0) h += 2 * PI
        return h
    }

    /** The wind as a velocity: it blows *from* the given direction. */
    fun wind(speed: Double, directionFromDegrees: Double): Vec3 {
        val theta = directionFromDegrees * DEG
        return Vec3(-sin(theta) * speed, 0.0, cos(theta) * speed)
    }

    /** The eight compass points, as an index. */
    fun compassPointIndex(degrees: Double) = (normalizedDegrees(degrees) / 45).roundToInt() % 8
}

/** Aerodynamic coefficients, retuned from the performance model. */
class AeroParameters(performance: CanopyPerformance) {
    var mass = 90.0
    var wingArea = 17.5
    val airDensity = 1.2
    val gravity = CanopyPerformance.STANDARD_GRAVITY
    var liftCoefficient0 = 0.55
    var liftCoefficientPerBrake = 0.62
    var dragCoefficient0 = 0.17
    var dragCoefficientPerBrake = 0.20
    val inducedDragFactor = 0.06
    val stallStart = 0.88
    val stallFull = 1.0
    var maximumBank = 60 * DEG
    var bankTimeConstant = 0.8
    var yawGain = 0.0
    val pitchRateLiftLoss = 0.6
    val differentialEffectiveness = 0.2
    val bankDive = 0.4
    var trimGlideAngle = -atan2(1.0, 3.0)
    var trimSinkRate = 3.8
    var trimForwardSpeed = 11.4

    init {
        apply(performance)
    }

    fun apply(performance: CanopyPerformance) {
        val g = performance.glideRatio
        val v = performance.airspeed
        mass = performance.exitWeightKilograms
        wingArea = performance.areaSquareMeters
        trimGlideAngle = -atan2(1.0, g)
        liftCoefficient0 = 2 * mass * gravity / (airDensity * wingArea * v * v * sqrt(1 + 1 / (g * g)))
        dragCoefficient0 = max(0.04, liftCoefficient0 / g - inducedDragFactor * liftCoefficient0 * liftCoefficient0)
        liftCoefficientPerBrake = liftCoefficient0 * (performance.coefficients.brakeLiftRatio - 1)
        dragCoefficientPerBrake = 0.20 * liftCoefficient0 / 0.55
        maximumBank = performance.maximumBank
        bankTimeConstant = performance.bankTimeConstant
        yawGain = DIRECT_YAW_SHARE * performance.coefficients.turnGain / max(1.0, performance.span)
        trimForwardSpeed = v * cos(trimGlideAngle)
        trimSinkRate = -v * sin(trimGlideAngle)
    }

    fun stallDepth(brake: Double, studentMode: Boolean) =
        if (studentMode) 0.0 else smoothStep(stallStart, stallFull, brake)

    /** Lift and drag coefficients for a common stroke [ds] with the stall developed. */
    fun coefficients(ds: Double, stall: Double): Pair<Double, Double> {
        val linearLift = liftCoefficient0 + liftCoefficientPerBrake * ds
        val lift = linearLift * (1 - 0.65 * stall)
        val drag = dragCoefficient0 + dragCoefficientPerBrake * ds + inducedDragFactor * lift * lift + 0.55 * stall
        return lift to drag
    }

    companion object {
        const val DIRECT_YAW_SHARE = 0.4
    }
}

/** The reference pattern: downwind, base with a crab, final into the wind. */
class PatternGeometry(
    hand: PatternHand,
    aero: AeroParameters,
    windSpeed: Double,
    windDirectionDegrees: Double,
    altitudes: PatternAltitudes,
) {
    /** Downwind entry, base turn, final turn and the target, with altitude. */
    val points: List<Vec3>
    val downwindHeading: Double
    val baseHeading: Double
    val finalHeading: Double
    val downwindLength: Double
    val baseLength: Double
    val finalLength: Double

    init {
        val target = SimConstants.TARGET
        val v = aero.trimForwardSpeed
        val sink = aero.trimSinkRate
        val w = windSpeed
        finalHeading = windDirectionDegrees * DEG
        val sign = if (hand == PatternHand.RIGHT) -1.0 else 1.0
        baseHeading = finalHeading + sign * PI / 2
        downwindHeading = finalHeading + PI
        finalLength = altitudes.final * max(0.3, v - w) / sink
        baseLength = max(0.0, altitudes.base - altitudes.final) * sqrt(max(0.09, v * v - w * w)) / sink
        downwindLength = max(0.0, altitudes.downwind - altitudes.base) * (v + w) / sink
        val finalPoint = target - Compass.direction(finalHeading) * finalLength
        val basePoint = finalPoint - Compass.direction(baseHeading) * baseLength
        val downwindPoint = basePoint - Compass.direction(downwindHeading) * downwindLength
        points = listOf(
            Vec3(downwindPoint.x, altitudes.downwind, downwindPoint.z),
            Vec3(basePoint.x, altitudes.base, basePoint.z),
            Vec3(finalPoint.x, altitudes.final, finalPoint.z),
            Vec3(target.x, 0.0, target.z),
        )
    }
}

/** Where a run begins and which way it faces. */
data class StartPlacement(val position: Vec3, val heading: Double)

fun SimulatorConfiguration.patternGeometry(aero: AeroParameters, hand: PatternHand = this.hand) =
    PatternGeometry(hand, aero, windSpeed, windDirectionDegrees, patternAltitudes)

/** A chosen start point faces the pattern's entry (or the target without one). */
fun SimulatorConfiguration.start(aero: AeroParameters): StartPlacement {
    val pattern = patternGeometry(aero)
    val entry = pattern.points[0]
    val target = SimConstants.TARGET
    val altitude = startAltitudeMeters + 1
    val east = startEastMeters
    val south = startSouthMeters
    if (east == null || south == null) {
        val d = Compass.direction(pattern.downwindHeading)
        return StartPlacement(Vec3(entry.x - runInMeters * d.x, altitude, entry.z - runInMeters * d.z), pattern.downwindHeading)
    }
    val position = Vec3(target.x + east, altitude, target.z + south)
    val goal = if (referencePattern.hand == null) target else entry
    val dx = goal.x - position.x
    val dz = goal.z - position.z
    val heading = if (hypot(dx, dz) > 5) Compass.heading(dx, dz) else pattern.downwindHeading
    return StartPlacement(position, heading)
}

/** What the scene and the instruments show for one moment. */
data class FlightFrame(
    val time: Double,
    val position: Vec3,
    val velocity: Vec3,
    val bank: Double,
    val pitch: Double,
    val leftBrake: Double,
    val rightBrake: Double,
    val stall: Double,
    val collapse: Double,
    val liftCoefficient: Double,
    val loadFactor: Double,
    val distance: Double,
    val heading: Double,
    val turnRate: Double,
    val landed: Boolean,
) {
    val altitude get() = max(0.0, position.y - 1)
    val groundSpeed get() = hypot(velocity.x, velocity.z)
    val sinkRate get() = -velocity.y
    fun airspeed(wind: Vec3) = Vec3(velocity.x - wind.x, velocity.y, velocity.z - wind.z).length
    val distanceToTarget get() = hypot(SimConstants.TARGET.x - position.x, SimConstants.TARGET.z - position.z)

    companion object {
        fun interpolated(a: FlightFrame, b: FlightFrame, u: Double): FlightFrame {
            fun mix(x: Double, y: Double) = x + (y - x) * u
            return FlightFrame(
                time = mix(a.time, b.time),
                position = a.position + (b.position - a.position) * u,
                velocity = a.velocity + (b.velocity - a.velocity) * u,
                bank = mix(a.bank, b.bank),
                pitch = mix(a.pitch, b.pitch),
                leftBrake = mix(a.leftBrake, b.leftBrake),
                rightBrake = mix(a.rightBrake, b.rightBrake),
                stall = mix(a.stall, b.stall),
                collapse = mix(a.collapse, b.collapse),
                liftCoefficient = mix(a.liftCoefficient, b.liftCoefficient),
                loadFactor = mix(a.loadFactor, b.loadFactor),
                distance = mix(a.distance, b.distance),
                heading = lerpAngle(a.heading, b.heading, u),
                turnRate = mix(a.turnRate, b.turnRate),
                landed = b.landed && u > 0.99,
            )
        }

        fun lerpAngle(a: Double, b: Double, t: Double): Double {
            val delta = atan2(sin(b - a), cos(b - a))
            return a + delta * t
        }
    }
}

/** A run's 30 Hz recording, played back at any speed. */
object FlightRecording {
    fun frame(time: Double, recording: List<FlightFrame>): FlightFrame? {
        if (recording.isEmpty()) return null
        val first = recording.first()
        val last = recording.last()
        if (time <= first.time) return first
        if (time >= last.time) return last
        var low = 0
        var high = recording.size - 1
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (recording[middle].time <= time) low = middle else high = middle
        }
        val a = recording[low]
        val b = recording[high]
        val u = (time - a.time) / max(1e-6, b.time - a.time)
        return FlightFrame.interpolated(a, b, u).copy(time = time)
    }
}

/** The integrated state of the canopy and jumper. */
class FlightState(configuration: SimulatorConfiguration, aero: AeroParameters) {
    var position: Vec3
    var velocity: Vec3
    var bank = 0.0
    var pitch = -12 * DEG
    var glideAngleRate = 0.0
    var time = 0.0
    var loadFactor = 1.0
    var liftCoefficient: Double
    var dragCoefficient: Double
    /** Seconds of full brake the stall has had to develop, 0…6. */
    var stallSeconds = 0.0
    var stall = 0.0
    var collapse = 0.0
    var glideAngle: Double
    var landed = false
    var distance = 0.0
    var heading: Double
    var turnRate = 0.0

    init {
        val start = configuration.start(aero)
        val d = Compass.direction(start.heading)
        val wind = Compass.wind(configuration.windSpeed, configuration.windDirectionDegrees)
        position = start.position
        velocity = Vec3(aero.trimForwardSpeed * d.x + wind.x, -aero.trimSinkRate, aero.trimForwardSpeed * d.z + wind.z)
        liftCoefficient = aero.liftCoefficient0
        dragCoefficient = aero.dragCoefficient0
        glideAngle = aero.trimGlideAngle
        heading = start.heading
    }

    fun step(dt: Double, leftBrake: Double, rightBrake: Double, p: AeroParameters, w: Vec3, studentMode: Boolean) {
        if (landed) return
        val da = rightBrake - leftBrake
        val ds = min(leftBrake, rightBrake) + p.differentialEffectiveness * abs(da)
        val bankTarget = (p.maximumBank * da).clamp(-p.maximumBank, p.maximumBank)
        bank += (bankTarget - bank) * (1 - exp(-dt / p.bankTimeConstant))

        var v = velocity
        val va = Vec3(v.x - w.x, v.y, v.z - w.z)
        val speed = max(va.length, 1e-6)
        val vh = va / speed
        var n = Vec3(-vh.x * vh.y, 1 - vh.y * vh.y, -vh.z * vh.y)
        n = n / max(n.length, 1e-6)
        val s = vh.cross(n)
        val liftDirection = n * cos(bank) + s * sin(bank)

        val phase = SimConstants.STALL_PHASE_SECONDS
        val developed = 3 * phase * p.stallDepth(ds, studentMode)
        stallSeconds = if (stallSeconds < developed) {
            min(developed, stallSeconds + dt)
        } else {
            max(developed, stallSeconds - dt * SimConstants.STALL_RECOVERY_RATE)
        }
        stall = smoothStep(phase, 2 * phase, stallSeconds)
        collapse = smoothStep(2 * phase, 3 * phase, stallSeconds)
        val (cl, cd) = p.coefficients(ds, stall)
        val bankCut = 1 - p.bankDive * (1 - cos(bank))
        val effectiveLift = max(0.05, cl * bankCut * (1 - 0.95 * collapse) - p.pitchRateLiftLoss * glideAngleRate)
        val q = 0.5 * p.airDensity * speed * speed * p.wingArea
        val lift = q * effectiveLift
        val drag = q * (cd * (1 - 0.5 * collapse) + 0.25 * collapse)
        var acceleration = (liftDirection * lift - vh * drag) / p.mass
        loadFactor = acceleration.length / p.gravity
        acceleration = Vec3(acceleration.x, acceleration.y - p.gravity, acceleration.z)
        v = v + acceleration * dt
        if (p.maximumBank > 0 && p.yawGain > 0) {
            val yaw = p.yawGain * speed * (bank / p.maximumBank) * dt
            val air = Vec3(v.x - w.x, v.y, v.z - w.z)
            val tx = air.x * cos(yaw) - air.z * sin(yaw)
            val tz = air.x * sin(yaw) + air.z * cos(yaw)
            v = Vec3(tx + w.x, air.y, tz + w.z)
        }
        position = position + v * dt
        distance += hypot(v.x, v.z) * dt
        time += dt
        liftCoefficient = effectiveLift
        dragCoefficient = cd

        val vaNew = Vec3(v.x - w.x, v.y, v.z - w.z)
        val newGlideAngle = atan2(vaNew.y, hypot(vaNew.x, vaNew.z))
        glideAngleRate += ((newGlideAngle - glideAngle) / dt - glideAngleRate) * (1 - exp(-dt / 0.12))
        glideAngle = newGlideAngle
        val newHeading = Compass.heading(vaNew.x, vaNew.z)
        var change = newHeading - heading
        if (change > PI) change -= 2 * PI
        if (change < -PI) change += 2 * PI
        turnRate += (change / dt - turnRate) * (1 - exp(-dt / 0.3))
        heading = newHeading
        val pitchTarget = (-12 + 26 * ds + 10 * stall) * DEG + 0.5 * (glideAngle - p.trimGlideAngle)
        pitch += (pitchTarget - pitch) * (1 - exp(-dt / 0.5))
        velocity = v

        if (position.y <= 1.0) {
            position = Vec3(position.x, 1.0, position.z)
            landed = true
        }
    }

    fun frame(leftBrake: Double, rightBrake: Double) = FlightFrame(
        time, position, velocity, bank, pitch, leftBrake, rightBrake, stall, collapse,
        liftCoefficient, loadFactor, distance, heading, turnRate, landed,
    )

    companion object {
        /** Seconds for the first full-toggle 360° from full flight in no wind. */
        fun fullToggleTurnSeconds(configuration: SimulatorConfiguration, aero: AeroParameters): Double {
            val setup = configuration.copy(startAltitudeMeters = 3000.0, windSpeed = 0.0)
            val state = FlightState(setup, aero)
            val dt = SimConstants.PHYSICS_STEP
            val limit = 30.0
            var turned = 0.0
            var lastHeading = state.heading
            while (state.time < limit && !state.landed) {
                state.step(dt, 0.0, 1.0, aero, Vec3.ZERO, setup.studentMode)
                var change = state.heading - lastHeading
                if (change > PI) change -= 2 * PI
                if (change < -PI) change += 2 * PI
                turned += change
                lastHeading = state.heading
                if (abs(turned) >= 2 * PI) return state.time
            }
            return limit
        }
    }
}

/** How the run ended. */
data class Touchdown(val verticalSpeed: Double, val groundSpeed: Double, val missDistance: Double) {
    enum class Quality { STAND_UP, FIRM, HARD, CRASH }

    val quality: Quality
        get() {
            val sinkMph = verticalSpeed * Units.MPH_PER_MPS
            if (sinkMph < 5.0 && groundSpeed < 5) return Quality.STAND_UP
            if (sinkMph < 7.0) return Quality.FIRM
            if (sinkMph < 10.0) return Quality.HARD
            return Quality.CRASH
        }
}

/** One point of the flight profile chart, sampled every 0.1 s. */
data class ProfileSample(val distance: Double, val altitude: Double, val brake: Double)

data class RunRecord(val number: Int, val profile: List<ProfileSample>, val touchdown: Touchdown?)

/** The brake toggles: where the hands are asked to go and where they are. */
class BrakeInputs {
    var targetLeft = 0.0
    var targetRight = 0.0
    var left = 0.0
    var right = 0.0
    var handSpeed = 2.0
    var releaseSpeed = 4.0

    fun update(dt: Double) {
        val step = handSpeed * dt
        val releaseStep = max(handSpeed, releaseSpeed) * dt
        left += (targetLeft - left).clamp(-releaseStep, step)
        right += (targetRight - right).clamp(-releaseStep, step)
    }

    fun release() {
        targetLeft = 0.0; targetRight = 0.0; left = 0.0; right = 0.0
    }
}

