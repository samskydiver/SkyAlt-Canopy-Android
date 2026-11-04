package com.skyalt.canopy.tools.cutaway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.CameraPositionState
import com.skyalt.canopy.tools.map.CameraRequest
import com.skyalt.canopy.tools.map.DEFAULT_MAP_CENTER
import com.skyalt.canopy.tools.map.PlanningMapStyle
import com.skyalt.canopy.tools.map.WindSheetSource
import com.skyalt.canopy.tools.map.WindSourceMode
import com.skyalt.canopy.tools.map.latLng
import com.skyalt.canopy.tools.planning.BoundingCircle
import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.CutawaySolution
import com.skyalt.canopy.tools.planning.FlightPlanningEngine
import com.skyalt.canopy.tools.planning.Geodesy
import com.skyalt.canopy.tools.planning.ManualWindLayer
import com.skyalt.canopy.tools.planning.MapGeometry
import com.skyalt.canopy.tools.planning.WindMath
import com.skyalt.canopy.tools.planning.WindProfile
import com.skyalt.canopy.tools.planning.WindProfileClient
import com.skyalt.canopy.tools.planning.WindServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Manual wind layers for the Cutaway Finder: one per 1,000 ft AGL up to the cutaway altitude. */
object CutawayWindLayers {
    val initial get() = altitudes(2_500.0).map { ManualWindLayer(altitudeAGLFeet = it.toDouble(), speedKnots = 0.0, directionFromDegrees = 0.0) }

    fun altitudes(upTo: Double): List<Int> {
        val ceiling = max(1_000, ceil(upTo / 1_000).toInt() * 1_000)
        return (0..ceiling step 1_000).toList()
    }

    /** Keeps the user's layers, seeds missing ones from the forecast when there is one, drops layers above the ceiling. */
    fun normalized(existing: List<ManualWindLayer>, profile: WindProfile?, cutawayAltitudeFeet: Double): List<ManualWindLayer> {
        val byAltitude = existing.groupBy { it.altitudeAGLFeet.roundToInt() }.mapValues { it.value.first() }
        return altitudes(cutawayAltitudeFeet).map { altitude ->
            byAltitude[altitude] ?: profile?.windAtAGL(altitude * WindMath.METERS_PER_FOOT)?.let {
                ManualWindLayer(altitudeAGLFeet = altitude.toDouble(), speedKnots = it.speedKnots, directionFromDegrees = it.directionFromDegrees)
            } ?: ManualWindLayer(altitudeAGLFeet = altitude.toDouble(), speedKnots = 0.0, directionFromDegrees = 0.0)
        }
    }
}

/**
 * State and logic for the Cutaway Finder, port of CutawayFinderSession.swift:
 * the drawn cutaway circle, the wind column above it and the drifted landing circle.
 */
class CutawayFinderSession(private val client: WindProfileClient = WindProfileClient.live) : WindSheetSource {
    private val scope = MainScope()

    // Map
    val camera = CameraPositionState(CameraPosition.fromLatLngZoom(DEFAULT_MAP_CENTER.latLng(), 14f))
    var cameraRequest by mutableStateOf<CameraRequest?>(null)
    var shouldCenterOnNextLocation = true
    var mapStyle by mutableStateOf(PlanningMapStyle.SATELLITE)
    var isDrawing by mutableStateOf(false)
        private set
    var drawnCoordinates by mutableStateOf<List<Coordinate>>(emptyList())
        private set
    var showsDrawTip by mutableStateOf(true)
    var drawingErrorKey by mutableStateOf<String?>(null)

    // Inputs
    var circleCenter by mutableStateOf<Coordinate?>(null)
        private set
    var circleRadiusMeters by mutableDoubleStateOf(500.0)
        private set
    var cutawayAltitudeFeet by mutableDoubleStateOf(2_500.0)
    var fallRateFeetPerSecond by mutableDoubleStateOf(10.0)
        private set

    // Wind
    override var mode by mutableStateOf(WindSourceMode.FORECAST)
        private set
    override var forecastDate by mutableStateOf(WindProfile.forecastHour(Instant.now()))
        private set
    override var profile by mutableStateOf<WindProfile?>(null)
        private set
    override var errorKey by mutableStateOf<String?>(null)
        private set
    override var isLoading by mutableStateOf(false)
        private set
    private var terrainElevationMeters = 0.0
    var manualLayers by mutableStateOf(CutawayWindLayers.initial)
        private set

    // Result
    var solution by mutableStateOf<CutawaySolution?>(null)
        private set

    private var windJob: Job? = null
    private var calculationJob: Job? = null
    private var windRevision = 0

    // Drawing

    fun toggleDrawing() {
        isDrawing = !isDrawing
        drawnCoordinates = emptyList()
        if (isDrawing) drawingErrorKey = null
    }

    /** Adds a stroke point while drawing, skipping points under 2 m apart. */
    fun addDrawingPoint(coordinate: Coordinate) {
        if (!isDrawing) return
        val last = drawnCoordinates.lastOrNull()
        if (last != null && Geodesy.distanceMeters(last, coordinate) < 2) return
        drawnCoordinates = drawnCoordinates + coordinate
    }

    /** Fits a circle to the stroke; the radius is kept within 25 m – 5 km. */
    fun finishDrawing() {
        if (!isDrawing) return
        val circle = MapGeometry.fittedCircle(drawnCoordinates)
        drawnCoordinates = emptyList()
        isDrawing = false
        if (circle == null) {
            drawingErrorKey = "Draw a larger, closed circle around the approximate cutaway area."
            return
        }
        circleCenter = circle.center
        circleRadiusMeters = min(max(circle.radiusMeters, 25.0), 5_000.0)
        refreshWindAndSolution()
    }

    // Inputs

    fun altitudeEditingEnded() {
        normalizeManualLayers()
        refreshWindAndSolution()
    }

    fun updateFallRate(feetPerSecond: Double) {
        if (feetPerSecond == fallRateFeetPerSecond) return
        fallRateFeetPerSecond = feetPerSecond
        calculateIfPossible()
    }

    fun updateManualLayers(layers: List<ManualWindLayer>) {
        if (layers == manualLayers) return
        manualLayers = layers
        if (mode == WindSourceMode.MANUAL) applyManualWindAndCalculate()
    }

    override fun selectMode(newMode: WindSourceMode) {
        if (newMode == mode) return
        mode = newMode
        if (mode == WindSourceMode.FORECAST) {
            requestAutomaticWind()
        } else {
            normalizeManualLayers()
            applyManualWindAndCalculate()
        }
    }

    /** Snaps the requested time to the forecast hour and refetches. */
    override fun selectForecastDate(date: Instant) {
        val normalized = WindProfile.forecastHour(date)
        if (normalized == forecastDate) return
        forecastDate = normalized
        if (mode == WindSourceMode.FORECAST) requestAutomaticWind()
    }

    // Map camera and chrome

    fun handleLocationFix(coordinate: Coordinate) {
        if (!shouldCenterOnNextLocation) return
        cameraRequest = CameraRequest.Center(coordinate, zoom = 14.5f)
        shouldCenterOnNextLocation = false
    }

    fun requestCentering(coordinate: Coordinate?) {
        shouldCenterOnNextLocation = true
        if (coordinate != null) handleLocationFix(coordinate)
    }

    fun cycleMapStyle() {
        mapStyle = mapStyle.next
    }

    // Wind

    private fun refreshWindAndSolution() {
        if (circleCenter == null) return
        if (mode == WindSourceMode.FORECAST) {
            requestAutomaticWind()
        } else {
            normalizeManualLayers()
            applyManualWindAndCalculate()
        }
    }

    /** Waits briefly so a run of quick edits only fetches once. */
    private fun requestAutomaticWind() {
        val coordinate = circleCenter ?: return
        if (mode != WindSourceMode.FORECAST) return
        profile = null
        solution = null
        errorKey = null
        calculationJob?.cancel()
        isLoading = true
        windRevision += 1
        val revision = windRevision
        windJob?.cancel()
        val queryDate = forecastDate
        windJob = scope.launch {
            try {
                delay(300)
                val fetched = client.fetch(coordinate, queryDate)
                if (revision != windRevision || queryDate != forecastDate || mode != WindSourceMode.FORECAST) return@launch
                profile = fetched
                terrainElevationMeters = fetched.terrainElevationMeters
                errorKey = null
                isLoading = false
                calculateIfPossible()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision != windRevision) return@launch
                profile = null
                solution = null
                isLoading = false
                errorKey = (e as? WindServiceException)?.key ?: "The weather service could not be reached."
            }
        }
    }

    private fun normalizeManualLayers() {
        manualLayers = CutawayWindLayers.normalized(manualLayers, profile, cutawayAltitudeFeet)
    }

    private fun applyManualWindAndCalculate() {
        val center = circleCenter ?: return
        if (mode != WindSourceMode.MANUAL) return
        windRevision += 1
        windJob?.cancel()
        val sanitized = manualLayers.map {
            it.copy(speedKnots = it.speedKnots.coerceIn(0.0, 200.0), directionFromDegrees = Geodesy.normalizedDegrees(it.directionFromDegrees))
        }
        profile = WindProfile.manual(center, terrainElevationMeters, Instant.now(), sanitized, source = "Manual", model = "User-entered wind layers")
        errorKey = null
        isLoading = false
        calculateIfPossible()
    }

    // Solving

    private fun calculateIfPossible() {
        val center = circleCenter
        val windProfile = profile
        calculationJob?.cancel()
        if (center == null || windProfile == null) {
            solution = null
            return
        }
        val radius = circleRadiusMeters
        val altitude = cutawayAltitudeFeet
        val fallRate = (if (fallRateFeetPerSecond.isFinite()) fallRateFeetPerSecond else 10.0).coerceIn(0.0, 60.0)
        calculationJob = scope.launch {
            delay(30)
            val newSolution = FlightPlanningEngine.cutaway(
                center, radius, altitude * WindMath.METERS_PER_FOOT, fallRate * WindMath.METERS_PER_FOOT, windProfile,
            )
            solution = newSolution
            cameraRequest = CameraRequest.Fit(
                listOf(center, newSolution.landingCenter) + newSolution.centerPath,
                circles = listOf(BoundingCircle(center, radius), BoundingCircle(newSolution.landingCenter, newSolution.landingRadiusMeters)),
                paddingFactor = 1.5,
            )
        }
    }
}
