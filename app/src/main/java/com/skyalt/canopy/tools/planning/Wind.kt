package com.skyalt.canopy.tools.planning

import java.time.Instant
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Horizontal wind as the velocity of the air mass (where it moves toward), m/s. */
data class WindVector(val eastMetersPerSecond: Double, val northMetersPerSecond: Double) {
    val speedMetersPerSecond get() = hypot(eastMetersPerSecond, northMetersPerSecond)
    /** Bearing the air is moving toward, degrees clockwise from north. */
    val towardBearingDegrees get() = atan2(eastMetersPerSecond, northMetersPerSecond) * 180 / PI
    val speedKnots get() = speedMetersPerSecond / WindMath.METERS_PER_SECOND_PER_KNOT
    /** Meteorological direction the wind blows from, 0..<360. */
    val directionFromDegrees get() = Geodesy.normalizedDegrees(towardBearingDegrees + 180)

    fun scaled(f: Double) = WindVector(eastMetersPerSecond * f, northMetersPerSecond * f)
    fun interpolated(toward: WindVector, fraction: Double) = this + (toward - this).scaled(fraction)
    fun displacement(seconds: Double) = LocalDisplacement(eastMetersPerSecond * seconds, northMetersPerSecond * seconds)
    operator fun plus(o: WindVector) = WindVector(eastMetersPerSecond + o.eastMetersPerSecond, northMetersPerSecond + o.northMetersPerSecond)
    operator fun minus(o: WindVector) = WindVector(eastMetersPerSecond - o.eastMetersPerSecond, northMetersPerSecond - o.northMetersPerSecond)

    companion object {
        val CALM = WindVector(0.0, 0.0)

        fun meteorological(speedMetersPerSecond: Double, directionFromDegrees: Double): WindVector {
            val r = (directionFromDegrees + 180) * PI / 180
            return WindVector(speedMetersPerSecond * sin(r), speedMetersPerSecond * cos(r))
        }

        fun mean(winds: List<WindVector>): WindVector? {
            if (winds.isEmpty()) return null
            val total = winds.fold(CALM) { a, b -> a + b }
            return total.scaled(1.0 / winds.size)
        }
    }
}

data class WindProfileSample(val pressureHectopascals: Int? = null, val altitudeMSLMeters: Double, val wind: WindVector)

/** A column of wind samples above one place at one moment. */
data class WindProfile(
    val coordinate: Coordinate,
    val terrainElevationMeters: Double,
    val validAt: Instant,
    val fetchedAt: Instant,
    val source: String,
    val model: String,
    val samples: List<WindProfileSample>,
) {
    private val sorted by lazy { samples.sortedBy { it.altitudeMSLMeters } }

    /** Wind at an MSL altitude: linear between the bracketing samples, clamped to the ends. */
    fun windAtMSL(altitude: Double): WindVector {
        val s = sorted
        val first = s.firstOrNull() ?: return WindVector.CALM
        if (s.size < 2) return first.wind
        val last = s.last()
        if (altitude <= first.altitudeMSLMeters) return first.wind
        if (altitude >= last.altitudeMSLMeters) return last.wind
        val upperIndex = s.indexOfFirst { it.altitudeMSLMeters >= altitude }
        if (upperIndex <= 0) return first.wind
        val lower = s[upperIndex - 1]
        val upper = s[upperIndex]
        val span = max(upper.altitudeMSLMeters - lower.altitudeMSLMeters, 1.0)
        return lower.wind.interpolated(upper.wind, (altitude - lower.altitudeMSLMeters) / span)
    }

    fun windAtAGL(altitudeAGLMeters: Double) = windAtMSL(terrainElevationMeters + altitudeAGLMeters)

    companion object {
        fun manual(coordinate: Coordinate, terrainElevationMeters: Double, validAt: Instant, layers: List<ManualWindLayer>, source: String, model: String) = WindProfile(
            coordinate, terrainElevationMeters, validAt, Instant.now(), source, model,
            layers.map {
                WindProfileSample(
                    altitudeMSLMeters = terrainElevationMeters + it.altitudeAGLFeet * WindMath.METERS_PER_FOOT,
                    wind = WindVector.meteorological(it.speedKnots * WindMath.METERS_PER_SECOND_PER_KNOT, it.directionFromDegrees),
                )
            },
        )

        /** Earliest hour the historical forecast archive covers (2021-03-23 UTC). */
        val HISTORICAL_COVERAGE_START: Instant = Instant.ofEpochSecond(1_616_457_600)

        fun forecastHour(containing: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(containing.epochSecond, 3600L) * 3600)
    }
}

data class ManualWindLayer(
    val id: String = UUID.randomUUID().toString(),
    val altitudeAGLFeet: Double,
    val speedKnots: Double,
    val directionFromDegrees: Double,
)

/** Wind-drift and ground-track arithmetic shared by the planners. Port of WindMath.swift. */
object WindMath {
    const val METERS_PER_FOOT = 0.3048
    const val METERS_PER_SECOND_PER_KNOT = 0.514444

    data class Integration(val displacement: LocalDisplacement, val cumulative: List<LocalDisplacement>)
    data class GroundTrack(val groundSpeedMetersPerSecond: Double, val displacement: LocalDisplacement)

    fun arithmeticMeanWind(altitudeAGLMeters: Double, minimumAltitudeAGLMeters: Double, profile: WindProfile): WindVector {
        val minAlt = max(min(minimumAltitudeAGLMeters, altitudeAGLMeters), 0.0)
        val maxAlt = max(altitudeAGLMeters, minAlt)
        val minMSL = profile.terrainElevationMeters + minAlt
        val maxMSL = profile.terrainElevationMeters + maxAlt
        WindVector.mean(profile.samples.filter { it.altitudeMSLMeters in minMSL..maxMSL }.map { it.wind })?.let { return it }
        val mid = (minMSL + maxMSL) / 2
        return profile.samples.minByOrNull { abs(it.altitudeMSLMeters - mid) }?.wind ?: WindVector.CALM
    }

    /** Thickness-weighted mean of the interpolated wind between two AGL altitudes (30 m trapezoids). */
    fun averageWind(fromAGLMeters: Double, toAGLMeters: Double, profile: WindProfile): WindVector {
        val lower = max(min(fromAGLMeters, toAGLMeters), 0.0)
        val upper = max(max(fromAGLMeters, toAGLMeters), lower)
        if (upper <= lower) return profile.windAtAGL(lower)
        val n = max(ceil((upper - lower) / 30).toInt(), 1)
        var e = 0.0
        var no = 0.0
        for (i in 0 until n) {
            val a0 = lower + (upper - lower) * i / n
            val a1 = lower + (upper - lower) * (i + 1) / n
            val w0 = profile.windAtAGL(a0)
            val w1 = profile.windAtAGL(a1)
            val t = a1 - a0
            e += (w0.eastMetersPerSecond + w1.eastMetersPerSecond) * 0.5 * t
            no += (w0.northMetersPerSecond + w1.northMetersPerSecond) * 0.5 * t
        }
        val span = upper - lower
        return WindVector(e / span, no / span)
    }

    fun constantIntegration(durationSeconds: Double, wind: WindVector): Integration {
        val d = max(durationSeconds, 0.0)
        val displacement = wind.displacement(d)
        return Integration(displacement, if (d > 0) listOf(LocalDisplacement.ZERO, displacement) else listOf(LocalDisplacement.ZERO))
    }

    /** Drift of a passive body sinking steadily, integrated down the column in 10 m steps. */
    fun passiveIntegration(altitudeAGLMeters: Double, minimumAltitudeAGLMeters: Double = 0.0, descentRateMetersPerSecond: Double, profile: WindProfile): Integration {
        val rate = max(descentRateMetersPerSecond, 0.1)
        val minAlt = max(min(minimumAltitudeAGLMeters, altitudeAGLMeters), 0.0)
        var alt = max(altitudeAGLMeters, minAlt)
        var displacement = LocalDisplacement.ZERO
        val cumulative = mutableListOf(LocalDisplacement.ZERO)
        while (alt > minAlt) {
            val next = max(alt - 10.0, minAlt)
            val dt = (alt - next) / rate
            val wind = profile.windAtAGL(alt).interpolated(profile.windAtAGL(next), 0.5)
            displacement += wind.displacement(dt)
            cumulative.add(displacement)
            alt = next
        }
        return Integration(displacement, cumulative)
    }

    /** Exit to opening with the simulator's freefall model: the first 1,000 ft at 100 ft/s, the rest at 200 ft/s. */
    fun freefallDuration(exitAltitudeAGLMeters: Double, openingAltitudeAGLMeters: Double): Double {
        val first = 1_000.0 * METERS_PER_FOOT
        val total = max(exitAltitudeAGLMeters - openingAltitudeAGLMeters, 0.0)
        val firstDrop = min(total, first)
        val rest = max(total - firstDrop, 0.0)
        return firstDrop / (first / 10) + rest / (first / 5)
    }

    /** Ground speed along a heading when the canopy crabs to hold that track; null when it cannot. */
    fun groundSpeed(airspeedMetersPerSecond: Double, headingDegrees: Double, wind: WindVector): Double? {
        val r = headingDegrees * PI / 180
        val fe = sin(r)
        val fn = cos(r)
        val re = cos(r)
        val rn = -sin(r)
        val along = wind.eastMetersPerSecond * fe + wind.northMetersPerSecond * fn
        val cross = wind.eastMetersPerSecond * re + wind.northMetersPerSecond * rn
        val air = max(airspeedMetersPerSecond, 0.1)
        if (abs(cross) >= air) return null
        val speed = sqrt(max(air * air - cross * cross, 0.0)) + along
        return if (speed > 0.1) speed else null
    }

    fun groundTrack(durationSeconds: Double, airspeedMetersPerSecond: Double, headingDegrees: Double, wind: WindVector): GroundTrack? {
        val speed = groundSpeed(airspeedMetersPerSecond, headingDegrees, wind) ?: return null
        val r = headingDegrees * PI / 180
        val distance = speed * max(durationSeconds, 0.0)
        return GroundTrack(speed, LocalDisplacement(sin(r) * distance, cos(r) * distance))
    }
}
