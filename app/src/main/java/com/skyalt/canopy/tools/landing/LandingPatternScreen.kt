package com.skyalt.canopy.tools.landing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.skyalt.canopy.app.AltitudeUnit
import com.skyalt.canopy.app.AppSettings
import com.skyalt.canopy.app.ProFeature
import com.skyalt.canopy.tools.AltixOrange
import com.skyalt.canopy.tools.SegmentedPicker
import com.skyalt.canopy.tools.SwipeNumberInput
import com.skyalt.canopy.tools.ToolScaffold
import com.skyalt.canopy.tools.map.ApplyCameraRequests
import com.skyalt.canopy.tools.map.Planning3DButtons
import com.skyalt.canopy.tools.map.Planning3DGroundLoader
import com.skyalt.canopy.tools.map.Planning3DLayer
import com.skyalt.canopy.tools.map.planning3DObstruction
import com.skyalt.canopy.tools.map.Planning3DMapHook
import com.skyalt.canopy.tools.map.Planning3DState
import com.skyalt.canopy.tools.map.rememberPlanning3DState
import com.skyalt.canopy.tools.planning.Path3DMarker
import com.skyalt.canopy.tools.planning.Path3DPath
import com.skyalt.canopy.tools.planning.Path3DPoint
import com.skyalt.canopy.tools.planning.PlanningPath3DContent
import androidx.compose.ui.graphics.toArgb
import com.skyalt.canopy.tools.map.SavesPlanningSettings
import com.skyalt.canopy.tools.map.BasePointBadge
import com.skyalt.canopy.tools.map.DraggableMapMarker
import com.skyalt.canopy.tools.map.LabeledDot
import com.skyalt.canopy.tools.map.LegArrowBadge
import com.skyalt.canopy.tools.map.MapButton
import com.skyalt.canopy.tools.map.MapInstructionBanner
import com.skyalt.canopy.tools.map.MapLabel
import com.skyalt.canopy.tools.map.MapLoadingPill
import com.skyalt.canopy.tools.map.PlanningManualWindSection
import com.skyalt.canopy.tools.map.StaticMapMarker
import com.skyalt.canopy.tools.map.WindDataSheet
import com.skyalt.canopy.tools.map.WindSheetText
import com.skyalt.canopy.tools.map.coordinate
import com.skyalt.canopy.tools.map.formatMonthDayTime
import com.skyalt.canopy.tools.map.grouped
import com.skyalt.canopy.tools.map.labeledDotAnchor
import com.skyalt.canopy.tools.map.latLng
import com.skyalt.canopy.tools.map.patternRotationGesture
import com.skyalt.canopy.tools.map.rememberMapLocationTracker
import com.skyalt.canopy.tools.map.windErrorText
import com.skyalt.canopy.tools.planning.CheckpointKind
import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.Geodesy
import com.skyalt.canopy.tools.planning.LandingPatternMode
import com.skyalt.canopy.tools.planning.PatternDirection
import com.skyalt.canopy.tools.planning.SkillLevel
import com.skyalt.canopy.tools.planning.WindMath
import com.skyalt.canopy.ui.LocalStrings
import com.skyalt.canopy.ui.Strings
import com.skyalt.canopy.ui.formatNumber
import com.skyalt.canopy.ui.parseNumber
import java.time.Duration
import java.time.Instant

/** "1,200 ft", or "1,200 ft · 366 m" when the app shows meters: swoop route points. */
fun swoopPointLabel(feet: Double, unit: AltitudeUnit, s: Strings): String {
    val ft = s.format("%@ ft", grouped(feet))
    return if (unit == AltitudeUnit.METERS) "$ft · ${s.format("%@ m", grouped(feet * WindMath.METERS_PER_FOOT))}" else ft
}

/** "1,200 ft" or "366 m": standard pattern checkpoints. */
fun checkpointLabel(feet: Double, unit: AltitudeUnit, s: Strings): String =
    if (unit == AltitudeUnit.METERS) s.format("%@ m", grouped(feet * WindMath.METERS_PER_FOOT)) else s.format("%@ ft", grouped(feet))

/**
 * Landing Pattern Assistant: a wind-adjusted standard pattern for three
 * canopy classes, or, for Pro users, a swoop route planned backwards from
 * the swoop start point.
 */
@Composable
fun LandingPatternScreen(
    session: LandingPatternSession,
    settings: AppSettings,
    isPro: Boolean,
    onBack: () -> Unit,
    onLocked: (ProFeature) -> Unit,
) {
    val s = LocalStrings.current
    val tracker = rememberMapLocationTracker()
    var showsWind by remember { mutableStateOf(false) }
    var showsReset by remember { mutableStateOf(false) }
    var topInset by remember { mutableIntStateOf(0) }
    var bottomInset by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val state3d = rememberPlanning3DState()
    val content3d = path3DContent(session, settings.altitudeUnit, s)
    Planning3DGroundLoader(state3d, content3d, session.mapStyle) { session.heldMapView = it }

    LaunchedEffect(isPro) { session.enforceProAccess(isPro) }
    LaunchedEffect(tracker.location) { tracker.location?.let { session.handleLocationFix(Coordinate(it.latitude, it.longitude)) } }
    ApplyCameraRequests(session.camera, session.cameraRequest) { session.cameraRequest = null }
    LaunchedEffect(Unit) { session.resumeSavedPlanIfNeeded() }
    SavesPlanningSettings(session.camera, session::saveSettings)

    ToolScaffold(s("Landing Pattern Assistant"), onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PlanningMap(session, settings.altitudeUnit, tracker.hasPermission, with(density) {
                PaddingValues(top = topInset.toDp(), bottom = bottomInset.toDp())
            }, state3d)
            Planning3DLayer(state3d, content3d, Modifier.fillMaxSize())

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 6.dp).onSizeChanged { topInset = it.height }
                    .planning3DObstruction(state3d, "result"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ResultCard(session, settings.altitudeUnit)
                // The plan is edited on the map; 3D only shows it.
                if (state3d.shows) {
                    // No banners in 3D.
                } else if (!session.hasBasePoint) {
                    MapInstructionBanner(s(session.basePointInstructionKey), Color(0xFF34C759), Icons.Filled.TouchApp)
                } else if (session.mode == LandingPatternMode.EXPERT) {
                    MapInstructionBanner(s(session.waypointInstructionKey), Color(0xFF32ADE6), Icons.Filled.TouchApp)
                }
            }

            Column(
                Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = with(density) { topInset.toDp() } + 18.dp)
                    .planning3DObstruction(state3d, "buttons"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (!state3d.shows) {
                    MapButton(Icons.Filled.MyLocation, s("Center on Current Location"), showsProgress = tracker.hasPermission && tracker.location == null) {
                        tracker.start()
                        session.requestCentering(tracker.location?.let { Coordinate(it.latitude, it.longitude) })
                    }
                }
                MapButton(session.mapStyle.icon, s("Change Map Style")) { session.cycleMapStyle() }
                if (state3d.shows || !content3d.isEmpty) {
                    Planning3DButtons(state3d)
                }
                if (session.hasBasePoint && !state3d.shows) {
                    MapButton(Icons.Filled.Replay, s("Reset Plan")) { showsReset = true }
                }
                MapButton(session.wind.mode.icon, s("Wind Data Mode"), showsProgress = session.wind.isLoading) { showsWind = true }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().padding(horizontal = 14.dp).padding(bottom = 10.dp)
                    .onSizeChanged { bottomInset = it.height },
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!state3d.shows) {
                    WaypointActions(session)
                    ConfigurationPanel(session, isPro) { onLocked(ProFeature.SWOOP_MODE) }
                }
            }

            if (session.wind.isLoading) {
                MapLoadingPill(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showsWind) {
        WindDataSheet(
            wind = session.wind,
            coordinate = session.windAnchor,
            maximumAltitudeFeet = session.maximumRelevantAltitudeFeet,
            text = WindSheetText(
                dateTitleKey = "Exit date and time",
                coordinateTitleKey = "Landing",
                noCoordinateKey = "Tap the intended landing point.",
                autoEmptyKey = "Wind data loads automatically after the landing point is set.",
            ),
            latestDate = Instant.now().plus(Duration.ofDays(15)),
            onDismiss = { showsWind = false },
        ) { PlanningManualWindSection(session.wind) }
    }

    if (showsReset) {
        AlertDialog(
            onDismissRequest = { showsReset = false },
            title = { Text(s("Reset Plan?")) },
            text = { Text(s(session.resetConfirmationKey)) },
            confirmButton = {
                TextButton(onClick = { showsReset = false; session.resetPlan() }) { Text(s("Reset"), color = Color(0xFFFF3B30)) }
            },
            dismissButton = { TextButton(onClick = { showsReset = false }) { Text(s("Cancel")) } },
        )
    }

    if (session.pendingSwoopStartPoint != null) {
        SwoopStartAltitudeDialog(
            onCancel = { session.cancelSwoopStart() },
            onSet = { session.confirmSwoopStart(it) },
        )
    }
}

@Composable
private fun PlanningMap(session: LandingPatternSession, unit: AltitudeUnit, myLocation: Boolean, contentPadding: PaddingValues, state3d: Planning3DState) {
    val s = LocalStrings.current
    val density = LocalDensity.current
    val lineWidth = with(density) { 4.dp.toPx() }
    val dash = with(density) { listOf(Dash(10.dp.toPx()), Gap(7.dp.toPx())) }
    var rotationStartHeading by remember { mutableStateOf(0.0) }

    GoogleMap(
        modifier = Modifier.fillMaxSize().patternRotationGesture(
            camera = session.camera,
            enabled = { session.mode == LandingPatternMode.NORMAL && session.displaySolutions.isNotEmpty() },
            pivot = { session.landingPoint },
            paths = { session.displaySolutions.map { it.solution.path } },
            onBegin = {
                rotationStartHeading = session.finalHeadingDegrees
                session.beginEnvelopeRotation()
            },
            onRotate = { session.envelopePreviewDegrees = it },
            onEnd = { session.finishEnvelopeRotation(rotationStartHeading, it) },
        ),
        cameraPositionState = session.camera,
        properties = MapProperties(mapType = session.mapStyle.mapType, isMyLocationEnabled = myLocation && !state3d.capturing),
        uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false, indoorLevelPickerEnabled = false),
        contentPadding = contentPadding,
        onMapClick = { latLng ->
            val coordinate = latLng.coordinate()
            when (session.mode) {
                LandingPatternMode.NORMAL -> session.placeStandardLandingPoint(coordinate)
                LandingPatternMode.EXPERT -> if (session.swoopStartPoint == null) session.beginSwoopStart(coordinate) else session.addSwoopWaypoint(coordinate)
            }
        },
    ) {
        Planning3DMapHook(state3d)
        // While the 3D view photographs the map for its ground, the plan stays off it.
        if (!state3d.capturing) {
            val landing = session.landingPoint
            if (session.mode == LandingPatternMode.NORMAL && landing != null) {
                DraggableMapMarker("landing", landing, "landing", zIndex = 3f, onDragEnd = { session.moveStandardLandingPoint(it) }) {
                    BasePointBadge(Icons.Filled.SportsScore, Color(0xFF34C759))
                }
            }

            val preview = session.envelopePreviewDegrees
            if (preview != null && landing != null) {
                // While a pattern is being rotated only its rotated lines are drawn.
                for (display in session.displaySolutions) {
                    key(display.id) {
                        Polyline(
                            LandingPatternSession.rotated(display.solution.path, landing, preview).map { it.latLng() },
                            color = display.color, width = lineWidth, pattern = dash, jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap(),
                        )
                    }
                }
            } else {
                for (display in session.displaySolutions) {
                    key(display.id) {
                        if (display.solution.path.size >= 2) {
                            Polyline(
                                display.solution.path.map { it.latLng() },
                                color = display.color, width = lineWidth, pattern = dash, jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap(),
                            )
                        }
                        for (checkpoint in display.solution.checkpoints) {
                            if (checkpoint.kind == CheckpointKind.LANDING) continue
                            key(checkpoint.kind) {
                                val label = checkpointLabel(checkpoint.altitudeAGLFeet, unit, s)
                                StaticMapMarker(checkpoint.coordinate, label, display.color, anchor = labeledDotAnchor()) {
                                    LabeledDot(label, display.color)
                                }
                            }
                        }
                    }
                }
                if (session.mode == LandingPatternMode.NORMAL) {
                    for (arrow in session.legArrows) {
                        key(arrow.id) {
                            StaticMapMarker(arrow.coordinate, arrow.color, rotation = arrow.bearingDegrees.toFloat(), flat = true, zIndex = 1.5f) {
                                LegArrowBadge(arrow.color)
                            }
                        }
                    }
                }
            }

            if (session.mode == LandingPatternMode.EXPERT) {
                val route = session.swoopRouteCoordinates()
                if (route.size >= 3) {
                    Polyline(route.dropLast(1).map { it.latLng() }, color = Color(0xFF32ADE6), width = lineWidth, pattern = dash, jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap())
                }
                if (route.size >= 2) {
                    Polyline(route.takeLast(2).map { it.latLng() }, color = Color(0xFF32ADE6).copy(alpha = 0.7f), width = lineWidth, pattern = dash, startCap = RoundCap(), endCap = RoundCap())
                }
                for (arrow in session.swoopLegArrows) {
                    key(arrow.id) {
                        StaticMapMarker(arrow.coordinate, arrow.color, rotation = arrow.bearingDegrees.toFloat(), flat = true, zIndex = 1.5f) {
                            LegArrowBadge(arrow.color)
                        }
                    }
                }
                for (point in session.swoopDisplayPoints) {
                    key(point.id) {
                        val altitude = point.altitudeFeet?.let { swoopPointLabel(it, unit, s) } ?: s("Unavailable")
                        val selected = session.selectedSwoopPoint == point.selection
                        DraggableMapMarker(
                            point.id, point.coordinate, point.label, altitude, selected, point.color,
                            anchor = Offset(0.5f, 1f), zIndex = 3f,
                            onClick = { session.selectSwoopPoint(point.selection) },
                            onDrag = { session.previewSwoopPointDrag(point.selection, it) },
                            onDragEnd = { session.finishDraggingSwoopPoint(point.selection, it) },
                        ) {
                            SwoopPointBadge(point.label, altitude, point.color, selected)
                        }
                    }
                }
            }
        }
    }
}

/** The patterns, or the swoop route, at their heights, for the 3D view. */
private fun path3DContent(session: LandingPatternSession, unit: AltitudeUnit, s: Strings): PlanningPath3DContent {
    val paths = mutableListOf<Path3DPath>()
    val markers = mutableListOf<Path3DMarker>()
    when (session.mode) {
        LandingPatternMode.NORMAL -> {
            for (display in session.displaySolutions) {
                val checkpoints = display.solution.checkpoints
                if (checkpoints.size < 2) continue
                val argb = display.color.toArgb()
                paths += Path3DPath(display.id, checkpoints.map { Path3DPoint(it.coordinate, it.altitudeAGLFeet * WindMath.METERS_PER_FOOT) }, argb)
                for (checkpoint in checkpoints) {
                    if (checkpoint.kind == CheckpointKind.LANDING) continue
                    markers += Path3DMarker(
                        "${display.id}-${checkpoint.kind.name}",
                        Path3DPoint(checkpoint.coordinate, checkpoint.altitudeAGLFeet * WindMath.METERS_PER_FOOT),
                        argb,
                        checkpointLabel(checkpoint.altitudeAGLFeet, unit, s),
                    )
                }
            }
            session.landingPoint?.let { markers += Path3DMarker("landing", Path3DPoint(it, 0.0), PATTERN_GREEN.toArgb(), s("Landing")) }
        }
        LandingPatternMode.EXPERT -> {
            val route = session.swoopDisplayPoints
            val feet = PlanningPath3DContent.filledAltitudes(route.map { it.altitudeFeet })
            val points = route.zip(feet).map { (point, f) -> Path3DPoint(point.coordinate, f * WindMath.METERS_PER_FOOT) }
            if (points.size >= 2) paths += Path3DPath("swoop-route", points, SWOOP_CYAN.toArgb())
            for ((point, position) in route.zip(points)) {
                val altitude = point.altitudeFeet
                val title = if (altitude != null && point.selection != SwoopSelection.Landing) "${point.label} · ${swoopPointLabel(altitude, unit, s)}" else point.label
                markers += Path3DMarker(point.id, position, point.color.toArgb(), title)
            }
        }
    }
    return PlanningPath3DContent(paths, markers)
}

@Composable
private fun SwoopPointBadge(label: String, altitude: String, color: Color, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MapLabel(altitude)
        Box(
            Modifier.size(if (selected) 36.dp else 32.dp)
                .background(if (selected) Color(0xFFFFD60A) else Color.White, CircleShape)
                .padding(if (selected) 4.dp else 2.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
        }
    }
}

/** A translucent rounded panel over the map. */
@Composable
fun MapPanel(modifier: Modifier = Modifier, corner: Int = 16, content: @Composable () -> Unit) {
    Surface(
        modifier.widthIn(max = 640.dp).fillMaxWidth(),
        shape = RoundedCornerShape(corner.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
        shadowElevation = 6.dp,
        content = content,
    )
}

/** The card at the top of the map: instruction, wind errors, the patterns' legend or the swoop points, and the wind footer. */
@Composable
private fun ResultCard(session: LandingPatternSession, unit: AltitudeUnit) {
    val s = LocalStrings.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    MapPanel {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when {
                    session.wind.isLoading -> Icons.Filled.TouchApp to Color(0xFF32ADE6)
                    session.hasPlanningIssues -> Icons.Filled.Warning to AltixOrange
                    session.isPatternComplete -> Icons.Filled.CheckCircle to Color(0xFF34C759)
                    else -> Icons.Filled.TouchApp to Color(0xFF32ADE6)
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s(session.currentInstructionKey), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            windErrorText(session.wind, s)?.let { error ->
                Row {
                    Icon(Icons.Filled.Warning, null, tint = AltixOrange, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(error, color = AltixOrange, fontSize = 12.sp)
                }
            }
            if (session.mode == LandingPatternMode.NORMAL && session.normalSolutions.isNotEmpty()) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.widthIn(min = 62.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Spacer(Modifier.height(16.dp))
                        Text(s("Airspeed"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = secondary, modifier = Modifier.height(16.dp))
                        Text(s("Glide ratio"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = secondary, modifier = Modifier.height(16.dp))
                    }
                    for (level in SkillLevel.entries) {
                        val hidden = level in session.hiddenLevels
                        Box(Modifier.weight(1f).alpha(if (hidden) 0.38f else 1f).clickable { session.toggleVisibility(level) }) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(Modifier.height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(level.patternColor, CircleShape))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s(level.titleKey), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                                Text(s.format("%@ kt", formatNumber(level.airspeedKnots, 0)), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.height(16.dp))
                                Text(s.format("%@:1", formatNumber(level.glideRatio, 1)), fontSize = 11.sp, color = secondary, modifier = Modifier.height(16.dp))
                            }
                            if (hidden) Icon(Icons.Filled.VisibilityOff, s("Pattern hidden"), tint = secondary, modifier = Modifier.align(Alignment.TopEnd).size(12.dp))
                        }
                    }
                }
                for (level in SkillLevel.entries) {
                    for (issue in session.normalSolutions[level]?.issues ?: emptyList()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(level.patternColor, CircleShape))
                            Spacer(Modifier.width(5.dp))
                            Text("${s(level.titleKey)} ${s(issue.messageKey)}", color = AltixOrange, fontSize = 12.sp)
                        }
                    }
                }
            } else if (session.mode == LandingPatternMode.EXPERT && session.swoopStartPoint != null) {
                HorizontalDivider()
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (point in session.swoopDisplayPoints) {
                        val selected = session.selectedSwoopPoint == point.selection
                        Column(
                            Modifier
                                .background(if (selected) point.color.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { session.selectSwoopPoint(point.selection) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(point.label, color = point.color, fontWeight = FontWeight.Black, fontSize = 12.sp)
                            Text(point.altitudeFeet?.let { swoopPointLabel(it, unit, s) } ?: s("Unavailable"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
                if (session.swoopSolution?.uncalculatedWaypointIds?.isNotEmpty() == true) {
                    Text(s("One or more waypoint altitudes cannot be calculated with the current wind and canopy settings."), color = AltixOrange, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(session.wind.mode.icon, null, tint = secondary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (session.wind.isLoading) s("Loading wind…") else s(session.wind.mode.statusKey), fontSize = 11.sp, color = secondary, modifier = Modifier.weight(1f))
                Text(formatMonthDayTime(session.wind.profile?.validAt ?: session.wind.forecastDate, s), fontSize = 11.sp, color = secondary)
            }
        }
    }
}

@Composable
private fun WaypointActions(session: LandingPatternSession) {
    val s = LocalStrings.current
    val deletable = session.selectedDeletableWaypointId
    if (session.selectedNewWaypointId != null) {
        Button(onClick = { session.finishAddingSwoopWaypoint() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF32ADE6), contentColor = Color.White)) {
            Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(s("Finish"), fontWeight = FontWeight.Bold)
        }
    } else if (deletable != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { session.cancelSwoopWaypointSelection() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.72f), contentColor = Color.White)) {
                Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s("Cancel"), fontWeight = FontWeight.Bold)
            }
            Button(onClick = { session.deleteSwoopWaypoint(deletable) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30), contentColor = Color.White)) {
                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s("Delete Waypoint"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The bottom panel: mode selector, then the inputs of the current mode. */
@Composable
private fun ConfigurationPanel(session: LandingPatternSession, isPro: Boolean, onSwoopLocked: () -> Unit) {
    val s = LocalStrings.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    MapPanel(corner = 18) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isPro) {
                SegmentedPicker(LandingPatternMode.entries, session.mode, { s(it.titleKey) }, session::selectMode)
            } else {
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(2.dp)) {
                    Box(Modifier.weight(1f).height(34.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Text(s("Standard Mode"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Row(Modifier.weight(1f).height(34.dp).clickable(onClick = onSwoopLocked), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = secondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s("Swoop Mode"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = secondary)
                    }
                }
            }

            if (session.mode == LandingPatternMode.NORMAL) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SwipeNumberInput(
                        s("Final heading"), session.finalHeadingDegrees, "°", 0, 1.0,
                        { session.updateFinalHeading(Geodesy.normalizedDegrees(it)) },
                        dpPerStep = 2f, valueFontSize = 20.sp, titleFontSize = 11.sp, titleColor = secondary,
                        modifier = Modifier.weight(1f),
                    )
                    VerticalDivider(Modifier.height(42.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s("Landing pattern"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = secondary, maxLines = 1)
                        SegmentedPicker(PatternDirection.entries, session.patternDirection, { s(it.titleKey) }, session::updatePatternDirection)
                    }
                    VerticalDivider(Modifier.height(42.dp))
                    SwipeNumberInput(
                        s("Altitude step"), session.altitudeStepFeet, "ft", 0, 100.0,
                        { session.updateAltitudeStep(it) }, range = 300.0..10_000.0,
                        valueFontSize = 20.sp, titleFontSize = 11.sp, titleColor = secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SwipeNumberInput(
                        s("Airspeed"), session.expertAirspeedKnots, "kt", 1, 1.0, { session.updateExpertAirspeed(it) },
                        range = 1.0..80.0, valueFontSize = 20.sp, titleFontSize = 11.sp, titleColor = secondary, modifier = Modifier.weight(1f),
                    )
                    VerticalDivider(Modifier.height(42.dp))
                    SwipeNumberInput(
                        s("Glide ratio"), session.expertGlideRatio, ":1", 1, 0.1, { session.updateExpertGlideRatio(it) },
                        range = 0.1..10.0, valueFontSize = 20.sp, titleFontSize = 11.sp, titleColor = secondary, modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SwapHoriz, null, tint = secondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(s("Slide numbers to adjust"), fontSize = 11.sp, color = secondary)
            }
        }
    }
}

@Composable
private fun SwoopStartAltitudeDialog(onCancel: () -> Unit, onSet: (Double) -> Unit) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf("") }
    val parsed = parseNumber(text)?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(s("Swoop Start Altitude")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s("Enter the altitude at swoop start point A."))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text(s("Altitude (ft AGL)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null) { Text(s("Set")) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(s("Cancel")) } },
    )
}
