package com.skyalt.canopy.tools.planning

import com.skyalt.canopy.scene.Json
import java.time.Instant

// What the planning tools remember between launches: each tool keeps its
// last setup as JSON and puts it back when it is created. Wind forecasts are
// never saved; they are fetched again for the saved place. Every field falls
// back to its default on its own, so an older or damaged save still loads.
// Port of PlanningSavedSettings.swift (iOS PR #20 and #21).

/** Where a tool's setup is kept: SharedPreferences in the app, a map in tests. */
interface PlanningSettingsStore {
    fun load(key: String): String?
    fun save(key: String, text: String)
}

/** The map as it was left: centre, zoom and rotation. */
data class SavedMapView(val center: Coordinate, val zoom: Float, val bearingDegrees: Float = 0f)

/** The wind source of a tool. Enum values are kept as their names. */
data class SavedWind(
    val mode: String = "FORECAST",
    val forecastDate: Instant? = null,
    val manualTerrainFeet: Double = 0.0,
    /** null keeps the tool's default layers. */
    val manualLayers: List<ManualWindLayer>? = null,
)

/** The Landing Pattern Assistant's setup as it is saved between launches. */
data class LandingPatternSettings(
    val mode: LandingPatternMode = LandingPatternMode.NORMAL,
    val landingPoint: Coordinate? = null,
    val finalHeadingDegrees: Double = 0.0,
    val patternDirection: PatternDirection = PatternDirection.LEFT,
    val altitudeStepFeet: Double = 300.0,
    val hiddenLevels: Set<SkillLevel> = emptySet(),
    val swoopStartPoint: Coordinate? = null,
    val swoopStartAltitudeFeet: Double = 300.0,
    val swoopLandingPoint: Coordinate? = null,
    val isSwoopLandingUserAdjusted: Boolean = false,
    /** In creation order. */
    val swoopWaypoints: List<Coordinate> = emptyList(),
    val expertAirspeedKnots: Double = 25.0,
    val expertGlideRatio: Double = 2.2,
    val wind: SavedWind = SavedWind(),
    val mapStyle: String = "SATELLITE",
    val mapView: SavedMapView? = null,
) {
    fun encode(): String = Json.write(
        mapOf(
            "mode" to mode.name,
            "landingPoint" to landingPoint?.let(PlanningSavedSettings::coordinate),
            "finalHeadingDegrees" to finalHeadingDegrees,
            "patternDirection" to patternDirection.name,
            "altitudeStepFeet" to altitudeStepFeet,
            "hiddenLevels" to hiddenLevels.map { it.name }.sorted(),
            "swoopStartPoint" to swoopStartPoint?.let(PlanningSavedSettings::coordinate),
            "swoopStartAltitudeFeet" to swoopStartAltitudeFeet,
            "swoopLandingPoint" to swoopLandingPoint?.let(PlanningSavedSettings::coordinate),
            "isSwoopLandingUserAdjusted" to isSwoopLandingUserAdjusted,
            "swoopWaypoints" to swoopWaypoints.map(PlanningSavedSettings::coordinate),
            "expertAirspeedKnots" to expertAirspeedKnots,
            "expertGlideRatio" to expertGlideRatio,
            "wind" to PlanningSavedSettings.wind(wind),
            "mapStyle" to mapStyle,
            "mapView" to mapView?.let(PlanningSavedSettings::mapView),
        ),
    )

    companion object {
        const val KEY = "tools.landing-pattern-assistant.settings"

        fun decode(text: String?): LandingPatternSettings? {
            val j = PlanningSavedSettings.root(text) ?: return null
            val d = LandingPatternSettings()
            val s = PlanningSavedSettings
            return LandingPatternSettings(
                mode = s.enum(j["mode"], d.mode),
                landingPoint = s.coordinate(j["landingPoint"]),
                finalHeadingDegrees = s.double(j["finalHeadingDegrees"], d.finalHeadingDegrees),
                patternDirection = s.enum(j["patternDirection"], d.patternDirection),
                altitudeStepFeet = s.double(j["altitudeStepFeet"], d.altitudeStepFeet),
                hiddenLevels = (j["hiddenLevels"] as? List<*>)?.mapNotNull { name -> SkillLevel.entries.firstOrNull { it.name == name } }?.toSet() ?: d.hiddenLevels,
                swoopStartPoint = s.coordinate(j["swoopStartPoint"]),
                swoopStartAltitudeFeet = s.double(j["swoopStartAltitudeFeet"], d.swoopStartAltitudeFeet),
                swoopLandingPoint = s.coordinate(j["swoopLandingPoint"]),
                isSwoopLandingUserAdjusted = j["isSwoopLandingUserAdjusted"] as? Boolean ?: d.isSwoopLandingUserAdjusted,
                swoopWaypoints = (j["swoopWaypoints"] as? List<*>)?.mapNotNull { s.coordinate(it) } ?: d.swoopWaypoints,
                expertAirspeedKnots = s.double(j["expertAirspeedKnots"], d.expertAirspeedKnots),
                expertGlideRatio = s.double(j["expertGlideRatio"], d.expertGlideRatio),
                wind = s.wind(j["wind"]),
                mapStyle = j["mapStyle"] as? String ?: d.mapStyle,
                mapView = s.mapView(j["mapView"]),
            )
        }
    }
}

/** The Exit Spot setup as it is saved between launches. */
data class ExitSpotSettings(
    val landingPoint: Coordinate? = null,
    val jumpRunCenter: Coordinate? = null,
    val jumpRunBearingDegrees: Double = 0.0,
    val isJumpRunCustomized: Boolean = false,
    val exitAltitudeFeet: Double = 12_000.0,
    val deploymentAltitudeFeet: Double = 5_000.0,
    val finalApproachHeadingDegrees: Double = 0.0,
    val isFinalApproachHeadingCustomized: Boolean = false,
    val landingPatternDirection: PatternDirection = PatternDirection.LEFT,
    /** null keeps the defaults. */
    val profileParameters: List<ExitSpotProfileParameters>? = null,
    val wind: SavedWind = SavedWind(),
    val mapStyle: String = "SATELLITE",
    /** The DistanceUnit key; null follows the app setting. */
    val distanceUnit: String? = null,
    val mapView: SavedMapView? = null,
) {
    fun encode(): String = Json.write(
        mapOf(
            "landingPoint" to landingPoint?.let(PlanningSavedSettings::coordinate),
            "jumpRunCenter" to jumpRunCenter?.let(PlanningSavedSettings::coordinate),
            "jumpRunBearingDegrees" to jumpRunBearingDegrees,
            "isJumpRunCustomized" to isJumpRunCustomized,
            "exitAltitudeFeet" to exitAltitudeFeet,
            "deploymentAltitudeFeet" to deploymentAltitudeFeet,
            "finalApproachHeadingDegrees" to finalApproachHeadingDegrees,
            "isFinalApproachHeadingCustomized" to isFinalApproachHeadingCustomized,
            "landingPatternDirection" to landingPatternDirection.name,
            "profileParameters" to profileParameters?.map {
                mapOf(
                    "experienceProfile" to it.experienceProfile.name,
                    "airspeedKnots" to it.airspeedKnots,
                    "glideRatio" to it.glideRatio,
                    "safetyAltitudeMarginFeet" to it.safetyAltitudeMarginFeet,
                )
            },
            "wind" to PlanningSavedSettings.wind(wind),
            "mapStyle" to mapStyle,
            "distanceUnit" to distanceUnit,
            "mapView" to mapView?.let(PlanningSavedSettings::mapView),
        ),
    )

    /** The saved canopy class parameters over the defaults, class by class. */
    val restoredProfileParameters: List<ExitSpotProfileParameters>
        get() = ExitSpotProfileParameters.defaults.map { defaults ->
            profileParameters?.firstOrNull { it.experienceProfile == defaults.experienceProfile } ?: defaults
        }

    companion object {
        const val KEY = "tools.exit-spot.settings"

        fun decode(text: String?): ExitSpotSettings? {
            val j = PlanningSavedSettings.root(text) ?: return null
            val d = ExitSpotSettings()
            val s = PlanningSavedSettings
            return ExitSpotSettings(
                landingPoint = s.coordinate(j["landingPoint"]),
                jumpRunCenter = s.coordinate(j["jumpRunCenter"]),
                jumpRunBearingDegrees = s.double(j["jumpRunBearingDegrees"], d.jumpRunBearingDegrees),
                isJumpRunCustomized = j["isJumpRunCustomized"] as? Boolean ?: d.isJumpRunCustomized,
                exitAltitudeFeet = s.double(j["exitAltitudeFeet"], d.exitAltitudeFeet),
                deploymentAltitudeFeet = s.double(j["deploymentAltitudeFeet"], d.deploymentAltitudeFeet),
                finalApproachHeadingDegrees = s.double(j["finalApproachHeadingDegrees"], d.finalApproachHeadingDegrees),
                isFinalApproachHeadingCustomized = j["isFinalApproachHeadingCustomized"] as? Boolean ?: d.isFinalApproachHeadingCustomized,
                landingPatternDirection = s.enum(j["landingPatternDirection"], d.landingPatternDirection),
                profileParameters = (j["profileParameters"] as? List<*>)?.mapNotNull { item ->
                    val p = item as? Map<*, *> ?: return@mapNotNull null
                    val profile = ExperienceProfile.entries.firstOrNull { it.name == p["experienceProfile"] } ?: return@mapNotNull null
                    val defaults = profile.defaultParameters
                    ExitSpotProfileParameters(
                        profile,
                        s.double(p["airspeedKnots"], defaults.airspeedKnots),
                        s.double(p["glideRatio"], defaults.glideRatio),
                        s.double(p["safetyAltitudeMarginFeet"], defaults.safetyAltitudeMarginFeet),
                    )
                },
                wind = s.wind(j["wind"]),
                mapStyle = j["mapStyle"] as? String ?: d.mapStyle,
                distanceUnit = j["distanceUnit"] as? String,
                mapView = s.mapView(j["mapView"]),
            )
        }
    }
}

object PlanningSavedSettings {
    /** The saved forecast hour while it is still ahead; once it has passed, the current hour, as on a fresh start. */
    fun forecastDate(saved: Instant?, now: Instant = Instant.now()): Instant {
        val current = WindProfile.forecastHour(now)
        if (saved == null) return current
        return maxOf(WindProfile.forecastHour(saved), current)
    }

    internal fun root(text: String?): Map<*, *>? =
        if (text.isNullOrBlank()) null else try { Json.parse(text) as? Map<*, *> } catch (_: Exception) { null }

    internal fun double(value: Any?, fallback: Double): Double =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: fallback

    internal inline fun <reified E : Enum<E>> enum(value: Any?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == value } ?: fallback

    internal fun coordinate(c: Coordinate): Map<String, Any?> = mapOf("latitude" to c.latitude, "longitude" to c.longitude)

    internal fun coordinate(value: Any?): Coordinate? {
        val m = value as? Map<*, *> ?: return null
        val lat = (m["latitude"] as? Number)?.toDouble() ?: return null
        val lon = (m["longitude"] as? Number)?.toDouble() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        return Coordinate(lat, lon)
    }

    internal fun mapView(v: SavedMapView): Map<String, Any?> =
        mapOf("center" to coordinate(v.center), "zoom" to v.zoom.toDouble(), "bearingDegrees" to v.bearingDegrees.toDouble())

    internal fun mapView(value: Any?): SavedMapView? {
        val m = value as? Map<*, *> ?: return null
        val center = coordinate(m["center"]) ?: return null
        val zoom = double(m["zoom"], -1.0)
        if (zoom < 1 || zoom > 22) return null
        return SavedMapView(center, zoom.toFloat(), double(m["bearingDegrees"], 0.0).toFloat())
    }

    internal fun wind(w: SavedWind): Map<String, Any?> = mapOf(
        "mode" to w.mode,
        "forecastDate" to w.forecastDate?.epochSecond,
        "manualTerrainFeet" to w.manualTerrainFeet,
        "manualLayers" to w.manualLayers?.map {
            mapOf("altitudeAGLFeet" to it.altitudeAGLFeet, "speedKnots" to it.speedKnots, "directionFromDegrees" to it.directionFromDegrees)
        },
    )

    internal fun wind(value: Any?): SavedWind {
        val m = value as? Map<*, *> ?: return SavedWind()
        val d = SavedWind()
        return SavedWind(
            mode = m["mode"] as? String ?: d.mode,
            forecastDate = (m["forecastDate"] as? Number)?.toLong()?.let { Instant.ofEpochSecond(it) },
            manualTerrainFeet = double(m["manualTerrainFeet"], d.manualTerrainFeet),
            manualLayers = (m["manualLayers"] as? List<*>)?.mapNotNull { item ->
                val l = item as? Map<*, *> ?: return@mapNotNull null
                val altitude = (l["altitudeAGLFeet"] as? Number)?.toDouble() ?: return@mapNotNull null
                ManualWindLayer(
                    altitudeAGLFeet = altitude,
                    speedKnots = double(l["speedKnots"], 0.0),
                    directionFromDegrees = double(l["directionFromDegrees"], 0.0),
                )
            },
        )
    }
}
