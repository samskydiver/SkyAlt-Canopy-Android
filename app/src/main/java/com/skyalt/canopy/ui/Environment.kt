package com.skyalt.canopy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import com.skyalt.canopy.model.AircraftLiverySettings
import com.skyalt.canopy.model.AircraftTouchdownSettings
import com.skyalt.canopy.model.BaseLegWind
import com.skyalt.canopy.model.Scenario
import com.skyalt.canopy.model.SimulatorMode
import com.skyalt.canopy.model.TrafficSettings
import com.skyalt.canopy.scene.ExitSceneView
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import kotlin.math.roundToInt

// The scene page and the settings for clouds, air traffic and the scenes.
// Port of CanopyScenarioPicker.swift and CanopyEnvironmentPanels.swift.

/**
 * Settings: how many other canopies fly around the jumper, whether the
 * turbulent air behind everything is drawn and, in free practice, whether
 * an aircraft is landing (which needs the runway drawn on the dropzone
 * picture first, so the switch opens the editor for it).
 */
@Composable
fun TrafficPanel(state: UiState, engine: SimulatorEngine, onSetRunway: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val traffic = state.traffic
    Block(s("Air traffic")) {
        FieldLabel(s("Other canopies"), "${traffic.count}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("0", fontSize = 11.sp, color = p.muted)
            Slider(
                value = traffic.count.toFloat(),
                onValueChange = { v -> engine.updateTraffic { it.copy(count = v.roundToInt()) } },
                valueRange = 0f..TrafficSettings.MAXIMUM_COUNT.toFloat(),
                steps = TrafficSettings.MAXIMUM_COUNT - 1,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent),
            )
            Text("${TrafficSettings.MAXIMUM_COUNT}", fontSize = 11.sp, color = p.muted)
        }
        SwitchRow(s("Show wake turbulence"), traffic.showsWakes) { on -> engine.updateTraffic { it.copy(showsWakes = on) } }
        Note(s("In a wake the cells lose pressure and the canopy slows; braking there makes it worse."))
        if (state.scenario == null) {
            SwitchRow(s("An aircraft is landing"), state.aircraftLanding) { on ->
                if (on && state.dropzone.runwayGeometry == null) onSetRunway() else engine.setAircraftLanding(on)
            }
            Note(s("The aircraft lands on the runway set on the dropzone picture."))
        }
        Note(s("Other canopies join at the next reset or Apply."))
    }
}

/** Settings: the cloud layer's base, thickness and density. */
@Composable
fun CloudPanel(state: UiState, engine: SimulatorEngine) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val u = state.units
    val clouds = state.clouds
    Block(s("Clouds")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(s("Cloud base"), u.distanceSymbol, u.distanceValue(clouds.baseAltitudeMeters), { v ->
                engine.updateClouds { it.copy(baseAltitudeMeters = u.meters(v)) }
            }, 0, Modifier.weight(1f))
            NumberField(s("Thickness"), u.distanceSymbol, u.distanceValue(clouds.thicknessMeters), { v ->
                engine.updateClouds { it.copy(thicknessMeters = u.meters(v)) }
            }, 0, Modifier.weight(1f))
        }
        FieldLabel(s("Density"), if (clouds.isVisible) "${(clouds.density * 100).roundToInt()}%" else s("Clear sky"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("0%", fontSize = 11.sp, color = p.muted)
            Slider(
                value = clouds.density.toFloat(),
                onValueChange = { v -> engine.updateClouds { it.copy(density = Math.round(v * 20) / 20.0) } },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent),
            )
            Text("100%", fontSize = 11.sp, color = p.muted)
        }
    }
}

/** Settings: the way to the path styles page. */
@Composable
fun PathStyleEntry(onOpen: () -> Unit) {
    val s = LocalStrings.current
    Block(s("Path styles")) {
        ChipButton(s("Edit path styles"), icon = Icons.Filled.Edit, fillsWidth = true, onClick = onOpen)
    }
}

/**
 * Settings in a scene: its brief, the wind speed, the wind's direction or
 * the base leg's wind and the aircraft switch where the scene allows them,
 * and Apply to start over.
 */
@Composable
fun ScenarioPanel(state: UiState, engine: SimulatorEngine, scenario: Scenario, onApply: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val u = state.units
    val c = state.configuration
    val focus = LocalFocusManager.current
    Block(s("Scene")) {
        Text("${scenario.number} · ${s(scenario.titleKey)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.foreground)
        Note(s(scenario.summaryKey))
        Note(s("A 220 ft² student canopy at a wing loading of 0.8, flying a left-hand pattern."))
        if (scenario.allowsWindSpeed) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(s("Wind speed"), u.speedSymbol, u.speedValue(c.windSpeed), { v ->
                    engine.updateConfiguration { it.copy(windSpeed = u.metersPerSecond(v)) }
                }, 1, Modifier.weight(1f))
                if (scenario.allowsWindDirection) {
                    NumberField(s("Wind from"), "° · ${s.compassName(c.windDirectionDegrees)}", c.windDirectionDegrees, { v ->
                        engine.updateConfiguration { it.copy(windDirectionDegrees = v) }
                    }, 0, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        if (scenario.choosesAircraft) {
            SwitchRow(s("An aircraft is landing"), state.aircraftLanding) { engine.setAircraftLanding(it) }
        }
        if (scenario.choosesBaseLegWind) {
            FieldLabel(s("Base leg"))
            SegmentedButtons(
                BaseLegWind.entries,
                { if (it == BaseLegWind.WITH_WIND) s("With the wind") else s("Into the wind") },
                BaseLegWind.of(c.windDirectionDegrees),
                { choice -> engine.updateConfiguration { it.copy(windDirectionDegrees = choice.windDirectionDegrees) } },
            )
        }
        Spacer(Modifier.size(2.dp))
        ChipButton(s("Apply"), style = ChipStyle.PRIMARY, fillsWidth = true) {
            focus.clearFocus()
            engine.applyAndReset()
            onApply()
        }
    }
}

/**
 * The page the simulator opens on: free practice with the saved setup and
 * the practice scenes at the shared airfield in one list, beside a picture
 * of the moment of exit to look round. The Scenes button brings it back,
 * and so does Back on the way out.
 */
@Composable
fun ScenarioPicker(state: UiState, engine: SimulatorEngine, onChoose: (SimulatorMode) -> Unit, onBack: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    // The hidden aircraft touchdown setting, behind seven quick taps on the picture.
    var showsTouchdownSetting by remember { mutableStateOf(false) }
    val livery = state.aircraftLivery.colorHex
    Box(Modifier.fillMaxSize().background(p.background).noRippleClick {}) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(30.dp).background(p.surface2, CircleShape).border(1.dp, p.line, CircleShape).noRippleClick(onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s("Back"), tint = p.foreground, modifier = Modifier.size(20.dp))
                }
                Text(s("Choose a scene").uppercase(), style = displayStyle(16.sp), color = p.muted, letterSpacing = 1.3.sp)
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScenarioRow(state.mode == SimulatorMode.Free, null, s("Free practice"),
                        s("Your own canopy, wind and start point, as set up in Settings.")) { onChoose(SimulatorMode.Free) }
                    for (scenario in Scenario.entries) {
                        ScenarioRow(state.scenario == scenario, "${scenario.number}", s(scenario.titleKey), s(scenario.summaryKey)) {
                            onChoose(SimulatorMode.Scene(scenario))
                        }
                    }
                }
                // The moment of exit, its top level with the first scene: a drag looks round it, a pinch zooms.
                // In its bottom corner, the aircraft's paint.
                val shape = RoundedCornerShape(12.dp)
                Box(Modifier.weight(1f).fillMaxHeight().clip(shape).border(1.dp, p.line, shape)) {
                    AndroidView(
                        factory = { context ->
                            ExitSceneView(context).also { view ->
                                view.onSecretTaps = { showsTouchdownSetting = true }
                            }
                        },
                        update = { view -> view.liveryHex = livery },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                        AircraftPaintControl(livery) { engine.setAircraftLiveryColor(it) }
                    }
                }
            }
        }
    }
    if (showsTouchdownSetting) {
        AircraftTouchdownDialog(
            initial = state.aircraftTouchdown.metersFromThreshold,
            onCancel = { showsTouchdownSetting = false },
            onDone = { meters ->
                engine.setAircraftTouchdown(meters)
                showsTouchdownSetting = false
            },
        )
    }
}

/** Colours offered for the aircraft's wings and tail; the first is the default red. */
private val LIVERY_COLORS = listOf(
    AircraftLiverySettings.DEFAULT_COLOR_HEX, 0xf07f1a, 0xf2c230, 0x2e8b57, 0x1a9e9e, 0x1f5fbf,
    0x1b2a4a, 0x6a3fa0, 0xd6337a, 0x202020, 0xa8adb3, 0xf4f4f4,
)

/**
 * The aircraft's paint: a swatch that opens the colours for the wings and
 * tail, and, once the colour has been changed, the way back to the
 * default red.
 */
@Composable
private fun AircraftPaintControl(livery: Int, onPick: (Int) -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (open) {
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier.background(p.surface, shape).border(1.dp, p.line, shape).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in LIVERY_COLORS.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (hex in row) {
                            Swatch(hex, selected = hex == livery, size = 26) {
                                onPick(hex)
                                open = false
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .background(p.surface.copy(alpha = 0.92f), CircleShape)
                .border(1.dp, p.line, CircleShape)
                .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Brush, contentDescription = null, tint = p.muted, modifier = Modifier.size(14.dp))
            Text(s("Aircraft paint"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = p.foreground)
            Swatch(livery, selected = open, size = 26) { open = !open }
            if (livery != AircraftLiverySettings.DEFAULT_COLOR_HEX) {
                Box(Modifier.size(24.dp).noRippleClick { onPick(AircraftLiverySettings.DEFAULT_COLOR_HEX); open = false }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Refresh, contentDescription = s("Default paint"), tint = p.foreground, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** A round patch of [hex], ringed while [selected]. */
@Composable
private fun Swatch(hex: Int, selected: Boolean, size: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(size.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.line, CircleShape)
            .padding(3.dp)
            .background(Color(0xff000000.toInt() or hex), CircleShape)
            .clip(CircleShape)
            .noRippleClick(onClick),
    )
}

/**
 * The hidden setting behind seven quick taps on the exit picture: how far
 * past the threshold every aircraft touches down, kept on Done.
 */
@Composable
private fun AircraftTouchdownDialog(initial: Double, onCancel: () -> Unit, onDone: (Double) -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    var draft by remember { mutableStateOf(initial.toFloat()) }
    Dialog(onDismissRequest = onCancel) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.width(320.dp).background(p.surface, shape).border(1.dp, p.line, shape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(s("Aircraft touchdown"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.foreground)
            Note(s("Metres past the threshold the aircraft crosses, in every scene and in free practice."))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val maximum = AircraftTouchdownSettings.MAXIMUM_METERS.toFloat()
                Slider(
                    value = draft,
                    onValueChange = { draft = (it / 10).roundToInt() * 10f },
                    valueRange = 0f..maximum,
                    steps = (maximum / 10).toInt() - 1,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent),
                )
                Text("${draft.roundToInt()} m", style = displayStyle(13.sp), color = p.foreground, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                ChipButton(s("Cancel"), onClick = onCancel)
                ChipButton(s("Done"), style = ChipStyle.PRIMARY) { onDone(draft.toDouble()) }
            }
        }
    }
}

/** One row of the scene page: its badge, name and brief, outlined while it is the mode being flown. */
@Composable
private fun ScenarioRow(current: Boolean, badge: String?, title: String, summary: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface, shape)
            .border(if (current) 2.dp else 1.dp, if (current) p.accent else p.line, shape)
            .noRippleClick(onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(if (current) p.accent else p.surface2, CircleShape)
                .border(1.dp, if (current) p.accent else p.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (current) p.background else p.foreground
            if (badge != null) Text(badge, style = displayStyle(16.sp), color = tint)
            else Icon(SimIcons.Figure, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.foreground)
            Text(summary, fontSize = 12.sp, color = p.muted)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = p.muted,
            modifier = Modifier.padding(top = 6.dp).size(18.dp),
        )
    }
}
