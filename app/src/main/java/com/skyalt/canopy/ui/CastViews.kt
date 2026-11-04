package com.skyalt.canopy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.sim.MotionCaptureController
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState

/**
 * The phone while casting: the instruments on top, a brake toggle on each
 * side at full strength and one controls window in the middle (the casting
 * guide, icon buttons and the camera pad); the 3D view is on the other
 * screen. When motion capture flies the canopy the toggles go, the controls
 * window takes the left half and the camera picture the right.
 */
@Composable
fun CastControls(
    state: UiState,
    engine: SimulatorEngine,
    capture: MotionCaptureController,
    isDisplayConnected: Boolean,
    onOpenSettings: () -> Unit,
    onOpenMotionCapture: () -> Unit,
    onStopCasting: () -> Unit,
    onOpenCastSettings: () -> Unit,
) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(Modifier.fillMaxSize()) {
            InstrumentBar(state)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout)) {
                val window: @Composable (Modifier) -> Unit = { modifier ->
                    ControlsWindow(state, engine, capture, isDisplayConnected, onOpenSettings, onOpenMotionCapture, onStopCasting, onOpenCastSettings, modifier)
                }
                if (capture.active) {
                    val half = (maxWidth - 24.dp - 10.dp) / 2
                    val pictureWidth = minOf(half, (maxHeight - 16.dp) * 4f / 3f)
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        window(Modifier.width(half).fillMaxHeight())
                        Box(Modifier.width(half).fillMaxHeight().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            MocapPreviewBox(capture, pictureWidth, pictureWidth * 3f / 4f)
                        }
                    }
                } else {
                    BrakeControls(state, engine, visualOpacity = 1f)
                    val side = 10.dp + 56.dp + 28.dp + 6.dp
                    Box(Modifier.fillMaxSize().padding(horizontal = side), contentAlignment = Alignment.TopCenter) {
                        window(Modifier.fillMaxSize())
                    }
                }
            }
        }
        val touchdown = state.touchdown
        if (state.showsLandingCard && touchdown != null) {
            Box(Modifier.align(Alignment.Center)) { LandingCard(state, touchdown, engine) }
        }
        val accident = state.accident
        if (accident != null && state.replay == null) {
            Box(Modifier.align(Alignment.Center)) { AccidentCard(state, accident, engine) }
        }
    }
}

@Composable
private fun ControlsWindow(
    state: UiState,
    engine: SimulatorEngine,
    capture: MotionCaptureController,
    isDisplayConnected: Boolean,
    onOpenSettings: () -> Unit,
    onOpenMotionCapture: () -> Unit,
    onStopCasting: () -> Unit,
    onOpenCastSettings: () -> Unit,
    modifier: Modifier,
) {
    val p = LocalPalette.current
    val s = LocalStrings.current
    Column(
        modifier
            .padding(top = 8.dp, bottom = 8.dp)
            .background(p.foreground.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SimIcons.Tv, null, tint = p.foreground, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isDisplayConnected) s("The 3D view is on the connected display.") else s("Cast to a TV or display"),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.foreground,
                )
            }
            if (!isDisplayConnected) {
                for ((i, step) in listOf(
                    "Open the phone's casting: Settings › Connected devices › Cast, or Wireless projection / Smart View on some phones.",
                    "Choose the TV. A USB-C to HDMI cable works too.",
                    "The 3D view moves there as soon as it connects. If the TV only mirrors the whole phone screen, stop casting here and fly on the mirrored screen.",
                ).withIndex()) {
                    Text("${i + 1}. ${s(step)}", fontSize = 12.sp, color = p.muted, lineHeight = 16.sp)
                }
                ChipButton(s("Open cast settings"), icon = SimIcons.Tv, fontSize = 11, onClick = onOpenCastSettings)
            }
        }
        state.replay?.let { ReplayBar(state, engine) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TransportButtons(state, engine, iconOnly = true)
            Spacer(Modifier.weight(1f))
            ToolButtons(state, engine, capture.active, true, onOpenSettings, onOpenMotionCapture, onStopCasting, stacked = true)
        }
        CameraTouchPad(engine, Modifier.fillMaxWidth().weight(1f).heightIn(min = 90.dp))
    }
}

/**
 * The framed area under the casting buttons that turns the camera on the
 * connected display: drag to orbit around the jumper, pinch to zoom, the same
 * as on the 3D view itself.
 */
@Composable
fun CameraTouchPad(engine: SimulatorEngine, modifier: Modifier) {
    val p = LocalPalette.current
    val s = LocalStrings.current
    val density = LocalDensity.current.density
    Box(
        modifier
            .background(p.foreground.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (pan.x != 0f || pan.y != 0f) {
                        engine.orbit((pan.x / density).toDouble(), (pan.y / density).toDouble())
                        engine.userMovedCamera()
                    }
                    if (zoom != 1f) engine.zoomBy(zoom.toDouble())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 0.75.dp.toPx()
            drawRoundRect(
                p.muted,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Icon(SimIcons.Pan, null, tint = p.muted, modifier = Modifier.size(22.dp))
            Text(s("Drag here to turn the camera, pinch to zoom"), fontSize = 12.sp, color = p.muted, textAlign = TextAlign.Center)
        }
    }
}

/**
 * What the connected display shows over the 3D view: the flight data across
 * the top, the mode label, the camera picture while motion capture runs and
 * the altimeter, enlarged for a screen read from across the room and kept
 * inside the margin a television may cut off.
 */
@Composable
fun ExternalStage(state: UiState?, engine: SimulatorEngine, capture: MotionCaptureController) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (state == null) return@BoxWithConstraints
        // About 2× on a 1080p display, never below the phone's size.
        val scale = (maxWidth.value / 900f).coerceIn(1f, 2.4f)
        val marginX = maxWidth * 0.04f
        val marginY = maxHeight * 0.04f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().background(Palette.chip)) {
                Box(Modifier.padding(start = marginX, end = marginX, top = marginY)) {
                    ScaledBox(scale) { InstrumentBar(state, Modifier.background(Color.Transparent)) }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp * scale)) {
                Box(Modifier.align(Alignment.TopCenter)) { ScaledBox(scale) { ModeChip(state) } }
                Row(Modifier.fillMaxWidth().padding(horizontal = marginX), verticalAlignment = Alignment.Top) {
                    if (capture.active) MocapPreviewBox(capture, 200.dp * scale, 150.dp * scale, scale)
                    Spacer(Modifier.weight(1f))
                    ScaledBox(scale) { AltimeterFace(state.display.altitude, state.units) }
                }
            }
        }
        val touchdown = state.touchdown
        if (state.showsLandingCard && touchdown != null) {
            Box(Modifier.align(Alignment.Center)) { ScaledBox(scale) { LandingCard(state, touchdown, engine) } }
        }
        val accident = state.accident
        if (accident != null && state.replay == null) {
            Box(Modifier.align(Alignment.Center)) { ScaledBox(scale) { AccidentCard(state, accident, engine) } }
        }
    }
}

/** Scales its content up by [scale] while taking up the scaled room in the layout. */
@Composable
private fun ScaledBox(scale: Float, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(
        content = { Box(Modifier.graphicsLayer(scaleX = scale, scaleY = scale, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f))) { content() } },
    ) { measurables, constraints ->
        val loose = constraints.copy(
            minWidth = 0, minHeight = 0,
            maxWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth / scale).toInt() else constraints.maxWidth,
            maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight / scale).toInt() else constraints.maxHeight,
        )
        val placeable = measurables.first().measure(loose)
        val w = (placeable.width * scale).toInt()
        val h = (placeable.height * scale).toInt()
        layout(w, h) { placeable.place(0, 0) }
    }
}
