package com.skyalt.canopy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.model.Touchdown
import com.skyalt.canopy.model.Units
import com.skyalt.canopy.scene.CameraPreset
import com.skyalt.canopy.sim.FlightStatus
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import com.skyalt.canopy.model.Accident
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import java.util.Locale
import kotlin.math.roundToInt

/** The strip across the top: the readouts, the flight state and the altitude. */
@Composable
fun InstrumentBar(state: UiState, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val s = LocalStrings.current
    val d = state.display
    val units = state.units
    val c = state.configuration
    val sinkTint = if (d.sinkRate > 6) BAD else if (d.sinkRate < 2) GOOD else null
    val range = d.glideRange
    val targetTint = when {
        range == null -> null
        kotlin.math.abs(range - d.distanceToTarget) < 30 -> GOOD
        range < d.distanceToTarget -> WARN
        else -> null
    }
    val windValue: String
    val windUnit: String
    if (c.windSpeed < 0.05) {
        windValue = s("Calm"); windUnit = ""
    } else {
        windValue = units.speedText(c.windSpeed)
        windUnit = "${units.speedSymbol} · ${String.format(Locale.US, "%03d", c.windDirectionDegrees.roundToInt())}°"
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(Palette.chip)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            // Back to the scene page, and from there out of the simulator.
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Palette.ink.copy(alpha = 0.08f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s("Back"),
                    tint = Palette.ink, modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Readout(s("Airspeed"), units.speedText(d.airspeed), units.speedSymbol, null, 40.dp)
            Readout(s("Ground"), units.speedText(d.groundSpeed), units.speedSymbol, null, 40.dp)
            Readout(s("Sink"), units.speedText(d.sinkRate), units.speedSymbol, sinkTint, 40.dp)
            Readout(s("Lift"), String.format(Locale.US, "%.2f", d.liftCoefficient), "CL", null, 40.dp)
            Readout(s("Target"), units.distanceText(d.distanceToTarget), units.distanceSymbol, targetTint, 50.dp)
            Readout(s("Wind"), windValue, windUnit, null, 40.dp)
            Readout(s("Time"), String.format(Locale.US, "%.1f", d.time), "s", null, 44.dp)
        }
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(34.dp).background(Palette.ink.copy(alpha = 0.18f)))
        StatusBadge(d.status)
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.widthIn(min = 92.dp), horizontalArrangement = Arrangement.End) {
            Text(units.distanceText(d.altitude), style = displayStyle(30.sp, FontWeight.Bold), color = Palette.ink, maxLines = 1)
            Spacer(Modifier.width(3.dp))
            Text(units.distanceSymbol, fontSize = 13.sp, color = Palette.ink.copy(alpha = 0.65f), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

private val BAD = hex(0xb42318)
private val WARN = hex(0xb7791f)
private val GOOD = hex(0x1f7a4d)

@Composable
private fun Readout(title: String, value: String, unit: String, tint: Color?, minValueWidth: Dp) {
    Column(Modifier.widthIn(min = minValueWidth + 16.dp)) {
        Text(title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink.copy(alpha = 0.65f), maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = displayStyle(19.sp), color = tint ?: Palette.ink, maxLines = 1)
            Spacer(Modifier.width(2.dp))
            Text(unit, fontSize = 10.sp, color = Palette.ink.copy(alpha = 0.65f), maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

fun statusLabel(status: FlightStatus, s: Strings): String = when (status) {
    FlightStatus.FullFlight -> s("Full flight")
    FlightStatus.Landed -> s("Landed")
    FlightStatus.Replay -> s("Replay")
    FlightStatus.StallOnset -> s("Stall onset")
    FlightStatus.FullStall -> s("Full stall")
    FlightStatus.DeepBrakes -> s("Deep brakes")
    FlightStatus.LeftTurn -> s("Left turn")
    FlightStatus.RightTurn -> s("Right turn")
    is FlightStatus.Brakes -> s.format("Brakes %d%%", status.percent)
    FlightStatus.Turbulence -> s("Turbulence")
    FlightStatus.Deflating -> s("Cells losing pressure")
    FlightStatus.WakeCollapse -> s("Wake collapse")
    FlightStatus.Collision -> s("Collision")
}

@Composable
private fun StatusBadge(status: FlightStatus) {
    val s = LocalStrings.current
    val (background, foreground) = when (status) {
        FlightStatus.StallOnset -> hex(0xd9581f) to Color.White
        FlightStatus.FullStall -> hex(0xc0281e) to Color.White
        FlightStatus.DeepBrakes -> hex(0xe3a21a) to hex(0x1b1100)
        FlightStatus.Turbulence -> hex(0x5c6bc0) to Color.White
        FlightStatus.Deflating -> hex(0x7b3fa0) to Color.White
        FlightStatus.WakeCollapse -> hex(0x8e1b6b) to Color.White
        FlightStatus.Collision -> hex(0x111111) to Color.White
        FlightStatus.Landed, FlightStatus.Replay -> Palette.ink to Color.White
        else -> Palette.ink.copy(alpha = 0.08f) to Palette.ink
    }
    Box(
        Modifier
            .widthIn(min = 104.dp)
            .background(background, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(statusLabel(status, s).uppercase(), style = displayStyle(15.sp), color = foreground, maxLines = 1, letterSpacing = 0.8.sp)
    }
}

/** The canopy flown: type, exit weight and size. Green for a student canopy. */
@Composable
fun ModeChip(state: UiState) {
    val s = LocalStrings.current
    val c = state.configuration
    val weight = state.units.weightValue(c.exitWeightKilograms).roundToInt()
    val canopy = "${c.canopyModel.name} · $weight ${state.units.weightSymbol} · ${c.canopyAreaSquareFeet.roundToInt()} ft²"
    // The scene being flown is named in front of the canopy.
    val scenario = state.scenario
    val text = if (scenario != null) "${scenario.number} · ${s(scenario.titleKey)} · $canopy" else canopy
    Text(
        text.uppercase(),
        style = displayStyle(13.sp),
        color = Color.White,
        maxLines = 1,
        letterSpacing = 0.9.sp,
        modifier = Modifier
            .background(if (c.studentMode) hex(0x1f7a4d) else Palette.ink, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** A wrist altimeter's face: whole units below 1,000, thousands to one decimal above. */
@Composable
fun AltimeterFace(altitudeMeters: Double, units: Units) {
    val value = maxOf(0.0, units.distanceValue(altitudeMeters))
    val whole = Math.round(value)
    val abbreviated = whole >= 1000
    val reading = if (abbreviated) String.format(Locale.US, "%.1f", Math.round(value / 100) / 10.0) else whole.toString()
    Box(
        Modifier
            .size(92.dp)
            .shadow(4.dp, CircleShape)
            .background(Palette.ink.copy(alpha = 0.88f), CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("×1000", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = if (abbreviated) 0.7f else 0f))
            Text(reading, style = displayStyle(30.sp, FontWeight.Bold), color = Color.White, maxLines = 1)
            Text(units.distanceSymbol, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/**
 * Both brake toggles with their touch columns: the finger is taken anywhere
 * on that side, from the screen's edge to past the toggle, and mapped onto
 * the track; lifting it lets the toggle spring back up.
 */
@Composable
fun BrakeControls(state: UiState, engine: SimulatorEngine, modifier: Modifier = Modifier, visualOpacity: Float = 0.3f) {
    val interactive = state.replay == null
    BoxWithConstraints(modifier.fillMaxSize()) {
        val trackHeight = maxOf(80.dp, maxHeight - 78.dp)
        Row(Modifier.fillMaxSize()) {
            BrakeColumn(true, state, trackHeight, interactive, visualOpacity) { engine.setBrakeTarget(left = it) }
            Spacer(Modifier.weight(1f))
            BrakeColumn(false, state, trackHeight, interactive, visualOpacity) { engine.setBrakeTarget(right = it) }
        }
    }
}

@Composable
private fun BrakeColumn(left: Boolean, state: UiState, trackHeight: Dp, interactive: Boolean, visualOpacity: Float, onTarget: (Double) -> Unit) {
    val p = LocalPalette.current
    val s = LocalStrings.current
    val d = state.display
    val value = if (left) d.leftBrake else d.rightBrake
    val target = if (left) d.leftTarget else d.rightTarget
    val color = if (left) p.left else p.right
    var columnTop by remember { mutableStateOf(0f) }
    var trackTop by remember { mutableStateOf(0f) }
    var trackPx by remember { mutableStateOf(1f) }
    Box(
        Modifier
            .width(10.dp + 56.dp + 28.dp)
            .fillMaxHeight()
            .onGloballyPositioned { columnTop = it.boundsInWindow().top }
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.filter { it.pressed }
                        if (down.isEmpty()) {
                            onTarget(0.0)
                        } else {
                            val y = down.first().position.y + columnTop - trackTop
                            onTarget((y / trackPx).toDouble().coerceIn(0.0, 1.0))
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Column(
            Modifier.padding(start = if (left) 10.dp else 0.dp, end = if (left) 0.dp else 10.dp).alpha(visualOpacity),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(s(if (left) "Left toggle" else "Right toggle").uppercase(), style = displayStyle(13.sp), color = color, modifier = Modifier.chipBackground())
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .width(56.dp)
                    .height(trackHeight)
                    .onGloballyPositioned {
                        val b = it.boundsInWindow()
                        trackTop = b.top; trackPx = maxOf(1f, b.height)
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.9f))
                    .border(1.dp, Palette.ink.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
            ) {
                if (!state.configuration.studentMode) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(trackHeight * 0.12f)) {
                        Canvas(Modifier.fillMaxSize()) {
                            clipRect {
                                var x = -size.height
                                while (x < size.width + size.height) {
                                    drawLine(hex(0xb42318, 0.35f), Offset(x, size.height), Offset(x + size.height, 0f), 3f)
                                    x += 8.dp.toPx()
                                }
                            }
                        }
                        Text(s("stall").uppercase(), style = displayStyle(10.sp), color = hex(0xb42318), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(trackHeight * value.toFloat()).background(color.copy(alpha = 0.28f)))
                Box(
                    Modifier
                        .offset(y = trackHeight * target.toFloat() - 1.dp)
                        .padding(horizontal = 6.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Palette.ink.copy(alpha = 0.45f)),
                )
                Box(
                    Modifier
                        .offset(y = (trackHeight * value.toFloat() - 11.dp).coerceIn(0.dp, trackHeight - 22.dp))
                        .padding(horizontal = 4.dp)
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(color, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (left) "L" else "R", style = displayStyle(12.sp), color = Color.White)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("${(value * 100).roundToInt()}%", style = displayStyle(18.sp), color = Palette.ink, modifier = Modifier.chipBackground())
        }
    }
}

/** Start or pause, reset and replay. */
@Composable
fun TransportButtons(state: UiState, engine: SimulatorEngine, iconOnly: Boolean = false) {
    val s = LocalStrings.current
    val landed = state.display.status == FlightStatus.Landed || state.accident != null
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipButton(
            if (state.isPaused || landed) s("Start run") else s("Pause"),
            style = ChipStyle.VIEWPORT,
            isOn = state.isPaused,
            isDisabled = state.replay != null || landed,
            icon = if (state.isPaused || landed) Icons.Filled.PlayArrow else if (iconOnly) SimIcons.Pause else null,
            fontSize = 12,
            iconOnly = iconOnly,
        ) { engine.togglePause() }
        ChipButton(s("Reset"), style = ChipStyle.VIEWPORT, icon = Icons.Filled.Refresh, fontSize = 12, iconOnly = iconOnly) { engine.resetRun() }
        ChipButton(
            s("Replay"), style = ChipStyle.VIEWPORT, isOn = state.replay != null, isDisabled = !state.canReplay, fontSize = 12,
            icon = if (iconOnly) SimIcons.Replay else null, iconOnly = iconOnly,
        ) {
            if (state.replay != null) engine.exitReplay() else engine.enterReplay()
        }
    }
}

/** The view, motion capture, cast and settings buttons; [stacked] puts each name under its icon where space is short. */
@Composable
fun ToolButtons(
    state: UiState,
    engine: SimulatorEngine,
    isCapturing: Boolean,
    isCasting: Boolean,
    onOpenSettings: () -> Unit,
    onOpenMotionCapture: () -> Unit,
    onToggleCast: () -> Unit,
    stacked: Boolean = false,
    /** Opens the scene page; none on the casting controls. */
    onOpenScenarios: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onOpenScenarios != null) {
            ChipButton(s("Scenes"), style = ChipStyle.VIEWPORT, icon = Icons.AutoMirrored.Filled.List, fontSize = 12, stacked = stacked, onClick = onOpenScenarios)
        }
        val title = when (state.cameraPreset) {
            CameraPreset.CHASE -> s("Chase")
            CameraPreset.SIDE -> s("Side")
            CameraPreset.FRONT -> s("Front")
            CameraPreset.TOP -> s("Top")
            CameraPreset.FIRST_PERSON -> s("First person")
            null -> s("Free view")
        }
        ChipButton(title, style = ChipStyle.VIEWPORT, icon = SimIcons.Videocam, fontSize = 12, stacked = stacked) { engine.cycleCameraPreset() }
        ChipButton(s("Motion capture"), style = ChipStyle.VIEWPORT, isOn = isCapturing, icon = SimIcons.Figure, fontSize = 12, stacked = stacked, onClick = onOpenMotionCapture)
        ChipButton(
            if (isCasting) s("Stop casting") else s("Cast"), style = ChipStyle.VIEWPORT, isOn = isCasting, icon = SimIcons.Tv,
            fontSize = 12, stacked = stacked, onClick = onToggleCast,
        )
        ChipButton(s("Settings"), style = ChipStyle.VIEWPORT, icon = Icons.Filled.Settings, fontSize = 12, stacked = stacked, onClick = onOpenSettings)
    }
}

/** The round see-through start button in the middle of a paused 3D view. */
@Composable
fun CentreStartButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = LocalStrings.current("Start run"), tint = Color.White, modifier = Modifier.size(52.dp).offset(x = 2.dp))
    }
}

/** Play, the scrubber, the speed and the way out of a replay. */
@Composable
fun ReplayBar(state: UiState, engine: SimulatorEngine) {
    val s = LocalStrings.current
    val replay = state.replay ?: return
    val d = state.display
    val fraction = if (d.replayDuration > 0) (d.replayTime / d.replayDuration).toFloat() else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChipButton(
            if (replay.isPlaying) s("Playing") else s("Play"),
            style = ChipStyle.VIEWPORT,
            isOn = replay.isPlaying,
            icon = if (replay.isPlaying) null else Icons.Filled.PlayArrow,
            fontSize = 12,
        ) { engine.toggleReplayPlayback() }
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = { engine.scrubReplay(it.toDouble()) },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Palette.ink, activeTrackColor = Palette.ink),
        )
        Text(String.format(Locale.US, "%.1f / %.1f s", d.replayTime, d.replayDuration), style = displayStyle(13.sp), color = Palette.ink)
        for ((speed, label) in listOf(0.25 to "¼×", 0.5 to "½×", 1.0 to "1×", 2.0 to "2×")) {
            ChipButton(label, style = ChipStyle.VIEWPORT, isOn = kotlin.math.abs(replay.speed - speed) < 1e-6, fontSize = 12) { engine.setReplaySpeed(speed) }
        }
        ChipButton(s("Exit replay"), style = ChipStyle.VIEWPORT, fontSize = 12) { engine.exitReplay() }
    }
}

/** The touchdown verdict with its numbers and the way on. */
@Composable
fun LandingCard(state: UiState, touchdown: Touchdown, engine: SimulatorEngine) {
    val s = LocalStrings.current
    val u = state.units
    val title = when (touchdown.quality) {
        Touchdown.Quality.STAND_UP -> s("Stand-up landing")
        Touchdown.Quality.FIRM -> s("Firm landing, PLF territory")
        Touchdown.Quality.HARD -> s("Hard landing")
        Touchdown.Quality.CRASH -> s("Crash landing")
    }
    Column(
        Modifier
            .padding(24.dp)
            .shadow(15.dp, RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .widthIn(min = 240.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.ink, textAlign = TextAlign.Center)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LandingRow(s("Vertical speed"), "${u.speedText(touchdown.verticalSpeed)} ${u.speedSymbol} ${s("down")}")
            LandingRow(s("Ground speed"), "${u.speedText(touchdown.groundSpeed)} ${u.speedSymbol}")
            LandingRow(s("From target"), "${u.distanceText(touchdown.missDistance)} ${u.distanceSymbol}")
        }
        if (state.configuration.studentMode) {
            // However soft the touchdown, a student is always ready to PLF.
            Text(
                s("Always be ready to PLF"),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = hex(0xb4541a),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipButton(s("Replay"), style = ChipStyle.VIEWPORT, isOn = true, fontSize = 14) { engine.enterReplay() }
            ChipButton(s("Reset"), style = ChipStyle.VIEWPORT, isOn = true, fontSize = 14) { engine.resetRun() }
        }
    }
}

/** A collision's verdict: what was hit, where and when, and the way on. The run is over. */
@Composable
fun AccidentCard(state: UiState, accident: Accident, engine: SimulatorEngine) {
    val s = LocalStrings.current
    val u = state.units
    val cause = when (accident.kind) {
        Accident.Kind.CANOPY -> s("Collided with another canopy")
        Accident.Kind.AIRCRAFT -> s("Flew into the aircraft")
        Accident.Kind.PERSON -> s("Hit a person on the ground")
        Accident.Kind.BUILDING -> s("Flew into the hangar")
    }
    Column(
        Modifier
            .padding(24.dp)
            .shadow(15.dp, RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .widthIn(min = 240.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(s("Severe accident"), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = hex(0xc0281e), textAlign = TextAlign.Center)
        Text(cause, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 280.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LandingRow(s("Altitude"), "${u.distanceText(accident.altitude)} ${u.distanceSymbol}")
            LandingRow(s("Time"), String.format(Locale.US, "%.1f s", accident.time))
        }
        Text(s("The simulation has stopped."), fontSize = 12.sp, color = hex(0x3d4b5c))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipButton(s("Replay"), style = ChipStyle.VIEWPORT, isOn = true, isDisabled = !state.canReplay, fontSize = 14) { engine.enterReplay() }
            ChipButton(s("Reset"), style = ChipStyle.VIEWPORT, isOn = true, fontSize = 14) { engine.resetRun() }
        }
    }
}

@Composable
private fun LandingRow(title: String, value: String) {
    Row {
        Text(title, fontSize = 15.sp, color = hex(0x3d4b5c), modifier = Modifier.widthIn(min = 120.dp), textAlign = TextAlign.End)
        Spacer(Modifier.width(14.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.ink)
    }
}

