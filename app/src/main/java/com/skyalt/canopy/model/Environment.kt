package com.skyalt.canopy.model

import kotlin.math.max
import kotlin.math.min

// The cloud layer, the air traffic settings and the wakes everything leaves
// in the air. Port of CanopyEnvironmentSettings.swift and CanopyWakeField.swift.

/** The cloud layer over the dropzone: where its base sits, how thick it is and how dense it looks. */
data class CloudSettings(
    /** Metres above the ground. */
    val baseAltitudeMeters: Double = 1_500 * METERS_PER_FOOT,
    /** Metres from the base of the layer to its top. */
    val thicknessMeters: Double = 400 * METERS_PER_FOOT,
    /** 0 a clear sky, 1 a solid overcast; nothing is drawn at 0. */
    val density: Double = 0.0,
) {
    val isVisible get() = density > 0.005
    val topAltitudeMeters get() = baseAltitudeMeters + thicknessMeters

    /** Whether [altitude] (metres) is inside the layer. */
    fun contains(altitude: Double) = isVisible && altitude >= baseAltitudeMeters && altitude <= topAltitudeMeters

    fun clamped() = copy(
        baseAltitudeMeters = baseAltitudeMeters.clamp(30.0, 3_000.0),
        thicknessMeters = thicknessMeters.clamp(30.0, 1_500.0),
        density = density.clamp(0.0, 1.0),
    )
}

/** Other canopies in the air, whether the wakes are drawn, and free practice's aircraft. */
data class TrafficSettings(
    /** Canopies generated at each reset, 0…20. */
    val count: Int = 0,
    /** Draws the turbulent air behind every canopy and aircraft as faint grey puffs. */
    val showsWakes: Boolean = true,
    /** Free practice: a P750 landing again and again. */
    val aircraftLanding: Boolean = false,
) {
    fun clamped() = copy(count = count.coerceIn(0, MAXIMUM_COUNT))

    companion object {
        const val MAXIMUM_COUNT = 20
    }
}

/** The paint on the aircraft's wings and tail; the fuselage stays white. */
data class AircraftLiverySettings(
    /** 0xRRGGBB. */
    val colorHex: Int = DEFAULT_COLOR_HEX,
) {
    val isDefault get() = colorHex == DEFAULT_COLOR_HEX

    companion object {
        const val DEFAULT_COLOR_HEX = 0xd42a1f
    }
}

/** The hidden setting: how far past the threshold every aircraft touches down. */
data class AircraftTouchdownSettings(
    val metersFromThreshold: Double = Airfield.TOUCHDOWN_FROM_THRESHOLD,
) {
    fun clamped() = copy(metersFromThreshold = metersFromThreshold.clamp(0.0, MAXIMUM_METERS))

    companion object {
        const val MAXIMUM_METERS = 500.0
    }
}

/** A puff of turbulent air left behind a canopy or an aircraft: it grows and weakens with age, and sinks slowly. */
class WakeSample(
    val position: Vec3,
    val born: Double,
    /** Turbulence at the centre when fresh, 0…1. */
    val strength: Double,
    /** Metres when fresh, growing by [growth] metres a second. */
    val radius: Double,
    val growth: Double,
    val lifetime: Double,
    /** Metres a second the puff descends. */
    val sink: Double,
    /** The wind carrying the puff along, metres a second. */
    val drift: Vec3,
    /** Who left it: its own turbulence never troubles a canopy. */
    val source: Int,
) {
    fun age(time: Double) = time - born
    fun radius(time: Double) = radius + growth * age(time)

    fun centre(time: Double): Vec3 {
        val age = age(time)
        return Vec3(position.x + drift.x * age, max(0.5, position.y - sink * age), position.z + drift.z * age)
    }

    /** The remaining strength, fading out over the lifetime. */
    fun strength(time: Double): Double {
        val life = age(time) / lifetime
        if (life >= 1) return 0.0
        return strength * (1 - life * life)
    }

    /** The turbulence this puff puts on a point, 0…1. */
    fun intensity(point: Vec3, time: Double): Double {
        val remaining = strength(time)
        if (remaining <= 0) return 0.0
        val r = radius(time)
        val d = (point - centre(time)).length
        if (d >= r) return 0.0
        val u = 1 - d / r
        return min(1.0, remaining * u * u * (3 - 2 * u))
    }
}

/** How a kind of flying thing stirs the air it passes through. */
data class WakeProfile(
    val strength: Double,
    val radius: Double,
    val growth: Double,
    val lifetime: Double,
    val sink: Double,
    /** Seconds between the puffs it leaves. */
    val interval: Double,
) {
    companion object {
        /** A canopy's wake: a few metres across, gone in about twelve seconds. */
        fun canopy(span: Double) = WakeProfile(0.85, max(2.5, span * 0.45), 0.6, 12.0, 1.2, 0.3)

        /** A heavier loading stirs the air harder. */
        fun strengthScale(wingLoading: Double) = min(1.3, 0.55 + 0.45 * wingLoading)

        /** An aircraft's wake: strong, wide and long-lived; the vortices descend a couple of metres a second. */
        val AIRCRAFT = WakeProfile(1.0, 9.45, 1.575, 45.0, 1.6, 0.3)
    }
}

/** Every wake in the air, pruned as it dies. */
class WakeField {
    val samples = ArrayList<WakeSample>()
    /** When each source last left a puff. */
    private val lastEmission = HashMap<Int, Double>()

    fun removeAll() {
        samples.clear()
        lastEmission.clear()
    }

    /** Leaves a puff for [source] at [position] if its interval has passed; the wind carries it along. */
    fun emit(source: Int, position: Vec3, profile: WakeProfile, strengthScale: Double = 1.0, wind: Vec3 = Vec3.ZERO, time: Double) {
        val last = lastEmission[source]
        if (last != null && time - last < profile.interval) return
        lastEmission[source] = time
        samples += WakeSample(position, time, profile.strength * strengthScale, profile.radius, profile.growth, profile.lifetime, profile.sink, wind, source)
    }

    /** A puff [source] left before the run began, at a negative [born]: the trail an aircraft already has behind it. */
    fun seed(source: Int, position: Vec3, profile: WakeProfile, strengthScale: Double = 1.0, wind: Vec3 = Vec3.ZERO, born: Double) {
        lastEmission[source] = born
        samples += WakeSample(position, born, profile.strength * strengthScale, profile.radius, profile.growth, profile.lifetime, profile.sink, wind, source)
    }

    fun prune(time: Double) {
        samples.removeAll { it.age(time) >= it.lifetime }
    }

    /** The turbulence at [point] from everything but [excluding]'s own wake: overlapping puffs add up as independent chances do. */
    fun intensity(point: Vec3, time: Double, excluding: Int): Double {
        var calm = 1.0
        for (sample in samples) {
            if (sample.source == excluding) continue
            val s = sample.intensity(point, time)
            if (s > 0) calm *= 1 - s
            if (calm < 0.01) return 1.0
        }
        return 1 - calm
    }

    companion object {
        const val PLAYER_SOURCE = 0
        const val AIRCRAFT_SOURCE = -1
    }
}
