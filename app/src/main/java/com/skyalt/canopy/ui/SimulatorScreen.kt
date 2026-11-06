package com.skyalt.canopy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.runtime.saveable.rememberSaveable
import com.skyalt.canopy.model.SimulatorMode
import com.skyalt.canopy.sim.CanopyGLView
import com.skyalt.canopy.sim.FlightStatus
import com.skyalt.canopy.sim.MotionCaptureController
import com.skyalt.canopy.sim.ProAccess
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.SimulatorTrial
import com.skyalt.canopy.sim.UiState

/** The panels that slide in from the right edge of the simulator. */
enum class SimDrawer(val titleKey: String) { SETTINGS("Settings"), PATH_STYLES("Path styles"), MOTION_CAPTURE("Motion capture") }

/** What the activity hands the simulator's interface. */
class SimulatorHost(
    val engine: SimulatorEngine,
    val glView: CanopyGLView,
    val capture: MotionCaptureController,
    val pro: ProAccess,
    val trial: SimulatorTrial,
    val trialEnforced: Boolean,
    val language: LanguageSetting,
    val onChoosePicture: () -> Unit,
    /** Opens the photo picker for the logo on the aircraft's sides. */
    val onChooseLogo: () -> Unit,
    val onToggleCamera: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenCastSettings: () -> Unit,
    /** Leaves the simulator. */
    val onLeave: () -> Unit,
)

/**
 * The simulator fills the screen in landscape: the 3D view with the
 * instruments across the top, a brake toggle on each side (or the camera
 * picture while motion capture runs) and the buttons along the bottom.
 * Panels slide in from the right; a replay opens the flight profile beside
 * the 3D view. While casting, the phone shows the controls only.
 */
@Composable
fun SimulatorScreen(
    state: UiState,
    host: SimulatorHost,
    drawer: SimDrawer?,
    onDrawer: (SimDrawer?) -> Unit,
    editingDropzone: Boolean,
    onEditDropzone: (Boolean) -> Unit,
    isCasting: Boolean,
    isDisplayConnected: Boolean,
    onCasting: (Boolean) -> Unit,
) {
    val engine = host.engine
    val capture = host.capture
    var choosingStart by remember { mutableStateOf(false) }
    var choosingCanopy by remember { mutableStateOf(false) }
    var showingPro by remember { mutableStateOf(false) }
    var barHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    // The scene page: open on arrival until a mode is chosen, and again from
    // the Scenes button. Reached with Back, its own back leaves the simulator.
    var choosingScenario by rememberSaveable { mutableStateOf(true) }
    var hasChosenMode by rememberSaveable { mutableStateOf(false) }
    var pickerLeaves by remember { mutableStateOf(false) }
    // The picture editor opened to set the runway for the aircraft switch; Done then switches the aircraft on.
    var editorForRunway by remember { mutableStateOf(false) }

    fun openScenarioPicker(leaving: Boolean) {
        focus.clearFocus()
        engine.pause()
        pickerLeaves = leaving
        onDrawer(null)
        choosingScenario = true
    }

    fun leaveScenarioPicker() {
        if (!hasChosenMode || pickerLeaves) host.onLeave() else choosingScenario = false
    }

    fun choose(mode: SimulatorMode) {
        engine.enter(mode)
        hasChosenMode = true
        pickerLeaves = false
        choosingScenario = false
        onDrawer(SimDrawer.SETTINGS)
    }

    fun openDropzoneEditor(forRunway: Boolean) {
        if (!forRunway && state.dropzoneImage == null) return
        focus.clearFocus()
        editorForRunway = forRunway
        onEditDropzone(true)
    }

    fun closeDropzoneEditor() {
        focus.clearFocus()
        if (editorForRunway && engine.hasRunway()) engine.setAircraftLanding(true)
        editorForRunway = false
        onEditDropzone(false)
    }

    // Back: out of the scene page, else to it on the way out of the simulator.
    BackHandler(enabled = choosingScenario || (drawer == null && !isCasting && !editingDropzone && !choosingStart && !choosingCanopy && !showingPro)) {
        if (choosingScenario) leaveScenarioPicker() else openScenarioPicker(leaving = true)
    }

    fun open(which: SimDrawer) {
        if (drawer == which) {
            onDrawer(null)
        } else {
            engine.pause()
            onDrawer(which)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isCasting) {
            CastControls(
                state, engine, capture, isDisplayConnected,
                onOpenSettings = { open(SimDrawer.SETTINGS) },
                onOpenMotionCapture = { open(SimDrawer.MOTION_CAPTURE) },
                onStopCasting = { onCasting(false) },
                onOpenCastSettings = host.onOpenCastSettings,
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val showsProfile = state.replay != null
                val profileWidth = if (showsProfile) minOf(maxOf(maxWidth / 4, 230.dp), maxWidth / 2) else 0.dp
                val viewWidth = maxWidth - profileWidth
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(viewWidth).fillMaxHeight()) {
                        AndroidView(factory = { host.glView }, modifier = Modifier.fillMaxSize())
                        Hud(
                            state, engine, capture, barHeight, drawer != null, compactTools = viewWidth < 820.dp,
                            onOpenSettings = { open(SimDrawer.SETTINGS) },
                            onOpenMotionCapture = { open(SimDrawer.MOTION_CAPTURE) },
                            onCast = { onCasting(true) },
                            onOpenScenarios = { openScenarioPicker(leaving = false) },
                        )
                    }
                    if (showsProfile) {
                        ProfileColumn(
                            state, engine,
                            Modifier.width(profileWidth).fillMaxHeight().padding(top = barHeight)
                                .windowInsetsPadding(WindowInsets.displayCutout),
                        )
                    }
                }
                InstrumentBar(
                    state,
                    Modifier
                        .align(Alignment.TopCenter)
                        .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                    onBack = { openScenarioPicker(leaving = true) },
                )
            }
        }

        // The drawer
        AnimatedVisibility(drawer != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).noRippleClick {
                focus.clearFocus(); onDrawer(null)
            })
        }
        AnimatedVisibility(
            drawer != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            // Keeps the last panel while it slides out.
            var shown by remember { mutableStateOf(SimDrawer.SETTINGS) }
            if (drawer != null) shown = drawer
            BoxWithConstraints {
                val drawerWidth = minOf(400.dp, maxWidth * 0.48f)
                val s = LocalStrings.current
                Drawer(
                    title = s(shown.titleKey),
                    modifier = Modifier.width(drawerWidth),
                    onBack = if (shown == SimDrawer.PATH_STYLES) ({ onDrawer(SimDrawer.SETTINGS) }) else null,
                    onClose = { focus.clearFocus(); onDrawer(null) },
                ) {
                    when (shown) {
                        SimDrawer.SETTINGS -> {
                            ProBlock(host.pro, host.trial, host.trialEnforced) { showingPro = true }
                            SettingsContent(
                                state, engine,
                                language = host.language,
                                onOpenStartPoint = { choosingStart = true },
                                onOpenCanopyPicker = { choosingCanopy = true },
                                onChoosePicture = host.onChoosePicture,
                                onEditPicture = { openDropzoneEditor(forRunway = false) },
                                onApply = { onDrawer(null) },
                                onOpenPathStyles = { onDrawer(SimDrawer.PATH_STYLES) },
                                onSetRunway = { if (!isCasting) openDropzoneEditor(forRunway = true) },
                                pictureLocked = isCasting,
                            )
                        }
                        SimDrawer.PATH_STYLES -> PathStylePanel(state, engine)
                        SimDrawer.MOTION_CAPTURE -> MocapPanel(capture, host.onToggleCamera, host.onOpenAppSettings)
                    }
                }
            }
        }

        if (editingDropzone && (state.dropzoneImage != null || editorForRunway) && !isCasting) {
            DropzoneEditor(state, engine, startsWithRunway = editorForRunway) { closeDropzoneEditor() }
        }
        if (choosingStart) {
            Box(Modifier.fillMaxSize().imePadding()) {
                StartPointScreen(state, engine) { choosingStart = false }
            }
        }
        // Over the settings drawer, like the start point map; Confirm and the back button land back in the drawer.
        if (choosingCanopy) {
            CanopyPicker(state, engine) { choosingCanopy = false }
        }
        if (choosingScenario) {
            ScenarioPicker(state, engine, onChooseLogo = host.onChooseLogo, onChoose = { choose(it) }, onBack = { leaveScenarioPicker() })
        }
        if (showingPro) {
            ProUpgradeScreen(host.pro, trialUsedUp = false, onClose = { showingPro = false })
        }
    }
}

@Composable
private fun Hud(
    state: UiState,
    engine: SimulatorEngine,
    capture: MotionCaptureController,
    barHeight: Dp,
    drawerOpen: Boolean,
    compactTools: Boolean,
    onOpenSettings: () -> Unit,
    onOpenMotionCapture: () -> Unit,
    onCast: () -> Unit,
    onOpenScenarios: () -> Unit,
) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(barHeight))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The toggle and its touch column own the right edge; the
                // altimeter sits just inside them. With motion capture the
                // arms are the toggles and the camera picture floats top left.
                val toggleColumn = if (capture.active) 0.dp else 10.dp + 56.dp + 10.dp
                if (capture.active) {
                    Box(Modifier.align(Alignment.TopStart).padding(top = 4.dp, start = 2.dp)) { MocapPreviewBox(capture) }
                } else {
                    BrakeControls(state, engine)
                }
                Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp)) { ModeChip(state) }
                Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = toggleColumn + 8.dp)) {
                    AltimeterFace(state.display.altitude, state.units)
                }
                val showsStart = state.isPaused && !drawerOpen && state.replay == null &&
                    state.display.status != FlightStatus.Landed && !state.showsLandingCard && state.accident == null
                if (showsStart) {
                    Box(Modifier.align(Alignment.Center)) { CentreStartButton { engine.togglePause() } }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReplayBar(state, engine)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TransportButtons(state, engine)
                    Spacer(Modifier.weight(1f))
                    ToolButtons(state, engine, capture.active, false, onOpenSettings, onOpenMotionCapture, onCast, stacked = compactTools, onOpenScenarios = onOpenScenarios)
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

/** The drawer's chrome: a heading with a close button over a scrolling column of blocks. */
@Composable
fun Drawer(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, onClose: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(modifier.fillMaxHeight().background(p.background).windowInsetsPadding(WindowInsets.displayCutout)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // A page reached from another drawer: a chevron back to it.
            if (onBack != null) {
                Box(
                    Modifier.size(30.dp).background(p.surface2, CircleShape).noRippleClick(onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = LocalStrings.current("Back"), tint = p.foreground, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(title.uppercase(), style = displayStyle(15.sp), color = p.muted, letterSpacing = 1.3.sp, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(30.dp).background(p.surface2, CircleShape).noRippleClick(onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = LocalStrings.current("Close"), tint = p.foreground, modifier = Modifier.size(16.dp))
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}
