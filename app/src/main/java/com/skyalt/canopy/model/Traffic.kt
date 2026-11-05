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
 * One of the computer-flown canopies in the air around the jumper. A
 * student or 7-cell flies the pattern, loosely; a performance or
 * cross-braced canopy flies a tight pattern, on heading, crabbing into the
 * wind, down to a carving turn onto final and a plane-out along the ground.
 */
class TrafficCanopy(
    /** The wake field's source id: 1 and up. */
    val id: Int,
    val performance: CanopyPerformance,
    val aero: AeroParameters,
    val colorIndex: Int,
    val hand: PatternHand,
    /**
     * A performance or cross-braced canopy: flies its pattern on exact
     * headings, crabbing into the wind, and lands out of a front-riser turn
     * or, when the height has gone, a straight-in, planing out along the
     * ground either way.
     */
    val isSwooper: Boolean,
    val state: FlightState,
    /**
     * The pattern's waypoints with their altitudes, ending at the landing
     * point. A student's or 7-cell's are the entry, the base turn and the
     * final turn; a swooper's are the entry, the corners and its set-up
     * point, each at the height the leg to it arrives at once the corner's
     * turn has been paid for.
     */
    var waypoints: List<Vec3>,
    /** The heading of the leg to each waypoint after the first. */
    var legHeadings: List<Double>,
    val landingPoint: Vec3,
    var holdingPoint: Vec3,
    /**
     * Degrees of the carving turn onto final; 0 flies a plain pattern, or,
     * for a swooper that has given its turn up, a straight-in.
     */
    var swoopDegrees: Double,
    private val wobblePhase: DoubleArray,
    /** Students fly in a little brake much of the time. */
    private val brakeBias: Double,
) {
    sealed class Phase {
        /** Circling near the holding point until down to the pattern. */
        data object Holding : Phase()
        /**
         * Flying the legs, each towards waypoint [leg] + 1; a swooper's last
         * leg but one is the run-in to its set-up point, where the carving
         * turn starts.
         */
        data class Pattern(val leg: Int) : Phase()
        /** A swooper's carving turn onto final, diving on the inside front riser. */
        data object SwoopTurn : Phase()
        /** The dive out of the turn, towards the landing point, until the ground is a second away. */
        data object SwoopFinal : Phase()
        /** Levelled off on the brakes a hand's breadth up, skimming the ground into the wind until the speed is gone. */
        data object PlaneOut : Phase()
        data object Landed : Phase()
    }

    /** The height lost on the way to a point, with the way's heading and length. */
    private class LegLoss(val loss: Double, val heading: Double, val distance: Double)

    /** A place to join the pattern. */
    private class Join(val leg: Int, val point: Vec3, val heading: Double, val distance: Double, val excess: Double, val corner: Double)

    /** The legs back from a set-up point, with the heading of each. */
    class BuiltPattern(val waypoints: List<Vec3>, val headings: List<Double>)

    val inputs = BrakeInputs().apply { handSpeed = 1.5 }
    var phase: Phase = Phase.Holding
        private set
    /** The height down to which the canopy circles near the holding point before it joins the pattern. */
    var holdAltitude = 0.0
    /** When a swooper last tried to join from the holding. */
    private var lastJoinTry = -10.0
    private var swoopTurned = 0.0
    private var lastHeading = state.heading
    private var evadeSeconds = 0.0
    private var evadeDirection = 0.0

    val isStudent get() = performance.type == CanopyType.STUDENT
    /** Committed to the carving turn, the dive or the plane-out: no wobble and no evasion. */
    val isInSwoop get() = phase == Phase.SwoopTurn || phase == Phase.SwoopFinal || phase == Phase.PlaneOut
    val span get() = performance.span
    val isFlying get() = phase != Phase.Landed
    val canopyCentre get() = state.canopyCentre

    val appearance get() = TrafficAppearance(id, performance.areaSquareFeet, colorIndex, isStudent)

    val frame get() = TrafficFrame(state.position, state.heading, state.bank, state.pitch, inputs.left, inputs.right, phase == Phase.Landed)

    /** Whether the ground is close enough, at the sink, to level off now. */
    val planeOutIsDue get() = state.position.y - 1 + state.velocity.y * PLANE_OUT_LOOK_AHEAD <= PLANE_OUT_HEIGHT

    /** A step of the flight, with the toggles and the risers moving towards where the last [control] put them. */
    fun advance(dt: Double, wind: Vec3) {
        if (phase == Phase.Landed) return
        inputs.update(dt)
        state.step(dt, inputs.left, inputs.right, aero, wind, isStudent, leftRiser = inputs.leftRiser, rightRiser = inputs.rightRiser)
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
     * Decides where the toggles and the front risers go, ten times a second:
     * steering towards the next waypoint with some wobble, a bit of brake
     * for a student, the carving turn, the plane-out or the flare, and an
     * evasive turn when another canopy is close.
     */
    fun control(wind: Vec3) {
        val t = state.time
        val p = state.position
        val altitude = p.y - 1
        // +1 is the right toggle down, −1 the left.
        var steer = 0.0
        var brake = 0.0
        var riserLeft = 0.0
        var riserRight = 0.0
        var wobbles = true
        when (val current = phase) {
            Phase.Landed -> return
            Phase.Holding -> {
                if (altitude <= holdAltitude) {
                    if (isSwooper) join(wind) else phase = Phase.Pattern(legIndex(altitude))
                    control(wind)
                    return
                }
                // Nearly down to it: a swooper tries the join once a second,
                // in case the height already fits from here.
                if (isSwooper && altitude <= holdAltitude + 20 && t - lastJoinTry >= 1) {
                    lastJoinTry = t
                    if (intercept(wind) && phase != Phase.Holding) {
                        control(wind)
                        return
                    }
                }
                val tx = holdingPoint.x - p.x; val tz = holdingPoint.z - p.z
                steer = if (hypot(tx, tz) > 80) steerTowards(Compass.heading(tx, tz)) else if (hand == PatternHand.LEFT) -0.4 else 0.4
            }
            is Phase.Pattern -> {
                val leg = current.leg
                if (isSwooper) wobbles = false
                val goal = waypoints[leg + 1]
                val tx = goal.x - p.x; val tz = goal.z - p.z
                val distance = hypot(tx, tz)
                val legDirection = Compass.direction(legHeadings[leg])
                val passed = tx * legDirection.x + tz * legDirection.z < 0
                val count = waypoints.size
                val last = leg + 2 >= count
                val runIn = isSwooper && !last && leg + 3 >= count
                val intoRunIn = isSwooper && leg + 4 == count
                if (runIn) {
                    // The run-in to the set-up point: the turn starts as soon
                    // as the canopy is down to the planned height, there or
                    // past it, so that arriving low never puts the turn
                    // lower; well short of the height, the turn is given up
                    // for a straight-in.
                    if (planeOutIsDue) {
                        phase = Phase.PlaneOut
                        control(wind)
                        return
                    }
                    if (altitude <= goal.y) {
                        if (altitude < goal.y - SHORTFALL) swoopDegrees = 0.0
                        phase = Phase.SwoopTurn
                        swoopTurned = 0.0
                        control(wind)
                        return
                    }
                    steer = if (altitude <= goal.y + ALIGN_HEIGHT) {
                        // Nearly down to the turn: straight on its heading.
                        steerTowards(legHeadings[leg])
                    } else if (passed || distance < 10) {
                        // Past the set-up point and still high: on along the line.
                        steerAlong(tx + 300 * legDirection.x, tz + 300 * legDirection.z, wind)
                    } else {
                        steerAlong(tx, tz, wind)
                    }
                } else {
                    if (!last && (distance < 25 || passed)) {
                        if (isSwooper && !intoRunIn && altitude > goal.y + HIGH_MARGIN) {
                            // High at the corner: the leg goes on until the
                            // canopy is down to the corner's height.
                        } else if (isSwooper && altitude < goal.y - LOW_MARGIN && cut(wind)) {
                            control(wind)
                            return
                        } else {
                            phase = Phase.Pattern(leg + 1)
                            control(wind)
                            return
                        }
                    }
                    if (isSwooper) {
                        steer = if (passed || distance < 10) steerTowards(legHeadings[leg]) else steerAlong(tx, tz, wind)
                    } else if (distance > 8) {
                        steer = steerTowards(Compass.heading(tx, tz))
                    }
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
            }
            Phase.SwoopTurn -> {
                if (swoopTurned >= swoopDegrees * DEG) {
                    phase = Phase.SwoopFinal
                    control(wind)
                    return
                }
                if (planeOutIsDue) {
                    // Too low to finish the turn: level off and land as is.
                    phase = Phase.PlaneOut
                    control(wind)
                    return
                }
                // The inside front riser, toggles up.
                val pull = riserPull(swoopDegrees)
                if (hand == PatternHand.LEFT) riserLeft = pull else riserRight = pull
                wobbles = false
            }
            Phase.SwoopFinal -> {
                if (planeOutIsDue) {
                    phase = Phase.PlaneOut
                    control(wind)
                    return
                }
                // The dive: toggles up, a nudge towards the landing point.
                val tx = landingPoint.x - p.x; val tz = landingPoint.z - p.z
                if (hypot(tx, tz) > 10) steer = steerTowards(Compass.heading(tx, tz)).clamp(-0.3, 0.3)
                wobbles = false
            }
            Phase.PlaneOut -> {
                brake = planeOutBrake(state, aero, wind)
                steer = steerTowards(legHeadings[legHeadings.size - 1]).clamp(-0.25, 0.25)
                wobbles = false
            }
        }
        if (wobbles) {
            steer += 0.15 * sin(0.4 * t + wobblePhase[0]) * sin(0.9 * t + wobblePhase[1])
            brake = max(brake, brakeBias * (0.5 + 0.5 * sin(0.3 * t + wobblePhase[2])))
        }
        if (evadeSeconds > 0 && !isInSwoop) {
            steer = 0.6 * evadeDirection
            brake = max(brake, 0.2)
        }
        steer = steer.clamp(-1.0, 1.0)
        brake = brake.clamp(0.0, 1.0)
        inputs.targetLeft = min(1.0, brake + max(0.0, -steer) * (1 - brake))
        inputs.targetRight = min(1.0, brake + max(0.0, steer) * (1 - brake))
        inputs.targetLeftRiser = riserLeft
        inputs.targetRightRiser = riserRight
    }

    // MARK: - A swooper's way onto its pattern

    /**
     * The height, metres, lost for each metre over the ground at trim on
     * [heading]; a leg into a wind that all but stops the canopy counts as
     * impossible.
     */
    private fun lossPerMetre(heading: Double, wind: Vec3): Double {
        val groundSpeed = PatternGeometry.groundSpeed(heading, aero, wind)
        return if (groundSpeed >= 3) aero.trimSinkRate / groundSpeed else 1e9
    }

    /**
     * The height a straight leg from [a] to [b] costs at trim, less the bit
     * of it the corner at its end cuts off, with the leg's heading and length.
     */
    private fun legLoss(a: Vec3, b: Vec3, fallbackHeading: Double, wind: Vec3): LegLoss {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val distance = hypot(dx, dz)
        val heading = if (distance > 1e-6) Compass.heading(dx, dz) else fallbackHeading
        return LegLoss(max(0.0, distance - CORNER_CUT) * lossPerMetre(heading, wind), heading, distance)
    }

    /** The heading that tracks straight towards ([tx], [tz]) over the ground at trim, the nose into the crosswind. */
    private fun crabHeading(tx: Double, tz: Double, wind: Vec3): Double {
        val distance = hypot(tx, tz)
        if (distance <= 1e-6) return state.heading
        val dx = tx / distance
        val dz = tz / distance
        val along = wind.x * dx + wind.z * dz
        val acrossX = wind.x - along * dx
        val acrossZ = wind.z - along * dz
        val v = aero.trimForwardSpeed
        val forward = sqrt(max(0.09, v * v - (acrossX * acrossX + acrossZ * acrossZ)))
        return Compass.heading(forward * dx - acrossX, forward * dz - acrossZ)
    }

    private fun steerAlong(tx: Double, tz: Double, wind: Vec3) = steerTowards(crabHeading(tx, tz, wind))

    /**
     * Joins the pattern where the height fits: the first point along the
     * legs down to the set-up point that the canopy reaches, at trim, with
     * the turn onto the way there and the corner onto the leg paid for, at
     * or above the leg's height there; a leg that could only be joined by
     * turning back is passed over when a later one will do. On the run-in
     * leg the point leaves room to settle before the turn. Well above the
     * join's height, the canopy circles here first. Fails when the canopy
     * is too low for all of them.
     */
    private fun intercept(wind: Vec3): Boolean {
        val p = state.position
        val altitude = p.y - 1
        val path = waypoints.dropLast(1)
        val heads = legHeadings.dropLast(1)
        val landing = waypoints[waypoints.size - 1]
        val finalHeading = legHeadings[legHeadings.size - 1]
        if (path.size < 2) return false
        var best: Join? = null
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val legHeading = heads[i]
            val length = hypot(b.x - a.x, b.z - a.z)
            val runInLeg = i == path.size - 2
            val altitudeAfterA = a.y - (if (i > 0) turnCost(heads[i] - heads[i - 1]) else 0.0)
            val samples = max(1, (length / 10).toInt())
            for (k in 0..samples) {
                val f = k.toDouble() / samples
                val q = Vec3(a.x + (b.x - a.x) * f, altitudeAfterA * (1 - f) + b.y * f, a.z + (b.z - a.z) * f)
                val remaining = length * (1 - f)
                val way = legLoss(p, q, legHeading, wind)
                val heading = if (way.distance < 1e-6) state.heading else way.heading
                val corner = abs(wrap(legHeading - heading))
                if (runInLeg && remaining < MINIMUM_RUN_IN + RUN_IN_PER_HALF_TURN * corner / PI) break
                val loss = way.loss + turnCost(heading - state.heading) + turnCost(corner)
                val excess = altitude - loss - q.y
                if (excess < 0) continue
                if (corner <= REVERSAL) {
                    best = Join(i, q, heading, way.distance, excess, corner)
                } else if (best == null) {
                    best = Join(i, q, heading, way.distance, excess, corner)
                }
                break
            }
            val found = best
            if (found != null && found.leg == i && found.corner <= REVERSAL) break
        }
        val found = best ?: return false
        if (found.excess > HIGH_JOIN) {
            holdingPoint = Vec3(p.x, 0.0, p.z)
            holdAltitude = altitude - found.excess + 5
            phase = Phase.Holding
            return true
        }
        val here = Vec3(p.x, altitude, p.z)
        val rest = path.subList(found.leg + 1, path.size)
        val restHeadings = heads.subList(found.leg, heads.size)
        if (found.distance < 30) {
            // On the pattern already: this leg from here.
            waypoints = listOf(here) + rest + landing
            legHeadings = restHeadings + finalHeading
        } else {
            val arrival = Vec3(found.point.x, found.point.y + turnCost(found.corner), found.point.z)
            waypoints = listOf(here, arrival) + rest + landing
            legHeadings = listOf(found.heading) + restHeadings + finalHeading
        }
        phase = Phase.Pattern(0)
        return true
    }

    /**
     * Low for the pattern: the shortest way onto the run-in line that still
     * uses up the height, a direct leg to a point behind the set-up point
     * and the run-in from there, the run-in long enough to settle after the
     * corner. With [holdIfHigh], when even the longest run-in leaves the
     * canopy too high, it circles here until the height fits. Fails when
     * even the shortest is too far.
     */
    private fun cut(wind: Vec3, holdIfHigh: Boolean = false): Boolean {
        val p = state.position
        val altitude = p.y - 1
        val setup = waypoints[waypoints.size - 2]
        val landing = waypoints[waypoints.size - 1]
        val runInHeading = legHeadings[legHeadings.size - 2]
        val finalHeading = legHeadings[legHeadings.size - 1]
        val runInDirection = Compass.direction(runInHeading)
        val behind = -((p.x - setup.x) * runInDirection.x + (p.z - setup.z) * runInDirection.z)
        val budget = altitude - setup.y
        val runInLossPerMetre = lossPerMetre(runInHeading, wind)
        val heading = state.heading
        class Direct(val total: Double, val point: Vec3, val heading: Double, val runInLoss: Double)
        fun direct(length: Double): Direct {
            val r = Vec3(setup.x - length * runInDirection.x, 0.0, setup.z - length * runInDirection.z)
            val approach = legLoss(p, r, runInHeading, wind)
            val runInLoss = length * runInLossPerMetre
            val total = approach.loss + runInLoss + turnCost(approach.heading - heading) + turnCost(runInHeading - approach.heading)
            return Direct(total, r, approach.heading, runInLoss)
        }
        val shortest = MINIMUM_RUN_IN + RUN_IN_PER_HALF_TURN * abs(wrap(runInHeading - direct(MINIMUM_RUN_IN).heading)) / PI
        if (direct(shortest).total > budget) return false
        val longest = max(shortest, behind) + DIRECT_EXTRA
        var low = shortest
        var high = longest
        if (direct(longest).total >= budget) {
            repeat(14) {
                val mid = 0.5 * (low + high)
                if (direct(mid).total > budget) high = mid else low = mid
            }
        } else if (holdIfHigh) {
            holdingPoint = Vec3(p.x, 0.0, p.z)
            holdAltitude = setup.y + direct(longest).total - 10
            phase = Phase.Holding
            return true
        } else {
            low = longest
        }
        val chosen = direct(low)
        val runInStart = Vec3(chosen.point.x, setup.y + chosen.runInLoss + turnCost(runInHeading - chosen.heading), chosen.point.z)
        waypoints = listOf(Vec3(p.x, altitude, p.z), runInStart, setup, landing)
        legHeadings = listOf(chosen.heading, runInHeading, finalHeading)
        phase = Phase.Pattern(0)
        return true
    }

    /**
     * No way onto the pattern from here: the turn is given up for a plain
     * pattern onto a straight-in final, joined where the height fits or by
     * a cut, or failing that the canopy just heads for the landing point.
     */
    private fun giveUp(wind: Vec3) {
        val p = state.position
        val altitude = p.y - 1
        val landing = waypoints[waypoints.size - 1]
        val finalHeading = legHeadings[legHeadings.size - 1]
        if (swoopDegrees > 0) {
            swoopDegrees = 0.0
            val finalLoss = STRAIGHT_IN * lossPerMetre(finalHeading, wind)
            if (finalLoss < 1e6) {
                val sign = if (hand == PatternHand.RIGHT) -1.0 else 1.0
                val baseHeading = finalHeading + sign * PI / 2
                val built = pattern(
                    Vec3(landing.x, PLANE_OUT_HEIGHT, landing.z), finalHeading, baseHeading, baseHeading + sign * PI / 2,
                    finalLoss, 90.0, 90.0, aero, wind,
                )
                waypoints = built.waypoints + landing
                legHeadings = built.headings + finalHeading
                if (intercept(wind)) return
                if (cut(wind)) return
            }
        }
        waypoints = listOf(Vec3(p.x, altitude, p.z), landing, landing)
        legHeadings = listOf(Compass.heading(landing.x - p.x, landing.z - p.z), finalHeading)
        phase = Phase.Pattern(0)
    }

    /** Down to the pattern from the holding: onto it where the height fits, else by the shortest way that uses the height, else without the turn. */
    private fun join(wind: Vec3) {
        if (intercept(wind)) return
        if (cut(wind, holdIfHigh = true)) return
        giveUp(wind)
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

    companion object {
        /** The height, metres, a swooper levels off at and holds through the plane-out. */
        const val PLANE_OUT_HEIGHT = 0.7
        /** A swooper levels off once, at its sink, it would be down to the plane-out height within this many seconds. */
        const val PLANE_OUT_LOOK_AHEAD = 1.0
        /** The shortest run-in to the set-up point, metres, so that the canopy is settled on heading when the turn starts… */
        const val MINIMUM_RUN_IN = 60.0
        /** …and the extra for each half-turn of corner onto the run-in. */
        const val RUN_IN_PER_HALF_TURN = 80.0
        /** How much further back than the canopy a cut's run-in may start. */
        const val DIRECT_EXTRA = 120.0
        /** Above a corner's height by this much, a swooper flies the leg on. */
        const val HIGH_MARGIN = 3.0
        /** Below a corner's height by this much, a swooper looks for a cut. */
        const val LOW_MARGIN = 10.0
        /** Below the planned turn height by this much at the trigger, the turn is given up for a straight-in. */
        const val SHORTFALL = 8.0
        /** The length of a straight-in final, metres. */
        const val STRAIGHT_IN = 300.0
        /** Metres of a leg a corner cuts short. */
        const val CORNER_CUT = 25.0
        /** A corner onto a leg sharper than this is turning back: the join goes to a later leg when one will do. */
        val REVERSAL = 110 * DEG
        /** Above a join's height by more than this, a swooper circles first. */
        const val HIGH_JOIN = 15.0
        /** Height above the plane-out height the swoop's natural arc is planned to bottom out at, for the planner's highest start. */
        const val ARC_MARGIN = 1.5
        /** The sink, m/s, a planned swoop may touch down with. */
        const val SOFT_LANDING = 1.5
        /** Within this height above the turn height the run-in holds the turn's heading rather than the crab, so the turn starts straight. */
        const val ALIGN_HEIGHT = 15.0

        /** How much front riser the turn is flown on: a big turn takes the full pull, a smaller one carves on a little less. */
        fun riserPull(degrees: Double) = if (degrees >= 270) 1.0 else 0.85

        /**
         * The brake that holds the canopy at the plane-out height: the lift
         * coefficient level flight needs at this airspeed over what the
         * canopy has at trim, in brake strokes, trimmed for the height and
         * the sink. Once the speed is gone the brakes are full and the
         * canopy settles onto the ground.
         */
        fun planeOutBrake(state: FlightState, aero: AeroParameters, wind: Vec3): Double {
            val ax = state.velocity.x - wind.x
            val ay = state.velocity.y
            val az = state.velocity.z - wind.z
            val airspeed = max(1.0, sqrt(ax * ax + ay * ay + az * az))
            val dynamicPressure = 0.5 * aero.airDensity * airspeed * airspeed * aero.wingArea
            val needed = aero.mass * aero.gravity / dynamicPressure
            val feedforward = (needed - aero.liftCoefficient0) / max(0.01, aero.liftCoefficientPerBrake)
            val altitude = state.position.y - 1
            val correction = 0.3 * (PLANE_OUT_HEIGHT - altitude) - 0.3 * state.velocity.y
            return (feedforward + correction).clamp(0.0, 1.0)
        }

        /**
         * The height, metres, a full-toggle turn through [change] costs on
         * top of the glide, as the flight model flies it: steep in the bank,
         * so growing with the square of the angle up to about 115°.
         */
        fun turnCost(change: Double): Double {
            val angle = abs(wrap(change))
            return 3.6 * angle * min(angle, 2.0)
        }

        /**
         * The legs back from a set-up point: the run-in, the leg before it
         * and the entry leg, each losing its drop at trim, with each corner
         * at the height the leg to it arrives at: the drop, less what the
         * corner cuts off, plus what the turn at the corner costs.
         */
        fun pattern(
            setup: Vec3,
            runInHeading: Double,
            baseHeading: Double,
            downwindHeading: Double,
            runInDrop: Double,
            baseDrop: Double,
            downwindDrop: Double,
            aero: AeroParameters,
            wind: Vec3,
        ): BuiltPattern {
            fun back(point: Vec3, loss: Double, heading: Double): Vec3 {
                val length = PatternGeometry.legLength(loss, heading, aero, wind)
                val direction = Compass.direction(heading)
                return Vec3(point.x - length * direction.x, 0.0, point.z - length * direction.z)
            }
            fun lossPerMetre(heading: Double): Double {
                val groundSpeed = PatternGeometry.groundSpeed(heading, aero, wind)
                return if (groundSpeed >= 3) aero.trimSinkRate / groundSpeed else 1e9
            }
            val runInStart = back(setup, runInDrop, runInHeading)
            val baseStart = back(runInStart, baseDrop, baseHeading)
            val entry = back(baseStart, downwindDrop, downwindHeading)
            val runInAltitude = setup.y + runInDrop + turnCost(runInHeading - baseHeading)
            val baseAltitude = runInAltitude + max(0.0, baseDrop - CORNER_CUT * lossPerMetre(baseHeading)) + turnCost(baseHeading - downwindHeading)
            val entryAltitude = baseAltitude + max(0.0, downwindDrop - CORNER_CUT * lossPerMetre(downwindHeading))
            return BuiltPattern(
                listOf(
                    Vec3(entry.x, entryAltitude, entry.z),
                    Vec3(baseStart.x, baseAltitude, baseStart.z),
                    Vec3(runInStart.x, runInAltitude, runInStart.z),
                    setup,
                ),
                listOf(downwindHeading, baseHeading, runInHeading),
            )
        }

        /**
         * A canopy at the start of its carving turn, at the origin and at
         * trim, with the landing point far ahead on final: for planning the
         * turn on its own.
         */
        fun swoopProbe(
            degrees: Double,
            hand: PatternHand,
            startHeading: Double,
            finalHeading: Double,
            altitude: Double,
            performance: CanopyPerformance,
            aero: AeroParameters,
            wind: Vec3,
        ): TrafficCanopy {
            val start = Vec3(0.0, altitude + 1, 0.0)
            val far = start + Compass.direction(finalHeading) * 2000.0
            val probe = TrafficCanopy(
                id = 0,
                performance = performance,
                aero = aero,
                colorIndex = 0,
                hand = hand,
                isSwooper = true,
                state = FlightState(start, startHeading, aero, wind),
                waypoints = listOf(start, far),
                legHeadings = listOf(finalHeading),
                landingPoint = Vec3(far.x, 0.0, far.z),
                holdingPoint = start,
                swoopDegrees = degrees,
                wobblePhase = DoubleArray(3),
                brakeBias = 0.0,
            )
            probe.phase = Phase.SwoopTurn
            return probe
        }
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
                val isSwooper = type == CanopyType.PERFORMANCE || type == CanopyType.CROSS_BRACED
                val swoopDegrees = when (type) {
                    CanopyType.PERFORMANCE -> listOf(90.0, 180.0).random()
                    CanopyType.CROSS_BRACED -> listOf(90.0, 180.0, 270.0).random()
                    else -> 0.0
                }
                val landingPoint = SimConstants.TARGET + randomOffset(if (isSwooper) 30.0 else 50.0)

                val waypoints: List<Vec3>
                val legHeadings: List<Double>
                if (isSwooper) {
                    // The turn's height and place come first; the corners
                    // before it are worked back from there.
                    val plan = planSwoop(swoopDegrees, hand, finalHeading, performance, aero, wind)
                    val setup = Vec3(landingPoint.x - plan.offset.x, plan.turnAltitude, landingPoint.z - plan.offset.z)
                    val sign = if (hand == PatternHand.RIGHT) -1.0 else 1.0
                    val runInHeading = plan.startHeading
                    // The leg before the run-in lies on whichever side the
                    // corner onto the run-in can be made from.
                    val baseHeading = if (swoopDegrees <= 180) runInHeading + sign * PI / 2 else runInHeading - sign * PI / 2
                    val built = TrafficCanopy.pattern(
                        setup, runInHeading, baseHeading, baseHeading + sign * PI / 2, 60.0, 90.0, 90.0, aero, wind,
                    )
                    waypoints = built.waypoints + landingPoint
                    legHeadings = built.headings + finalHeading
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
                // 150 m before the entry, on the first leg.
                val holdingPoint = waypoints[0] - Compass.direction(legHeadings[0]) * 150.0

                val bearing = Random.nextDouble(0.0, 2 * PI)
                val distance = spawn.horizontalRange.random()
                var altitude = spawn.altitudeRange.random()
                if (groupMember) altitude = students!!.altitude + Random.nextDouble(-10.0, 10.0)
                // A swooper sets up high: above the corner onto its run-in.
                if (isSwooper) altitude = max(altitude, waypoints[2].y + Random.nextDouble(20.0, 150.0))
                altitude = max(20.0, altitude)
                val position = Vec3(spawn.centre.x + distance * sin(bearing), altitude + 1, spawn.centre.z - distance * cos(bearing))
                val heading = Compass.heading(holdingPoint.x - position.x, holdingPoint.z - position.z)
                val canopy = TrafficCanopy(
                    id = index + 1,
                    performance = performance,
                    aero = aero,
                    colorIndex = index,
                    hand = hand,
                    isSwooper = isSwooper,
                    state = FlightState(position, heading, aero, wind),
                    waypoints = waypoints,
                    legHeadings = legHeadings,
                    landingPoint = landingPoint,
                    holdingPoint = holdingPoint,
                    swoopDegrees = swoopDegrees,
                    wobblePhase = DoubleArray(3) { Random.nextDouble(0.0, 2 * PI) },
                    brakeBias = if (type == CanopyType.STUDENT) Random.nextDouble(0.08, 0.25) else Random.nextDouble(0.0, 0.08),
                )
                canopy.holdAltitude = waypoints[0].y + 15
                canopies += canopy
            }
            return TrafficSimulation(canopies)
        }

        class SwoopPlan(val turnAltitude: Double, val offset: Vec3, val startHeading: Double)

        /**
         * Where a swooper starts its front-riser turn, found by flying it on
         * the flight model. From high up, the turn and the dive out of it
         * show how much height the canopy loses to its fastest point and to
         * the bottom of its recovery arc. Starts between the two are then
         * flown down to the touchdown, planing out on the brakes, and the
         * one that skims the ground the furthest while still landing softly
         * is kept, with how far the canopy travels over the ground from the
         * start of the turn to the touchdown, into the wind.
         */
        fun planSwoop(
            degrees: Double,
            hand: PatternHand,
            finalHeading: Double,
            performance: CanopyPerformance,
            aero: AeroParameters,
            wind: Vec3,
        ): SwoopPlan {
            val sign = if (hand == PatternHand.RIGHT) -1.0 else 1.0
            val startHeading = finalHeading + sign * degrees * DEG
            val dt = STEP_INTERVAL

            fun probe(altitude: Double) = TrafficCanopy.swoopProbe(degrees, hand, startHeading, finalHeading, altitude, performance, aero, wind)

            /** Flies the canopy as the simulation would, controls every control interval, until [done] or it lands. */
            fun fly(canopy: TrafficCanopy, done: (TrafficCanopy) -> Boolean) {
                var clock = CONTROL_INTERVAL
                while (canopy.isFlying && canopy.state.time < 60 && !done(canopy)) {
                    clock += dt
                    if (clock >= CONTROL_INTERVAL) {
                        clock -= CONTROL_INTERVAL
                        canopy.control(wind)
                    }
                    canopy.advance(dt, wind)
                }
            }

            // The natural arc from high up: the turn, then ten seconds of the
            // dive and the recovery.
            val high = 400.0
            val arc = probe(high)
            var fastest = 0.0
            var lossAtFastest = 0.0
            var easiestSink = Double.NEGATIVE_INFINITY
            var lossAtBottom = 0.0
            var turnEnd: Double? = null
            fly(arc) { canopy ->
                if (canopy.phase == TrafficCanopy.Phase.SwoopTurn) {
                    false
                } else {
                    if (turnEnd == null) turnEnd = canopy.state.time
                    val loss = high - (canopy.state.position.y - 1)
                    val airspeed = hypot(canopy.state.velocity.x - wind.x, canopy.state.velocity.z - wind.z)
                    if (airspeed > fastest) {
                        fastest = airspeed
                        lossAtFastest = loss
                    }
                    if (canopy.state.velocity.y > easiestSink) {
                        easiestSink = canopy.state.velocity.y
                        lossAtBottom = loss
                    }
                    canopy.state.time - turnEnd!! > 10
                }
            }
            if (turnEnd == null) return SwoopPlan(140 * degrees / 90, Vec3.ZERO, startHeading)
            val lowest = lossAtFastest
            val highest = max(lossAtBottom, lossAtFastest) + TrafficCanopy.ARC_MARGIN

            // Starts between the fastest point and the bottom of the arc at
            // the plane-out height, flown to the touchdown.
            var best: SwoopPlan? = null
            var bestSkim = 0.0
            var fallback: SwoopPlan? = null
            for (fraction in doubleArrayOf(1.0, 0.85, 0.7, 0.5)) {
                val turnAltitude = lowest + (highest - lowest) * fraction
                val swoop = probe(turnAltitude)
                var skimStart: Vec3? = null
                fly(swoop) { canopy ->
                    if (skimStart == null && canopy.state.position.y - 1 < 2) skimStart = canopy.state.position
                    false
                }
                val end = swoop.state.position
                val offset = Vec3(end.x, 0.0, end.z)
                if (fallback == null) fallback = SwoopPlan(turnAltitude, offset, startHeading)
                if (swoop.phase != TrafficCanopy.Phase.Landed || -swoop.state.velocity.y > TrafficCanopy.SOFT_LANDING) continue
                val skimFrom = skimStart
                val skim = if (skimFrom != null) hypot(end.x - skimFrom.x, end.z - skimFrom.z) else 0.0
                if (best == null || skim > bestSkim) {
                    best = SwoopPlan(turnAltitude, offset, startHeading)
                    bestSkim = skim
                }
            }
            return best ?: fallback ?: SwoopPlan(highest, Vec3.ZERO, startHeading)
        }
    }
}
