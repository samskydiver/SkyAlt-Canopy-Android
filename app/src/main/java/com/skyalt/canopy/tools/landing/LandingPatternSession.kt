package com.skyalt.canopy.tools.landing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.CameraPositionState
import com.skyalt.canopy.tools.map.CameraRequest
import com.skyalt.canopy.tools.map.DEFAULT_MAP_CENTER
import com.skyalt.canopy.tools.map.DEFAULT_MAP_ZOOM
import com.skyalt.canopy.tools.map.PlanningMapStyle
import com.skyalt.canopy.tools.map.WindController
import com.skyalt.canopy.tools.map.latLng
import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.Geodesy
import com.skyalt.canopy.tools.planning.LandingPatternMode
import com.skyalt.canopy.tools.planning.LandingPatternPlanningEngine
import com.skyalt.canopy.tools.planning.LandingPatternSolution
import com.skyalt.canopy.tools.planning.LocalDisplacement
import com.skyalt.canopy.tools.planning.MapGeometry
import com.skyalt.canopy.tools.planning.PatternDirection
import com.skyalt.canopy.tools.planning.SkillLevel
import com.skyalt.canopy.tools.planning.SwoopPatternSolution
import com.skyalt.canopy.tools.planning.SwoopWaypoint
import com.skyalt.canopy.tools.planning.WindMath
import com.skyalt.canopy.tools.planning.WindProfileClient
import androidx.compose.runtime.snapshotFlow
import com.skyalt.canopy.tools.map.cameraPosition
import com.skyalt.canopy.tools.map.savedMapView
import com.skyalt.canopy.tools.planning.LandingPatternSettings
import com.skyalt.canopy.tools.planning.PlanningSettingsStore
import com.skyalt.canopy.tools.planning.SavedMapView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Which point of a swoop route is selected or being dragged. */
sealed interface SwoopSelection {
    data object Start : SwoopSelection
    data object Landing : SwoopSelection
    data class Waypoint(val id: String) : SwoopSelection
}

data class SwoopDragPreview(val selection: SwoopSelection, val coordinate: Coordinate)

/** One calculated pattern drawn on the map, in its skill level's colour. */
data class DisplaySolution(val id: String, val level: SkillLevel, val solution: LandingPatternSolution, val color: Color)

/** The small arrow drawn at the middle of each leg. */
data class LegArrow(val id: String, val coordinate: Coordinate, val bearingDegrees: Double, val color: Color)

/** A swoop route point as shown on the map and in the result card. */
data class SwoopDisplayPoint(val id: String, val selection: SwoopSelection, val label: String, val coordinate: Coordinate, val altitudeFeet: Double?, val color: Color)

val SWOOP_CYAN = Color(0xFF32ADE6)
val SWOOP_BLUE = Color(0xFF0A84FF)
val PATTERN_GREEN = Color(0xFF34C759)

val SkillLevel.patternColor: Color
    get() = when (this) {
        SkillLevel.STUDENT -> PATTERN_GREEN
        SkillLevel.EXPERIENCED -> Color(0xFFFF9500)
        SkillLevel.EXPERT -> Color(0xFFFF3B30)
    }

/**
 * State and logic of the Landing Pattern Assistant, port of
 * LandingPatternAssistantSession.swift. Kept for the life of the app so a
 * plan survives leaving and re-entering the tool.
 */
class LandingPatternSession(
    client: WindProfileClient = WindProfileClient.live,
    /** Where the setup is kept between launches; null starts empty and saves nothing. */
    private val store: PlanningSettingsStore? = null,
) {
    private val scope = MainScope()
    private val saved = LandingPatternSettings.decode(store?.load(LandingPatternSettings.KEY))

    // Map
    /** Opens where the map was left, or on a saved plan, before any GPS fix. */
    val camera = CameraPositionState(
        saved?.mapView?.cameraPosition
            ?: (saved?.let { if (it.mode == LandingPatternMode.NORMAL) it.landingPoint else it.swoopStartPoint })
                ?.let { CameraPosition.fromLatLngZoom(it.latLng(), DEFAULT_MAP_ZOOM) }
            ?: CameraPosition.fromLatLngZoom(DEFAULT_MAP_CENTER.latLng(), DEFAULT_MAP_ZOOM),
    )
    var cameraRequest by mutableStateOf<CameraRequest?>(null)
    var shouldCenterOnNextLocation = true
    /** While the 3D view borrows the map to photograph the ground, the view to save instead of the borrowed one. */
    var heldMapView: SavedMapView? = null
    var mapStyle by mutableStateOf(PlanningMapStyle.SATELLITE)

    // Mode
    var mode by mutableStateOf(LandingPatternMode.NORMAL)
        private set

    // Standard pattern
    var landingPoint by mutableStateOf<Coordinate?>(null)
        private set
    var finalHeadingDegrees by mutableDoubleStateOf(0.0)
        private set
    var patternDirection by mutableStateOf(PatternDirection.LEFT)
        private set
    var altitudeStepFeet by mutableDoubleStateOf(300.0)
        private set
    var normalSolutions by mutableStateOf<Map<SkillLevel, LandingPatternSolution>>(emptyMap())
        private set
    var hiddenLevels by mutableStateOf<Set<SkillLevel>>(emptySet())
        private set

    // Swoop route
    var swoopStartPoint by mutableStateOf<Coordinate?>(null)
        private set
    var swoopLandingPoint by mutableStateOf<Coordinate?>(null)
        private set
    var swoopWaypoints by mutableStateOf<List<SwoopWaypoint>>(emptyList())
        private set
    var selectedSwoopPoint by mutableStateOf<SwoopSelection?>(null)
        private set
    var newlyAddedWaypointId by mutableStateOf<String?>(null)
        private set
    var isSwoopLandingUserAdjusted by mutableStateOf(false)
        private set
    var swoopStartAltitudeFeet by mutableDoubleStateOf(300.0)
        private set
    var pendingSwoopStartPoint by mutableStateOf<Coordinate?>(null)
        private set
    var swoopDragPreview by mutableStateOf<SwoopDragPreview?>(null)
        private set
    var expertAirspeedKnots by mutableDoubleStateOf(25.0)
        private set
    var expertGlideRatio by mutableDoubleStateOf(2.2)
        private set
    var swoopSolution by mutableStateOf<SwoopPatternSolution?>(null)
        private set

    /** Rotation of the drawn patterns about the landing point while one is being dragged round. */
    var envelopePreviewDegrees by mutableStateOf<Double?>(null)

    private var calculationJob: Job? = null
    private var fitOnNextWind = true

    val wind = WindController(scope, client, anchor = { windAnchor }) {
        recalculate(shouldFitMap = fitOnNextWind)
        fitOnNextWind = true
    }

    /** A saved plan waits for the tool to open before its wind is fetched. */
    private var needsSavedPlanResume = false
    /** The first calculation of a restored plan keeps the saved map view. */
    private var keepsMapOnNextCalculation = false

    init {
        // Set directly, so nothing is fetched or solved until the tool opens.
        saved?.let { saved ->
            mode = saved.mode
            landingPoint = saved.landingPoint
            finalHeadingDegrees = Geodesy.normalizedDegrees(saved.finalHeadingDegrees)
            patternDirection = saved.patternDirection
            altitudeStepFeet = min(max(saved.altitudeStepFeet, 300.0), 2_000.0)
            hiddenLevels = saved.hiddenLevels
            saved.swoopStartPoint?.let { start ->
                swoopStartPoint = start
                swoopStartAltitudeFeet = max(saved.swoopStartAltitudeFeet, 1.0)
                swoopLandingPoint = saved.swoopLandingPoint ?: suggestedSwoopLandingPoint(start)
                isSwoopLandingUserAdjusted = saved.isSwoopLandingUserAdjusted && saved.swoopLandingPoint != null
                swoopWaypoints = saved.swoopWaypoints.map { SwoopWaypoint(coordinate = it) }
            }
            expertAirspeedKnots = min(max(saved.expertAirspeedKnots, 1.0), 80.0)
            expertGlideRatio = min(max(saved.expertGlideRatio, 0.1), 10.0)
            wind.restore(saved.wind)
            mapStyle = PlanningMapStyle.entries.firstOrNull { it.name == saved.mapStyle } ?: PlanningMapStyle.SATELLITE
            needsSavedPlanResume = windAnchor != null
            if (saved.mapView != null || windAnchor != null) shouldCenterOnNextLocation = false
            keepsMapOnNextCalculation = saved.mapView != null && needsSavedPlanResume
        }
        if (store != null) {
            // Saved half a second after the last change; the screen also saves when it closes.
            scope.launch {
                snapshotFlow { currentSettings() }.drop(1).collectLatest {
                    delay(500)
                    store.save(LandingPatternSettings.KEY, it.encode())
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
        store?.save(LandingPatternSettings.KEY, currentSettings().encode())
    }

    private fun currentSettings() = LandingPatternSettings(
        mode = mode,
        landingPoint = landingPoint,
        finalHeadingDegrees = finalHeadingDegrees,
        patternDirection = patternDirection,
        altitudeStepFeet = altitudeStepFeet,
        hiddenLevels = hiddenLevels,
        swoopStartPoint = swoopStartPoint,
        swoopStartAltitudeFeet = swoopStartAltitudeFeet,
        swoopLandingPoint = swoopLandingPoint,
        isSwoopLandingUserAdjusted = isSwoopLandingUserAdjusted,
        swoopWaypoints = swoopWaypoints.map { it.coordinate },
        expertAirspeedKnots = expertAirspeedKnots,
        expertGlideRatio = expertGlideRatio,
        wind = wind.saved,
        mapStyle = mapStyle.name,
        mapView = heldMapView ?: camera.position.savedMapView,
    )

    // Derived state

    /** The landing point in Standard Mode, the swoop start point in Swoop Mode. */
    val windAnchor: Coordinate? get() = if (mode == LandingPatternMode.NORMAL) landingPoint else swoopStartPoint
    val hasBasePoint get() = windAnchor != null

    // Pro access

    /** Swoop Mode is a Pro feature; fall back to Standard Mode without it. */
    fun enforceProAccess(isPro: Boolean) {
        if (!isPro && mode == LandingPatternMode.EXPERT) selectMode(LandingPatternMode.NORMAL)
    }

    fun selectMode(newMode: LandingPatternMode) {
        if (newMode == mode) return
        mode = newMode
        cancelPendingCalculation()
        envelopePreviewDegrees = null
        selectedSwoopPoint = null
        newlyAddedWaypointId = null
        pendingSwoopStartPoint = null
        if (windAnchor != null) {
            wind.reload()
        } else {
            wind.clear()
            normalSolutions = emptyMap()
            swoopSolution = null
        }
    }

    // Standard inputs

    fun updateFinalHeading(degrees: Double) {
        if (degrees == finalHeadingDegrees) return
        finalHeadingDegrees = degrees
        updateSuggestedSwoopLandingPoint()
        recalculate(shouldFitMap = false)
    }

    fun updatePatternDirection(direction: PatternDirection) {
        if (direction == patternDirection) return
        patternDirection = direction
        recalculate(shouldFitMap = false)
    }

    fun updateAltitudeStep(feet: Double) {
        val clamped = max(feet, 300.0)
        if (clamped == altitudeStepFeet) return
        altitudeStepFeet = clamped
        recalculate(shouldFitMap = false)
    }

    fun updateExpertAirspeed(knots: Double) {
        val clamped = min(max(knots, 1.0), 80.0)
        if (clamped == expertAirspeedKnots) return
        expertAirspeedKnots = clamped
        recalculate(shouldFitMap = false)
    }

    fun updateExpertGlideRatio(ratio: Double) {
        val clamped = min(max(ratio, 0.1), 10.0)
        if (clamped == expertGlideRatio) return
        expertGlideRatio = clamped
        recalculate(shouldFitMap = false)
    }

    // Standard pattern editing

    fun placeStandardLandingPoint(coordinate: Coordinate) {
        if (mode != LandingPatternMode.NORMAL || landingPoint != null) return
        landingPoint = coordinate
        wind.reload()
    }

    fun moveStandardLandingPoint(coordinate: Coordinate) {
        if (mode != LandingPatternMode.NORMAL) return
        landingPoint = coordinate
        wind.reload()
    }

    fun toggleVisibility(level: SkillLevel) {
        hiddenLevels = if (level in hiddenLevels) hiddenLevels - level else hiddenLevels + level
    }

    // Swoop route editing

    /** The first tap in Swoop Mode proposes A; it becomes the start once its altitude is entered. */
    fun beginSwoopStart(coordinate: Coordinate) {
        if (mode != LandingPatternMode.EXPERT || swoopStartPoint != null) return
        pendingSwoopStartPoint = coordinate
    }

    fun cancelSwoopStart() {
        pendingSwoopStartPoint = null
    }

    fun confirmSwoopStart(altitudeFeet: Double) {
        val start = pendingSwoopStartPoint ?: return
        pendingSwoopStartPoint = null
        swoopStartPoint = start
        swoopStartAltitudeFeet = max(altitudeFeet, 1.0)
        swoopLandingPoint = suggestedSwoopLandingPoint(start)
        isSwoopLandingUserAdjusted = false
        swoopWaypoints = emptyList()
        selectedSwoopPoint = SwoopSelection.Start
        newlyAddedWaypointId = null
        swoopSolution = null
        wind.reload()
    }

    fun addSwoopWaypoint(coordinate: Coordinate) {
        if (mode != LandingPatternMode.EXPERT || swoopStartPoint == null) return
        val waypoint = SwoopWaypoint(coordinate = coordinate)
        swoopWaypoints = swoopWaypoints + waypoint
        newlyAddedWaypointId = waypoint.id
        selectedSwoopPoint = SwoopSelection.Waypoint(waypoint.id)
        recalculate(shouldFitMap = false)
    }

    fun selectSwoopPoint(selection: SwoopSelection) {
        if (selection != newlyAddedWaypointId?.let { SwoopSelection.Waypoint(it) }) newlyAddedWaypointId = null
        selectedSwoopPoint = selection
    }

    fun finishAddingSwoopWaypoint() {
        newlyAddedWaypointId = null
        selectedSwoopPoint = null
    }

    fun cancelSwoopWaypointSelection() {
        selectedSwoopPoint = null
    }

    fun deleteSwoopWaypoint(id: String) {
        swoopWaypoints = swoopWaypoints.filter { it.id != id }
        if (newlyAddedWaypointId == id) newlyAddedWaypointId = null
        selectedSwoopPoint = null
        recalculate(shouldFitMap = false)
    }

    fun previewSwoopPointDrag(selection: SwoopSelection, coordinate: Coordinate) {
        if (selectedSwoopPoint != selection) selectSwoopPoint(selection)
        swoopDragPreview = SwoopDragPreview(selection, coordinate)
    }

    fun finishDraggingSwoopPoint(selection: SwoopSelection, coordinate: Coordinate) {
        swoopDragPreview = null
        when (selection) {
            SwoopSelection.Start -> {
                swoopStartPoint = coordinate
                if (!isSwoopLandingUserAdjusted) swoopLandingPoint = suggestedSwoopLandingPoint(coordinate)
                wind.reload()
            }
            SwoopSelection.Landing -> {
                // L is downstream of the altitude-defined A and does not feed the
                // reverse-waypoint altitudes; dragging it only moves the final leg.
                swoopLandingPoint = coordinate
                isSwoopLandingUserAdjusted = true
            }
            is SwoopSelection.Waypoint -> {
                swoopWaypoints = swoopWaypoints.map { if (it.id == selection.id) it.copy(coordinate = coordinate) else it }
                recalculate(shouldFitMap = false)
            }
        }
    }

    /** L is proposed 300 ft beyond A along the final heading until the user drags it. */
    fun suggestedSwoopLandingPoint(start: Coordinate): Coordinate =
        MapGeometry.coordinate(start, 300 * WindMath.METERS_PER_FOOT, Geodesy.normalizedDegrees(finalHeadingDegrees))

    private fun updateSuggestedSwoopLandingPoint() {
        val start = swoopStartPoint ?: return
        if (mode != LandingPatternMode.EXPERT || isSwoopLandingUserAdjusted) return
        swoopLandingPoint = suggestedSwoopLandingPoint(start)
    }

    // Reset

    fun resetPlan() {
        wind.clear()
        cancelPendingCalculation()
        envelopePreviewDegrees = null
        if (mode == LandingPatternMode.NORMAL) {
            landingPoint = null
            normalSolutions = emptyMap()
        } else {
            swoopStartPoint = null
            swoopLandingPoint = null
            swoopWaypoints = emptyList()
            selectedSwoopPoint = null
            newlyAddedWaypointId = null
            swoopDragPreview = null
            isSwoopLandingUserAdjusted = false
            pendingSwoopStartPoint = null
            swoopSolution = null
        }
    }

    // Envelope rotation

    fun beginEnvelopeRotation() = cancelPendingCalculation()

    fun finishEnvelopeRotation(initialHeadingDegrees: Double, deltaDegrees: Double) {
        if (abs(deltaDegrees) > 0.0001) {
            // The heading change recalculates and clears the preview once the rotated pattern is ready.
            updateFinalHeading(Geodesy.normalizedDegrees(initialHeadingDegrees + deltaDegrees))
        } else {
            envelopePreviewDegrees = null
        }
    }

    // Calculation

    /** Debounced so a slide over a number solves once per burst. */
    private fun recalculate(shouldFitMap: Boolean = true) {
        if (wind.profile == null || !hasBasePoint) {
            if (!wind.isLoading) keepsMapOnNextCalculation = false
            cancelPendingCalculation()
            envelopePreviewDegrees = null
            normalSolutions = emptyMap()
            swoopSolution = null
            return
        }
        calculationJob?.cancel()
        calculationJob = scope.launch {
            delay(30)
            performRecalculation(shouldFitMap)
        }
    }

    private fun performRecalculation(requestedFit: Boolean) {
        val shouldFitMap = requestedFit && !keepsMapOnNextCalculation
        keepsMapOnNextCalculation = false
        val profile = wind.profile ?: run {
            normalSolutions = emptyMap(); swoopSolution = null; return
        }
        when (mode) {
            LandingPatternMode.NORMAL -> {
                val landing = landingPoint ?: run { normalSolutions = emptyMap(); swoopSolution = null; return }
                val calculated = SkillLevel.entries.associateWith { level ->
                    LandingPatternPlanningEngine.standard(
                        landing, finalHeadingDegrees, patternDirection, altitudeStepFeet,
                        level.airspeedKnots, level.glideRatio, profile,
                    )
                }
                normalSolutions = calculated
                envelopePreviewDegrees = null
                swoopSolution = null
                if (shouldFitMap) {
                    val coordinates = SkillLevel.entries.flatMap { calculated[it]?.path ?: emptyList() }
                    if (coordinates.size >= 2) cameraRequest = CameraRequest.Fit(coordinates)
                }
            }
            LandingPatternMode.EXPERT -> {
                val start = swoopStartPoint ?: run { normalSolutions = emptyMap(); swoopSolution = null; return }
                swoopSolution = LandingPatternPlanningEngine.swoop(
                    start, swoopStartAltitudeFeet, swoopWaypoints, expertAirspeedKnots, expertGlideRatio, profile,
                )
                normalSolutions = emptyMap()
                val route = swoopRouteCoordinates(null)
                if (shouldFitMap && route.size >= 2) cameraRequest = CameraRequest.Fit(route)
            }
        }
    }

    private fun cancelPendingCalculation() {
        calculationJob?.cancel()
        calculationJob = null
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

    // Display

    val displaySolutions: List<DisplaySolution>
        get() = if (mode != LandingPatternMode.NORMAL) emptyList() else SkillLevel.entries.mapNotNull { level ->
            val solution = normalSolutions[level] ?: return@mapNotNull null
            if (level in hiddenLevels) null else DisplaySolution("normal-${level.name}", level, solution, level.patternColor)
        }

    val legArrows: List<LegArrow>
        get() = displaySolutions.flatMap { legArrows(it.solution.path, "${it.id}-leg", it.color) }

    /** The route with the point being dragged at its preview position: waypoints in flight order, then A and L. */
    fun swoopRouteCoordinates(preview: SwoopDragPreview? = swoopDragPreview): List<Coordinate> {
        val startPoint = swoopStartPoint ?: return emptyList()
        val landingPoint = swoopLandingPoint ?: return emptyList()
        val start = if (preview?.selection == SwoopSelection.Start) preview.coordinate else startPoint
        val landing = when {
            preview?.selection == SwoopSelection.Landing -> preview.coordinate
            preview?.selection == SwoopSelection.Start && !isSwoopLandingUserAdjusted -> suggestedSwoopLandingPoint(start)
            else -> landingPoint
        }
        val waypoints = swoopWaypoints.reversed().map { w ->
            if (preview?.selection == SwoopSelection.Waypoint(w.id)) preview.coordinate else w.coordinate
        }
        return waypoints + listOf(start, landing)
    }

    val swoopLegArrows: List<LegArrow> get() = legArrows(swoopRouteCoordinates(), "swoop-leg", SWOOP_CYAN)

    val swoopDisplayPoints: List<SwoopDisplayPoint>
        get() {
            val start = swoopStartPoint ?: return emptyList()
            val landing = swoopLandingPoint ?: return emptyList()
            val waypoints = swoopWaypoints.withIndex().reversed().map { (index, w) ->
                SwoopDisplayPoint(w.id, SwoopSelection.Waypoint(w.id), waypointLabel(index), w.coordinate, swoopSolution?.altitudeFeet(w.id), SWOOP_CYAN)
            }
            return waypoints + listOf(
                SwoopDisplayPoint("swoop-start", SwoopSelection.Start, "A", start, swoopStartAltitudeFeet, SWOOP_BLUE),
                SwoopDisplayPoint("swoop-landing", SwoopSelection.Landing, "L", landing, 0.0, PATTERN_GREEN),
            )
        }

    val selectedDeletableWaypointId: String?
        get() {
            val selection = selectedSwoopPoint as? SwoopSelection.Waypoint ?: return null
            if (mode != LandingPatternMode.EXPERT || selection.id == newlyAddedWaypointId) return null
            return selection.id.takeIf { id -> swoopWaypoints.any { it.id == id } }
        }

    val selectedNewWaypointId: String?
        get() {
            val id = newlyAddedWaypointId ?: return null
            if (mode != LandingPatternMode.EXPERT || selectedSwoopPoint != SwoopSelection.Waypoint(id)) return null
            return id.takeIf { swoopWaypoints.any { it.id == id } }
        }

    /** The highest altitude the wind sheet lists layers for. */
    val maximumRelevantAltitudeFeet: Double
        get() = if (mode == LandingPatternMode.NORMAL) altitudeStepFeet * 3
        else max(swoopSolution?.altitudeByWaypointId?.values?.maxOrNull() ?: 0.0, swoopStartAltitudeFeet)

    val isPatternComplete: Boolean
        get() = if (mode == LandingPatternMode.NORMAL) normalSolutions.size == SkillLevel.entries.size
        else swoopStartPoint != null && swoopLandingPoint != null

    val hasPlanningIssues: Boolean
        get() = if (mode == LandingPatternMode.NORMAL) normalSolutions.values.any { it.issues.isNotEmpty() }
        else swoopSolution?.uncalculatedWaypointIds?.isNotEmpty() == true

    val currentInstructionKey: String
        get() {
            if (wind.isLoading) return "Loading wind data and calculating the pattern…"
            return if (mode == LandingPatternMode.NORMAL) when {
                landingPoint == null -> "Tap the map to set the landing point."
                normalSolutions.isEmpty() -> "Open Wind Data to provide winds for the pattern."
                else -> "Suggested landing pattern ready. Verify it with local guidance."
            } else when {
                swoopStartPoint == null -> "Tap the map to set the swoop start point."
                wind.profile == null -> "Open Wind Data to continue."
                swoopWaypoints.isEmpty() -> "The A → L route is ready. Add reverse-order waypoints if needed."
                else -> "Custom swoop route ready. Verify every waypoint and altitude."
            }
        }

    val basePointInstructionKey: String
        get() = if (mode == LandingPatternMode.NORMAL) "Tap the map to set the landing point." else "Tap the map to set the swoop start point."

    val waypointInstructionKey: String
        get() = if (isSwoopLandingUserAdjusted) "Tap the map to add a waypoint. Drag any point to adjust it."
        else "Drag landing point L to your planned position."

    val resetConfirmationKey: String
        get() = if (mode == LandingPatternMode.NORMAL) "This removes the landing point and calculated pattern." else "This removes A, L, and all added waypoints."

    companion object {
        /** Waypoints are lettered B, C, D… in creation order, skipping L, which is the landing point. */
        fun waypointLabel(index: Int): String {
            var candidate = 2
            var remaining = max(index, 0)
            while (true) {
                val label = alphabeticLabel(candidate)
                if (label != "L") {
                    if (remaining == 0) return label
                    remaining -= 1
                }
                candidate += 1
            }
        }

        /** 1 → A, 26 → Z, 27 → AA, like spreadsheet columns. */
        fun alphabeticLabel(ordinal: Int): String {
            var value = max(ordinal, 1)
            val characters = StringBuilder()
            while (value > 0) {
                value -= 1
                characters.append('A' + value % 26)
                value /= 26
            }
            return characters.reverse().toString()
        }

        fun legArrows(path: List<Coordinate>, idPrefix: String, color: Color): List<LegArrow> =
            path.zipWithNext().mapIndexed { index, (a, b) ->
                LegArrow("$idPrefix-$index", MapGeometry.midpoint(a, b), MapGeometry.bearingDegrees(a, b), color)
            }

        /** Rotates map coordinates clockwise about [pivot] by [degrees]. */
        fun rotated(coordinates: List<Coordinate>, pivot: Coordinate, degrees: Double): List<Coordinate> {
            val r = Math.toRadians(degrees)
            return coordinates.map {
                val d = MapGeometry.displacement(pivot, it)
                MapGeometry.coordinate(pivot, LocalDisplacement(d.eastMeters * cos(r) + d.northMeters * sin(r), -d.eastMeters * sin(r) + d.northMeters * cos(r)))
            }
        }
    }
}
