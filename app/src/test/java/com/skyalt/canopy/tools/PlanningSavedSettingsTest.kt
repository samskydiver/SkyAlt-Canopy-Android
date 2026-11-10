package com.skyalt.canopy.tools

import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.ExitSpotProfileParameters
import com.skyalt.canopy.tools.planning.ExitSpotSettings
import com.skyalt.canopy.tools.planning.ExperienceProfile
import com.skyalt.canopy.tools.planning.LandingPatternMode
import com.skyalt.canopy.tools.planning.LandingPatternSettings
import com.skyalt.canopy.tools.planning.ManualWindLayer
import com.skyalt.canopy.tools.planning.PatternDirection
import com.skyalt.canopy.tools.planning.PlanningSavedSettings
import com.skyalt.canopy.tools.planning.SavedMapView
import com.skyalt.canopy.tools.planning.SavedWind
import com.skyalt.canopy.tools.planning.SkillLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class PlanningSavedSettingsTest {
    private val layers = listOf(ManualWindLayer(altitudeAGLFeet = 0.0, speedKnots = 7.0, directionFromDegrees = 250.0))

    @Test
    fun landingPatternSetupRoundTrips() {
        val settings = LandingPatternSettings(
            mode = LandingPatternMode.EXPERT,
            landingPoint = Coordinate(13.7, 100.5),
            finalHeadingDegrees = 245.0,
            patternDirection = PatternDirection.RIGHT,
            altitudeStepFeet = 400.0,
            hiddenLevels = setOf(SkillLevel.EXPERT),
            swoopStartPoint = Coordinate(13.71, 100.51),
            swoopStartAltitudeFeet = 600.0,
            swoopLandingPoint = Coordinate(13.712, 100.512),
            isSwoopLandingUserAdjusted = true,
            swoopWaypoints = listOf(Coordinate(13.72, 100.52), Coordinate(13.73, 100.53)),
            expertAirspeedKnots = 30.0,
            expertGlideRatio = 2.0,
            wind = SavedWind("MANUAL", Instant.ofEpochSecond(1_800_000_000), 120.0, layers),
            mapStyle = "HYBRID",
            mapView = SavedMapView(Coordinate(13.705, 100.505), 16.25f, 30f),
        )
        val decoded = LandingPatternSettings.decode(settings.encode())!!
        // Layer ids are not saved.
        assertEquals(settings.copy(wind = settings.wind.copy(manualLayers = null)), decoded.copy(wind = decoded.wind.copy(manualLayers = null)))
        assertEquals(1, decoded.wind.manualLayers!!.size)
        assertEquals(250.0, decoded.wind.manualLayers!![0].directionFromDegrees, 0.0)
    }

    @Test
    fun exitSpotSetupRoundTrips() {
        val custom = ExitSpotProfileParameters(ExperienceProfile.STUDENT, 20.0, 2.8, 1_800.0)
        val settings = ExitSpotSettings(
            landingPoint = Coordinate(-33.9, 151.2),
            jumpRunCenter = Coordinate(-33.91, 151.21),
            jumpRunBearingDegrees = 90.0,
            isJumpRunCustomized = true,
            exitAltitudeFeet = 14_000.0,
            deploymentAltitudeFeet = 4_000.0,
            finalApproachHeadingDegrees = 180.0,
            isFinalApproachHeadingCustomized = true,
            landingPatternDirection = PatternDirection.RIGHT,
            profileParameters = listOf(custom),
            wind = SavedWind("FORECAST", null, 0.0, null),
            mapStyle = "STANDARD",
            distanceUnit = "miles",
            mapView = SavedMapView(Coordinate(-33.9, 151.2), 13f),
        )
        val decoded = ExitSpotSettings.decode(settings.encode())!!
        assertEquals(settings, decoded)
        val restored = decoded.restoredProfileParameters
        assertEquals(custom, restored.first { it.experienceProfile == ExperienceProfile.STUDENT })
        assertEquals(ExperienceProfile.EXPERT.defaultParameters, restored.first { it.experienceProfile == ExperienceProfile.EXPERT })
    }

    @Test
    fun aBadFieldKeepsItsDefaultAndTheRest() {
        val text = """{"mode":"GLIDING","finalHeadingDegrees":"north","altitudeStepFeet":500,"landingPoint":{"latitude":95,"longitude":0},"mapView":{"center":{"latitude":1,"longitude":2}}}"""
        val decoded = LandingPatternSettings.decode(text)!!
        assertEquals(LandingPatternMode.NORMAL, decoded.mode)
        assertEquals(0.0, decoded.finalHeadingDegrees, 0.0)
        assertEquals(500.0, decoded.altitudeStepFeet, 0.0)
        assertNull(decoded.landingPoint)
        assertNull(decoded.mapView)
        assertNull(LandingPatternSettings.decode("not json"))
        assertNull(ExitSpotSettings.decode(null))
    }

    @Test
    fun aPastForecastHourBecomesTheCurrentHour() {
        val now = Instant.parse("2026-11-10T10:25:00Z")
        assertEquals(Instant.parse("2026-11-10T10:00:00Z"), PlanningSavedSettings.forecastDate(Instant.parse("2026-11-09T08:00:00Z"), now))
        assertEquals(Instant.parse("2026-11-11T06:00:00Z"), PlanningSavedSettings.forecastDate(Instant.parse("2026-11-11T06:30:00Z"), now))
        assertEquals(Instant.parse("2026-11-10T10:00:00Z"), PlanningSavedSettings.forecastDate(null, now))
    }
}
