package com.skyalt.canopy.windtunnel

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The wind tunnel's dark colours (the web page's dark theme), as WTStyle on the iPhone. */
object WTStyle {
    val bg = Color(0xFF0C1319)
    val surface = Color(0xFF121B23)
    val surface2 = Color(0xFF18232D)
    val ink = Color(0xFFE7EEF3)
    val ink2 = Color(0xFFA9B6C0)
    val ink3 = Color(0xFF7D8C98)
    val line = Color(0xFF24323D)
    val lineStrong = Color(0xFF34454F)
    val accent = Color(0xFF3987E5)
    val accentSoft = Color(0xFF17304F)
    val barMuted = Color(0xFF1C5CAB)
    val series2 = Color(0xFFD95926)
    val good = Color(0xFF0CA30C)
    val warn = Color(0xFFFAB219)

    fun tone(t: WTTagTone) = when (t) {
        WTTagTone.ACCENT -> accent
        WTTagTone.GOOD -> good
        WTTagTone.WARN -> warn
    }

    fun icon(i: WTTagIcon): ImageVector = when (i) {
        WTTagIcon.UP -> Icons.Filled.ArrowUpward
        WTTagIcon.DOWN -> Icons.Filled.ArrowDownward
        WTTagIcon.HOLD -> Icons.Filled.PanTool
        WTTagIcon.OK -> Icons.Filled.CheckCircle
        WTTagIcon.WARN -> Icons.Filled.Warning
        WTTagIcon.DOT -> Icons.Filled.FiberManualRecord
        WTTagIcon.SIDE -> Icons.Filled.SwapHoriz
        WTTagIcon.NET -> Icons.Filled.GridOn
        WTTagIcon.PIN -> Icons.Filled.PushPin
    }
}

/** Figures that line up, like the iPhone's monospaced digits. */
private const val DIGITS = "tnum"

private fun text(size: TextUnit, color: Color, weight: FontWeight = FontWeight.Normal, digits: Boolean = false) =
    TextStyle(fontSize = size, color = color, fontWeight = weight, fontFeatureSettings = if (digits) DIGITS else null)

/**
 * The Wind Tunnel tool, landscape only: the analysis on the left, the 3D
 * view in the middle, the pose and the body and environment on the right.
 * Port of WindTunnelView.swift.
 */
@Composable
fun WindTunnelScreen(engine: WindTunnelEngine, state: WTScreenState, glView: WindTunnelGLView, zh: Boolean, onBack: () -> Unit) {
    val t = remember(zh) { WTText(zh) }
    LaunchedEffect(zh) { engine.zh = zh }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var flashGroup by remember { mutableStateOf<String?>(null) }
    var viewIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun setView(i: Int) {
        viewIndex = i
        glView.setView(VIEWS[i].first)
    }

    fun pick(grp: String) {
        val id = if (grp.startsWith("arm")) "arm" else if (grp.startsWith("leg")) "leg" else grp
        openGroup = id
        flashGroup = id
        glView.highlight(if (id == grp) groupHighlight(id) else setOf(grp))
        scope.launch {
            delay(1400)
            if (flashGroup == id) {
                flashGroup = null
                glView.highlight(emptySet())
            }
        }
    }
    glView.onPick = ::pick
    glView.onHome = { setView(0) }
    BackHandler(onBack = onBack)

    BoxWithConstraints(Modifier.fillMaxSize().background(WTStyle.bg)) {
        val w = maxWidth.value
        val left = min(270f, max(190f, w * 0.27f)).dp
        val right = min(280f, max(205f, w * 0.285f)).dp
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            AnalysisColumn(engine, state, t, Modifier.width(left).fillMaxHeight())
            // The picture runs to the bottom edge of the screen.
            Box(Modifier.weight(1f).fillMaxHeight().padding(top = 6.dp).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))) {
                AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
                Stage(engine, state, t, viewIndex, onBack = onBack, onNextView = { setView((viewIndex + 1) % VIEWS.size) })
            }
            ControlsColumn(engine, state, t, openGroup, onOpened = { openGroup = null }, flashGroup = flashGroup, modifier = Modifier.width(right).fillMaxHeight())
        }
    }
}

private val VIEWS = listOf(
    WTView.HOME to ("斜视" to "Oblique"), WTView.SIDE to ("侧面" to "Side"),
    WTView.FRONT to ("正面" to "Front"), WTView.TOP to ("俯视" to "Top"),
)

private fun groupHighlight(id: String): Set<String> = when (id) {
    "head" -> setOf("head")
    "spine" -> setOf("spine")
    "arm" -> setOf("armL", "armR")
    "leg" -> setOf("legL", "legR")
    else -> emptySet()
}

// Stage

@Composable
private fun Stage(engine: WindTunnelEngine, state: WTScreenState, t: WTText, viewIndex: Int, onBack: () -> Unit, onNextView: () -> Unit) {
    val st = state.settings
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onBack)
                    .semantics { contentDescription = t.s("返回", "Back") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = WTStyle.ink, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Hud(state.hud, st.unit, t)
            Spacer(Modifier.weight(1f))
            val name = VIEWS[viewIndex].second
            Row(
                Modifier.height(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onNextView)
                    .padding(horizontal = 10.dp).semantics { contentDescription = t.s("切换视角", "Change view") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Videocam, null, tint = WTStyle.ink, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(t.s(name.first, name.second), style = text(12.sp, WTStyle.ink, FontWeight.SemiBold))
            }
        }
        Spacer(Modifier.weight(1f))
        val note = state.note
        AnimatedVisibility(note != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                note ?: "",
                style = text(11.5.sp, WTStyle.ink),
                modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable { engine.dismissNote() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Segments(
                listOf(WTMode.PIN to t.s("固定", "Pin"), WTMode.FLY to t.s("飞行", "Fly"), WTMode.FREE to t.s("放手", "Free")),
                st.mode, compact = true,
            ) { engine.setMode(it) }
            Spacer(Modifier.weight(1f).width(4.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (st.env == WTEnvKind.TUNNEL) {
                    Column(
                        Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Knob(
                            value = WTFormat.round(st.V * 3.6), range = 0.0..320.0, rest = 175.0, size = 60.dp, travel = 320f,
                            text = WTFormat.fixed(st.V * st.unit.factor, st.unit.decimals), unit = st.unit.label,
                        ) { engine.setWind(it / 3.6) }
                        Text(t.s("风速", "Wind"), style = text(10.sp, WTStyle.ink2))
                    }
                }
                Segments(listOf(WTEnvKind.FREE to t.s("自由落体", "Free fall"), WTEnvKind.TUNNEL to t.s("风洞", "Tunnel")), st.env, compact = true) {
                    engine.setEnv(it)
                }
            }
        }
    }
}

@Composable
private fun Hud(h: WTHud, u: WTUnit, t: WTText) {
    val vy = if (abs(h.vy) < 0.05) "0.0" else (if (h.vy > 0) "+" else "−") + WTFormat.fixed(abs(h.vy), 1)
    val vh = WTFormat.fixed(h.vh * u.factor, u.decimals)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        HudCell(vy, "m/s", t.s("升降", "Vertical"))
        HudCell(vh, u.label, t.s("水平速度", "Horizontal"))
        HudCell(h.glide?.let { WTFormat.fixed(it, 2) } ?: "–", "", t.s("滑翔比", "Glide"))
    }
}

@Composable
private fun HudCell(value: String, unit: String, name: String) {
    val shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = text(20.sp, WTStyle.ink, FontWeight.SemiBold, digits = true).copy(shadow = shadow), maxLines = 1, softWrap = false)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(unit, style = text(10.sp, WTStyle.ink2).copy(shadow = shadow), modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Text(name, style = text(10.sp, WTStyle.ink2).copy(shadow = shadow))
    }
}

// Controls

/** A rotary knob: drag up or right to turn it up, double-tap for its rest value. */
@Composable
fun Knob(
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double = 1.0,
    origin: Double? = null,
    rest: Double? = null,
    size: Dp = 46.dp,
    /** Points of drag for the whole range. */
    travel: Float = 200f,
    text: String,
    unit: String? = null,
    onChange: (Double) -> Unit,
) {
    var turning by remember { mutableStateOf(false) }
    val lo = range.start; val hi = range.endInclusive
    val originValue = origin ?: if (lo < 0 && hi > 0) 0.0 else lo
    fun angle(v: Double) = -135.0 + (WTCore.clamp(v, lo, hi) - lo) / (hi - lo) * 270.0
    // the newest value and callback, for the gesture that outlives a recomposition
    val current by androidx.compose.runtime.rememberUpdatedState(value)
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    fun commit(v0: Double) {
        var v = WTCore.clamp(v0, lo, hi)
        v = lo + WTFormat.round((v - lo) / step) * step
        if (v != current) change(v)
    }
    var lastTap by remember { mutableStateOf(0L) }
    Box(
        Modifier.size(size).pointerInput(lo, hi, step, travel) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val start = current
                var dx = 0f; var dy = 0f
                turning = true
                while (true) {
                    val event = awaitPointerEvent()
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val d = c.positionChange()
                    c.consume()
                    dx += d.x / density; dy += d.y / density
                    commit(start + (dx - dy) / travel * (hi - lo))
                }
                turning = false
                if (abs(dx) + abs(dy) < 4) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 350) {
                        commit(rest ?: originValue)
                        lastTap = 0L
                    } else {
                        lastTap = now
                    }
                }
            }
        }.semantics { contentDescription = text + (unit?.let { " $it" } ?: "") },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.width
            val r = s * 0.41f
            val sw = max(2.5f * density, s * 0.075f)
            val c = Offset(s / 2, s / 2)
            val box = Size(r * 2, r * 2)
            val tl = Offset(c.x - r, c.y - r)
            // Compose measures arcs from three o'clock; the knob's angles from twelve.
            drawArc(WTStyle.line, -225f, 270f, false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
            val a0 = angle(originValue); val a1 = angle(value)
            if (abs(a1 - a0) > 0.2) {
                drawArc(WTStyle.accent, (min(a0, a1) - 90).toFloat(), abs(a1 - a0).toFloat(), false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
            }
            drawCircle(if (turning) WTStyle.surface2 else WTStyle.surface, s * 0.31f, c)
            val rad = Math.toRadians(a1)
            drawCircle(WTStyle.ink, s * 0.065f, Offset(c.x + r * sin(rad).toFloat(), c.y - r * cos(rad).toFloat()))
        }
        Column(Modifier.width(size * 0.58f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text, style = text((size.value * 0.24f).sp, WTStyle.ink, FontWeight.SemiBold, digits = true),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, textAlign = TextAlign.Center,
            )
            if (unit != null) Text(unit, style = text((size.value * 0.15f).sp, WTStyle.ink3), maxLines = 1, softWrap = false)
        }
    }
}

/** A small segmented control in the simulator's style. */
@Composable
fun <T> Segments(
    items: List<Pair<T, String>>,
    selection: T,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    /** Compact items that still share the whole width. */
    fill: Boolean = false,
    onPick: (T) -> Unit,
) {
    Row(modifier.background(WTStyle.surface2, RoundedCornerShape(9.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((value, label) in items) {
            val on = value == selection
            Box(
                (if (compact && !fill) Modifier else Modifier.weight(1f))
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) WTStyle.accent else Color.Transparent)
                    .clickable { onPick(value) }
                    .padding(horizontal = if (fill) 2.dp else if (compact) 6.dp else 9.dp, vertical = if (compact) 4.dp else 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = text(if (compact) 11.sp else 12.5.sp, if (on) Color.White else WTStyle.ink2, FontWeight.SemiBold), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A card with a title that opens and closes it. */
@Composable
private fun Card(title: String, open: Boolean, onToggle: () -> Unit, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(WTStyle.surface, RoundedCornerShape(10.dp)).border(1.dp, WTStyle.line, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = text(13.5.sp, WTStyle.ink, FontWeight.Bold), maxLines = 1)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(subtitle, style = text(10.5.sp, WTStyle.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f).width(4.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = WTStyle.ink3, modifier = Modifier.size(16.dp).rotate(if (open) 0f else -90f))
        }
        if (open) content()
    }
}

// Analysis column

@Composable
private fun AnalysisColumn(engine: WindTunnelEngine, state: WTScreenState, t: WTText, modifier: Modifier) {
    var openPresets by remember { mutableStateOf(true) }
    var openZones by remember { mutableStateOf(true) }
    var openChart by remember { mutableStateOf(false) }
    var openFlight by remember { mutableStateOf(false) }
    var openRows by remember { mutableStateOf(false) }
    val p = state.panel
    Column(modifier.verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(t.s("预设姿态", "Presets"), openPresets, { openPresets = !openPresets }) { Presets(engine, state, t, p) }
        Card(t.s("各部位的受力", "Forces by body part"), openZones, { openZones = !openZones }) { Zones(engine, state, t, p) }
        Card(t.s("偏一点之后会不会回正", "Does it right itself?"), openChart, { openChart = !openChart }) { Chart(t, p) }
        Card(t.s("这个姿态会怎么飞", "How this pose flies"), openFlight, { openFlight = !openFlight }) { Flight(p) }
        Card(t.s("现在的运动和受力", "Motion and forces now"), openRows, { openRows = !openRows }, subtitle = p.flowLine) { Rows(t, p) }
    }
}

@Composable
private fun Presets(engine: WindTunnelEngine, state: WTScreenState, t: WTText, p: WTPanel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (pair in WTCore.presets.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (pr in pair) {
                        val on = pr.id == state.settings.preset && !state.settings.modified
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(if (on) WTStyle.accent else WTStyle.surface2)
                                .clickable { engine.applyPreset(pr.id) }.padding(vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val color = if (on) Color.White else WTStyle.ink
                            Text(if (t.zh) pr.name else pr.en, style = text(12.sp, color, FontWeight.Bold), maxLines = 1, softWrap = false)
                            if (t.zh) Text(pr.en, style = text(9.sp, color.copy(alpha = 0.75f)), maxLines = 1, softWrap = false)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(p.presetNote, style = text(11.sp, WTStyle.ink2))
    }
}

@Composable
private fun Zones(engine: WindTunnelEngine, state: WTScreenState, t: WTText, p: WTPanel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Segments(WTMeasure.entries.map { it to t.measureName(it) }, state.settings.measure, compact = true, modifier = Modifier.fillMaxWidth(), fill = true) {
            engine.setMeasure(it)
        }
        for (bar in p.zoneBars) {
            Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    val r = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                    val r3 = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                    drawRoundRect(WTStyle.surface2, cornerRadius = r)
                    if (p.zoneSigned) {
                        drawRect(WTStyle.lineStrong, Offset(w / 2, 0f), Size(1.dp.toPx(), h))
                        val bw = max(0f, w / 2 * bar.fraction.toFloat())
                        drawRoundRect(if (bar.right) WTStyle.accent else WTStyle.series2, Offset(if (bar.right) w / 2 else w / 2 - bw, 0f), Size(bw, h), r3)
                    } else {
                        drawRoundRect(WTStyle.barMuted.copy(alpha = 0.45f), Offset.Zero, Size(max(0f, w * bar.ghost.toFloat()), h), r3)
                        drawRoundRect(WTStyle.accent, Offset.Zero, Size(max(0f, w * bar.fraction.toFloat()), h), r3)
                    }
                }
                val shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 3f)
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(bar.name, style = text(10.5.sp, WTStyle.ink, FontWeight.SemiBold).copy(shadow = shadow), maxLines = 1)
                    Spacer(Modifier.weight(1f).width(2.dp))
                    if (bar.word.isNotEmpty()) {
                        Text(bar.word, style = text(9.5.sp, WTStyle.ink.copy(alpha = 0.8f)).copy(shadow = shadow), maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(bar.value, style = text(10.5.sp, WTStyle.ink, FontWeight.SemiBold, digits = true).copy(shadow = shadow), maxLines = 1)
                }
            }
        }
        if (p.zoneSigned) {
            Row(Modifier.fillMaxWidth()) {
                Text(p.zoneAxisLeft, style = text(9.5.sp, WTStyle.ink3))
                Spacer(Modifier.weight(1f))
                Text(p.zoneAxisRight, style = text(9.5.sp, WTStyle.ink3))
            }
        }
        Text(p.zoneNote, style = text(10.5.sp, WTStyle.ink3))
        Text(p.headLine, style = text(10.5.sp, WTStyle.ink2))
    }
}

@Composable
private fun Chart(t: WTText, p: WTPanel) {
    val measurer = rememberTextMeasurer()
    val small = text(8.5.sp, WTStyle.ink3)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(WTStyle.accent)
            Text(t.s("俯仰", "Pitch"), style = text(10.sp, WTStyle.ink2))
            Spacer(Modifier.width(10.dp))
            Dot(WTStyle.series2)
            Text(t.s("侧倾", "Roll"), style = text(10.sp, WTStyle.ink2))
            Spacer(Modifier.weight(1f))
            Text("N·m", style = text(10.sp, WTStyle.ink3))
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val l = 32.dp.toPx(); val r = 6.dp.toPx(); val top = 6.dp.toPx(); val b = 18.dp.toPx()
            val pw = size.width - l - r; val ph = size.height - top - b
            if (p.curveX.isEmpty() || pw <= 10 || ph <= 10) return@Canvas
            val span = p.curveX.last()
            for (i in 0..4) {
                val y = top + ph * i / 4
                drawLine(if (i == 2) WTStyle.lineStrong else WTStyle.line, Offset(l, y), Offset(l + pw, y), 1.dp.toPx())
                val v = p.curveMax - i * p.curveMax / 2
                val layout = measurer.measure(v.toInt().toString(), small)
                drawText(layout, topLeft = Offset(l - 4.dp.toPx() - layout.size.width, y - layout.size.height / 2))
            }
            for (i in 0..4) {
                val x = l + pw * i / 4
                drawLine(WTStyle.line, Offset(x, top), Offset(x, top + ph), 1.dp.toPx())
                val deg = (-span + i * span / 2).toInt()
                val layout = measurer.measure((if (deg > 0) "+" else "") + "$deg°", small)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2, top + ph + 9.dp.toPx() - layout.size.height / 2))
            }
            fun point(j: Int, ys: List<Double>) = Offset(
                l + pw * ((p.curveX[j] + span) / (2 * span)).toFloat(),
                top + ph * (0.5 - ys[j] / p.curveMax / 2).toFloat(),
            )
            fun line(ys: List<Double>, color: Color) {
                val path = Path()
                for (j in p.curveX.indices) {
                    if (j >= ys.size) break
                    val pt = point(j, ys)
                    if (j == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            line(p.curveRoll, WTStyle.series2)
            line(p.curvePitch, WTStyle.accent)
            val mid = p.curveX.size / 2
            for ((ys, color) in listOf(p.curveRoll to WTStyle.series2, p.curvePitch to WTStyle.accent)) {
                if (mid < ys.size) drawCircle(color, 4.dp.toPx(), Offset(l + pw / 2, top + ph * (0.5 - ys[mid] / p.curveMax / 2).toFloat()))
            }
        }
        Text(t.s("偏离当前姿态的角度（正值为抬头、向右倾）", "Angle away from the pose now (+ = head up, right)"), style = text(9.5.sp, WTStyle.ink3))
        Text(p.trimText, style = text(11.sp, WTStyle.ink2))
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.padding(end = 3.dp).size(6.dp).background(color, CircleShape))
}

@Composable
private fun Flight(p: WTPanel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(p.insight, style = text(11.5.sp, WTStyle.ink))
        if (p.prediction.isNotEmpty()) Text(p.prediction, style = text(11.5.sp, WTStyle.ink), modifier = Modifier.alpha(if (p.stale) 0.55f else 1f))
        Column(Modifier.alpha(if (p.stale) 0.55f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pair in p.stats.chunked(2)) {
                Row {
                    for (s in pair) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = text(9.5.sp, WTStyle.ink3))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(s.value, style = text(16.sp, WTStyle.ink, FontWeight.SemiBold, digits = true))
                                Spacer(Modifier.width(3.dp))
                                Text(s.unit, style = text(9.sp, WTStyle.ink2), modifier = Modifier.padding(bottom = 2.dp))
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Rows(t: WTText, p: WTPanel) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(t.s("运动", "Motion"), style = text(10.sp, WTStyle.ink3, FontWeight.Bold))
        for (r in p.motionRows) RowLine(r)
        Text(t.s("受力", "Forces"), style = text(10.sp, WTStyle.ink3, FontWeight.Bold), modifier = Modifier.padding(top = 4.dp))
        for (r in p.forceRows) RowLine(r)
    }
}

@Composable
private fun RowLine(r: WTRow) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, style = text(11.sp, WTStyle.ink2), modifier = Modifier.width(34.dp), maxLines = 1)
            if (r.value.isNotEmpty()) {
                Text(r.value, style = text(12.sp, WTStyle.ink, FontWeight.SemiBold, digits = true), maxLines = 1, softWrap = false)
                Spacer(Modifier.width(5.dp))
            }
            if (!r.wide) Text(r.detail, style = text(10.sp, WTStyle.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            r.tag?.let { tag ->
                Icon(WTStyle.icon(tag.icon), null, tint = WTStyle.tone(tag.tone), modifier = Modifier.size(9.dp))
                Spacer(Modifier.width(2.dp))
                Text(tag.text, style = text(9.5.sp, WTStyle.ink2), maxLines = 1, softWrap = false)
            }
        }
        if (r.wide) Text(r.detail, style = text(10.sp, WTStyle.ink3))
    }
}

// Controls column

private class Joint(val key: String, val zh: String, val en: String, val hintZh: String = "", val hintEn: String = "")

private class JointGroup(val id: String, val zh: String, val en: String, val joints: List<Joint>, val limb: Boolean)

private val GROUPS = listOf(
    JointGroup(
        "orient", "整体朝向", "Attitude",
        listOf(Joint("pitch", "俯仰", "Pitch", "正值抬头", "+ head up"), Joint("roll", "侧倾", "Roll", "正值向右", "+ right"), Joint("yaw", "朝向", "Heading", "正值向左转", "+ turn left")),
        false,
    ),
    JointGroup(
        "head", "头颈", "Head",
        listOf(Joint("neckFlex", "低头", "Nod", "负值抬头", "− chin up"), Joint("neckSide", "歪头", "Tilt", "正值向右", "+ right"), Joint("neckTurn", "转头", "Turn", "正值向左", "+ left")),
        false,
    ),
    JointGroup(
        "spine", "躯干", "Torso",
        listOf(Joint("spineFlex", "前屈", "Bend", "负值弓身", "− arch"), Joint("spineSide", "侧弯", "Side", "正值向右", "+ right"), Joint("spineTwist", "扭转", "Twist", "正值向左", "+ left")),
        false,
    ),
    JointGroup(
        "arm", "手臂", "Arms",
        listOf(
            Joint("shAbd", "肩外展", "Shoulder out", "手臂向两侧抬起", "arm raised sideways"),
            Joint("shFlex", "肩前屈", "Shoulder fwd", "负值向后", "− backwards"),
            Joint("shRot", "上臂旋转", "Arm rotation"),
            Joint("elbow", "屈肘", "Elbow"),
            Joint("pron", "前臂旋前", "Forearm turn", "翻转手掌", "turns the palm"),
            Joint("wrist", "屈腕", "Wrist"),
        ),
        true,
    ),
    JointGroup(
        "leg", "腿", "Legs",
        listOf(
            Joint("hipFlex", "屈髋", "Hip bend", "负值后伸", "− backwards"),
            Joint("hipAbd", "髋外展", "Hip out", "双腿分开", "legs apart"),
            Joint("hipRot", "大腿旋转", "Thigh rotation", "正值脚向内摆", "+ foot swings in"),
            Joint("knee", "屈膝", "Knee"),
            Joint("ankle", "绷脚", "Ankle"),
        ),
        true,
    ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlsColumn(
    engine: WindTunnelEngine,
    state: WTScreenState,
    t: WTText,
    openGroup: String?,
    onOpened: () -> Unit,
    flashGroup: String?,
    modifier: Modifier,
) {
    var openPose by remember { mutableStateOf(true) }
    var openBody by remember { mutableStateOf(false) }
    var openGroups by remember { mutableStateOf(setOf("orient", "arm", "leg")) }
    val requesters = remember { GROUPS.associate { it.id to BringIntoViewRequester() } }
    LaunchedEffect(openGroup) {
        val id = openGroup ?: return@LaunchedEffect
        openPose = true
        openGroups = openGroups + id
        onOpened()
        // once the opened group is laid out
        delay(60)
        requesters[id]?.bringIntoView()
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(t.s("姿态", "Pose"), openPose, { openPose = !openPose }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (g in GROUPS) {
                    val open = openGroups.contains(g.id)
                    Column(
                        Modifier.fillMaxWidth().bringIntoViewRequester(requesters.getValue(g.id))
                            .background(if (flashGroup == g.id) WTStyle.accentSoft else WTStyle.surface2.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                openGroups = if (open) openGroups - g.id else openGroups + g.id
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t.s(g.zh, g.en), style = text(12.sp, WTStyle.ink, FontWeight.SemiBold))
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Filled.KeyboardArrowDown, null, tint = WTStyle.ink3, modifier = Modifier.size(14.dp).rotate(if (open) 0f else -90f))
                        }
                        if (open) {
                            if (g.limb) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { for (j in g.joints) LimbRow(engine, state, t, j) }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (j in g.joints) CoreCell(engine, state, t, j, Modifier.weight(1f)) }
                                if (g.id == "orient") {
                                    Text(t.s("俯仰 0 是趴着，90 头朝上，-90 头朝下，180 仰面。", "Pitch 0 is belly down, 90 head up, −90 head down, 180 on the back."), style = text(10.sp, WTStyle.ink3))
                                    Text(state.panel.holdNote, style = text(10.sp, WTStyle.ink3))
                                }
                            }
                        }
                    }
                }
            }
        }
        Card(t.s("人体与环境", "Body and air"), openBody, { openBody = !openBody }) { BodyPane(engine, state, t) }
    }
}

private fun shown(state: WTScreenState, key: String) = WTFormat.round(state.shownPose[key])

@Composable
private fun CoreCell(engine: WindTunnelEngine, state: WTScreenState, t: WTText, j: Joint, modifier: Modifier) {
    val lim = WTCore.limits.getValue(j.key)
    val st = state.settings
    val v = shown(state, j.key)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Knob(v, lim, rest = 0.0, size = 44.dp, text = "${v.toInt()}°") { engine.setJoint(j.key, it) }
        Text(t.s(j.zh, j.en), style = text(10.5.sp, WTStyle.ink, FontWeight.SemiBold), maxLines = 1)
        if (j.hintZh.isNotEmpty()) Text(t.s(j.hintZh, j.hintEn), style = text(8.5.sp, WTStyle.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (j.key == "pitch" || j.key == "roll") {
            val on = (if (j.key == "pitch") st.holdP else st.holdR) && st.mode != WTMode.FREE
            val enabled = st.mode != WTMode.FREE
            Box(
                Modifier.clip(CircleShape).background(if (on) WTStyle.accent else WTStyle.surface2)
                    .clickable(enabled = enabled) { engine.setHold(j.key, !(if (j.key == "pitch") st.holdP else st.holdR)) }
                    .padding(horizontal = 8.dp, vertical = 3.dp).alpha(if (enabled) 1f else 0.5f),
            ) {
                Text(t.s("保持", "Hold"), style = text(10.sp, if (on) Color.White else WTStyle.ink2, FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun LimbRow(engine: WindTunnelEngine, state: WTScreenState, t: WTText, j: Joint) {
    val lim = WTCore.limits.getValue(j.key)
    val linked = state.settings.link.contains(j.key)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(t.s(j.zh, j.en), style = text(10.5.sp, WTStyle.ink, FontWeight.SemiBold), maxLines = 2)
            if (j.hintZh.isNotEmpty()) Text(t.s(j.hintZh, j.hintEn), style = text(8.5.sp, WTStyle.ink3), maxLines = 2)
        }
        Side(engine, state, j.key + "L", lim, t.s("左", "L"))
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (linked) WTStyle.accent else Color.Transparent)
                .clickable { engine.toggleLink(j.key) }.semantics { contentDescription = t.s("左右联动", "Link left and right") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Link, null, tint = if (linked) Color.White else WTStyle.ink3, modifier = Modifier.size(14.dp))
        }
        Side(engine, state, j.key + "R", lim, t.s("右", "R"))
    }
}

@Composable
private fun Side(engine: WindTunnelEngine, state: WTScreenState, key: String, lim: ClosedFloatingPointRange<Double>, label: String) {
    val v = shown(state, key)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Knob(v, lim, rest = 0.0, size = 42.dp, text = "${v.toInt()}°") { engine.setJoint(key, it) }
        Text(label, style = text(8.5.sp, WTStyle.ink3))
    }
}

@Composable
private fun BodyPane(engine: WindTunnelEngine, state: WTScreenState, t: WTText) {
    val st = state.settings
    val lower = WTFormat.round(WTCore.proportions(1 + st.legs / 100.0).hip * 100).toInt()
    val legsNote = if (st.legs == 0) {
        t.s("上身 : 下身，标准", "upper : lower, standard")
    } else {
        t.s("上身 : 下身，腿" + (if (st.legs > 0) "长 " else "短 ") + "${abs(st.legs)}%", "upper : lower, legs ${abs(st.legs)}% " + if (st.legs > 0) "longer" else "shorter")
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val knobs: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> BodyKnob(m, t.s("身高", "Height"), null, st.H.toDouble(), 140.0..205.0, 1.0, 175.0, "${st.H}", "cm") { engine.setBody(H = it.toInt()) } },
            { m -> BodyKnob(m, t.s("体重", "Weight"), null, st.M.toDouble(), 40.0..130.0, 1.0, 75.0, "${st.M}", "kg") { engine.setBody(M = it.toInt()) } },
            { m -> BodyKnob(m, t.s("上下身比例", "Proportions"), legsNote, st.legs.toDouble(), -8.0..8.0, 1.0, 0.0, "${100 - lower}:$lower", null) { engine.setBody(legs = it.toInt()) } },
            { m ->
                BodyKnob(
                    m, t.s("海拔", "Altitude"), t.s("空气密度 ", "air density ") + WTFormat.fixed(WTCore.airDensity(st.alt.toDouble()), 3),
                    st.alt.toDouble(), 0.0..4500.0, 50.0, 0.0, "${st.alt}", "m", travel = 260f,
                ) { engine.setBody(alt = it.toInt()) }
            },
            { m ->
                BodyKnob(
                    m, t.s("阻力校准", "Drag calibration"), t.s("按实际落速微调", "match a real fall rate"),
                    st.cal, 0.8..1.2, 0.01, 1.0, "×" + WTFormat.fixed(st.cal, 2), null, origin = 1.0,
                ) { engine.setBody(cal = it) }
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in knobs.chunked(3)) {
                Row(verticalAlignment = Alignment.Top) {
                    for (k in row) k(Modifier.weight(1f))
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.s("连体服", "Suit"), style = text(10.5.sp, WTStyle.ink2, FontWeight.SemiBold))
            Segments(listOf("tight" to t.s("紧身", "Tight"), "std" to t.s("标准", "Standard"), "baggy" to t.s("宽松", "Baggy")), st.suit, modifier = Modifier.fillMaxWidth()) {
                engine.setBody(suit = it)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.s("速度单位", "Speed unit"), style = text(10.5.sp, WTStyle.ink2, FontWeight.SemiBold))
            Segments(WTUnit.entries.map { it to it.label }, st.unit, modifier = Modifier.fillMaxWidth()) { engine.setUnit(it) }
        }
        Toggle(
            st.autoMatch, { engine.setAutoMatch(it) },
            t.s("风洞里选预设时把风速调到悬停", "Set hover wind when picking a preset in the tunnel"),
            t.s("关掉后可以看到同一风速下不同姿态的升降。自由落体没有风", "Off, you see how poses rise or sink at the same wind. Free fall has no wind"),
        )
        Toggle(
            st.center, { engine.setCenter(it) },
            t.s("自动稳住位置", "Hold position automatically"),
            t.s("代表飞行者的小幅修正，最多相当于体重的 6%。姿态本身推力明显时不起作用", "Stands for the flier's small corrections, up to 6% of the weight. No effect when the pose itself drives clearly"),
        )
        Text(
            t.s("所有系数都是工程估算，没有和真实风洞或自由落体数据核对过。", "All coefficients are engineering estimates, not checked against real tunnel or free-fall data."),
            style = text(9.5.sp, WTStyle.ink3),
        )
    }
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = text(11.sp, WTStyle.ink))
            Text(detail, style = text(9.5.sp, WTStyle.ink3))
        }
        Spacer(Modifier.width(6.dp))
        Switch(
            on, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = WTStyle.accent, checkedThumbColor = Color.White,
                uncheckedTrackColor = WTStyle.surface2, uncheckedThumbColor = WTStyle.ink3, uncheckedBorderColor = WTStyle.lineStrong,
            ),
        )
    }
}

@Composable
private fun BodyKnob(
    modifier: Modifier,
    name: String,
    hint: String?,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    rest: Double,
    shown: String,
    unit: String?,
    origin: Double? = null,
    travel: Float = 200f,
    onChange: (Double) -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Knob(value, range, step, origin, rest, 52.dp, travel, shown, unit, onChange)
        Text(name, style = text(10.sp, WTStyle.ink, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hint != null) Text(hint, style = text(8.5.sp, WTStyle.ink3), maxLines = 2, textAlign = TextAlign.Center)
    }
}
