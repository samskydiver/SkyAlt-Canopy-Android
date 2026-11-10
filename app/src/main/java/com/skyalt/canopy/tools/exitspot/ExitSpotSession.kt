package com.skyalt.canopy.tools.exitspot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.CameraPositionState
import com.skyalt.canopy.app.DistanceUnit
import com.skyalt.canopy.tools.map.CameraRequest
import com.skyalt.canopy.tools.map.DEFAULT_MAP_CENTER
import com.skyalt.canopy.tools.map.PlanningMapStyle
import com.skyalt.canopy.tools.map.WindController
import com.skyalt.canopy.tools.map.latLng
import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.ExitSpotProfileParameters
import com.skyalt.canopy.tools.planning.ExitSpotReferenceSolution
import com.skyalt.canopy.tools.planning.ExitSpotSolution
import com.skyalt.canopy.tools.planning.ExperienceProfile
import com.skyalt.canopy.tools.planning.FlightPlanningEngine
import com.skyalt.canopy.tools.planning.Geodesy
import com.skyalt.canopy.tools.planning.MapGeometry
import com.skyalt.canopy.tools.planning.MapLineOffset
import com.skyalt.canopy.tools.planning.PatternDirection
import com.skyalt.canopy.tools.planning.WindMath
import com.skyalt.canopy.tools.planning.WindProfile
import com.skyalt.canopy.tools.planning.WindProfileClient
import com.skyalt.canopy.tools.planning.WindVector
import androidx.compose.runtime.snapshotFlow
import com.skyalt.canopy.tools.map.cameraPosition
import com.skyalt.canopy.tools.map.savedMapView
import com.skyalt.canopy.tools.planning.ExitSpotSettings
import com.skyalt.canopy.tools.planning.PlanningSettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One wind level in the notice's wind-aloft page. */
data class ExitWindNoticeLevel(val id: Int, val pressureHectopascals: Int?, val altitudeFeet: Double, val wind: WindVector)

val ExitSpotReferenceSolution.mapColor: Color
    get() = when (experienceProfile) {
        ExperienceProfile.STUDENT -> Color(0xFF34C759)
        ExperienceProfile.EXPERIENCED -> Color(0xFFFF9500)
        ExperienceProfile.EXPERT -> Color(0xFFFF3B30)
    }

/** Wider, fainter lines for the slower canopies so all three ranges stay readable. */
val ExitSpotReferenceSolution.exitRangeLineWidthDp: Float
    get() = when (experienceProfile) {
        ExperienceProfile.STUDENT -> 10f
        ExperienceProfile.EXPERIENCED -> 7f
        ExperienceProfile.EXPERT -> 4f
    }

val ExitSpotReferenceSolution.exitRangeOpacity: Float
    get() = when (experienceProfile) {
        ExperienceProfile.STUDENT -> 0.7f
        ExperienceProfile.EXPERIENCED -> 0.85f
        ExperienceProfile.EXPERT -> 1f
    }

/** Signed distances along the jump run: positive past the zero point ("A"), negative before it ("B"). */
object ExitRangeMetrics {
    /** Mean start and end over every profile with a flyable pattern, or null when any has no exit window. */
    fun averageRange(references: List<ExitSpotReferenceSolution>, zeroPoint: Coordinate, bearingDegrees: Double): ClosedFloatingPointRange<Double>? {
        val valid = references.filter { it.hasValidLandingPattern }
        if (valid.isEmpty()) return null
        val starts = valid.mapNotNull { r -> r.exitStart?.let { signedDistance(zeroPoint, it, bearingDegrees) } }
        val ends = valid.mapNotNull { r -> r.exitEnd?.let { signedDistance(zeroPoint, it, bearingDegrees) } }
        if (starts.size != valid.size || ends.size != valid.size) return null
        val start = starts.average()
        val end = ends.average()
        return if (start <= end) start..end else null
    }

    fun signedDistance(zeroPoint: Coordinate, coordinate: Coordinate, bearingDegrees: Double): Double {
        val d = MapGeometry.displacement(zeroPoint, coordinate)
        val r = Math.toRadians(bearingDegrees)
        return d.eastMeters * sin(r) + d.northMeters * cos(r)
    }

    fun formattedDistance(signedMeters: Double, unit: DistanceUnit): String {
        if (abs(signedMeters) < 0.5) return "0"
        return fractionText(unit.fromMeters(abs(signedMeters)), 1, 2) + if (signedMeters > 0) "A" else "B"
    }

    /** A number with between [min] and [max] decimals, as .fractionLength(min...max). */
    fun fractionText(value: Double, min: Int, max: Int): String {
        var text = String.format(Locale.US, "%.${max}f", value)
        val keep = text.indexOf('.') + 1 + min
        while (text.length > keep && text.endsWith("0")) text = text.dropLast(1)
        return text.trimEnd('.')
    }
}

/**
 * State and planning logic for the Exit Spot tool, port of
 * ExitSpotSession.swift. Outlives the screen so the plan survives leaving it.
 */
class ExitSpotSession(
    client: WindProfileClient = WindProfileClient.live,
    /** Where the setup is kept between launches; null starts empty and saves nothing. */
    private val store: PlanningSettingsStore? = null,
) {
    private val scope = MainScope()
    private val saved = ExitSpotSettings.decode(store?.load(ExitSpotSettings.KEY))

    // Map
    /** Opens where the map was left, or on a saved plan, before any GPS fix. */
    val camera = CameraPositionState(
        saved?.mapView?.cameraPosition
            ?: saved?.landingPoint?.let { CameraPosition.fromLatLngZoom(it.latLng(), 15f) }
            ?: CameraPosition.fromLatLngZoom(DEFAULT_MAP_CENTER.latLng(), 15f),
    )
    var cameraRequest by mutableStateOf<CameraRequest?>(null)
    var shouldCenterOnNextLocation = true
    var mapStyle by mutableStateOf(PlanningMapStyle.SATELLITE)
    /** The unit toggled on the map; null follows the app setting. */
    var selectedDistanceUnit by mutableStateOf<DistanceUnit?>(null)
    var fallbackDistanceUnit by mutableStateOf(DistanceUnit.KILOMETERS)
    val distanceUnit get() = selectedDistanceUnit ?: fallbackDistanceUnit

    // Plan inputs
    var landingPoint by mutableStateOf<Coordinate?>(null)
        private set
    var jumpRunCenter by mutableStateOf<Coordinate?>(null)
        private set
    var jumpRunBearingDegrees by mutableDoubleStateOf(0.0)
        private set
    var isJumpRunCustomized by mutableStateOf(false)
        private set
    var exitAltitudeFeet by mutableDoubleStateOf(12_000.0)
        private set
    var deploymentAltitudeFeet by mutableDoubleStateOf(5_000.0)
        private set
    var finalApproachHeadingDegrees by mutableDoubleStateOf(0.0)
        private set
    var isFinalApproachHeadingCustomized = false
    var landingPatternDirection by mutableStateOf(PatternDirection.LEFT)
        private set
    var profileParameters by mutableStateOf(ExitSpotProfileParameters.defaults)
        private set

    // Result and presentation
    var solution by mutableStateOf<ExitSpotSolution?>(null)
        private set
    var calculationMessageKey by mutableStateOf<String?>(null)
        private set
    var envelopePreviewDegrees by mutableStateOf<Double?>(null)
    var noticePage by mutableIntStateOf(0)
    var isNoticeMinimized by mutableStateOf(true)
        private set

    private var calculationJob: Job? = null

    val wind: WindController = WindController(scope, client, anchor = { landingPoint }) {
        val profile = wind.profile
        if (profile != null) {
            updateSuggestedJumpRun()
            updateDefaultFinalApproachHeading(profile)
            refreshSolution()
        } else {
            if (!wind.isLoading) keepsMapOnNextCalculation = false
            invalidateSolution()
        }
    }

    /** A saved plan waits for the tool to open before its wind is fetched. */
    private var needsSavedPlanResume = false
    /** The first calculation of a restored plan keeps the saved map view. */
    private var keepsMapOnNextCalculation = false

    init {
        // Set directly, so nothing is fetched or solved until the tool opens.
        saved?.let { saved ->
            saved.landingPoint?.let { landing ->
                landingPoint = landing
                jumpRunCenter = saved.jumpRunCenter ?: landing
                jumpRunBearingDegrees = Geodesy.normalizedDegrees(saved.jumpRunBearingDegrees)
                isJumpRunCustomized = saved.isJumpRunCustomized
            }
            // The same limits as typing them in.
            val exit = min(max(saved.exitAltitudeFeet, 4_000.0), 18_000.0)
            deploymentAltitudeFeet = min(min(max(saved.deploymentAltitudeFeet, 3_500.0), 17_500.0), exit - 500)
            exitAltitudeFeet = exit
            finalApproachHeadingDegrees = Geodesy.normalizedDegrees(saved.finalApproachHeadingDegrees)
            isFinalApproachHeadingCustomized = saved.isFinalApproachHeadingCustomized
            landingPatternDirection = saved.landingPatternDirection
            profileParameters = saved.restoredProfileParameters
            wind.restore(saved.wind)
            mapStyle = PlanningMapStyle.entries.firstOrNull { it.name == saved.mapStyle } ?: PlanningMapStyle.SATELLITE
            selectedDistanceUnit = DistanceUnit.entries.firstOrNull { it.key == saved.distanceUnit }
            needsSavedPlanResume = landingPoint != null
            if (saved.mapView != null || landingPoint != null) shouldCenterOnNextLocation = false
            keepsMapOnNextCalculation = saved.mapView != null && needsSavedPlanResume
        }
        if (store != null) {
            // Saved half a second after the last change; the screen also saves when it closes.
            scope.launch {
                snapshotFlow { currentSettings() }.drop(1).collectLatest {
                    delay(500)
                    store.save(ExitSpotSettings.KEY, it.encode())
                }
            }
        }
    }

    /** Fetches the wind for a plan restored from the last launch; called when the tool opens. */
    fun resumeSavedPlanIfNeeded() {
        if (!needsSavedPlanResume) return
        needsSavedPlanResume = false
        wind.resumeIfNeeded()
    }

    /** Writes the setup now: when the map comes to rest and when the tool closes. */
    fun saveSettings() {
        store?.save(ExitSpotSettings.KEY, currentSettings().encode())
    }

    private fun currentSettings() = ExitSpotSettings(
        landingPoint = landingPoint,
        jumpRunCenter = jumpRunCenter,
        jumpRunBearingDegrees = jumpRunBearingDegrees,
        isJumpRunCustomized = isJumpRunCustomized,
        exitAltitudeFeet = exitAltitudeFeet,
        deploymentAltitudeFeet = deploymentAltitudeFeet,
        finalApproachHeadingDegrees = finalApproachHeadingDegrees,
        isFinalApproachHeadingCustomized = isFinalApproachHeadingCustomized,
        landingPatternDirection = landingPatternDirection,
        profileParameters = profileParameters,
        wind = wind.saved,
        mapStyle = mapStyle.name,
        distanceUnit = selectedDistanceUnit?.key,
        mapView = camera.position.savedMapView,
    )

    // Derived geometry

    val jumpRunCoordinates: List<Coordinate>
        get() = jumpRunZeroPoint?.let { MapGeometry.lineEndpoints(it, jumpRunBearingDegrees, JUMP_RUN_HALF_LENGTH_METERS) } ?: emptyList()

    /** Where the landing point projects onto the jump run, and how far off the line it sits. */
    val jumpRunOffset: MapLineOffset?
        get() {
            val landing = landingPoint ?: return null
            val center = jumpRunCenter ?: return null
            return MapGeometry.perpendicularOffset(landing, center, jumpRunBearingDegrees)
        }

    /** The "0" mark of the jump run ruler: abeam the landing point. */
    val jumpRunZeroPoint: Coordinate? get() = jumpRunOffset?.projectedCoordinate ?: jumpRunCenter

    val generalExitRangeMeters: ClosedFloatingPointRange<Double>?
        get() {
            val s = solution ?: return null
            val zero = jumpRunZeroPoint ?: return null
            return ExitRangeMetrics.averageRange(s.referenceSolutions, zero, jumpRunBearingDegrees)
        }

    val hasDistantGeneralExitEndpoint: Boolean
        get() {
            val range = generalExitRangeMeters ?: return false
            return abs(range.start) > EXIT_DISTANCE_ADVISORY_METERS || abs(range.endInclusive) > EXIT_DISTANCE_ADVISORY_METERS
        }

    val windNoticeLevels: List<ExitWindNoticeLevel>
        get() {
            val profile = wind.profile ?: return emptyList()
            val surface = profile.terrainElevationMeters
            val exit = surface + exitAltitudeFeet * WindMath.METERS_PER_FOOT
            return profile.samples.filter { it.altitudeMSLMeters >= surface - 1 && it.altitudeMSLMeters <= exit }
                .sortedBy { it.altitudeMSLMeters }
                .mapIndexed { index, sample ->
                    ExitWindNoticeLevel(index, sample.pressureHectopascals, max((sample.altitudeMSLMeters - surface) / WindMath.METERS_PER_FOOT, 0.0), sample.wind)
                }
        }

    // Display text

    val jumpRunBearingText: String get() = Math.round(Geodesy.normalizedDegrees(jumpRunBearingDegrees)).toString()
    val jumpRunDirectionText: String get() = if (jumpRunCenter == null) "—" else "$jumpRunBearingText°"
    val jumpRunOffsetText: String
        get() = jumpRunOffset?.let { "${distanceUnit.format(it.distanceMeters)} ${distanceUnit.shortLabel}" } ?: "—"
    val generalExitRangeText: String
        get() {
            val range = generalExitRangeMeters ?: return "—"
            return "${ExitRangeMetrics.formattedDistance(range.start, distanceUnit)} – ${ExitRangeMetrics.formattedDistance(range.endInclusive, distanceUnit)} ${distanceUnit.shortLabel}"
        }
    val exitDistanceThresholdText: String
        get() = "${ExitRangeMetrics.fractionText(distanceUnit.fromMeters(EXIT_DISTANCE_ADVISORY_METERS), 1, 2)} ${distanceUnit.shortLabel}"

    // Inputs

    /** Exit stays at least 500 ft above deployment. */
    val exitAltitudeRange get() = min(max(deploymentAltitudeFeet + 500, 4_000.0), 18_000.0)..18_000.0
    val deploymentAltitudeRange get() = 3_500.0..max(min(exitAltitudeFeet - 500, 17_500.0), 3_500.0)

    fun updateExitAltitude(feet: Double) {
        val clamped = feet.coerceIn(exitAltitudeRange)
        if (clamped == exitAltitudeFeet) return
        exitAltitudeFeet = clamped
        updateSuggestedJumpRun()
        refreshSolution()
    }

    fun updateDeploymentAltitude(feet: Double) {
        val clamped = feet.coerceIn(deploymentAltitudeRange)
        if (clamped == deploymentAltitudeFeet) return
        deploymentAltitudeFeet = clamped
        refreshSolution()
    }

    fun updateFinalApproachHeading(degrees: Double) {
        isFinalApproachHeadingCustomized = true
        val normalized = Geodesy.normalizedDegrees(degrees)
        if (normalized == finalApproachHeadingDegrees) return
        finalApproachHeadingDegrees = normalized
        refreshSolution(requestedFit = false)
    }

    fun updateLandingPatternDirection(direction: PatternDirection) {
        if (direction == landingPatternDirection) return
        landingPatternDirection = direction
        refreshSolution(requestedFit = false)
    }

    fun toggleNotice() {
        isNoticeMinimized = !isNoticeMinimized
        val s = solution ?: return
        val landing = landingPoint ?: return
        fitMap(s, landing)
    }

    // Landing point and jump run

    /** A customized jump run keeps its offset from the landing point; otherwise it is re-centred on it. */
    fun placeLandingPoint(coordinate: Coordinate, isInitialPlacement: Boolean) {
        val previousLanding = landingPoint
        val previousCenter = jumpRunCenter
        landingPoint = coordinate
        jumpRunCenter = if (!isInitialPlacement && isJumpRunCustomized && previousLanding != null && previousCenter != null) {
            MapGeometry.coordinate(coordinate, MapGeometry.displacement(previousLanding, previousCenter))
        } else {
            coordinate
        }
        if (isInitialPlacement) {
            jumpRunBearingDegrees = 0.0
            isJumpRunCustomized = false
        }
        wind.reload()
    }

    fun resetPlan() {
        wind.clear()
        envelopePreviewDegrees = null
        landingPoint = null
        jumpRunCenter = null
        jumpRunBearingDegrees = 0.0
        isJumpRunCustomized = false
        noticePage = 0
        invalidateSolution()
    }

    fun moveJumpRun(center: Coordinate) {
        jumpRunCenter = center
        isJumpRunCustomized = true
        refreshSolution(requestedFit = false)
    }

    fun rotateJumpRun(bearingDegrees: Double) {
        jumpRunBearingDegrees = Geodesy.normalizedDegrees(bearingDegrees)
        isJumpRunCustomized = true
        refreshSolution(requestedFit = false)
    }

    /** Re-anchors the run abeam the landing point so later rotations pivot there. */
    fun snapJumpRunCenterToZeroPoint() {
        jumpRunZeroPoint?.let { jumpRunCenter = it }
    }

    fun finishJumpRunAdjustment() {
        snapJumpRunCenterToZeroPoint()
        refreshSolution(requestedFit = false)
    }

    // Envelope rotation

    fun beginEnvelopeRotation() {
        calculationJob?.cancel()
        isFinalApproachHeadingCustomized = true
    }

    fun finishEnvelopeRotation(initialHeadingDegrees: Double, deltaDegrees: Double) {
        if (abs(deltaDegrees) > 0.0001) {
            updateFinalApproachHeading(initialHeadingDegrees + deltaDegrees)
        } else {
            envelopePreviewDegrees = null
        }
    }

    // Map camera and chrome

    fun handleLocationFix(coordinate: Coordinate) {
        if (!shouldCenterOnNextLocation) return
        cameraRequest = CameraRequest.Center(coordinate)
        shouldCenterOnNextLocation = false
    }

    fun requestCentering(coordinate: Coordinate?) {
        shouldCenterOnNextLocation = true
        if (coordinate != null) handleLocationFix(coordinate)
    }

    fun cycleMapStyle() {
        mapStyle = mapStyle.next
    }

    fun toggleDistanceUnit() {
        selectedDistanceUnit = if (distanceUnit == DistanceUnit.KILOMETERS) DistanceUnit.MILES else DistanceUnit.KILOMETERS
    }

    // Wind-driven defaults

    /** Until the user moves the run, it is centred on the landing point and points into the wind at exit altitude. */
    private fun updateSuggestedJumpRun() {
        val landing = landingPoint ?: return
        if (jumpRunCenter == null) jumpRunCenter = landing
        val profile = wind.profile ?: return
        if (isJumpRunCustomized) return
        val exitWind = profile.windAtAGL(exitAltitudeFeet * WindMath.METERS_PER_FOOT)
        val w = if (exitWind.speedMetersPerSecond > 0.1) exitWind else profile.windAtAGL(0.0)
        jumpRunCenter = landing
        jumpRunBearingDegrees = if (w.speedMetersPerSecond > 0.1) Geodesy.normalizedDegrees(w.towardBearingDegrees + 180) else 0.0
    }

    /** Final approach faces the surface wind, or follows the jump run in calm air, until the user sets a heading. */
    private fun updateDefaultFinalApproachHeading(profile: WindProfile) {
        if (isFinalApproachHeadingCustomized) return
        val surface = profile.windAtAGL(0.0)
        finalApproachHeadingDegrees = if (surface.speedMetersPerSecond > 0.1) Geodesy.normalizedDegrees(surface.towardBearingDegrees + 180)
        else Geodesy.normalizedDegrees(jumpRunBearingDegrees)
    }

    // Solving

    private fun refreshSolution(requestedFit: Boolean = true) {
        val shouldFitMap = requestedFit && !keepsMapOnNextCalculation
        keepsMapOnNextCalculation = false
        val landing = landingPoint
        val profile = wind.profile
        val run = jumpRunCoordinates
        if (landing == null || jumpRunCenter == null || profile == null || run.size < 2) {
            invalidateSolution()
            return
        }
        val exit = exitAltitudeFeet
        val deploy = deploymentAltitudeFeet
        val parameters = profileParameters
        val heading = finalApproachHeadingDegrees
        val direction = landingPatternDirection
        calculationJob?.cancel()
        calculationJob = scope.launch {
            delay(30)
            val newSolution = FlightPlanningEngine.exitSpot(
                landing, run.first(), run.last(),
                exit * WindMath.METERS_PER_FOOT, deploy * WindMath.METERS_PER_FOOT,
                profile, parameters, heading, direction,
            )
            solution = newSolution
            envelopePreviewDegrees = null
            calculationMessageKey = message(newSolution)
            if (newSolution != null && shouldFitMap) fitMap(newSolution, landing)
        }
    }

    private fun message(solution: ExitSpotSolution?): String? = when {
        solution == null -> "Exit altitude must be above estimated opening altitude, and deployment must allow enough canopy time to reach the 900 ft point."
        !solution.hasAnyValidLandingPattern -> "Low-level wind prevents all modeled canopies from flying a forward landing pattern."
        !solution.hasAnyExitRange -> "The jump run does not intersect the modeled Student, Experienced, or Expert reachable areas."
        else -> null
    }

    private fun fitMap(solution: ExitSpotSolution, landing: Coordinate) {
        val coordinates = mutableListOf(landing, solution.targetPoint)
        jumpRunOffset?.let { coordinates += it.projectedCoordinate }
        for (reference in solution.referenceSolutions) {
            coordinates += reference.passiveDriftPath
            coordinates += reference.landingPatternPath
            val start = reference.exitStart
            val end = reference.exitEnd
            if (start != null && end != null) {
                coordinates += start
                coordinates += end
            }
        }
        cameraRequest = CameraRequest.Fit(coordinates)
    }

    private fun invalidateSolution() {
        calculationJob?.cancel()
        envelopePreviewDegrees = null
        solution = null
        calculationMessageKey = null
    }

    companion object {
        const val EXIT_DISTANCE_ADVISORY_METERS = 1.2 * 1_609.344
        private const val JUMP_RUN_HALF_LENGTH_METERS = 50_000.0
    }
}
