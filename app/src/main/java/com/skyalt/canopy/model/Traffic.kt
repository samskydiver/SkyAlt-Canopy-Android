package com.skyalt.canopy.model

import com.skyalt.canopy.scene.CanopyMeshShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// The computer-flown canopies around the jumper. Port of CanopyTraffic.swift.

/** How a run ended in a collision. */
data class Accident(val kind: Kind, /** Seconds into the run. */ val time: Double, /** Metres above the ground. */ val altitude: Double) {
    enum class Kind { CANOPY, AIRCRAFT, PERSON, BUILDING }
}

/** How a computer-flown canopy looks, for the scene. */
data class TrafficAppearance(val id: Int, val areaSquareFeet: Double, val colorIndex: Int, val isStudent: Boolean)

/** One moment of a computer-flown canopy, for the scene and the replay. */
data class TrafficFrame(
    val position: Vec3,
    val heading: Double,
    val bank: Double,
    val pitch: Double,
    val leftBrake: Double,
    val rightBrake: Double,
    val landed: Boolean,
) {
    companion object {
        fun interpolated(a: List<TrafficFrame>, b: List<TrafficFrame>, u: Double): List<TrafficFrame> {
            if (a.size != b.size) return if (u < 0.5) a else b
            fun mix(x: Double, y: Double) = x + (y - x) * u
            return a.indices.map { i ->
                val p = a[i]; val q = b[i]
                TrafficFrame(
                    p.position + (q.position - p.position) * u,
                    FlightFrame.lerpAngle(p.heading, q.heading, u),
                    mix(p.bank, q.bank), mix(p.pitch, q.pitch),
                    mix(p.leftBrake, q.leftBrake), mix(p.rightBrake, q.rightBrake),
                    q.landed && u > 0.99,
                )
            }
        }
    }
}

fun ClosedFloatingPointRange<Double>.random() = if (endInclusive > start) Random.nextDouble(start, endInclusive) else start

private fun wrap(angle: Double) = atan2(sin(angle), cos(angle))

/**
 * One of the computer-flown canopies. A student or 7-cell flies the
 * pattern, loosely; a performance or cross-braced canopy sets up for a
 * carving turn onto final.
 */
class TrafficCanopy(
    /** The wake field's source id: 1 and up. */
    val id: Int,
    val performance: CanopyPerformance,
    val aero: AeroParameters,
    val colorIndex: Int,
    val hand: PatternHand,
    val state: FlightState,
    /** The pattern's entry, base turn, final turn and landing point (a swooper has a run-in to its set-up point). */
    val waypoints: List<Vec3>,
    /** The heading of the leg to each waypoint after the first. */
    val legHeadings: List<Double>,
    val landingPoint: Vec3,
    val holdingPoint: Vec3,
    /** Degrees of the carving turn onto final; 0 flies a plain pattern. */
    val swoopDegrees: Double,
    private val wobblePhase: DoubleArray,
    /** Students fly in a little brake much of the time. */
    private val brakeBias: Double,
) {
    sealed class Phase {
        /** Circling near the holding point until down to the pattern. */
        data object Holding : Phase()
        /** Flying the legs towards waypoint [leg] + 1. */
        data class Pattern(val leg: Int) : Phase()
        /** A swooper's full-toggle turn onto final. */
        data object SwoopTurn : Phase()
        /** Flying out the swoop to the landing point. */
        data object SwoopFinal : Phase()
        data object Landed : Phase()
    }

    val inputs = BrakeInputs().apply { handSpeed = 1.5 }
    var phase: Phase = Phase.Holding
        private set
    private var swoopTurned = 0.0
    private var lastHeading = state.heading
    private var evadeSeconds = 0.0
    private var evadeDirection = 0.0

    val isStudent get() = performance.type == CanopyType.STUDENT
    val isSwooper get() = swoopDegrees > 0
    val span get() = performance.span
    val isFlying get() = phase != Phase.Landed
    val canopyCentre get() = state.canopyCentre

    val appearance get() = TrafficAppearance(id, performance.areaSquareFeet, colorIndex, isStudent)

    val frame get() = TrafficFrame(state.position, state.heading, state.bank, state.pitch, inputs.left, inputs.right, phase == Phase.Landed)

    /** A step of the flight, with the toggles moving towards where the last [control] put them. */
    fun advance(dt: Double, wind: Vec3) {
        if (phase == Phase.Landed) return
        inputs.update(dt)
        state.step(dt, inputs.left, inputs.right, aero, wind, isStudent)
        if (state.landed) {
            phase = Phase.Landed
            return
        }
        if (phase == Phase.SwoopTurn) {
            val change = wrap(state.heading - lastHeading)
            swoopTurned += if (hand == PatternHand.LEFT) -change else change
        }
        lastHeading = state.heading
        evadeSeconds = max(0.0, evadeSeconds - dt)
    }

    /**
     * Decides where the toggles go, ten times a second: steering towards the
     * next waypoint with some wobble, a bit of brake for a student, the
     * carving turn and the flare, and an evasive turn when another canopy is
     * close.
     */
    fun control(wind: Vec3) {
        val t = state.time
        val p = state.position
        val altitude = p.y - 1
        // +1 is the right toggle down, −1 the left.
        var steer = 0.0
        var brake = 0.0
        var wobbles = true
        when (val current = phase) {
            Phase.Landed -> return
            Phase.Holding -> {
                if (altitude <= waypoints[0].y + 15) {
                    phase = Phase.Pattern(legIndex(altitude))
                    control(wind)
                    return
                }
                val tx = holdingPoint.x - p.x; val tz = holdingPoint.z - p.z
                steer = if (hypot(tx, tz) > 80) steerTowards(Compass.heading(tx, tz)) else if (hand == PatternHand.LEFT) -0.4 else 0.4
            }
            is Phase.Pattern -> {
                val leg = current.leg
                val goal = waypoints[leg + 1]
                val tx = goal.x - p.x; val tz = goal.z - p.z
                val distance = hypot(tx, tz)
                val legDirection = Compass.direction(legHeadings[leg])
                val passed = tx * legDirection.x + tz * legDirection.z < 0
                val last = leg + 2 >= waypoints.size
                if (!last && (distance < 25 || passed)) {
                    if (leg + 3 >= waypoints.size && isSwooper) {
                        phase = Phase.SwoopTurn
                        swoopTurned = 0.0
                    } else {
                        phase = Phase.Pattern(leg + 1)
                    }
                    control(wind)
                    return
                }
                if (distance > 8) steer = steerTowards(Compass.heading(tx, tz))
                // Well above the glide to the waypoint: brakes steepen the
                // descent; a swooper carries its height to the turn.
                if (!isSwooper && distance > 30) {
                    val groundSpeed = max(1.0, hypot(state.velocity.x, state.velocity.z))
                    val glide = aero.trimSinkRate / groundSpeed
                    val needed = (altitude - goal.y) / distance
                    if (needed > glide * 1.35) brake = 0.45
                }
                if (last && altitude < 3.5) {
                    brake = if (isStudent) 1.0 else 0.75
                    steer = 0.0
                    wobbles = false
                }
            }
            Phase.SwoopTurn -> {
                if (swoopTurned >= swoopDegrees * DEG || altitude < 20) {
                    phase = Phase.SwoopFinal
                    control(wind)
                    return
                }
                steer = if (hand == PatternHand.LEFT) -1.0 else 1.0
                wobbles = false
            }
            Phase.SwoopFinal -> {
                val tx = landingPoint.x - p.x; val tz = landingPoint.z - p.z
                if (hypot(tx, tz) > 10) steer = steerTowards(Compass.heading(tx, tz))
                if (altitude < 5) {
                    brake = 0.7 * smoothStep(5.0, 0.5, altitude)
                    wobbles = false
                }
            }
        }
        if (wobbles) {
            steer += 0.15 * sin(0.4 * t + wobblePhase[0]) * sin(0.9 * t + wobblePhase[1])
            brake = max(brake, brakeBias * (0.5 + 0.5 * sin(0.3 * t + wobblePhase[2])))
        }
        if (evadeSeconds > 0 && phase != Phase.SwoopTurn) {
            steer = 0.6 * evadeDirection
            brake = max(brake, 0.2)
        }
        steer = steer.clamp(-1.0, 1.0)
        brake = brake.clamp(0.0, 1.0)
        inputs.targetLeft = min(1.0, brake + max(0.0, -steer) * (1 - brake))
        inputs.targetRight = min(1.0, brake + max(0.0, steer) * (1 - brake))
    }

    /** Turns away from another canopy for a moment and slows a little. */
    fun evade(other: Vec3) {
        if (evadeSeconds > 0) return
        val ahead = Compass.direction(state.heading)
        val to = other - state.position
        // Positive when the other is off to the right.
        val side = ahead.x * to.z - ahead.z * to.x
        evadeDirection = if (side > 0) -1.0 else 1.0
        evadeSeconds = 1.5
    }

    private fun steerTowards(wanted: Double) = (1.2 * wrap(wanted - state.heading)).clamp(-1.0, 1.0)

    /** Which leg suits the altitude on joining the pattern: the first whose end lies below the canopy. */
    private fun legIndex(altitude: Double): Int {
        for (i in 1 until waypoints.size - 1) if (altitude > waypoints[i].y) return i - 1
        return waypoints.size - 2
    }
}

/** Where the generated canopies are put: around a centre, between two horizontal distances and two altitudes. */
data class TrafficSpawn(val centre: Vec3, val horizontalRange: ClosedFloatingPointRange<Double>, val altitudeRange: ClosedFloatingPointRange<Double>)

/** Some of the generated canopies are students of a given loading at one altitude, as a scene asks. */
data class TrafficStudentGroup(val count: Int, val wingLoading: ClosedFloatingPointRange<Double>, val altitude: Double)

/** Everything the computer flies: generated at each reset, stepped with the jumper, keeping clear of one another. */
class TrafficSimulation(val canopies: List<TrafficCanopy> = emptyList()) {
    private var controlClock = CONTROL_INTERVAL
    private var avoidanceClock = 0.0

    val isEmpty get() = canopies.isEmpty()
    val appearances get() = canopies.map { it.appearance }
    val frames get() = canopies.map { it.frame }

    fun step(dt: Double, wind: Vec3) {
        if (canopies.isEmpty()) return
        controlClock += dt
        avoidanceClock += dt
        val control = controlClock >= CONTROL_INTERVAL
        if (control) controlClock -= CONTROL_INTERVAL
        if (avoidanceClock >= AVOIDANCE_INTERVAL) {
            avoidanceClock -= AVOIDANCE_INTERVAL
            avoidCollisions()
        }
        for (canopy in canopies) {
            if (control) canopy.control(wind)
            canopy.advance(dt, wind)
        }
    }

    /** The lower canopy has the right of way: a higher one closing on it turns away. At the same level the earlier gives way. */
    private fun avoidCollisions() {
        val n = canopies.size
        if (n < 2) return
        for (i in 0 until n) {
            if (!canopies[i].isFlying) continue
            for (j in i + 1 until n) {
                if (!canopies[j].isFlying) continue
                val a = canopies[i].state
                val b = canopies[j].state
                val d = b.position - a.position
                if (hypot(d.x, d.z) >= 60 || abs(d.y) >= 25) continue
                if ((b.velocity - a.velocity).dot(d) >= 0) continue
                val giverIsA = abs(d.y) < 3 || a.position.y > b.position.y
                if (giverIsA) canopies[i].evade(b.position) else canopies[j].evade(a.position)
            }
        }
    }

    /** Whether the jumper's canopy (a sphere at [canopy] of [radius]) or body (a metre around [body]) touches any flying canopy. */
    fun collides(canopy: Vec3, radius: Double, body: Vec3): Boolean {
        for (other in canopies) {
            if (!other.isFlying) continue
            val otherRadius = canopyRadius(other.performance.areaSquareFeet)
            val otherCanopy = other.canopyCentre
            val otherBody = other.state.position
            if ((canopy - otherCanopy).length < radius + otherRadius) return true
            if ((body - otherCanopy).length < 1 + otherRadius) return true
            if ((canopy - otherBody).length < radius + 1) return true
            if ((body - otherBody).length < 2) return true
        }
        return false
    }

    companion object {
        const val STEP_INTERVAL = 1.0 / 60
        const val CONTROL_INTERVAL = 0.1
        const val AVOIDANCE_INTERVAL = 0.2

        /** The mix of types in free practice. */
        val FREE_MIX = listOf(
            CanopyType.STUDENT to 0.45, CanopyType.SEVEN_CELL to 0.2, CanopyType.PERFORMANCE to 0.2, CanopyType.CROSS_BRACED to 0.15,
        )

        /** A canopy's reach for collisions: a little under half its span. */
        fun canopyRadius(areaSquareFeet: Double) = 3.4 * CanopyMeshShape.scale(areaSquareFeet)

        /** Wing loadings, lb/ft², the types are flown at. */
        fun loadingRange(type: CanopyType) = when (type) {
            CanopyType.STUDENT -> 0.6..0.9
            CanopyType.SEVEN_CELL -> 0.8..1.2
            CanopyType.PERFORMANCE -> 1.1..1.6
            CanopyType.CROSS_BRACED -> 1.5..2.3
        }

        /** Sizes, ft², the types come in. */
        fun areaRange(type: CanopyType) = when (type) {
            CanopyType.STUDENT -> 190.0..260.0
            CanopyType.SEVEN_CELL -> 150.0..210.0
            CanopyType.PERFORMANCE -> 120.0..170.0
            CanopyType.CROSS_BRACED -> 80.0..110.0
        }

        private fun pick(mix: List<Pair<CanopyType, Double>>): CanopyType {
            val total = mix.sumOf { it.second }
            var roll = Random.nextDouble(0.0, max(total, 1e-9))
            for ((type, weight) in mix) {
                roll -= weight
                if (roll < 0) return type
            }
            return mix.lastOrNull()?.first ?: CanopyType.STUDENT
        }

        /** A point in a disc, evenly spread. */
        private fun randomOffset(radius: Double): Vec3 {
            val angle = Random.nextDouble(0.0, 2 * PI)
            val r = radius * sqrt(Random.nextDouble())
            return Vec3(r * sin(angle), 0.0, -r * cos(angle))
        }

        /**
         * [count] canopies of the mix, each with its own loading, size,
         * pattern and landing point, put in the air around the spawn.
         */
        fun generate(
            count: Int,
            configuration: SimulatorConfiguration,
            wind: Vec3,
            spawn: TrafficSpawn,
            mix: List<Pair<CanopyType, Double>> = FREE_MIX,
            students: TrafficStudentGroup? = null,
        ): TrafficSimulation {
            if (count <= 0) return TrafficSimulation()
            val finalHeading = configuration.finalHeadingDegrees?.let { it * DEG } ?: (configuration.windDirectionDegrees * DEG)
            val canopies = ArrayList<TrafficCanopy>()
            for (index in 0 until count) {
                val groupMember = students != null && index < students.count
                val type = if (groupMember) CanopyType.STUDENT else pick(mix)
                val loading = if (groupMember) students!!.wingLoading.random() else loadingRange(type).random()
                val area = Math.round(areaRange(type).random() / 5) * 5.0
                val weight = (loading * area / CanopyPerformance.POUNDS_PER_KILOGRAM).clamp(40.0, 160.0)
                val performance = CanopyPerformance(weight, area, type)
                val aero = AeroParameters(performance)
                val hand = configuration.hand
                val swoopDegrees = when (type) {
                    CanopyType.PERFORMANCE -> listOf(90.0, 180.0).random()
                    CanopyType.CROSS_BRACED -> listOf(90.0, 180.0, 270.0).random()
                    else -> 0.0
                }
                val landingPoint = SimConstants.TARGET + randomOffset(if (swoopDegrees > 0) 30.0 else 50.0)

                val waypoints: List<Vec3>
                val legHeadings: List<Double>
                if (swoopDegrees > 0) {
                    val plan = planSwoop(swoopDegrees, hand, finalHeading, aero, wind)
                    val setup = Vec3(landingPoint.x - plan.offset.x, plan.turnAltitude, landingPoint.z - plan.offset.z)
                    val sign = if (hand == PatternHand.RIGHT) -1.0 else 1.0
                    val runInHeading = plan.startHeading
                    val baseHeading = runInHeading + sign * PI / 2
                    val downwindHeading = runInHeading + PI
                    val drops = doubleArrayOf(60.0, 90.0, 90.0)
                    val runInStart = setup - Compass.direction(runInHeading) * PatternGeometry.legLength(drops[0], runInHeading, aero, wind)
                    val baseStart = runInStart - Compass.direction(baseHeading) * PatternGeometry.legLength(drops[1], baseHeading, aero, wind)
                    val entry = baseStart - Compass.direction(downwindHeading) * PatternGeometry.legLength(drops[2], downwindHeading, aero, wind)
                    waypoints = listOf(
                        Vec3(entry.x, plan.turnAltitude + drops[0] + drops[1] + drops[2], entry.z),
                        Vec3(baseStart.x, plan.turnAltitude + drops[0] + drops[1], baseStart.z),
                        Vec3(runInStart.x, plan.turnAltitude + drops[0], runInStart.z),
                        setup,
                        landingPoint,
                    )
                    legHeadings = listOf(downwindHeading, baseHeading, runInHeading, finalHeading)
                } else {
                    // The pattern altitudes, loosely: everyone flies their own.
                    val factor = (0.85..1.15).random()
                    var final = configuration.finalAltitudeMeters * factor * (0.92..1.08).random()
                    var base = configuration.baseAltitudeMeters * factor * (0.92..1.08).random()
                    var downwind = configuration.downwindAltitudeMeters * factor * (0.92..1.08).random()
                    final = max(final, 15.0)
                    base = max(base, final + SimulatorConfiguration.PATTERN_GAP)
                    downwind = max(downwind, base + SimulatorConfiguration.PATTERN_GAP)
                    val pattern = PatternGeometry(
                        hand, aero, configuration.windSpeed, configuration.windDirectionDegrees,
                        PatternAltitudes(downwind, base, final), finalHeading, landingPoint,
                    )
                    waypoints = pattern.points
                    legHeadings = listOf(pattern.downwindHeading, pattern.baseHeading, pattern.finalHeading)
                }
                val holdingPoint = waypoints[0] + Compass.direction(legHeadings[2]) * 150.0

                val bearing = Random.nextDouble(0.0, 2 * PI)
                val distance = spawn.horizontalRange.random()
                var altitude = spawn.altitudeRange.random()
                if (groupMember) altitude = students!!.altitude + Random.nextDouble(-10.0, 10.0)
                altitude = max(20.0, altitude)
                val position = Vec3(spawn.centre.x + distance * sin(bearing), altitude + 1, spawn.centre.z - distance * cos(bearing))
                val heading = Compass.heading(holdingPoint.x - position.x, holdingPoint.z - position.z)
                canopies += TrafficCanopy(
                    id = index + 1,
                    performance = performance,
                    aero = aero,
                    colorIndex = index,
                    hand = hand,
                    state = FlightState(position, heading, aero, wind),
                    waypoints = waypoints,
                    legHeadings = legHeadings,
                    landingPoint = landingPoint,
                    holdingPoint = holdingPoint,
                    swoopDegrees = swoopDegrees,
                    wobblePhase = DoubleArray(3) { Random.nextDouble(0.0, 2 * PI) },
                    brakeBias = if (type == CanopyType.STUDENT) Random.nextDouble(0.08, 0.25) else Random.nextDouble(0.0, 0.08),
                )
            }
            return TrafficSimulation(canopies)
        }

        class SwoopPlan(val turnAltitude: Double, val offset: Vec3, val startHeading: Double)

        /**
         * Flies the carving turn and the run-out on the flight model to find
         * the altitude to start it at and how far the canopy travels from the
         * start of the turn to the touchdown; the start is raised until the
         * turn finishes with height to spare.
         */
        fun planSwoop(degrees: Double, hand: PatternHand, finalHeading: Double, aero: AeroParameters, wind: Vec3): SwoopPlan {
            val sign = if (hand == PatternHand.RIGHT) -1.0 else 1.0
            val startHeading = finalHeading + sign * degrees * DEG
            // A coarser step than the flight's own: this runs for every swooper at each reset.
            val dt = STEP_INTERVAL
            var best: SwoopPlan? = null
            for (attempt in 0 until 6) {
                val altitude = 80 * degrees / 90 + 40 * attempt
                val start = Vec3(0.0, altitude + 1, 0.0)
                val state = FlightState(start, startHeading, aero, wind)
                var turning = true
                var turned = 0.0
                var lastHeading = state.heading
                var endAltitude = 0.0
                while (!state.landed && state.time < 90) {
                    if (turning) {
                        state.step(dt, if (hand == PatternHand.LEFT) 1.0 else 0.0, if (hand == PatternHand.RIGHT) 1.0 else 0.0, aero, wind, false)
                        val change = wrap(state.heading - lastHeading)
                        turned += if (hand == PatternHand.LEFT) -change else change
                        lastHeading = state.heading
                        if (turned >= degrees * DEG || state.position.y - 1 < 20) {
                            turning = false
                            endAltitude = state.position.y - 1
                        }
                    } else {
                        val agl = state.position.y - 1
                        val brake = if (agl < 5) 0.7 * smoothStep(5.0, 0.5, agl) else 0.0
                        state.step(dt, brake, brake, aero, wind, false)
                    }
                }
                best = SwoopPlan(altitude, Vec3(state.position.x - start.x, 0.0, state.position.z - start.z), startHeading)
                if (endAltitude >= 25) break
            }
            return best ?: SwoopPlan(80 * degrees / 90, Vec3.ZERO, startHeading)
        }
    }
}
