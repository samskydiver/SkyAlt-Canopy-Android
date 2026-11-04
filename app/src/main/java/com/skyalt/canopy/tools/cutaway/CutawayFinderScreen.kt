package com.skyalt.canopy.tools.cutaway

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.skyalt.canopy.app.AltitudeUnit
import com.skyalt.canopy.app.AppSettings
import com.skyalt.canopy.tools.AltixOrange
import com.skyalt.canopy.tools.ToolScaffold
import com.skyalt.canopy.tools.landing.MapPanel
import com.skyalt.canopy.tools.map.ApplyCameraRequests
import com.skyalt.canopy.tools.map.BasePointBadge
import com.skyalt.canopy.tools.map.CutawayManualWindSection
import com.skyalt.canopy.tools.map.MapButton
import com.skyalt.canopy.tools.map.MapLoadingPill
import com.skyalt.canopy.tools.map.StaticMapMarker
import com.skyalt.canopy.tools.map.WindDataSheet
import com.skyalt.canopy.tools.map.WindSheetText
import com.skyalt.canopy.tools.map.coordinate
import com.skyalt.canopy.tools.map.grouped
import com.skyalt.canopy.tools.map.latLng
import com.skyalt.canopy.tools.map.rememberMapLocationTracker
import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.WindMath
import com.skyalt.canopy.tools.planning.WindProfile
import com.skyalt.canopy.ui.LocalStrings
import com.skyalt.canopy.ui.formatNumber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Cutaway Finder: draw where the main was cut away, and see where the wind column carries it to the ground. */
@Composable
fun CutawayFinderScreen(session: CutawayFinderSession, settings: AppSettings, onBack: () -> Unit) {
    val s = LocalStrings.current
    val tracker = rememberMapLocationTracker()
    var showsWind by remember { mutableStateOf(false) }
    var topInset by remember { mutableIntStateOf(0) }
    var bottomInset by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    LaunchedEffect(tracker.location) { tracker.location?.let { session.handleLocationFix(Coordinate(it.latitude, it.longitude)) } }
    ApplyCameraRequests(session.camera, session.cameraRequest) { session.cameraRequest = null }

    ToolScaffold(s("Cutaway Finder"), onBack) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val landscape = maxWidth > maxHeight
            CutawayMap(session, tracker.hasPermission, with(density) { PaddingValues(top = topInset.toDp(), bottom = bottomInset.toDp()) })

            WindStatusBadge(session, Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 8.dp).onSizeChanged { topInset = it.height })

            val controls: @Composable () -> Unit = {
                MapButton(Icons.Filled.MyLocation, s("Center on Current Location"), showsProgress = tracker.hasPermission && tracker.location == null) {
                    tracker.start()
                    session.requestCentering(tracker.location?.let { Coordinate(it.latitude, it.longitude) })
                }
                MapButton(session.mapStyle.icon, s("Change Map Style")) { session.cycleMapStyle() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.showsDrawTip && !landscape) {
                        DrawTip()
                        Spacer(Modifier.width(8.dp))
                    }
                    MapButton(if (session.isDrawing) Icons.Filled.Close else Icons.Filled.Draw, s("Draw Cutaway Area"), isActive = session.isDrawing) {
                        session.showsDrawTip = false
                        session.toggleDrawing()
                    }
                }
                MapButton(session.mode.icon, s("Wind Data Mode"), showsProgress = session.isLoading) { showsWind = true }
            }
            if (landscape) {
                Row(Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { controls() }
            } else {
                Column(Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) { controls() }
            }

            InputPanel(
                session, settings.altitudeUnit,
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = 18.dp).onSizeChanged { bottomInset = it.height },
            )

            if (session.isLoading) {
                MapLoadingPill(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showsWind) {
        WindDataSheet(
            wind = session,
            coordinate = session.circleCenter,
            maximumAltitudeFeet = session.cutawayAltitudeFeet,
            text = WindSheetText(
                dateTitleKey = "Cutaway date and time",
                coordinateTitleKey = "Cutaway Location",
                noCoordinateKey = "Draw the cutaway area to load winds.",
                autoEmptyKey = "Wind data loads automatically after the cutaway area is drawn.",
                historicalNoteKey = "Historical GFS wind data is available from March 23, 2021. Times are queried hourly.",
            ),
            latestDate = WindProfile.forecastHour(Instant.now()),
            onDismiss = { showsWind = false },
            cutawayRows = true,
        ) { CutawayManualWindSection(session.manualLayers, session::updateManualLayers) }
    }

    session.drawingErrorKey?.let { key ->
        AlertDialog(
            onDismissRequest = { session.drawingErrorKey = null },
            title = { Text(s("Could Not Fit Circle")) },
            text = { Text(s(key)) },
            confirmButton = { TextButton(onClick = { session.drawingErrorKey = null }) { Text(s("OK")) } },
        )
    }
}

@Composable
private fun CutawayMap(session: CutawayFinderSession, myLocation: Boolean, contentPadding: PaddingValues) {
    val s = LocalStrings.current
    val density = LocalDensity.current
    fun px(dp: Float) = with(density) { dp.dp.toPx() }
    val drawing = session.isDrawing

    GoogleMap(
        modifier = Modifier.fillMaxSize().pointerInput(drawing) {
            if (!drawing) return@pointerInput
            // While drawing, every touch is a stroke on the map; the map itself does not move.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                fun add(position: Offset) {
                    val projection = session.camera.projection ?: return
                    session.addDrawingPoint(projection.fromScreenLocation(android.graphics.Point(position.x.toInt(), position.y.toInt())).coordinate())
                }
                add(down.position)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    event.changes.forEach { it.consume() }
                    add(change.position)
                    if (!change.pressed) break
                }
                session.finishDrawing()
            }
        },
        cameraPositionState = session.camera,
        properties = MapProperties(mapType = session.mapStyle.mapType, isMyLocationEnabled = myLocation),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false, myLocationButtonEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false,
            indoorLevelPickerEnabled = false, scrollGesturesEnabled = !drawing, zoomGesturesEnabled = !drawing, rotationGesturesEnabled = !drawing,
        ),
        contentPadding = contentPadding,
    ) {
        val center = session.circleCenter
        if (center != null) {
            Circle(center.latLng(), fillColor = Color(0xFFFF9500).copy(alpha = 0.24f), radius = session.circleRadiusMeters, strokeColor = Color(0xFFFF9500), strokeWidth = px(3f))
            StaticMapMarker(center, "cutaway", anchor = Offset(0.5f, 0.5f), zIndex = 2f) {
                BasePointBadge(Icons.Filled.TrackChanges, Color(0xFFFF9500), size = 30)
            }
        }
        if (session.drawnCoordinates.size >= 2) {
            Polyline(session.drawnCoordinates.map { it.latLng() }, color = Color(0xFFFFD60A), width = px(5f), zIndex = 3f)
        }
        val solution = session.solution
        if (solution != null) {
            Circle(solution.landingCenter.latLng(), fillColor = Color(0xFFFF3B30).copy(alpha = 0.25f), radius = solution.landingRadiusMeters, strokeColor = Color(0xFFFF3B30), strokeWidth = px(3f))
            if (solution.centerPath.size >= 2) {
                Polyline(solution.centerPath.map { it.latLng() }, color = Color(0xFFFFD60A).copy(alpha = 0.9f), width = px(3f), zIndex = 1f)
            }
            StaticMapMarker(solution.landingCenter, "landing", anchor = Offset(0.5f, 0.5f), zIndex = 2f) {
                BasePointBadge(Icons.Filled.TrackChanges, Color(0xFFFF3B30), size = 30)
            }
        }
    }
}

@Composable
private fun DrawTip() {
    val s = LocalStrings.current
    Text(
        s("Tap to draw cutaway area"),
        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(AltixOrange, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 9.dp),
    )
}

@Composable
private fun WindStatusBadge(session: CutawayFinderSession, modifier: Modifier) {
    val s = LocalStrings.current
    Column(
        modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(11.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(session.mode.icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(s(session.mode.statusKey), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            val date = session.profile?.validAt ?: session.forecastDate
            val pattern = if (s.chinese) "yyyy年M月d日 HH:mm" else "MMM d, yyyy HH:mm"
            Text(
                DateTimeFormatter.ofPattern(pattern, if (s.chinese) Locale.SIMPLIFIED_CHINESE else Locale.US).format(date.atZone(ZoneId.systemDefault())),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InputPanel(session: CutawayFinderSession, unit: AltitudeUnit, modifier: Modifier) {
    val s = LocalStrings.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val altitudeText = s.format("%@ ft", grouped(session.cutawayAltitudeFeet)).let {
        if (unit == AltitudeUnit.METERS) "$it · ${s.format("%@ m", grouped(session.cutawayAltitudeFeet * WindMath.METERS_PER_FOOT))}" else it
    }
    val sliderColors = SliderDefaults.colors(thumbColor = AltixOrange, activeTrackColor = AltixOrange)
    MapPanel(modifier, corner = 18) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VerticalAlignTop, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s("Cut@"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(altitudeText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Slider(
                    value = session.cutawayAltitudeFeet.toFloat(),
                    onValueChange = { session.cutawayAltitudeFeet = ((it / 100).roundToInt() * 100).toDouble() },
                    onValueChangeFinished = { session.altitudeEditingEnded() },
                    valueRange = 1_000f..12_000f, steps = 109, colors = sliderColors,
                )
                Row {
                    Text(s.format("%@ ft", "1,000"), fontSize = 11.sp, color = secondary, modifier = Modifier.weight(1f))
                    Text(s.format("%@ ft", "12,000"), fontSize = 11.sp, color = secondary)
                }
            }
            VerticalDivider(Modifier.height(68.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VerticalAlignBottom, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s("Fall Rate"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("${formatNumber(session.fallRateFeetPerSecond, 1)} ft/s", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Slider(
                    value = session.fallRateFeetPerSecond.toFloat(),
                    onValueChange = { session.updateFallRate(it.roundToInt().toDouble()) },
                    valueRange = 0f..60f, steps = 59, colors = sliderColors,
                )
                Row {
                    Text("0 ft/s", fontSize = 11.sp, color = secondary, modifier = Modifier.weight(1f))
                    Text("60 ft/s", fontSize = 11.sp, color = secondary)
                }
            }
        }
    }
}
