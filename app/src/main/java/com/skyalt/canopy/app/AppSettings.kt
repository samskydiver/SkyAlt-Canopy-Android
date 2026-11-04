package com.skyalt.canopy.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.abs

/** Altitude display unit, as AltitudeUnit on iOS. */
enum class AltitudeUnit(val key: String, val shortLabel: String) {
    FEET("feet", "ft"), METERS("meters", "m");

    fun fromMeters(meters: Double) = if (this == FEET) meters * FEET_PER_METER else meters
    fun toMeters(value: Double) = if (this == FEET) value / FEET_PER_METER else value
    fun format(meters: Double) = String.format(Locale.US, "%,.0f", fromMeters(meters))

    companion object {
        const val FEET_PER_METER = 3.28084
    }
}

/** Horizontal distance display unit, as DistanceUnit on iOS. */
enum class DistanceUnit(val key: String, val title: String, val shortLabel: String) {
    KILOMETERS("kilometers", "Kilometers", "km"), MILES("miles", "Miles", "mi");

    fun fromMeters(meters: Double) = if (this == KILOMETERS) meters / 1_000 else meters / 1_609.344
    fun toMeters(value: Double) = if (this == KILOMETERS) value * 1_000 else value * 1_609.344

    fun format(meters: Double): String {
        val d = fromMeters(meters)
        val digits = if (abs(d) < 10) 2 else if (abs(d) < 100) 1 else 0
        return String.format(Locale.US, "%.${digits}f", d)
    }
}

/** The display units chosen in Settings, observable by Compose. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("altix-settings", Context.MODE_PRIVATE)

    var altitudeUnit by mutableStateOf(AltitudeUnit.entries.firstOrNull { it.key == prefs.getString(ALTITUDE, null) } ?: AltitudeUnit.FEET)
        private set
    var distanceUnit by mutableStateOf(DistanceUnit.entries.firstOrNull { it.key == prefs.getString(DISTANCE, null) } ?: DistanceUnit.KILOMETERS)
        private set

    fun selectAltitudeUnit(unit: AltitudeUnit) {
        altitudeUnit = unit
        prefs.edit().putString(ALTITUDE, unit.key).apply()
    }

    fun selectDistanceUnit(unit: DistanceUnit) {
        distanceUnit = unit
        prefs.edit().putString(DISTANCE, unit.key).apply()
    }

    private companion object {
        const val ALTITUDE = "altitude-unit"
        const val DISTANCE = "distance-unit"
    }
}
