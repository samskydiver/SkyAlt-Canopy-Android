package com.skyalt.canopy.model

import kotlin.math.roundToLong

/** Metres and m/s, or feet and mph, on the instruments and setup fields. */
enum class Units(val key: String) {
    METRIC("metric"),
    IMPERIAL("imperial");

    val speedSymbol get() = if (this == METRIC) "m/s" else "mph"
    val distanceSymbol get() = if (this == METRIC) "m" else "ft"
    val weightSymbol get() = if (this == METRIC) "kg" else "lb"

    fun weightValue(kg: Double) = if (this == METRIC) kg else kg * CanopyPerformance.POUNDS_PER_KILOGRAM
    fun kilograms(display: Double) = if (this == METRIC) display else display / CanopyPerformance.POUNDS_PER_KILOGRAM
    fun speedValue(mps: Double) = if (this == METRIC) mps else mps * MPH_PER_MPS
    fun metersPerSecond(display: Double) = if (this == METRIC) display else display / MPH_PER_MPS
    fun distanceValue(m: Double) = if (this == METRIC) m else m / METERS_PER_FOOT
    fun meters(display: Double) = if (this == METRIC) display else display * METERS_PER_FOOT

    /** One decimal. */
    fun speedText(mps: Double) = String.format(java.util.Locale.US, "%.1f", speedValue(mps))

    /** Whole units, with thousands grouping. */
    fun distanceText(m: Double) = groupedInt(distanceValue(m).roundToLong())

    companion object {
        const val MPH_PER_MPS = 2.23694
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: IMPERIAL
    }
}

fun groupedInt(value: Long): String = String.format(java.util.Locale.US, "%,d", value)

enum class PatternHand { LEFT, RIGHT }

/** The reference pattern drawn in the scene; it also decides which way the run starts. */
enum class ReferencePattern(val key: String) {
    OFF("off"), LEFT("left"), RIGHT("right");

    val hand: PatternHand?
        get() = when (this) {
            OFF -> null
            LEFT -> PatternHand.LEFT
            RIGHT -> PatternHand.RIGHT
        }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: LEFT
    }
}

/** Where the reference pattern's three legs begin, metres above the target. */
data class PatternAltitudes(val downwind: Double, val base: Double, val final: Double)

/** The setup panel. Altitude and run-in apply on reset; everything else applies live. */
data class SimulatorConfiguration(
    val startAltitudeMeters: Double = 300.0,
    /** Jumper plus gear, kilograms. */
    val exitWeightKilograms: Double = 80.0,
    /** Square feet, as canopies are sold. */
    val canopyAreaSquareFeet: Double = 230.0,
    val canopyType: CanopyType = CanopyType.STUDENT,
    /** Motion capture: how far 100% brake moves from 1.12 torso lengths, −0.1…+0.1. */
    val stallTolerance: Double = 0.0,
    val windSpeed: Double = 3.0,
    val windDirectionDegrees: Double = 0.0,
    val referencePattern: ReferencePattern = ReferencePattern.LEFT,
    val hand: PatternHand = PatternHand.LEFT,
    val runInMeters: Double = 300 * METERS_PER_FOOT,
    val downwindAltitudeMeters: Double = 900 * METERS_PER_FOOT,
    val baseAltitudeMeters: Double = 600 * METERS_PER_FOOT,
    val finalAltitudeMeters: Double = 300 * METERS_PER_FOOT,
    /** Metres east and south of the target; both null: the run-in before the pattern entry. */
    val startEastMeters: Double? = null,
    val startSouthMeters: Double? = null,
    val windsockEastMeters: Double = 30.0,
    val windsockSouthMeters: Double = 0.0,
) {
    val performance get() = CanopyPerformance(exitWeightKilograms, canopyAreaSquareFeet, canopyType)

    /** A student canopy's brake lines stop a full flare short of the stall. */
    val studentMode get() = canopyType == CanopyType.STUDENT

    val motionCaptureFullBrakeDepth get() = if (studentMode) 1.12 else 1.12 + stallTolerance

    val patternAltitudes get() = PatternAltitudes(downwindAltitudeMeters, baseAltitudeMeters, finalAltitudeMeters)

    val hasCustomStart get() = startEastMeters != null && startSouthMeters != null

    fun affectsLiveFlight(other: SimulatorConfiguration) =
        exitWeightKilograms != other.exitWeightKilograms ||
            canopyAreaSquareFeet != other.canopyAreaSquareFeet ||
            canopyType != other.canopyType ||
            windSpeed != other.windSpeed ||
            windDirectionDegrees != other.windDirectionDegrees ||
            referencePattern != other.referencePattern ||
            hand != other.hand ||
            downwindAltitudeMeters != other.downwindAltitudeMeters ||
            baseAltitudeMeters != other.baseAltitudeMeters ||
            finalAltitudeMeters != other.finalAltitudeMeters

    fun clamped(): SimulatorConfiguration {
        val final = finalAltitudeMeters.clamp(15.0, 1500.0)
        val base = maxOf(baseAltitudeMeters.clamp(15.0, 1500.0), final + PATTERN_GAP)
        val downwind = maxOf(downwindAltitudeMeters.clamp(15.0, 1500.0), base + PATTERN_GAP)
        val custom = startEastMeters != null && startSouthMeters != null
        return copy(
            startAltitudeMeters = startAltitudeMeters.clamp(50.0, 3000.0),
            exitWeightKilograms = exitWeightKilograms.clamp(40.0, 160.0),
            canopyAreaSquareFeet = canopyAreaSquareFeet.clamp(60.0, 400.0),
            stallTolerance = stallTolerance.clamp(-0.1, 0.1),
            windSpeed = windSpeed.clamp(0.0, 15.0),
            windDirectionDegrees = normalizedDegrees(Math.rint(windDirectionDegrees)),
            runInMeters = runInMeters.clamp(0.0, 2000.0),
            finalAltitudeMeters = final,
            baseAltitudeMeters = base,
            downwindAltitudeMeters = downwind,
            startEastMeters = if (custom) startEastMeters!!.clamp(-5000.0, 5000.0) else null,
            startSouthMeters = if (custom) startSouthMeters!!.clamp(-5000.0, 5000.0) else null,
            windsockEastMeters = windsockEastMeters.clamp(-2000.0, 2000.0),
            windsockSouthMeters = windsockSouthMeters.clamp(-2000.0, 2000.0),
            hand = referencePattern.hand ?: hand,
        )
    }

    companion object {
        const val PATTERN_GAP = 15.0
    }
}

fun normalizedDegrees(degrees: Double): Double {
    val d = degrees % 360.0
    return if (d < 0) d + 360.0 else d
}

/** How the dropzone picture sits under the target rings. */
data class DropzoneSettings(
    /** How many metres the picture spans east to west. */
    val widthMeters: Double = 400.0,
    val eastOffsetMeters: Double = 0.0,
    val southOffsetMeters: Double = 0.0,
    /** Counter-clockwise seen from above. */
    val rotationDegrees: Double = 0.0,
) {
    fun clamped(): DropzoneSettings {
        var rotation = normalizedDegrees(rotationDegrees)
        if (rotation > 180) rotation -= 360
        return copy(
            widthMeters = widthMeters.clamp(50.0, 3000.0),
            eastOffsetMeters = eastOffsetMeters.clamp(-1000.0, 1000.0),
            southOffsetMeters = southOffsetMeters.clamp(-1000.0, 1000.0),
            rotationDegrees = rotation,
        )
    }
}

/** The colour and thickness of one kind of line in the scene. */
data class LineStyle(val colorHex: Int, val opacity: Double, val widthMeters: Double) {
    fun clamped() = copy(widthMeters = widthMeters.clamp(0.1, 4.0), opacity = opacity.clamp(0.05, 1.0))
}

data class PathStyleSettings(
    val pattern: LineStyle = LineStyle(0x7b3fe4, 0.9, 0.6),
    val patternGround: LineStyle = LineStyle(0x7b3fe4, 0.9, 0.6),
    val trail: LineStyle = LineStyle(0xe4582b, 1.0, 0.4),
    val trailColoredBySink: Boolean = true,
    val history: LineStyle = LineStyle(0x3a4454, 0.5, 0.35),
) {
    fun clamped() = copy(
        pattern = pattern.clamped(),
        patternGround = patternGround.clamped(),
        trail = trail.clamped(),
        history = history.clamped(),
    )
}
