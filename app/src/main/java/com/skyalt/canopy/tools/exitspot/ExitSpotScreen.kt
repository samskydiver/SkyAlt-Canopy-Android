package com.skyalt.canopy.tools.exitspot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.skyalt.canopy.app.AppSettings
import com.skyalt.canopy.app.DistanceUnit
import com.skyalt.canopy.tools.AltixOrange
import com.skyalt.canopy.tools.AltixRed
import com.skyalt.canopy.tools.SegmentedPicker
import com.skyalt.canopy.tools.SwipeNumberInput
import com.skyalt.canopy.tools.ToolScaffold
import com.skyalt.canopy.tools.landing.MapPanel
import com.skyalt.canopy.tools.map.ApplyCameraRequests
import com.skyalt.canopy.tools.map.SavesPlanningSettings
import com.skyalt.canopy.tools.map.BasePointBadge
import com.skyalt.canopy.tools.map.DraggableMapMarker
import com.skyalt.canopy.tools.map.MapButton
import com.skyalt.canopy.tools.map.MapInstructionBanner
import com.skyalt.canopy.tools.map.MapLoadingPill
import com.skyalt.canopy.tools.map.PlanningManualWindSection
import com.skyalt.canopy.tools.map.StaticMapMarker
import com.skyalt.canopy.tools.map.WindDataSheet
import com.skyalt.canopy.tools.map.WindSheetText
import com.skyalt.canopy.tools.map.coordinate
import com.skyalt.canopy.tools.map.grouped
import com.skyalt.canopy.tools.map.latLng
import com.skyalt.canopy.tools.map.patternRotationGesture
import com.skyalt.canopy.tools.map.rememberMapLocationTracker
import com.skyalt.canopy.tools.map.screenBearing
import com.skyalt.canopy.tools.map.signedDelta
import com.skyalt.canopy.tools.map.windErrorText
import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.ExitSpotSolution
import com.skyalt.canopy.tools.planning.Geodesy
import com.skyalt.canopy.tools.planning.PatternDirection
import com.skyalt.canopy.ui.LocalStrings
import com.skyalt.canopy.ui.formatNumber
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Exit Spot: pick a landing point, let the wind suggest a jump run, and see
 * where along it each kind of canopy can leave the plane.
 */
@Composable
fun ExitSpotScreen(session: ExitSpotSession, settings: AppSettings, onBack: () -> Unit) {
    val s = LocalStrings.current
    val tracker = rememberMapLocationTracker()
    var showsWind by remember { mutableStateOf(false) }
    var topInset by remember { mutableIntStateOf(0) }
    var bottomInset by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(settings.distanceUnit) { session.fallbackDistanceUnit = settings.distanceUnit }
    LaunchedEffect(tracker.location) { tracker.location?.let { session.handleLocationFix(Coordinate(it.latitude, it.longitude)) } }
    ApplyCameraRequests(session.camera, session.cameraRequest) { session.cameraRequest = null }
    LaunchedEffect(Unit) { session.resumeSavedPlanIfNeeded() }
    SavesPlanningSettings(session.camera, session::saveSettings)

    ToolScaffold(s("Exit Spot"), onBack) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val landscape = maxWidth > maxHeight
            ExitMap(session, tracker.hasPermission, with(density) { PaddingValues(top = topInset.toDp(), bottom = bottomInset.toDp()) })
            JumpRunRuler(session)

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 8.dp).onSizeChanged { topInset = it.height },
            ) {
                if (landscape) {
                    if (session.jumpRunCenter != null) LandscapeStrip(session, Modifier.widthIn(max = 420.dp))
                } else {
                    NoticePanel(session)
                }
            }

            Column(
                Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = with(density) { topInset.toDp() } + 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                MapButton(Icons.Filled.MyLocation, s("Center on Current Location"), showsProgress = tracker.hasPermission && tracker.location == null) {
                    tracker.start()
                    session.requestCentering(tracker.location?.let { Coordinate(it.latitude, it.longitude) })
                }
                MapButton(session.mapStyle.icon, s("Change Map Style")) { session.cycleMapStyle() }
                DistanceUnitButton(session.distanceUnit) { session.toggleDistanceUnit() }
                if (session.landingPoint != null) {
                    MapButton(Icons.Filled.Replay, s("Reset Plan")) { session.resetPlan() }
                }
                MapButton(session.wind.mode.icon, s("Wind Data Mode"), showsProgress = session.wind.isLoading) { showsWind = true }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = 14.dp)
                    .onSizeChanged { bottomInset = it.height },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (session.landingPoint == null) {
                    MapInstructionBanner(s("Tap the map to set the landing point."), Color(0xFF34C759), Icons.Filled.TouchApp)
                }
                InputBar(session, landscape)
            }

            if (session.wind.isLoading) {
                MapLoadingPill(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showsWind) {
        WindDataSheet(
            wind = session.wind,
            coordinate = session.landingPoint,
            maximumAltitudeFeet = session.exitAltitudeFeet,
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
}

@Composable
private fun ExitMap(session: ExitSpotSession, myLocation: Boolean, contentPadding: PaddingValues) {
    val s = LocalStrings.current
    val density = LocalDensity.current
    fun px(dp: Float) = with(density) { dp.dp.toPx() }
    var rotationStartHeading by remember { mutableStateOf(0.0) }

    GoogleMap(
        modifier = Modifier.fillMaxSize().patternRotationGesture(
            camera = session.camera,
            enabled = { session.solution != null },
            pivot = { session.landingPoint },
            paths = { session.solution?.referenceSolutions?.filter { it.hasValidLandingPattern }?.map { it.landingPatternPath } ?: emptyList() },
            onBegin = {
                rotationStartHeading = session.finalApproachHeadingDegrees
                session.beginEnvelopeRotation()
            },
            onRotate = { session.envelopePreviewDegrees = it },
            onEnd = { session.finishEnvelopeRotation(rotationStartHeading, it) },
        ).jumpRunGesture(session),
        cameraPositionState = session.camera,
        properties = MapProperties(mapType = session.mapStyle.mapType, isMyLocationEnabled = myLocation),
        uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false, indoorLevelPickerEnabled = false),
        contentPadding = contentPadding,
        onMapClick = { latLng -> if (session.landingPoint == null) session.placeLandingPoint(latLng.coordinate(), isInitialPlacement = true) },
    ) {
        val landing = session.landingPoint
        if (landing != null) {
            DraggableMapMarker("exit-landing", landing, "landing", anchor = Offset(0.5f, 0.5f), zIndex = 3f, onDragEnd = { session.placeLandingPoint(it, isInitialPlacement = false) }) {
                BasePointBadge(Icons.Filled.SportsScore, Color(0xFF34C759))
            }
            session.jumpRunOffset?.let { offset ->
                Polyline(listOf(landing.latLng(), offset.projectedCoordinate.latLng()), color = Color.White, width = px(2f), pattern = listOf(Dash(px(6f)), Gap(px(5f))))
            }
        }
        val run = session.jumpRunCoordinates
        if (run.size >= 2) {
            Polyline(run.map { it.latLng() }, color = Color.White.copy(alpha = 0.9f), width = px(2f), zIndex = 1f)
        }
        val solution = session.solution
        if (solution != null) {
            // Widest (student) range first so the narrower ones draw on top.
            for (reference in solution.referenceSolutions.sortedByDescending { it.experienceProfile.ordinal }) {
                val start = reference.exitStart
                val end = reference.exitEnd
                if (start != null && end != null) {
                    key("range-${reference.experienceProfile}") {
                        Polyline(
                            listOf(start.latLng(), end.latLng()),
                            color = reference.mapColor.copy(alpha = reference.exitRangeOpacity),
                            width = px(reference.exitRangeLineWidthDp), zIndex = 2f,
                        )
                    }
                }
            }
            val preview = session.envelopePreviewDegrees
            for (reference in solution.referenceSolutions) {
                key("pattern-${reference.experienceProfile}") {
                    val path = if (preview != null && landing != null) {
                        com.skyalt.canopy.tools.landing.LandingPatternSession.rotated(reference.landingPatternPath, landing, preview)
                    } else {
                        reference.landingPatternPath
                    }
                    if (path.size >= 2) {
                        Polyline(
                            path.map { it.latLng() }, color = reference.mapColor.copy(alpha = 0.9f), width = px(3f),
                            pattern = listOf(Dash(px(8f)), Gap(px(6f))), jointType = JointType.ROUND, startCap = RoundCap(), endCap = RoundCap(), zIndex = 2f,
                        )
                    }
                    val entry = reference.patternEntryPoint
                    if (preview == null && entry != null) {
                        val title = s(reference.experienceProfile.titleKey)
                        StaticMapMarker(entry, title, reference.airspeedKnots, reference.mapColor, anchor = Offset(0.5f, 1f), zIndex = 2f) {
                            ExitPointMarker(title, reference.airspeedKnots, reference.mapColor)
                        }
                    }
                }
            }
        }
    }
}

/** Capsule label plus dot for a pattern entry point. */
@Composable
private fun ExitPointMarker(title: String, airspeedKnots: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            Modifier.background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(50)).border(2.dp, color, RoundedCornerShape(50))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.VerticalAlignBottom, null, tint = Color.Black, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("$title · $airspeedKnots kt", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.Black, maxLines = 1)
        }
        Box(Modifier.size(11.dp).background(Color.White, CircleShape).padding(2.dp).background(color, CircleShape))
    }
}

@Composable
private fun DistanceUnitButton(unit: DistanceUnit, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(unit.shortLabel.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The distance ruler along the jump run with an aircraft at the zero point.
 * Drag it to move the run, twist with two fingers to rotate it.
 */
@Composable
private fun JumpRunRuler(session: ExitSpotSession) {
    val zero = session.jumpRunZeroPoint ?: return
    // Reading the camera position recomposes the ruler as the map moves.
    val cameraBearing = session.camera.position.bearing
    val projection = session.camera.projection ?: return
    val center = projection.toScreenLocation(zero.latLng()).let { Offset(it.x.toFloat(), it.y.toFloat()) }
    val probe = projection.fromScreenLocation(android.graphics.Point(center.x.toInt() + 100, center.y.toInt()))
    val metersPerPx = max(Geodesy.distanceMeters(zero, probe.coordinate()) / 100, 0.001)
    val unit = session.distanceUnit
    val textMeasurer = rememberTextMeasurer()
    val plane = rememberVectorPainter(Icons.Filled.Flight)
    val screenAngle = (session.jumpRunBearingDegrees - cameraBearing).toFloat()
    val density = LocalDensity.current

    Canvas(Modifier.fillMaxSize()) {
        val width = hypot(size.width, size.height) * 1.35f
        // Rounds roughly 86 dp of map to 1, 2, 5 or 10 display units.
        val target = max(unit.fromMeters(metersPerPx * 86.dp.toPx()), 0.001)
        val magnitude = 10.0.pow(floor(log10(target)))
        val normalized = target / magnitude
        val nice = when {
            normalized < 1.5 -> 1.0
            normalized < 3.5 -> 2.0
            normalized < 7.5 -> 5.0
            else -> 10.0
        }
        val majorMeters = nice * magnitude * unit.toMeters(1.0)
        val spacing = (majorMeters / metersPerPx).toFloat()
        val count = max(ceil(width / 2 / max(spacing, 1f)).toInt() + 1, 1)
        val labelStyle = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)

        rotate(screenAngle - 90f, center) {
            for (index in -count..count) {
                val x = center.x + index * spacing
                val tickHeight = if (index == 0) 28.dp.toPx() else 18.dp.toPx()
                val tickWidth = if (index == 0) 3.dp.toPx() else 2.dp.toPx()
                drawRect(Color.White, topLeft = Offset(x - tickWidth / 2, center.y - tickHeight / 2), size = androidx.compose.ui.geometry.Size(tickWidth, tickHeight))

                val distance = unit.fromMeters(index * majorMeters)
                val label = "${ExitRangeMetrics.fractionText(distance, 0, 2)} ${unit.shortLabel}"
                val layout = textMeasurer.measure(label, labelStyle)
                val labelCenter = Offset(x, center.y + if (index % 2 == 0) -32.dp.toPx() else 31.dp.toPx())
                rotate(90f - screenAngle, labelCenter) {
                    val padH = 4.dp.toPx()
                    val padV = 2.dp.toPx()
                    val w = layout.size.width + padH * 2
                    val h = layout.size.height + padV * 2
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.68f), topLeft = Offset(labelCenter.x - w / 2, labelCenter.y - h / 2),
                        size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
                    )
                    drawText(layout, topLeft = Offset(labelCenter.x - layout.size.width / 2, labelCenter.y - layout.size.height / 2))
                }
            }
            // The aircraft at the zero point, nose along the run.
            val r = 16.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.7f), r, center)
            rotate(90f, center) {
                translate(center.x - 9.dp.toPx(), center.y - 9.dp.toPx()) {
                    with(plane) { draw(androidx.compose.ui.geometry.Size(18.dp.toPx(), 18.dp.toPx()), alpha = 0.7f, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF0A84FF))) }
                }
            }
        }
    }
}

/** The portrait notice card: a minimized summary, or two swipeable pages (plan details and wind aloft). */
@Composable
private fun NoticePanel(session: ExitSpotSession) {
    val s = LocalStrings.current
    MapPanel(corner = 15) {
        Box {
            if (session.isNoticeMinimized) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    JumpRunSummary(session)
                    val solution = session.solution
                    val zero = session.jumpRunZeroPoint
                    if (solution != null && zero != null) RangeTable(session, solution, zero, showsDetails = false) else PlanningStatus(session)
                    if (session.hasDistantGeneralExitEndpoint) DistanceAdvisory(session.exitDistanceThresholdText)
                }
            } else {
                val pager = rememberPagerState(initialPage = session.noticePage) { 2 }
                LaunchedEffect(pager.currentPage) { session.noticePage = pager.currentPage }
                Column {
                    HorizontalPager(pager, Modifier.fillMaxWidth().height(230.dp)) { page ->
                        if (page == 0) PlanningPage(session) else WindAloftPage(session)
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
                        repeat(2) { i ->
                            Box(Modifier.padding(horizontal = 3.dp).size(6.dp).background(if (pager.currentPage == i) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), CircleShape))
                        }
                    }
                }
            }
            Box(Modifier.align(Alignment.TopCenter).size(width = 52.dp, height = 22.dp).clickable { session.toggleNotice() }, contentAlignment = Alignment.Center) {
                Icon(
                    if (session.isNoticeMinimized) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    s(if (session.isNoticeMinimized) "Expand notice" else "Minimize notice"),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PlanningPage(session: ExitSpotSession) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (session.solution == null) PlanningStatus(session)
        JumpRunSummary(session)
        val solution = session.solution
        val zero = session.jumpRunZeroPoint
        if (solution != null && zero != null) RangeTable(session, solution, zero, showsDetails = true)
        val message = session.calculationMessageKey
        val windError = windErrorText(session.wind, s)
        if (message != null) Text(s(message), fontSize = 11.sp, color = AltixOrange)
        else if (windError != null) Text(windError, fontSize = 11.sp, color = AltixRed)
        if (session.hasDistantGeneralExitEndpoint) DistanceAdvisory(session.exitDistanceThresholdText)
        else Text(s("Swipe left for wind aloft"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WindAloftPage(session: ExitSpotSession) {
    val s = LocalStrings.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Air, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(s("Current wind aloft"), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(session.wind.mode.icon, null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(s(session.wind.mode.titleKey), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(s("Wind time"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val date = session.wind.profile?.validAt ?: session.wind.forecastDate
            Text(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").format(date.atZone(ZoneId.systemDefault())), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        when {
            session.wind.isLoading -> Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(s("Fetching wind aloft…"), fontSize = 12.sp)
            }
            session.wind.profile == null -> {
                val error = windErrorText(session.wind, s)
                Text(error ?: s("Wind data is required"), fontSize = 12.sp, color = if (error == null) secondary else AltixRed, modifier = Modifier.padding(top = 20.dp))
            }
            else -> {
                HorizontalDivider()
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (level in session.windNoticeLevels) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.format("%@ ft", grouped(level.altitudeFeet)), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                if (level.pressureHectopascals != null) Text("${level.pressureHectopascals} hPa", fontSize = 9.sp, color = secondary)
                            }
                            Text(
                                "${formatNumber(level.wind.directionFromDegrees, 0)}° · ${s.format("%@ kt", formatNumber(level.wind.speedKnots, 1))}",
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpRunSummary(session: ExitSpotSession) {
    val s = LocalStrings.current
    if (session.jumpRunCenter == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Flight, null, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 90f })
        Spacer(Modifier.width(8.dp))
        Text("${s("Jump run")} · ${session.jumpRunBearingText}°", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (session.jumpRunOffset != null) {
            Text("${s("offset")} ${session.jumpRunOffsetText}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun PlanningStatus(session: ExitSpotSession) {
    val s = LocalStrings.current
    val solution = session.solution
    val (icon, text, color) = when {
        session.landingPoint == null -> Triple(Icons.Filled.SportsScore, "Tap the map to set the landing point.", Color(0xFF34C759))
        session.jumpRunCenter == null -> Triple(Icons.Filled.Navigation, "Generating suggested jump run", Color(0xFF0A84FF))
        session.wind.profile == null -> Triple(Icons.Filled.Air, "Wind data is required", AltixOrange)
        solution != null && solution.hasAnyExitRange -> Triple(Icons.Filled.CheckCircle, "Exit ranges ready", Color(0xFF34C759))
        solution != null -> Triple(Icons.Filled.Warning, "No safe exit range", AltixOrange)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(s(text), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DistanceAdvisory(thresholdText: String) {
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, null, tint = AltixOrange, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text("${s("Exit distance exceeds")} $thresholdText. ${s("Confirm the dropzone is identifiable before exit.")}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AltixOrange)
    }
}

/** Distances along the jump run from the point abeam the landing point: the averaged window, then one row per profile. */
@Composable
private fun RangeTable(session: ExitSpotSession, solution: ExitSpotSolution, zero: Coordinate, showsDetails: Boolean) {
    val s = LocalStrings.current
    val unit = session.distanceUnit
    val bearing = session.jumpRunBearingDegrees
    val column = 76.dp
    val general = session.generalExitRangeMeters
    fun endpoint(c: Coordinate?) = c?.let { ExitRangeMetrics.formattedDistance(ExitRangeMetrics.signedDistance(zero, it, bearing), unit) } ?: "—"

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s("Exit Points"), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            Text(general?.let { ExitRangeMetrics.formattedDistance(it.start, unit) } ?: "—", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(column), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text(general?.let { ExitRangeMetrics.formattedDistance(it.endInclusive, unit) } ?: "—", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(column), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        if (showsDetails) {
            val header = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            Row {
                Text(s("Experience"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = header, modifier = Modifier.weight(1f))
                Text("${s("Start")} (${unit.shortLabel})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = header, modifier = Modifier.width(column), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
                Text("${s("End")} (${unit.shortLabel})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = header, modifier = Modifier.width(column), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
            }
            HorizontalDivider()
            for (reference in solution.referenceSolutions) {
                val color = if (reference.hasValidLandingPattern && !reference.hasExitRange) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(reference.mapColor, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${s(reference.experienceProfile.titleKey)} · ${reference.airspeedKnots} kt · ${formatNumber(reference.glideRatio, 1)}:1",
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, modifier = Modifier.weight(1f),
                    )
                    if (reference.hasValidLandingPattern) {
                        Text(endpoint(reference.exitStart), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.width(column), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Text(endpoint(reference.exitEnd), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.width(column), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    } else {
                        Text(s("Invalid in current wind"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AltixOrange, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The compact summary strip shown in landscape instead of the notice card. */
@Composable
private fun LandscapeStrip(session: ExitSpotSession, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            StripItem(Icons.Filled.Flight, session.jumpRunDirectionText, Modifier.weight(1f))
            VerticalDivider(Modifier.height(20.dp), color = Color.White.copy(alpha = 0.3f))
            StripItem(Icons.Filled.SwapHoriz, session.jumpRunOffsetText, Modifier.weight(1f))
            VerticalDivider(Modifier.height(20.dp), color = Color.White.copy(alpha = 0.3f))
            StripItem(Icons.Filled.VerticalAlignBottom, session.generalExitRangeText, Modifier.weight(1.4f))
        }
        session.calculationMessageKey?.let { Text(s(it), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AltixOrange) }
        windErrorText(session.wind, s)?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AltixRed) }
        if (session.hasDistantGeneralExitEndpoint) DistanceAdvisory(session.exitDistanceThresholdText)
    }
}

@Composable
private fun StripItem(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, modifier: Modifier) {
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The bar at the bottom: exit and deploy altitudes, final heading and pattern hand, all slide-adjustable. */
@Composable
private fun InputBar(session: ExitSpotSession, landscape: Boolean) {
    val s = LocalStrings.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable
    fun altitudes(modifier: Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SwipeNumberInput(
            s("Exit altitude"), session.exitAltitudeFeet, "ft", 0, 100.0, { session.updateExitAltitude(it) },
            range = session.exitAltitudeRange, valueFontSize = 18.sp, titleFontSize = 11.sp, titleColor = secondary, modifier = Modifier.weight(1f),
        )
        VerticalDivider(Modifier.height(38.dp))
        SwipeNumberInput(
            s("Deploy altitude"), session.deploymentAltitudeFeet, "ft", 0, 100.0, { session.updateDeploymentAltitude(it) },
            range = session.deploymentAltitudeRange, valueFontSize = 18.sp, titleFontSize = 11.sp, titleColor = secondary, modifier = Modifier.weight(1f),
        )
    }
    @Composable
    fun approach(modifier: Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SwipeNumberInput(
            s("Final heading"), session.finalApproachHeadingDegrees, "°", 0, 1.0, { session.updateFinalApproachHeading(it) },
            dpPerStep = 2f, valueFontSize = 18.sp, titleFontSize = 11.sp, titleColor = secondary, modifier = Modifier.weight(1f),
        )
        VerticalDivider(Modifier.height(38.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s("Landing pattern"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = secondary, maxLines = 1)
            SegmentedPicker(PatternDirection.entries, session.landingPatternDirection, { s(it.titleKey) }, session::updateLandingPatternDirection)
        }
    }
    MapPanel(corner = 18) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (landscape) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    altitudes(Modifier.weight(1f))
                    VerticalDivider(Modifier.height(38.dp))
                    approach(Modifier.weight(1f))
                }
            } else {
                altitudes(Modifier.fillMaxWidth())
                HorizontalDivider()
                approach(Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SwapHoriz, null, tint = secondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(s("Slide numbers to adjust"), fontSize = 11.sp, color = secondary)
            }
        }
    }
}

/**
 * Dragging along the jump run moves it, a two-finger twist rotates it. The
 * touch must start within 34 dp of the run line and away from the landing
 * point and its patterns, which keep their own gestures.
 */
private fun Modifier.jumpRunGesture(session: ExitSpotSession): Modifier = pointerInput(Unit) {
            val halfBand = 34.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val proj = session.camera.projection ?: return@awaitEachGesture
                val zp = session.jumpRunZeroPoint ?: return@awaitEachGesture
                val c = proj.toScreenLocation(zp.latLng()).let { Offset(it.x.toFloat(), it.y.toFloat()) }
                val angle = Math.toRadians(session.jumpRunBearingDegrees - session.camera.position.bearing)
                // Distance across the run line, in screen pixels.
                val d = down.position - c
                val across = abs(d.x * cos(angle) + d.y * sin(angle))
                if (across > halfBand) return@awaitEachGesture
                // Leave the landing point and its patterns to their own gestures.
                val landing = session.landingPoint
                if (landing != null) {
                    val l = proj.toScreenLocation(landing.latLng())
                    if ((down.position - Offset(l.x.toFloat(), l.y.toFloat())).getDistance() < 30.dp.toPx()) return@awaitEachGesture
                }
                fun screen(p: Coordinate) = proj.toScreenLocation(p.latLng()).let { Offset(it.x.toFloat(), it.y.toFloat()) }
                val patterns = session.solution?.referenceSolutions?.filter { it.hasValidLandingPattern }?.map { r -> r.landingPatternPath.map(::screen) } ?: emptyList()
                if (patterns.any { path -> path.zipWithNext().any { (a, b) -> distanceToSegment(down.position, a, b) <= 22.dp.toPx() } }) return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                var moving = false
                var rotateStartAngle: Double? = null
                var rotateStartBearing = 0.0
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        val a = pressed[0].position
                        val b = pressed[1].position
                        val twist = screenBearing(a, b)
                        if (rotateStartAngle == null) {
                            session.snapJumpRunCenterToZeroPoint()
                            rotateStartAngle = twist
                            rotateStartBearing = session.jumpRunBearingDegrees
                        }
                        session.rotateJumpRun(rotateStartBearing + signedDelta(rotateStartAngle, twist))
                        moving = true
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    val change = pressed.first()
                    if (!moving && (change.position - down.position).getDistance() < slop) continue
                    moving = true
                    event.changes.forEach { it.consume() }
                    if (rotateStartAngle == null) {
                        val target = c + (change.position - down.position)
                        val latLng = proj.fromScreenLocation(android.graphics.Point(target.x.toInt(), target.y.toInt()))
                        session.moveJumpRun(latLng.coordinate())
                    }
                }
                if (moving) session.finishJumpRunAdjustment()
            }
        
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lengthSquared = ab.x * ab.x + ab.y * ab.y
    if (lengthSquared == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lengthSquared).coerceIn(0f, 1f)
    return (p - Offset(a.x + ab.x * t, a.y + ab.y * t)).getDistance()
}
