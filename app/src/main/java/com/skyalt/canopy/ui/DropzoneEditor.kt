package com.skyalt.canopy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The dropzone picture under the fixed target rings: drag to move it, pinch
 * to size it, twist to turn it, and measure a known distance to set the
 * scale exactly.
 */
@Composable
fun DropzoneEditor(state: UiState, engine: SimulatorEngine, onDone: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val image = state.dropzoneImage ?: return
    val density = LocalDensity.current
    var ppm by remember { mutableStateOf(0f) }
    var measuring by remember { mutableStateOf(false) }
    val points = remember { mutableStateListOf<Offset>() }
    var realDistance by remember { mutableStateOf(0.0) }

    BoxWithConstraints(Modifier.fillMaxSize().background(hex(0x1b2430))) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        if (ppm == 0f) ppm = (w * 0.7f / max(state.dropzone.widthMeters.toFloat(), 100f))
        val centre = Offset(w / 2 - with(density) { 130.dp.toPx() }, h / 2)
        val dz = state.dropzone
        val widthPx = dz.widthMeters.toFloat() * ppm
        val heightPx = widthPx * image.height / max(1, image.width)
        val at = Offset(centre.x + dz.eastOffsetMeters.toFloat() * ppm, centre.y + dz.southOffsetMeters.toFloat() * ppm)
        val bitmap = remember(image) { image.asImageBitmap() }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(measuring) {
                    if (measuring) {
                        detectTapGestures { point ->
                            if (points.size >= 2) points.clear()
                            points.add(point)
                        }
                    } else {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            engine.updateDropzone { d ->
                                d.copy(
                                    widthMeters = d.widthMeters * zoom,
                                    eastOffsetMeters = d.eastOffsetMeters + pan.x / ppm,
                                    southOffsetMeters = d.southOffsetMeters + pan.y / ppm,
                                    // Compose turns clockwise; the setting is counter-clockwise.
                                    rotationDegrees = d.rotationDegrees - rotation,
                                )
                            }
                        }
                    }
                },
        ) {
            Image(
                bitmap, null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset { IntOffset((at.x - widthPx / 2).roundToInt(), (at.y - heightPx / 2).roundToInt()) }
                    .requiredSize(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                    .rotate(-dz.rotationDegrees.toFloat()),
            )
            Canvas(Modifier.fillMaxSize()) {
                for ((outer, hexColor) in listOf(20f to 0xf4f4f0, 15f to 0xe4582b, 10f to 0xf4f4f0, 5f to 0xe4582b)) {
                    drawCircle(hex(hexColor, 0.55f), outer * ppm, centre, style = Stroke(max(1.5f, 5f * ppm)))
                }
                drawLine(Color.White, centre - Offset(16.dp.toPx(), 0f), centre + Offset(16.dp.toPx(), 0f), 1.5.dp.toPx())
                drawLine(Color.White, centre - Offset(0f, 16.dp.toPx()), centre + Offset(0f, 16.dp.toPx()), 1.5.dp.toPx())
                if (measuring) {
                    for (pt in points) {
                        drawCircle(hex(0xffd23f), 7.dp.toPx(), pt)
                        drawCircle(Color.Black, 7.dp.toPx(), pt, style = Stroke(1.5.dp.toPx()))
                    }
                    if (points.size == 2) drawLine(hex(0xffd23f), points[0], points[1], 2.dp.toPx())
                }
            }
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
            Note(s("Drag to move · pinch to size · two fingers to turn"))
            FieldLabel(s("Picture width"), "${state.units.distanceText(dz.widthMeters)} ${state.units.distanceSymbol}")
            FieldLabel(s("Rotation"), "${dz.rotationDegrees.roundToInt()}°")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton(s("Zoom out"), fontSize = 11) { ppm = (ppm / 1.5f).coerceIn(0.02f, 50f) }
                ChipButton(s("Zoom in"), fontSize = 11) { ppm = (ppm * 1.5f).coerceIn(0.02f, 50f) }
            }
            ChipButton(s("Measure"), isOn = measuring, fillsWidth = true) {
                measuring = !measuring
                points.clear()
            }
            if (measuring) {
                if (points.size < 2) {
                    Note(s("Tap two points a known distance apart"))
                } else {
                    val pixels = (points[1] - points[0]).getDistance()
                    val measured = pixels / ppm
                    FieldLabel(s("Measured"), "${state.units.distanceText(measured.toDouble())} ${state.units.distanceSymbol}")
                    NumberField(s("Actual distance"), state.units.distanceSymbol, realDistance, { realDistance = it }, 0)
                    ChipButton(s("Apply scale"), style = ChipStyle.PRIMARY, fillsWidth = true, isDisabled = realDistance <= 0) {
                        val real = state.units.meters(realDistance)
                        val ratio = real / max(measured.toDouble(), 0.01)
                        engine.updateDropzone { d ->
                            d.copy(widthMeters = d.widthMeters * ratio, eastOffsetMeters = d.eastOffsetMeters * ratio, southOffsetMeters = d.southOffsetMeters * ratio)
                        }
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

