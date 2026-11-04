package com.skyalt.canopy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.model.METERS_PER_FOOT
import com.skyalt.canopy.model.Touchdown
import com.skyalt.canopy.model.Units
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The column beside the 3D view during a replay: altitude against distance, and the run history. */
@Composable
fun ProfileColumn(state: UiState, engine: SimulatorEngine, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    var touchX by remember { mutableStateOf<Float?>(null) }
    Column(
        modifier
            .background(p.surface)
            .padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(s("Flight profile").uppercase(), style = displayStyle(13.sp), color = p.muted, letterSpacing = 1.3.sp)
        Text(s("altitude vs distance flown"), fontSize = 11.sp, color = p.muted)
        val measurer = rememberTextMeasurer()
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { touchX = it.x },
                        onDragEnd = { touchX = null },
                        onDragCancel = { touchX = null },
                    ) { change, _ -> touchX = change.position.x }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease(); touchX = null })
                },
        ) {
            drawProfile(state, p, s, measurer, touchX)
        }
        LegendLine(s("This run (darker = more brake)"), p.accent, false)
        LegendLine(s("Pattern altitudes"), p.muted, true)
        // Stacked, so a narrow column never squeezes the heading into a sliver.
        Text(s("Run history"), fontSize = 12.sp, color = p.muted)
        ChipButton(s("Clear history"), isDisabled = state.runs.isEmpty(), fontSize = 11) { engine.clearHistory() }
        Column(Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (run in state.runs) RunRow(s.format("Run %d", run.number), run.touchdown, false, state.units)
            RunRow(s("This run"), state.touchdown, true, state.units)
        }
    }
}

@Composable
private fun LegendLine(title: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Canvas(Modifier.width(14.dp).heightIn(min = 2.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null)
        }
        Spacer(Modifier.width(5.dp))
        Text(title, fontSize = 11.sp, color = LocalPalette.current.muted, maxLines = 1)
    }
}

@Composable
private fun RunRow(name: String, touchdown: Touchdown?, current: Boolean, u: Units) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val description = if (touchdown != null) s.format(
        "touched down %@ %@ down, %@ %@ over the ground, %@ %@ from target",
        u.speedText(touchdown.verticalSpeed), u.speedSymbol,
        u.speedText(touchdown.groundSpeed), u.speedSymbol,
        u.distanceText(touchdown.missDistance), u.distanceSymbol,
    ) else if (current) s("in flight") else s("reset before landing")
    Column {
        Text(name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (current) p.accent else p.foreground)
        Text(description, fontSize = 11.sp, color = p.muted)
    }
}

private fun mix(a: Int, b: Int, t: Double): Int {
    val u = t.coerceIn(0.0, 1.0)
    fun ch(shift: Int) = ((((a shr shift) and 0xff) * (1 - u) + ((b shr shift) and 0xff) * u).roundToInt() and 0xff)
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private fun DrawScope.drawProfile(state: UiState, p: Palette, s: Strings, measurer: TextMeasurer, touchX: Float?) {
    val units = state.units
    val profile = state.display.profile
    val padL = 34.dp.toPx(); val padR = 8.dp.toPx(); val padT = 10.dp.toPx(); val padB = 18.dp.toPx()
    val x0 = padL; val x1 = size.width - padR; val y0 = padT; val y1 = size.height - padB
    if (x1 <= x0 || y1 <= y0) return
    var maxD = 700.0
    var maxA = max(state.configuration.startAltitudeMeters, 100.0) * 1.05
    for (run in state.runs) for (sample in run.profile) {
        maxD = max(maxD, sample.distance + 20); maxA = max(maxA, sample.altitude * 1.05)
    }
    for (sample in profile) maxD = max(maxD, sample.distance + 20)
    fun xOf(d: Double) = (x0 + (x1 - x0) * (d / maxD)).toFloat()
    fun yOf(a: Double) = (y1 - (y1 - y0) * (a / maxA)).toFloat()
    val labelStyle = TextStyle(fontSize = 10.sp, color = p.muted, fontFeatureSettings = "tnum")
    fun label(text: String, x: Float, y: Float, anchorX: Float, anchorY: Float) {
        val layout = measurer.measure(text, labelStyle)
        drawText(layout, topLeft = Offset(x - layout.size.width * anchorX, y - layout.size.height * anchorY))
    }

    val foot = METERS_PER_FOOT
    val aStep = if (units == Units.METRIC) (if (maxA > 600) 200.0 else 100.0) else (if (maxA > 600) 500 * foot else 250 * foot)
    var a = 0.0
    while (a <= maxA) {
        val y = yOf(a)
        drawLine(p.line, Offset(x0, y), Offset(x1, y), 1f)
        label(units.distanceText(a), x0 - 4, y, 1f, 0.5f)
        a += aStep
    }
    val baseStep = if (units == Units.METRIC) (if (maxD > 2000) 500.0 else 200.0) else (if (maxD > 2000) 2000 * foot else 1000 * foot)
    val dStep = if (x1 - x0 < 220.dp.toPx()) baseStep * 2 else baseStep
    var d = 0.0
    while (d <= maxD) {
        val x = xOf(d)
        drawLine(p.line, Offset(x, y1), Offset(x, y1 + 3), 1f)
        label(units.distanceText(d), x, y1 + 5, 0.5f, 0f)
        d += dStep
    }
    val pa = state.configuration.patternAltitudes
    for (alt in listOf(pa.downwind, pa.base, pa.final)) {
        if (alt > maxA) continue
        val y = yOf(alt)
        drawLine(p.muted, Offset(x0, y), Offset(x1, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        label("${units.distanceText(alt)} ${units.distanceSymbol}", x1 - 2, y - 7, 1f, 0.5f)
    }
    for (run in state.runs) {
        if (run.profile.isEmpty()) continue
        val path = Path()
        run.profile.forEachIndexed { i, sample ->
            val x = xOf(sample.distance); val y = yOf(max(0.0, sample.altitude))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, p.muted.copy(alpha = 0.45f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        val end = run.profile.last()
        label(s.format("Run %d", run.number), xOf(end.distance) + 4, yOf(max(0.0, end.altitude)) - 7, 0f, 0.5f)
    }
    val accent = p.accentHex
    val light = mix(accent, 0xffffff, 0.45)
    val dark = mix(accent, 0x000000, 0.45)
    for (i in 1 until profile.size) {
        val s0 = profile[i - 1]; val s1 = profile[i]
        val t = s1.brake.coerceIn(0.0, 1.0)
        val c = if (t < 0.5) mix(light, accent, t * 2) else mix(accent, dark, (t - 0.5) * 2)
        drawLine(hex(c), Offset(xOf(s0.distance), yOf(max(0.0, s0.altitude))), Offset(xOf(s1.distance), yOf(max(0.0, s1.altitude))), 2.5.dp.toPx(), cap = StrokeCap.Round)
    }
    drawCircle(p.accent, 4.dp.toPx(), Offset(xOf(state.display.currentDistance), yOf(max(0.0, state.display.currentAltitude))))

    if (touchX != null && profile.size > 1 && touchX in x0..x1) {
        val target = (touchX - x0) / (x1 - x0) * maxD
        var best = profile[0]
        for (sample in profile) if (kotlin.math.abs(sample.distance - target) < kotlin.math.abs(best.distance - target)) best = sample
        val x = xOf(best.distance); val y = yOf(max(0.0, best.altitude))
        drawLine(p.muted, Offset(x, y0), Offset(x, y1), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        val text = s.format(
            "%@ %@ flown · %@ %@ up · brakes %d%%",
            units.distanceText(best.distance), units.distanceSymbol,
            units.distanceText(max(0.0, best.altitude)), units.distanceSymbol,
            (best.brake * 100).roundToInt(),
        )
        val maxWidth = max(60f, size.width - 14.dp.toPx()).toInt()
        val layout = measurer.measure(text, TextStyle(fontSize = 11.sp, color = p.background), constraints = Constraints(maxWidth = maxWidth))
        val w = layout.size.width + 14.dp.toPx(); val h = layout.size.height + 6.dp.toPx()
        val bx = min(max(x - w / 2, 0f), max(0f, size.width - w))
        val by = max(0f, y - h * 1.15f)
        drawRoundRect(p.foreground, Offset(bx, by), Size(w, h), CornerRadius(4.dp.toPx()))
        drawText(layout, topLeft = Offset(bx + 7.dp.toPx(), by + 3.dp.toPx()))
    }
}

