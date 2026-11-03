package com.skyalt.canopy.model

import kotlin.math.atan
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** The four canopy families the performance model tells apart. */
enum class CanopyType(val key: String, val titleKey: String) {
    STUDENT("student", "Student canopy"),
    SEVEN_CELL("sevenCellWingsuit", "7-cell"),
    PERFORMANCE("performance", "Performance"),
    CROSS_BRACED("crossBraced", "Cross-braced");

    /** First-pass estimates, to be calibrated against GPS logs. */
    val coefficients: CanopyTypeCoefficients
        get() = when (this) {
            STUDENT -> CanopyTypeCoefficients(20.0, 2.5, 2.2, 0.9, 45.0, 1.2, 2.4)
            SEVEN_CELL -> CanopyTypeCoefficients(19.0, 2.2, 1.9, 0.85, 50.0, 1.0, 2.2)
            PERFORMANCE -> CanopyTypeCoefficients(22.0, 2.7, 2.6, 1.0, 65.0, 0.8, 2.0)
            CROSS_BRACED -> CanopyTypeCoefficients(25.0, 3.2, 3.0, 1.1, 80.0, 0.6, 1.8)
        }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: STUDENT
    }
}

data class CanopyTypeCoefficients(
    /** Full-flight airspeed at a wing loading of 1.0 lb/ft² at sea level. */
    val airspeedAtUnitLoadingMph: Double,
    val glideRatio: Double,
    val aspectRatio: Double,
    /** κ in ω = κ·V/b. */
    val turnGain: Double,
    val maximumBankDegrees: Double,
    /** Bank response time for a 170 ft² canopy; scales with √area. */
    val responseSeconds: Double,
    /** Full-brake lift coefficient over the full-flight one (β). */
    val brakeLiftRatio: Double,
)

/**
 * Exit weight, canopy size and type turned into the numbers the flight
 * model needs (see the "翼伞性能数学模型" doc).
 */
data class CanopyPerformance(
    val exitWeightKilograms: Double,
    val areaSquareFeet: Double,
    val type: CanopyType,
) {
    val coefficients get() = type.coefficients

    /** Pounds per square foot. */
    val wingLoading get() = exitWeightKilograms * POUNDS_PER_KILOGRAM / maxOf(1.0, areaSquareFeet)

    val areaSquareMeters get() = areaSquareFeet * SQUARE_METERS_PER_SQUARE_FOOT

    /** Metres. */
    val span get() = sqrt(coefficients.aspectRatio * areaSquareMeters)

    /** Full-flight airspeed in m/s: V = V₁·√WL. */
    val airspeed get() = coefficients.airspeedAtUnitLoadingMph * sqrt(wingLoading) / Units.MPH_PER_MPS

    val glideRatio get() = coefficients.glideRatio

    /** Steady full-toggle turn rate, rad/s: ω = κ·V/b, capped by the bank limit. */
    val turnRate: Double
        get() {
            val free = coefficients.turnGain * airspeed / maxOf(1.0, span)
            val bank = atan(free * airspeed / STANDARD_GRAVITY)
            val limit = coefficients.maximumBankDegrees * DEG
            if (bank <= limit) return free
            return STANDARD_GRAVITY * tan(limit) / airspeed
        }

    val maximumBank get() = min(atan(turnRate * airspeed / STANDARD_GRAVITY), coefficients.maximumBankDegrees * DEG)

    val bankTimeConstant get() = coefficients.responseSeconds * sqrt(areaSquareFeet / 170)

    companion object {
        const val POUNDS_PER_KILOGRAM = 2.20462
        const val SQUARE_METERS_PER_SQUARE_FOOT = 0.092903
        const val STANDARD_GRAVITY = 9.81
    }
}

const val DEG = Math.PI / 180
const val METERS_PER_FOOT = 0.3048
