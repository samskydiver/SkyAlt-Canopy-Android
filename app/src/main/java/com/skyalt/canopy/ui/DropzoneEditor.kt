package com.skyalt.canopy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import com.skyalt.canopy.model.DEG
import com.skyalt.canopy.model.DropzoneSettings
import com.skyalt.canopy.model.RunwayGeometry
import com.skyalt.canopy.model.RunwayPlacement
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.PathEffect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The dropzone picture under the fixed target rings: drag to move it, pinch
 * to size it, twist to turn it, and measure a known distance to set the
 * scale exactly.
 */
@Composable
fun DropzoneEditor(state: UiState, engine: SimulatorEngine, startsWithRunway: Boolean = false, onDone: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    // For the runway the editor opens even without a picture.
    val image = state.dropzoneImage
    if (image == null && !startsWithRunway) return
    val density = LocalDensity.current
    var ppm by remember { mutableStateOf(0f) }
    var measuring by remember { mutableStateOf(false) }
    // Measure points as fractions of the picture's width from its centre, so they stay on it at any zoom.
    val points = remember { mutableStateListOf<Offset>() }
    var realDistance by remember { mutableStateOf(0.0) }
    var placingRunway by remember { mutableStateOf(false) }
    var runwayDrag by remember { mutableStateOf<RunwayDrag?>(null) }

    fun beginPlacingRunway() {
        measuring = false
        points.clear()
        if (state.dropzone.runway == null) engine.updateDropzone { it.copy(runway = defaultRunway(it)) }
        placingRunway = true
    }
    LaunchedEffect(Unit) { if (startsWithRunway) beginPlacingRunway() }

    BoxWithConstraints(Modifier.fillMaxSize().background(hex(0x1b2430))) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        if (ppm == 0f) ppm = (w * 0.7f / max(state.dropzone.widthMeters.toFloat(), 100f))
        val centre = Offset(w / 2 - with(density) { 130.dp.toPx() }, h / 2)
        val dz = state.dropzone
        val picture = remember(image) { image?.asImageBitmap() }
        // One frame places the picture, the runway and the measure points on screen.
        fun frameOf(d: DropzoneSettings) = PictureFrame(
            Offset(centre.x + d.eastOffsetMeters.toFloat() * ppm, centre.y + d.southOffsetMeters.toFloat() * ppm),
            d.widthMeters.toFloat() * ppm,
            d.rotationDegrees,
        )
        val frame = frameOf(dz)
        val latestDz by rememberUpdatedState(dz)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(measuring, placingRunway, ppm, centre) {
                    if (placingRunway) {
                        detectDragGestures(
                            onDragStart = begin@{ start ->
                                val runway = latestDz.runway ?: return@begin
                                val f = frameOf(latestDz)
                                runwayDrag = RunwayDrag(runwayHandle(start, runway, f), runway, f.fraction(start))
                            },
                            onDragEnd = { runwayDrag = null },
                            onDragCancel = { runwayDrag = null },
                        ) move@{ change, _ ->
                            val drag = runwayDrag ?: return@move
                            val d = latestDz
                            val moved = runwayMoved(drag, frameOf(d).fraction(change.position), d.widthMeters)
                            engine.updateDropzone { it.copy(runway = moved) }
                        }
                    } else if (measuring) {
                        detectTapGestures { point ->
                            if (points.size >= 2) points.clear()
                            points.add(frameOf(latestDz).fraction(point))
                        }
                    } else {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            engine.updateDropzone { d ->
                                // Sized about the target, so what lies under the rings stays under them.
                                val sized = d.resized(d.widthMeters * zoom)
                                sized.copy(
                                    eastOffsetMeters = sized.eastOffsetMeters + pan.x / ppm,
                                    southOffsetMeters = sized.southOffsetMeters + pan.y / ppm,
                                    // Compose turns clockwise; the setting is counter-clockwise.
                                    rotationDegrees = d.rotationDegrees - rotation,
                                )
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // The picture in the canvas the rings and the runway are drawn in, through the same frame.
                if (picture != null) drawDropzonePicture(picture, frame.at, frame.widthPx, dz.rotationDegrees)
                // The target as it lies on the ground: 5 m bands out to 20 m.
                for ((outer, hexColor) in listOf(20f to 0xf4f4f0, 15f to 0xe4582b, 10f to 0xf4f4f0, 5f to 0xe4582b)) {
                    drawCircle(hex(hexColor, 0.55f), (outer - 2.5f) * ppm, centre, style = Stroke(max(1.5f, 5f * ppm)))
                }
                drawLine(Color.White, centre - Offset(16.dp.toPx(), 0f), centre + Offset(16.dp.toPx(), 0f), 1.5.dp.toPx())
                drawLine(Color.White, centre - Offset(0f, 16.dp.toPx()), centre + Offset(0f, 16.dp.toPx()), 1.5.dp.toPx())
                dz.runway?.let { runway ->
                    val corners = runwayCorners(runway).map { frame.screen(it) }
                    val outline = Path().apply {
                        moveTo(corners[0].x, corners[0].y)
                        for (c in corners.drop(1)) lineTo(c.x, c.y)
                        close()
                    }
                    drawPath(outline, hex(0xf07f1a, if (placingRunway) 0.32f else 0.2f))
                    drawPath(outline, Color.White.copy(alpha = if (placingRunway) 1f else 0.8f), style = Stroke(if (placingRunway) 2.dp.toPx() else 1.5.dp.toPx()))
                    val near = midpoint(corners[2], corners[3])
                    val far = midpoint(corners[0], corners[1])
                    drawLine(
                        Color.White.copy(alpha = 0.8f), near, far, 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                    )
                    if (placingRunway) {
                        val turn = turnHandlePoint(corners, TURN_STEM.dp.toPx())
                        drawLine(Color.White, far, turn, 1.5.dp.toPx())
                        drawCircle(Color.White, 9.dp.toPx(), turn)
                        drawCircle(p.accent, 9.dp.toPx(), turn, style = Stroke(2.dp.toPx()))
                        for (c in corners) {
                            drawCircle(p.accent, 7.dp.toPx(), c)
                            drawCircle(Color.White, 7.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
                if (measuring) {
                    val marks = points.map { frame.screen(it) }
                    for (pt in marks) {
                        drawCircle(hex(0xffd23f), 7.dp.toPx(), pt)
                        drawCircle(Color.Black, 7.dp.toPx(), pt, style = Stroke(1.5.dp.toPx()))
                    }
                    if (marks.size == 2) drawLine(hex(0xffd23f), marks[0], marks[1], 2.dp.toPx())
                }
            }
            // North is straight up in this view, as in the 3D scene and the start-point map.
            NorthArrow(Modifier.align(Alignment.TopStart).padding(14.dp))
        }

        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp)
                .width(240.dp)
                .background(p.background.copy(alpha = 0.96f), shape)
                .border(1.dp, p.line, shape)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(s("Edit dropzone picture").uppercase(), style = displayStyle(13.sp), color = p.muted)
            ChipButton(s("Set runway"), isOn = placingRunway, fillsWidth = true) {
                if (placingRunway) placingRunway = false else beginPlacingRunway()
            }
            if (placingRunway) {
                if (startsWithRunway) {
                    Text(s("Set the runway on the picture first; the aircraft lands on it."), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.foreground)
                }
                Note(s("Drag a corner to size the runway, drag inside to move it, drag the end handle to turn it."))
                dz.runwayGeometry?.let { g -> FieldLabel(s("Runway"), runwayText(g, state)) }
                ChipButton(s("Remove runway"), fontSize = 12) {
                    engine.updateDropzone { it.copy(runway = null) }
                    placingRunway = false
                }
            }
            Note(s("Drag to move · pinch to size · two fingers to turn"))
            Note(s("Turn the picture so its north points to N"))
            FieldLabel(s("Picture width"), "${state.units.distanceText(dz.widthMeters)} ${state.units.distanceSymbol}")
            FieldLabel(s("Rotation"), "${dz.rotationDegrees.roundToInt()}°")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton(s("Zoom out"), fontSize = 11) { ppm = (ppm / 1.5f).coerceIn(0.02f, 50f) }
                ChipButton(s("Zoom in"), fontSize = 11) { ppm = (ppm * 1.5f).coerceIn(0.02f, 50f) }
            }
            ChipButton(s("Measure"), isOn = measuring, fillsWidth = true, isDisabled = image == null) {
                measuring = !measuring
                if (measuring) placingRunway = false
                points.clear()
            }
            if (measuring) {
                if (points.size < 2) {
                    Note(s("Tap two points a known distance apart"))
                } else {
                    val measured = (points[1] - points[0]).getDistance() * dz.widthMeters
                    FieldLabel(s("Measured"), "${state.units.distanceText(measured)} ${state.units.distanceSymbol}")
                    NumberField(s("Actual distance"), state.units.distanceSymbol, realDistance, { realDistance = it }, 0)
                    ChipButton(s("Apply scale"), style = ChipStyle.PRIMARY, fillsWidth = true, isDisabled = realDistance <= 0) {
                        val real = state.units.meters(realDistance)
                        engine.updateDropzone { d -> d.resized(d.widthMeters * real / max(measured, 0.01)) }
                        points.clear()
                        measuring = false
                    }
                }
                ChipButton(s("Clear points"), fontSize = 11) { points.clear() }
            }
            ChipButton(s("Reset position"), fontSize = 11) {
                engine.updateDropzone { it.copy(eastOffsetMeters = 0.0, southOffsetMeters = 0.0, rotationDegrees = 0.0) }
            }
            ChipButton(s("Done"), style = ChipStyle.PRIMARY, icon = Icons.Filled.Check, fillsWidth = true, onClick = onDone)
        }
    }
}


/**
 * The picture on screen: its centre, its width in pixels and its turn
 * (counter-clockwise on screen); fractions are of the picture's width
 * from its centre, x right and y down in the picture.
 */
private class PictureFrame(val at: Offset, val widthPx: Float, rotationDegrees: Double) {
    private val c = cos(rotationDegrees * DEG).toFloat()
    private val s = sin(rotationDegrees * DEG).toFloat()

    fun screen(f: Offset) = Offset(at.x + widthPx * (f.x * c + f.y * s), at.y + widthPx * (-f.x * s + f.y * c))

    fun fraction(point: Offset): Offset {
        val dx = (point.x - at.x) / max(widthPx, 0.001f)
        val dy = (point.y - at.y) / max(widthPx, 0.001f)
        return Offset(dx * c - dy * s, dx * s + dy * c)
    }
}

private enum class RunwayHandleKind { BODY, TURN, CORNER }
private class RunwayHandle(val kind: RunwayHandleKind, val corner: Int = 0)
private class RunwayDrag(val handle: RunwayHandle, val start: RunwayPlacement, val startFraction: Offset)

private const val HANDLE_RADIUS = 24
/** How far beyond the runway's far end the turn handle sits, in dp. */
private const val TURN_STEM = 40

/** A first runway: 300 m by 30 m, or smaller on a small picture, level on the screen and centred on the target. */
private fun defaultRunway(d: DropzoneSettings): RunwayPlacement {
    val width = max(d.widthMeters, 1.0)
    val theta = d.rotationDegrees * DEG
    val e = d.eastOffsetMeters
    val so = d.southOffsetMeters
    return RunwayPlacement(
        centreX = (-e * cos(theta) + so * sin(theta)) / width,
        centreY = (-e * sin(theta) - so * cos(theta)) / width,
        length = min(300.0, width * 0.6) / width,
        width = max(30.0, width * 0.02) / width,
        angleDegrees = d.rotationDegrees,
    )
}

/** Unit vectors along and across the runway in the picture's frame. */
private fun runwayAxes(r: RunwayPlacement): Pair<Offset, Offset> {
    val phi = r.angleDegrees * DEG
    return Offset(cos(phi).toFloat(), sin(phi).toFloat()) to Offset(-sin(phi).toFloat(), cos(phi).toFloat())
}

/** The corners as picture fractions: far right, far left, near left and near right, "far" along the runway's angle. */
private fun runwayCorners(r: RunwayPlacement): List<Offset> {
    val (along, across) = runwayAxes(r)
    val l = (r.length / 2).toFloat()
    val w = (r.width / 2).toFloat()
    return listOf(1f to 1f, 1f to -1f, -1f to -1f, -1f to 1f).map { (a, b) ->
        Offset(r.centreX.toFloat() + along.x * l * a + across.x * w * b, r.centreY.toFloat() + along.y * l * a + across.y * w * b)
    }
}

private fun midpoint(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)

private fun unit(a: Offset, b: Offset): Offset {
    val d = b - a
    val length = max(d.getDistance(), 0.001f)
    return Offset(d.x / length, d.y / length)
}

/** The turn handle on screen: beyond the middle of the far end. */
private fun turnHandlePoint(corners: List<Offset>, stem: Float): Offset {
    val far = midpoint(corners[0], corners[1])
    val along = unit(midpoint(corners[2], corners[3]), far)
    return Offset(far.x + along.x * stem, far.y + along.y * stem)
}

private fun androidx.compose.ui.input.pointer.PointerInputScope.runwayHandle(point: Offset, runway: RunwayPlacement, frame: PictureFrame): RunwayHandle {
    val r = HANDLE_RADIUS.dp.toPx()
    val corners = runwayCorners(runway).map { frame.screen(it) }
    val turn = turnHandlePoint(corners, TURN_STEM.dp.toPx())
    if ((point - turn).getDistance() <= r) return RunwayHandle(RunwayHandleKind.TURN)
    var best = -1
    var bestDistance = Float.MAX_VALUE
    for ((i, c) in corners.withIndex()) {
        val d = (point - c).getDistance()
        if (d <= r && d < bestDistance) { best = i; bestDistance = d }
    }
    return if (best >= 0) RunwayHandle(RunwayHandleKind.CORNER, best) else RunwayHandle(RunwayHandleKind.BODY)
}

/**
 * A corner drag keeps the opposite corner where it is and sizes the runway
 * to the finger; a drag inside moves it; the turn handle swings the far
 * end round the centre.
 */
private fun runwayMoved(drag: RunwayDrag, fraction: Offset, pictureWidthMeters: Double): RunwayPlacement {
    val start = drag.start
    val widthMeters = max(pictureWidthMeters, 1.0)
    return when (drag.handle.kind) {
        RunwayHandleKind.BODY -> start.copy(
            centreX = start.centreX + (fraction.x - drag.startFraction.x),
            centreY = start.centreY + (fraction.y - drag.startFraction.y),
        )
        RunwayHandleKind.TURN -> {
            val dx = fraction.x - start.centreX
            val dy = fraction.y - start.centreY
            if (hypot(dx, dy) > 0.001) start.copy(angleDegrees = atan2(dy, dx) / DEG) else start
        }
        RunwayHandleKind.CORNER -> {
            val fixed = runwayCorners(start)[(drag.handle.corner + 2) % 4]
            val (along, across) = runwayAxes(start)
            val dx = fraction.x - fixed.x
            val dy = fraction.y - fixed.y
            val u = dx * along.x + dy * along.y
            val v = dx * across.x + dy * across.y
            val length = max(abs(u).toDouble(), RunwayPlacement.MINIMUM_LENGTH_METERS / widthMeters)
            val width = max(abs(v).toDouble(), RunwayPlacement.MINIMUM_WIDTH_METERS / widthMeters)
            val su = if (u < 0) -1 else 1
            val sv = if (v < 0) -1 else 1
            start.copy(
                length = length,
                width = width,
                centreX = fixed.x + along.x * su * length / 2 + across.x * sv * width / 2,
                centreY = fixed.y + along.y * su * length / 2 + across.y * sv * width / 2,
            )
        }
    }
}

private fun runwayText(g: RunwayGeometry, state: UiState): String {
    val u = state.units
    return "${u.distanceText(g.lengthMeters)} × ${u.distanceText(g.widthMeters)} ${u.distanceSymbol} · ${Math.round(g.headingDegrees) % 360}°"
}

/** A fixed north arrow: a red north half, a white south half and the letter N above. */
@Composable
private fun NorthArrow(modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("N", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Canvas(Modifier.requiredSize(22.dp, 40.dp)) {
            val cx = size.width / 2
            val mid = size.height / 2
            val north = Path().apply { moveTo(cx, 0f); lineTo(size.width, mid); lineTo(0f, mid); close() }
            val south = Path().apply { moveTo(0f, mid); lineTo(size.width, mid); lineTo(cx, size.height); close() }
            drawPath(north, hex(0xe4582b))
            drawPath(south, Color.White)
            drawPath(south, Color.Black.copy(alpha = 0.4f), style = Stroke(1.dp.toPx()))
        }
    }
}
