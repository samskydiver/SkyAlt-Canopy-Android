package com.skyalt.canopy.tools.planning

import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class LandingPatternMode(val titleKey: String) { NORMAL("Standard Mode"), EXPERT("Swoop Mode") }

enum class SkillLevel(val titleKey: String, val airspeedKnots: Double, val glideRatio: Double) {
    STUDENT("Student", 17.0, 2.5), EXPERIENCED("Experienced", 25.0, 2.2), EXPERT("Expert", 35.0, 1.9)
}

enum class CheckpointKind(val titleKey: String) { DOWNWIND("Downwind start"), BASE("Base start"), FINAL("Final start"), LANDING("Landing") }

data class LandingPatternCheckpoint(val kind: CheckpointKind, val coordinate: Coordinate, val altitudeAGLFeet: Double)

enum class PlanningIssue(val messageKey: String) {
    WIND_REQUIRED("Wind data is required to calculate the landing pattern."),
    FINAL_LEG_UNFLYABLE("The final approach cannot maintain forward progress in the current wind."),
    BASE_LEG_UNFLYABLE("The base leg cannot maintain its selected ground track in the current wind."),
    DOWNWIND_LEG_UNFLYABLE("The downwind leg cannot maintain its selected ground track in the current wind."),
    WRONG_PATTERN_HAND("The selected points do not follow the chosen left- or right-hand pattern."),
}

data class LandingPatternSolution(val checkpoints: List<LandingPatternCheckpoint>, val issues: List<PlanningIssue>) {
    val path get() = checkpoints.map { it.coordinate }
    fun checkpoint(kind: CheckpointKind) = checkpoints.firstOrNull { it.kind == kind }
}

data class SwoopWaypoint(val id: String = UUID.randomUUID().toString(), val coordinate: Coordinate)

data class SwoopPatternSolution(val altitudeByWaypointId: Map<String, Double>, val uncalculatedWaypointIds: Set<String>) {
    fun altitudeFeet(id: String) = altitudeByWaypointId[id]
}

enum class PatternDirection(val titleKey: String, val baseHeadingOffsetDegrees: Double) {
    LEFT("Left", 90.0), RIGHT("Right", -90.0)
}

/** Port of LandingPatternPlanningEngine. */
object LandingPatternPlanningEngine {
    private data class Leg(val kind: CheckpointKind, val heading: Double, val issue: PlanningIssue)

    /** Solves the standard pattern backwards from the landing point, one leg per altitude step. */
    fun standard(
        landing: Coordinate,
        finalHeadingDegrees: Double,
        direction: PatternDirection,
        altitudeStepFeet: Double,
        airspeedKnots: Double,
        glideRatio: Double,
        profile: WindProfile,
    ): LandingPatternSolution {
        val step = max(altitudeStepFeet, 300.0) * WindMath.METERS_PER_FOOT
        val airspeed = max(airspeedKnots, 1.0) * WindMath.METERS_PER_SECOND_PER_KNOT
        val descent = airspeed / max(glideRatio, 0.1)
        val legDuration = step / max(descent, 0.1)
        val finalHeading = MapGeometry.normalizedBearing(finalHeadingDegrees)
        val legs = listOf(
            Leg(CheckpointKind.FINAL, finalHeading, PlanningIssue.FINAL_LEG_UNFLYABLE),
            Leg(CheckpointKind.BASE, MapGeometry.normalizedBearing(finalHeading + direction.baseHeadingOffsetDegrees), PlanningIssue.BASE_LEG_UNFLYABLE),
            Leg(CheckpointKind.DOWNWIND, MapGeometry.normalizedBearing(finalHeading + 180), PlanningIssue.DOWNWIND_LEG_UNFLYABLE),
        )
        val checkpoints = mutableListOf(LandingPatternCheckpoint(CheckpointKind.LANDING, landing, 0.0))
        var legEnd = landing
        legs.forEachIndexed { index, leg ->
            val bottom = step * index
            val track = WindMath.groundTrack(legDuration, airspeed, leg.heading, WindMath.averageWind(bottom, bottom + step, profile))
                ?: return LandingPatternSolution(checkpoints.reversed(), listOf(leg.issue))
            legEnd = MapGeometry.coordinate(legEnd, -track.displacement)
            checkpoints += LandingPatternCheckpoint(leg.kind, legEnd, altitudeStepFeet * (index + 1))
        }
        return LandingPatternSolution(checkpoints.reversed(), emptyList())
    }

    /** Estimated altitudes of user-placed swoop waypoints, given in reverse flight order (B, C, D…). */
    fun swoop(
        start: Coordinate,
        startAltitudeFeet: Double,
        reverseWaypoints: List<SwoopWaypoint>,
        airspeedKnots: Double,
        glideRatio: Double,
        profile: WindProfile,
    ): SwoopPatternSolution {
        var end = start
        var endAltitude = max(startAltitudeFeet, 1.0) * WindMath.METERS_PER_FOOT
        val airspeed = max(airspeedKnots, 1.0) * WindMath.METERS_PER_SECOND_PER_KNOT
        val descent = airspeed / max(glideRatio, 0.1)
        val altitudes = mutableMapOf<String, Double>()
        val uncalculated = mutableSetOf<String>()
        for ((index, waypoint) in reverseWaypoints.withIndex()) {
            val altitude = calculatedStartAltitude(waypoint.coordinate, end, endAltitude, airspeed, descent, profile)
            if (altitude == null) {
                uncalculated += reverseWaypoints.drop(index).map { it.id }
                break
            }
            altitudes[waypoint.id] = altitude / WindMath.METERS_PER_FOOT
            end = waypoint.coordinate
            endAltitude = altitude
        }
        return SwoopPatternSolution(altitudes, uncalculated)
    }

    private fun calculatedStartAltitude(start: Coordinate, end: Coordinate, endAltitude: Double, airspeed: Double, descent: Double, profile: WindProfile): Double? {
        val distance = MapGeometry.displacement(start, end).magnitude
        if (distance < 1) return endAltitude
        val heading = MapGeometry.bearingDegrees(start, end)
        var estimate = endAltitude + distance / max(airspeed, 0.1) * descent
        repeat(10) {
            val wind = WindMath.averageWind(endAltitude, estimate, profile)
            val ground = WindMath.groundSpeed(airspeed, heading, wind) ?: return null
            val next = endAltitude + distance / ground * descent
            if (abs(next - estimate) < 0.3) return next
            estimate = next
        }
        return estimate
    }
}

object ExitSpotPlanningModel {
    /** Altitude lost between pulling and flying a steerable canopy. */
    const val OPENING_BUFFER_METERS = 500.0 * WindMath.METERS_PER_FOOT
    /** Height at which the standard pattern is entered. */
    const val PATTERN_ENTRY_ALTITUDE_METERS = 900.0 * WindMath.METERS_PER_FOOT
    const val SAFETY_MARGIN_FRACTION = 0.05
    const val USABLE_PERFORMANCE_FRACTION = 1 - SAFETY_MARGIN_FRACTION
}

enum class ExperienceProfile(val titleKey: String, val skill: SkillLevel, val safetyAltitudeMarginFeet: Double) {
    STUDENT("Student", SkillLevel.STUDENT, 1_500.0),
    EXPERIENCED("Experienced", SkillLevel.EXPERIENCED, 1_000.0),
    EXPERT("Expert", SkillLevel.EXPERT, 1_000.0);

    val airspeedKnots get() = skill.airspeedKnots.roundToInt()
    val glideRatio get() = skill.glideRatio
    val defaultParameters get() = ExitSpotProfileParameters(this, airspeedKnots.toDouble(), glideRatio, safetyAltitudeMarginFeet)
}

data class ExitSpotProfileParameters(val experienceProfile: ExperienceProfile, val airspeedKnots: Double, val glideRatio: Double, val safetyAltitudeMarginFeet: Double) {
    companion object {
        val defaults get() = ExperienceProfile.entries.map { it.defaultParameters }
    }
}

data class ExitSpotReferenceSolution(
    val airspeedKnots: Int,
    val glideRatio: Double,
    val experienceProfile: ExperienceProfile,
    val patternEntryPoint: Coordinate?,
    val landingPatternPath: List<Coordinate>,
    val reachableCenter: Coordinate?,
    val reachableRadiusMeters: Double,
    val exitStart: Coordinate?,
    val exitEnd: Coordinate?,
    val passiveDriftPath: List<Coordinate>,
    val windDrift: LocalDisplacement,
) {
    val hasValidLandingPattern get() = patternEntryPoint != null
    val hasExitRange get() = exitStart != null && exitEnd != null

    companion object {
        fun unflyable(experience: ExperienceProfile, airspeedKnots: Double, glideRatio: Double) = ExitSpotReferenceSolution(
            airspeedKnots.roundToInt(), glideRatio, experience, null, emptyList(), null, 0.0, null, null, emptyList(), LocalDisplacement.ZERO,
        )
    }
}

data class ExitSpotSolution(val targetPoint: Coordinate, val referenceSolutions: List<ExitSpotReferenceSolution>) {
    val hasAnyExitRange get() = referenceSolutions.any { it.hasExitRange }
    val hasAnyValidLandingPattern get() = referenceSolutions.any { it.hasValidLandingPattern }
    val hasInvalidLandingPattern get() = referenceSolutions.any { !it.hasValidLandingPattern }
}

data class CutawaySolution(val centerPath: List<Coordinate>, val landingCenter: Coordinate, val landingRadiusMeters: Double)

/** Port of FlightPlanningEngine. */
object FlightPlanningEngine {
    /** Per experience profile, where along the jump run a jumper can exit and still reach the pattern entry. */
    fun exitSpot(
        landing: Coordinate,
        jumpRunStart: Coordinate,
        jumpRunEnd: Coordinate,
        exitAltitudeAGLMeters: Double,
        deploymentAltitudeAGLMeters: Double,
        profile: WindProfile,
        profileParameters: List<ExitSpotProfileParameters> = ExitSpotProfileParameters.defaults,
        finalApproachHeadingDegrees: Double? = null,
        landingPatternDirection: PatternDirection = PatternDirection.LEFT,
    ): ExitSpotSolution? {
        val opening = deploymentAltitudeAGLMeters - ExitSpotPlanningModel.OPENING_BUFFER_METERS
        val entryAltitude = ExitSpotPlanningModel.PATTERN_ENTRY_ALTITUDE_METERS
        if (exitAltitudeAGLMeters < opening || opening <= entryAltitude) return null
        // One mean wind for the whole column drives both the freefall and the canopy drift.
        val columnWind = WindMath.arithmeticMeanWind(exitAltitudeAGLMeters, 0.0, profile)
        val freefall = WindMath.constantIntegration(WindMath.freefallDuration(exitAltitudeAGLMeters, opening), columnWind)
        val finalBearing = MapGeometry.normalizedBearing(finalApproachHeadingDegrees ?: defaultFinalApproachBearing(profile, jumpRunStart, jumpRunEnd))

        val references = ExperienceProfile.entries.map { experience ->
            val p = profileParameters.firstOrNull { it.experienceProfile == experience } ?: experience.defaultParameters
            val glide = max(p.glideRatio, 0.1)
            val descent = max(p.airspeedKnots, 1.0) * WindMath.METERS_PER_SECOND_PER_KNOT / glide
            val pattern = LandingPatternPlanningEngine.standard(landing, finalBearing, landingPatternDirection, 300.0, p.airspeedKnots, glide, profile)
            val entry = pattern.checkpoint(CheckpointKind.DOWNWIND)?.coordinate
                ?: return@map ExitSpotReferenceSolution.unflyable(experience, p.airspeedKnots, glide)
            val required = entryAltitude + max(p.safetyAltitudeMarginFeet, 0.0) * WindMath.METERS_PER_FOOT
            val canopyAltitude = max(opening - required, 0.0)
            val canopy = WindMath.constantIntegration(canopyAltitude / descent, columnWind)
            val radius = canopyAltitude * glide * ExitSpotPlanningModel.USABLE_PERFORMANCE_FRACTION
            // Walk the drift back from the pattern entry.
            val centerDisplacement = -(canopy.displacement + freefall.displacement)
            val center = MapGeometry.coordinate(entry, centerDisplacement)
            val openingCenter = MapGeometry.coordinate(entry, -canopy.displacement)
            val hits = if (canopyAltitude > 0) exitRangeIntersections(entry, jumpRunStart, jumpRunEnd, centerDisplacement, radius) else null
            val freefallPath = freefall.cumulative.map { MapGeometry.coordinate(center, it) }
            val canopyPath = canopy.cumulative.drop(1).map { MapGeometry.coordinate(openingCenter, it) }
            ExitSpotReferenceSolution(
                p.airspeedKnots.roundToInt(), glide, experience, entry, pattern.path, center, radius,
                hits?.first, hits?.second, freefallPath + canopyPath, freefall.displacement + canopy.displacement,
            )
        }
        return ExitSpotSolution(landing, references)
    }

    /** Where a cutaway main lands: the drawn circle, carried by the wind column to the ground. */
    fun cutaway(center: Coordinate, radiusMeters: Double, cutawayAltitudeAGLMeters: Double, nominalDescentRateMetersPerSecond: Double, profile: WindProfile): CutawaySolution {
        val integration = WindMath.passiveIntegration(cutawayAltitudeAGLMeters, 0.0, nominalDescentRateMetersPerSecond, profile)
        return CutawaySolution(
            integration.cumulative.map { MapGeometry.coordinate(center, it) },
            MapGeometry.coordinate(center, integration.displacement),
            radiusMeters,
        )
    }

    /** Final faces the surface wind; if calm, the 150 ft wind; if calm too, the jump run heading. */
    private fun defaultFinalApproachBearing(profile: WindProfile, start: Coordinate, end: Coordinate): Double {
        val surface = profile.windAtAGL(0.0)
        val reference = if (surface.speedMetersPerSecond > 0.1) surface else profile.windAtAGL(150 * WindMath.METERS_PER_FOOT)
        if (reference.speedMetersPerSecond <= 0.1) return MapGeometry.bearingDegrees(start, end)
        return reference.towardBearingDegrees + 180
    }

    private fun exitRangeIntersections(origin: Coordinate, start: Coordinate, end: Coordinate, centerDisplacement: LocalDisplacement, radius: Double): Pair<Coordinate, Coordinate>? {
        val lineStart = MapGeometry.displacement(origin, start)
        val lineEnd = MapGeometry.displacement(origin, end)
        val line = lineEnd - lineStart
        val length = line.magnitude
        if (length <= 10) return null
        val unit = line.scaled(1 / length)
        val rel = lineStart - centerDisplacement
        val b = 2 * (rel.eastMeters * unit.eastMeters + rel.northMeters * unit.northMeters)
        val c = rel.eastMeters * rel.eastMeters + rel.northMeters * rel.northMeters - radius * radius
        val disc = b * b - 4 * c
        if (disc < 0) return null
        val root = sqrt(disc)
        return MapGeometry.coordinate(origin, lineStart + unit.scaled((-b - root) / 2)) to
            MapGeometry.coordinate(origin, lineStart + unit.scaled((-b + root) / 2))
    }
}
