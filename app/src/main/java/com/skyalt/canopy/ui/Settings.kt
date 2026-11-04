package com.skyalt.canopy.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.model.CanopyType
import com.skyalt.canopy.model.Compass
import com.skyalt.canopy.model.DEG
import com.skyalt.canopy.model.LineStyle
import com.skyalt.canopy.model.METERS_PER_FOOT
import com.skyalt.canopy.model.ReferencePattern
import com.skyalt.canopy.model.Units
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import java.util.Locale
import kotlin.math.hypot

/** The settings drawer: flight setup, language, units, camera, path styles and the dropzone picture. */
@Composable
fun SettingsContent(
    state: UiState,
    engine: SimulatorEngine,
    language: LanguageSetting,
    onOpenStartPoint: () -> Unit,
    onChoosePicture: () -> Unit,
    onEditPicture: () -> Unit,
    onApply: () -> Unit,
    onOpenPathStyles: () -> Unit,
    onSetRunway: () -> Unit,
    pictureLocked: Boolean = false,
) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    // A scene shows only what it lets the jumper change.
    val scenario = state.scenario
    if (scenario != null) {
        ScenarioPanel(state, engine, scenario, onApply)
        if (scenario.allowsTraffic) TrafficPanel(state, engine, onSetRunway = {})
        if (scenario.showsClouds) CloudPanel(state, engine)
        if (scenario.showsPaths) PathStyleEntry(onOpenPathStyles)
        return
    }
    SetupPanel(state, engine, onOpenStartPoint, onApply)
    Block(s("Language")) {
        // Each language is named in itself, so the way back is readable whichever is on.
        SegmentedButtons(
            AppLanguage.entries,
            { when (it) { AppLanguage.SYSTEM -> s("System"); AppLanguage.CHINESE -> "简体中文"; AppLanguage.ENGLISH -> "English" } },
            language.value,
            { language.select(it) },
        )
    }
    Block(s("Units")) {
        SegmentedButtons(Units.entries, { if (it == Units.METRIC) "m/s · m" else "mph · ft" }, state.units, { engine.setUnits(it) })
    }
    Block(s("Camera")) {
        SwitchRow(s("Auto first-person view"), state.autoFirstPerson) { engine.setAutoFirstPerson(it) }
        if (state.autoFirstPerson) {
            NumberField(
                s("Switch below"), state.units.distanceSymbol,
                state.units.distanceValue(state.firstPersonAltitudeMeters),
                { engine.setFirstPersonAltitude(state.units.meters(it)) },
                fractionDigits = 0,
            )
        }
    }
    TrafficPanel(state, engine, onSetRunway)
    CloudPanel(state, engine)
    PathStyleEntry(onOpenPathStyles)
    Block(s("Dropzone aerial view")) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.size(72.dp).clip(shape).background(p.surface2).border(1.dp, p.line, shape),
                contentAlignment = Alignment.Center,
            ) {
                val image = state.dropzoneImage
                if (image != null) {
                    val bitmap = remember(image) { image.asImageBitmap() }
                    Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
                } else {
                    Text(s("No image").uppercase(), style = displayStyle(10.sp), color = p.muted, modifier = Modifier.padding(4.dp))
                }
            }
            if (pictureLocked) {
                Note(s("The dropzone picture is set up on the phone's own screen. Stop casting to change it."))
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton(s("Choose picture"), onClick = onChoosePicture)
                if (state.dropzoneImage != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChipButton(s("Edit picture"), icon = Icons.Filled.Edit, onClick = onEditPicture)
                        ChipButton(s("Remove")) { engine.removeDropzoneImage() }
                    }
                }
            }
        }
        Note(s("Satellite or drone shot of the landing area. The editor opens after you pick one: drag it under the target rings, pinch to size it, and measure a known distance to set the scale exactly."))
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.foreground, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
    }
}

@Composable
private fun SetupPanel(state: UiState, engine: SimulatorEngine, onOpenStartPoint: () -> Unit, onApply: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val c = state.configuration
    val u = state.units
    val focus = LocalFocusManager.current
    Block(s("Flight setup")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(s("Exit weight"), u.weightSymbol, u.weightValue(c.exitWeightKilograms), { v ->
                engine.updateConfiguration { it.copy(exitWeightKilograms = u.kilograms(v)) }
            }, 0, Modifier.weight(1f))
            NumberField(s("Canopy size"), "ft²", c.canopyAreaSquareFeet, { v ->
                engine.updateConfiguration { it.copy(canopyAreaSquareFeet = v) }
            }, 0, Modifier.weight(1f))
        }
        FieldLabel(s("Canopy type"))
        SegmentedButtons(CanopyType.entries, { s(it.titleKey) }, c.canopyType, { t -> engine.updateConfiguration { it.copy(canopyType = t) } }, columns = 2)
        if (!c.studentMode) {
            FieldLabel(s("Stall tolerance"), signedPercent(c.stallTolerance))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("−10%", fontSize = 11.sp, color = p.muted)
                Slider(
                    value = c.stallTolerance.toFloat(),
                    onValueChange = { v -> engine.updateConfiguration { it.copy(stallTolerance = Math.round(v * 100) / 100.0) } },
                    valueRange = -0.1f..0.1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent),
                )
                Text("+10%", fontSize = 11.sp, color = p.muted)
            }
        }
        val perf = c.performance
        Text(
            s.format(
                "Wing loading %@ · %@ · %@ : 1 · 360° in %@ s",
                String.format(Locale.US, "%.2f", perf.wingLoading),
                "${u.speedText(perf.airspeed)} ${u.speedSymbol}",
                String.format(Locale.US, "%.1f", perf.glideRatio),
                String.format(Locale.US, "%.1f", state.fullToggleTurnSeconds),
            ),
            fontSize = 12.sp,
            color = p.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(s("Wind speed"), u.speedSymbol, u.speedValue(c.windSpeed), { v ->
                engine.updateConfiguration { it.copy(windSpeed = u.metersPerSecond(v)) }
            }, 1, Modifier.weight(1f))
            NumberField(s("Wind from"), "° · ${s.compassName(c.windDirectionDegrees)}", c.windDirectionDegrees, { v ->
                engine.updateConfiguration { it.copy(windDirectionDegrees = v) }
            }, 0, Modifier.weight(1f))
        }
        FieldLabel(s("Reference landing pattern"))
        SegmentedButtons(
            ReferencePattern.entries,
            { when (it) { ReferencePattern.OFF -> s("Off"); ReferencePattern.LEFT -> s("Left-hand"); ReferencePattern.RIGHT -> s("Right-hand") } },
            c.referencePattern,
            { r -> engine.updateConfiguration { it.copy(referencePattern = r) } },
        )
        ChipButton(s("Start point"), icon = Icons.Filled.Place, fillsWidth = true) {
            focus.clearFocus()
            onOpenStartPoint()
        }
        Text(startSummary(state, s), fontSize = 12.sp, color = p.muted)
        Spacer(Modifier.size(2.dp))
        ChipButton(s("Apply"), style = ChipStyle.PRIMARY, fillsWidth = true) {
            focus.clearFocus()
            engine.applyAndReset()
            onApply()
        }
    }
}

private fun startSummary(state: UiState, s: Strings): String {
    val u = state.units
    val c = state.configuration
    val altitude = "${u.distanceText(c.startAltitudeMeters)} ${u.distanceSymbol}"
    val east = c.startEastMeters
    val south = c.startSouthMeters
    val place = if (east != null && south != null) {
        val distance = hypot(east, south)
        if (distance < 5) s("at the target") else {
            val bearing = Compass.heading(east, south) / DEG
            s.format("%@ %@ of the target", "${u.distanceText(distance)} ${u.distanceSymbol}", s.compassName(bearing))
        }
    } else {
        s.format("%@ before the pattern entry", "${u.distanceText(c.runInMeters)} ${u.distanceSymbol}")
    }
    return "$altitude · $place"
}

/** Colour and thickness of the pattern, its ground projection, the trail and past runs. */
@Composable
fun PathStylePanel(state: UiState, engine: SimulatorEngine) {
    val s = LocalStrings.current
    val style = state.pathStyle
    Block(s("Path styles")) {
        StyleRow(s("Landing pattern"), style.pattern, state.units, false) { l -> engine.updatePathStyle { it.copy(pattern = l) } }
        StyleRow(s("Ground projection"), style.patternGround, state.units, false) { l -> engine.updatePathStyle { it.copy(patternGround = l) } }
        StyleRow(s("Flight trail"), style.trail, state.units, style.trailColoredBySink) { l -> engine.updatePathStyle { it.copy(trail = l) } }
        SwitchRow(s("Colour by sink rate"), style.trailColoredBySink) { on -> engine.updatePathStyle { it.copy(trailColoredBySink = on) } }
        StyleRow(s("Past runs"), style.history, state.units, false) { l -> engine.updatePathStyle { it.copy(history = l) } }
        ChipButton(s("Reset styles")) { engine.resetPathStyle() }
    }
}

private val SWATCHES = listOf(0x7b3fe4, 0xe4582b, 0x2f6fd6, 0x1f7a4d, 0xffd23f, 0xffffff, 0x3a4454, 0x0e1a28)

@Composable
private fun StyleRow(title: String, line: LineStyle, units: Units, colourDisabled: Boolean, onChange: (LineStyle) -> Unit) {
    val p = LocalPalette.current
    val width = if (units == Units.METRIC) String.format(Locale.US, "%.2f m", line.widthMeters)
    else String.format(Locale.US, "%.1f ft", line.widthMeters / METERS_PER_FOOT)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(title, width)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            for (swatch in SWATCHES) {
                val selected = swatch == line.colorHex
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(hex(swatch, if (colourDisabled) 0.35f else 1f))
                        .border(if (selected) 2.5.dp else 1.dp, if (selected) p.accent else p.line, CircleShape)
                        .clickable(enabled = !colourDisabled) { onChange(line.copy(colorHex = swatch)) },
                )
            }
        }
        Slider(
            value = line.widthMeters.toFloat(),
            onValueChange = { onChange(line.copy(widthMeters = Math.round(it * 20) / 20.0)) },
            valueRange = 0.1f..4f,
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("α", fontSize = 12.sp, color = p.muted)
            Spacer(Modifier.width(6.dp))
            Slider(
                value = line.opacity.toFloat(),
                onValueChange = { onChange(line.copy(opacity = it.toDouble())) },
                valueRange = 0.05f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent),
            )
        }
    }
}

