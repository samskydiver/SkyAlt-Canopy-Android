package com.skyalt.canopy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.model.Compass
import com.skyalt.canopy.model.DEG
import com.skyalt.canopy.model.METERS_PER_FOOT
import com.skyalt.canopy.model.SimulatorConfiguration
import com.skyalt.canopy.model.Units
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen north-up map for choosing where a run starts: the target
 * rings, the pattern's ground track, the dropzone picture and the windsock.
 * One finger moves the map (or the windsock, dragged by its marker), a pinch
 * zooms, a double tap puts the start there.
 */
@Composable
fun StartPointScreen(state: UiState, engine: SimulatorEngine, onDone: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val u = state.units
    val c = state.configuration
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val openedWith = remember { c }
    var pixelsPerMeter by remember { mutableStateOf(0f) }
    var centre by remember { mutableStateOf(Offset.Zero) }
    var windsockDrag by remember { mutableStateOf<Offset?>(null) }
    val latest by rememberUpdatedState(state)

    BoxWithConstraints(Modifier.fillMaxSize().background(hex(0x1b2430))) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val screenCentre = Offset(w / 2, h / 2)
        val cardWidthPx = with(density) { 260.dp.toPx() }
        if (pixelsPerMeter == 0f) {
            // Fit the target, the pattern, the start and the windsock beside the card.
            val xs = ArrayList<Double>(); val zs = ArrayList<Double>()
            xs += 0.0; zs += 0.0
            state.referencePattern?.points?.forEach { xs += it.x; zs += it.z }
            state.startPlacement?.position?.let { xs += it.x; zs += it.z }
            xs += c.windsockEastMeters; zs += c.windsockSouthMeters
            val spanX = max(xs.max() - xs.min(), 200.0)
            val spanZ = max(zs.max() - zs.min(), 200.0)
            val cardRoom = cardWidthPx + with(density) { 36.dp.toPx() }
            val freeW = max(w - cardRoom - 40, 100f)
            val freeH = max(h - with(density) { 80.dp.toPx() }, 100f)
            val scale = min(freeW / spanX.toFloat(), freeH / spanZ.toFloat()).coerceIn(0.02f, 50f)
            pixelsPerMeter = scale
            centre = Offset(((xs.min() + xs.max()) / 2 + cardRoom / 2 / scale).toFloat(), ((zs.min() + zs.max()) / 2).toFloat())
        }
        val ppm = pixelsPerMeter
        fun screen(east: Double, south: Double) = Offset(
            screenCentre.x + (east.toFloat() - centre.x) * ppm,
            screenCentre.y + (south.toFloat() - centre.y) * ppm,
        )
        fun ground(point: Offset) = Offset(
            centre.x + (point.x - screenCentre.x) / max(ppm, 0.001f),
            centre.y + (point.y - screenCentre.y) / max(ppm, 0.001f),
        )

        // The map: pan, pinch and double tap.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newPpm = (pixelsPerMeter * zoom).coerceIn(0.02f, 50f)
                        pixelsPerMeter = newPpm
                        centre = Offset(centre.x - pan.x / newPpm, centre.y - pan.y / newPpm)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { point ->
                        val g = Offset(
                            centre.x + (point.x - w / 2) / max(pixelsPerMeter, 0.001f),
                            centre.y + (point.y - h / 2) / max(pixelsPerMeter, 0.001f),
                        )
                        engine.updateConfiguration { it.copy(startEastMeters = g.x.toDouble(), startSouthMeters = g.y.toDouble()) }
                    })
                },
        ) {
            val image = state.dropzoneImage
            val picture = remember(image) { image?.asImageBitmap() }
            val start = state.startPlacement
            Canvas(Modifier.fillMaxSize()) {
                if (ppm <= 0) return@Canvas
                if (picture != null) {
                    val dz = state.dropzone
                    drawDropzonePicture(picture, screen(dz.eastOffsetMeters, dz.southOffsetMeters), dz.widthMeters.toFloat() * ppm, dz.rotationDegrees)
                }
                val target = screen(0.0, 0.0)
                state.referencePattern?.let { pattern ->
                    val style = state.pathStyle.patternGround
                    val color = hex(style.colorHex, style.opacity.toFloat())
                    val path = Path()
                    pattern.points.forEachIndexed { i, pt ->
                        val q = screen(pt.x, pt.z)
                        if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
                    }
                    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))))
                    for (pt in pattern.points.dropLast(1)) drawCircle(color, 3.dp.toPx(), screen(pt.x, pt.z))
                }
                for ((outer, hexColor) in listOf(20.0 to 0xf4f4f0, 15.0 to 0xe4582b, 10.0 to 0xf4f4f0, 5.0 to 0xe4582b)) {
                    drawCircle(hex(hexColor, 0.8f), (outer * ppm).toFloat(), target)
                }
                drawLine(Color.White, target - Offset(14.dp.toPx(), 0f), target + Offset(14.dp.toPx(), 0f), 1.5.dp.toPx())
                drawLine(Color.White, target - Offset(0f, 14.dp.toPx()), target + Offset(0f, 14.dp.toPx()), 1.5.dp.toPx())
                if (start != null) {
                    val sp = screen(start.position.x, start.position.z)
                    val dir = Compass.direction(start.heading)
                    val tip = sp + Offset(dir.x.toFloat(), dir.z.toFloat()) * 34.dp.toPx()
                    drawLine(Color.White, sp, tip, 2.dp.toPx(), cap = StrokeCap.Round)
                    val angle = atan2(dir.z, dir.x)
                    val head = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo((tip.x - 10.dp.toPx() * cos(angle - 0.45)).toFloat(), (tip.y - 10.dp.toPx() * sin(angle - 0.45)).toFloat())
                        lineTo((tip.x - 10.dp.toPx() * cos(angle + 0.45)).toFloat(), (tip.y - 10.dp.toPx() * sin(angle + 0.45)).toFloat())
                        close()
                    }
                    drawPath(head, Color.White)
                    drawCircle(p.accent, 9.dp.toPx(), sp)
                    drawCircle(Color.White, 9.dp.toPx(), sp, style = Stroke(2.dp.toPx()))
                }
            }
            if (start != null) {
                val sp = screen(start.position.x, start.position.z)
                val below = Compass.direction(start.heading).z <= 0.2
                Text(
                    "${s("Start")} · ${u.distanceText(c.startAltitudeMeters)} ${u.distanceSymbol}",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier
                        .offset { IntOffset((sp.x - 50.dp.toPx()).roundToInt(), (sp.y + (if (below) 14 else -38).dp.toPx()).roundToInt()) }
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }

        // The windsock marker, dragged to move it.
        val sock = windsockDrag ?: Offset(c.windsockEastMeters.toFloat(), c.windsockSouthMeters.toFloat())
        val sockAt = screen(sock.x.toDouble(), sock.y.toDouble())
        val markerPx = with(density) { 76.dp.toPx() }
        Canvas(
            Modifier
                .offset { IntOffset((sockAt.x - markerPx / 2).roundToInt(), (sockAt.y - markerPx / 2).roundToInt()) }
                .size(76.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { windsockDrag = Offset(latest.configuration.windsockEastMeters.toFloat(), latest.configuration.windsockSouthMeters.toFloat()) },
                        onDragEnd = {
                            windsockDrag?.let { g -> engine.updateConfiguration { it.copy(windsockEastMeters = g.x.toDouble(), windsockSouthMeters = g.y.toDouble()) } }
                            windsockDrag = null
                        },
                        onDragCancel = { windsockDrag = null },
                    ) { change, amount ->
                        change.consume()
                        val current = windsockDrag ?: return@detectDragGestures
                        windsockDrag = Offset(current.x + amount.x / max(pixelsPerMeter, 0.001f), current.y + amount.y / max(pixelsPerMeter, 0.001f))
                    }
                },
        ) {
            val pole = Offset(size.width / 2, size.height / 2)
            if (c.windSpeed >= 0.05) {
                val blowing = (c.windDirectionDegrees + 180) * DEG
                val dx = sin(blowing).toFloat(); val dy = -cos(blowing).toFloat()
                val length = 30.dp.toPx() * (0.4f + 0.6f * min(1.0, c.windSpeed / 7.7).toFloat())
                val nx = -dy; val ny = dx
                for (band in 0 until 5) {
                    val a = band / 5f; val b = (band + 1) / 5f
                    val wa = 7.dp.toPx() * (1 - 0.5f * a); val wb = 7.dp.toPx() * (1 - 0.5f * b)
                    val pa = pole + Offset(dx, dy) * (length * a)
                    val pb = pole + Offset(dx, dy) * (length * b)
                    val quad = Path().apply {
                        moveTo(pa.x + nx * wa, pa.y + ny * wa)
                        lineTo(pb.x + nx * wb, pb.y + ny * wb)
                        lineTo(pb.x - nx * wb, pb.y - ny * wb)
                        lineTo(pa.x - nx * wa, pa.y - ny * wa)
                        close()
                    }
                    drawPath(quad, if (band % 2 == 0) hex(0xff6b0d) else Color.White)
                    drawPath(quad, Color.Black.copy(alpha = 0.45f), style = Stroke(0.75.dp.toPx()))
                }
            }
            drawCircle(Color.White, 5.dp.toPx(), pole)
            drawCircle(Color.Black.copy(alpha = 0.6f), 5.dp.toPx(), pole, style = Stroke(1.5.dp.toPx()))
        }

        // Title and north arrow
        Text(
            s("Start point").uppercase(), style = displayStyle(15.sp), color = Color.White, letterSpacing = 1.2.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 10.dp),
        )
        Text("▲\nN", style = displayStyle(12.sp), color = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp), lineHeight = 12.sp)

        // Scale bar
        val metersPerUnit = if (u == Units.METRIC) 1.0 else METERS_PER_FOOT
        val candidates = listOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
        val maxBar = with(density) { 140.dp.toPx() }
        val chosen = candidates.lastOrNull { it * metersPerUnit * ppm <= maxBar } ?: candidates[0]
        val barPx = max((chosen * metersPerUnit * ppm).toFloat(), 2f)
        Column(
            Modifier.align(Alignment.BottomStart).padding(14.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(6.dp),
        ) {
            Text("${chosen.roundToInt()} ${u.distanceSymbol}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Box(Modifier.width(with(density) { barPx.toDp() }).height(3.dp).background(Color.White))
        }

        // The card
        val cardShape = RoundedCornerShape(10.dp)
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .width(260.dp)
                .background(p.background.copy(alpha = 0.96f), cardShape)
                .border(1.dp, p.line, cardShape)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(s("Double-tap to set the start · drag to move the map or the windsock · pinch to zoom"), fontSize = 12.sp, color = p.muted)
            val start = state.startPlacement
            val fromTarget = if (start == null || hypot(start.position.x, start.position.z) < 5) "0 ${u.distanceSymbol}" else {
                val bearing = Compass.heading(start.position.x, start.position.z) / DEG
                "${u.distanceText(hypot(start.position.x, start.position.z))} ${u.distanceSymbol} · ${s.compassName(bearing)}"
            }
            SettingRow(s("From target"), fromTarget)
            val windText = if (c.windSpeed < 0.05) s("Calm") else
                "${u.speedText(c.windSpeed)} ${u.speedSymbol} · ${String.format(Locale.US, "%03d", c.windDirectionDegrees.roundToInt())}° ${s.compassName(c.windDirectionDegrees)}"
            SettingRow(s("Wind"), windText)
            NumberField(s("Start altitude"), u.distanceSymbol, u.distanceValue(c.startAltitudeMeters), { v ->
                engine.updateConfiguration { it.copy(startAltitudeMeters = u.meters(v)) }
            }, 0)
            Text(s("Pattern altitudes"), fontSize = 12.sp, color = p.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(s("downwind"), u.distanceSymbol, u.distanceValue(c.downwindAltitudeMeters), { v ->
                    engine.updateConfiguration { it.copy(downwindAltitudeMeters = u.meters(v)) }
                }, 0, Modifier.weight(1f))
                NumberField(s("base"), u.distanceSymbol, u.distanceValue(c.baseAltitudeMeters), { v ->
                    engine.updateConfiguration { it.copy(baseAltitudeMeters = u.meters(v)) }
                }, 0, Modifier.weight(1f))
                NumberField(s("final"), u.distanceSymbol, u.distanceValue(c.finalAltitudeMeters), { v ->
                    engine.updateConfiguration { it.copy(finalAltitudeMeters = u.meters(v)) }
                }, 0, Modifier.weight(1f))
            }
            ChipButton(s("Done"), style = ChipStyle.PRIMARY, icon = Icons.Filled.Check, fontSize = 12, fillsWidth = true) {
                focus.clearFocus()
                if (startChanged(openedWith, state.configuration)) engine.resetRun(keepPaused = true)
                onDone()
            }
        }
    }
}

private fun startChanged(a: SimulatorConfiguration, b: SimulatorConfiguration) =
    a.startEastMeters != b.startEastMeters || a.startSouthMeters != b.startSouthMeters ||
        a.startAltitudeMeters != b.startAltitudeMeters || a.patternAltitudes != b.patternAltitudes

@Composable
private fun SettingRow(title: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 12.sp, color = p.muted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.foreground, maxLines = 1)
    }
}
