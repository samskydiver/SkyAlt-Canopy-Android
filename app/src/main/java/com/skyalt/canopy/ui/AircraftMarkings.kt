package com.skyalt.canopy.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.model.AircraftLogoPlacement
import com.skyalt.canopy.model.AircraftMarkingsSettings
import com.skyalt.canopy.scene.Models
import com.skyalt.canopy.scene.aerofoilThickness
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * The page behind four quick taps on the scene page's picture: the
 * registration painted on the P750, and a picture from the photos as a logo
 * on both sides of its fuselage, moved and sized on a side view of it.
 * Every change is kept at once. Port of CanopyAircraftMarkingsView.swift.
 */
@Composable
fun AircraftMarkingsPage(state: UiState, engine: SimulatorEngine, onChooseLogo: () -> Unit, onBack: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    val markings = state.aircraftMarkings
    var draft by remember { mutableStateOf(markings.registration) }
    fun commitRegistration() {
        val text = draft
        engine.updateAircraftMarkings { it.copy(registration = text) }
        draft = AircraftMarkingsSettings.normalizedRegistration(text)
    }
    fun leave() {
        focus.clearFocus()
        commitRegistration()
        onBack()
    }
    BackHandler { leave() }

    Box(Modifier.fillMaxSize().background(p.background).noRippleClick { focus.clearFocus() }) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(30.dp).background(p.surface2, CircleShape).border(1.dp, p.line, CircleShape).noRippleClick { leave() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s("Back"), tint = p.foreground, modifier = Modifier.size(20.dp))
                }
                Text(s("Aircraft markings").uppercase(), style = displayStyle(16.sp), color = p.muted, letterSpacing = 1.3.sp)
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // The registration, the logo's picture and its width, in a column beside the side view.
                val shape = RoundedCornerShape(12.dp)
                Column(
                    Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier.fillMaxWidth().background(p.surface, shape).border(1.dp, p.line, shape).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel(s("Registration"))
                            val fieldShape = RoundedCornerShape(8.dp)
                            BasicTextField(
                                value = draft,
                                onValueChange = { text ->
                                    draft = text.replace("\n", " ").take(AircraftMarkingsSettings.REGISTRATION_LENGTH)
                                },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = p.foreground),
                                cursorBrush = SolidColor(p.accent),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    autoCorrectEnabled = false,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { commitRegistration(); focus.clearFocus() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .background(p.surface2, fieldShape)
                                    .border(1.dp, p.line, fieldShape)
                                    .onFocusChanged { if (!it.isFocused) commitRegistration() },
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                                        if (draft.isEmpty()) {
                                            Text(AircraftMarkingsSettings.DEFAULT_REGISTRATION, fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = p.muted)
                                        }
                                        inner()
                                    }
                                },
                            )
                            Note(s("On the tail and the wings, in every scene and in free practice. Blank puts SZ-520 back."))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel(s("Fuselage logo"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChipButton(s("Choose picture"), onClick = onChooseLogo)
                                if (state.aircraftLogo != null) ChipButton(s("Remove")) { engine.removeAircraftLogo() }
                            }
                            Note(s("A picture from your photos, on both sides of the fuselage, kept under a megabyte. Drag it on the side view to move it; pinch, or use the slider, to size it."))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel(s("Logo width"))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Slider(
                                    value = markings.logo.width.toFloat(),
                                    onValueChange = { w ->
                                        val width = (w / 0.05f).roundToInt() * 0.05
                                        engine.updateAircraftMarkings { it.copy(logo = it.logo.copy(width = width)) }
                                    },
                                    valueRange = AircraftLogoPlacement.WIDTH_MIN.toFloat()..AircraftLogoPlacement.WIDTH_MAX.toFloat(),
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent),
                                )
                                Text(
                                    String.format(java.util.Locale.US, "%.2f m", markings.logo.width),
                                    style = displayStyle(13.sp), color = p.foreground,
                                    modifier = Modifier.width(58.dp), textAlign = TextAlign.End,
                                )
                            }
                        }
                    }
                }
                // The aircraft from its left side, the logo on it: a drag moves the logo, a pinch sizes it.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(p.surface, shape)
                        .border(1.dp, p.line, shape),
                ) {
                    AircraftSideView(state, engine, s("Logo goes here"), Modifier.fillMaxSize())
                    Text(
                        s("Left side · drag to move · pinch to size"),
                        fontSize = 11.sp, color = p.muted,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text.uppercase(), style = displayStyle(11.sp), color = LocalPalette.current.muted, letterSpacing = 1.sp)
}

/** The side view's scale and the point of the aircraft's datum (z = 0 at the wing, y = 0 on the centre line), pixels. */
private class SideViewFrame(val origin: Offset, val pixelsPerMetre: Float) {
    fun point(z: Double, y: Double) = Offset(origin.x + z.toFloat() * pixelsPerMetre, origin.y - y.toFloat() * pixelsPerMetre)
    fun box(z: Double, y: Double, width: Double, height: Double) = Rect(
        origin.x + (z - width / 2).toFloat() * pixelsPerMetre,
        origin.y - (y + height / 2).toFloat() * pixelsPerMetre,
        origin.x + (z + width / 2).toFloat() * pixelsPerMetre,
        origin.y - (y - height / 2).toFloat() * pixelsPerMetre,
    )

    companion object {
        /** Fits the aircraft, spinner to tail and wheels to fin, into [size] with a margin round it. */
        fun fit(size: Size): SideViewFrame {
            val zMin = -6.4f; val zMax = 5.9f; val yMin = -1.8f; val yMax = 2.5f
            val scale = max(0.001f, min(size.width / (zMax - zMin + 0.8f), size.height / (yMax - yMin + 0.6f)))
            return SideViewFrame(
                Offset(size.width / 2 - (zMin + zMax) / 2 * scale, size.height / 2 + (yMin + yMax) / 2 * scale),
                scale,
            )
        }
    }
}

/**
 * The P750 from its left side, nose to the left, drawn after the 3D model:
 * the registration where it is painted and the logo where it sits, with the
 * near wing, the door and the gear in front of them.
 */
@Composable
private fun AircraftSideView(state: UiState, engine: SimulatorEngine, placeholder: String, modifier: Modifier) {
    val p = LocalPalette.current
    val markings = state.aircraftMarkings
    val logo = state.aircraftLogo
    val logoImage = remember(logo) { logo?.asImageBitmap() }
    // The pixels per metre the view was last drawn at, for the gestures; not state, as drawing sets it.
    val scale = remember { FloatArray(1) { 1f } }
    val accent = p.accent
    Canvas(
        modifier.pointerInput(engine) {
            detectTransformGestures { _, pan, zoom, _ ->
                val k = max(scale[0], 0.001f)
                engine.updateAircraftMarkings {
                    it.copy(
                        logo = it.logo.copy(
                            along = it.logo.along + pan.x / k,
                            height = it.logo.height - pan.y / k,
                            width = it.logo.width * zoom,
                        ),
                    )
                }
            }
        },
    ) {
        val frame = SideViewFrame.fit(size)
        scale[0] = frame.pixelsPerMetre
        val k = frame.pixelsPerMetre
        fun polygon(corners: List<Pair<Double, Double>>) = Path().apply {
            for ((index, c) in corners.withIndex()) {
                val o = frame.point(c.first, c.second)
                if (index == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
            close()
        }
        val livery = Color(0xff000000.toInt() or state.aircraftLivery.colorHex)
        val white = Color(0xfff4f4f4)
        val dark = Color(0xff30343a)
        val glass = Color(0xff1a1f26)
        val rubber = Color(0xff15171a)

        // The fin and the tailplane, behind the fuselage.
        drawPath(polygon(Models.FIN_OUTLINE.map { it.first.toDouble() to it.second.toDouble() }), livery)
        drawPath(polygon(aerofoil(4.55, 0.7, 1.25, 0.1)), livery)
        // The fuselage: the tops and bottoms of its stations.
        val sections = Models.FUSELAGE_SECTIONS
        val fuselage = polygon(
            sections.map { it.z.toDouble() to (it.c + it.h).toDouble() } +
                sections.reversed().map { it.z.toDouble() to (it.c - it.h).toDouble() },
        )
        drawPath(fuselage, white)
        drawPath(fuselage, Color.Black.copy(alpha = 0.35f), style = Stroke(width = 1.dp.toPx()))

        // The registration ahead of the fin, scaled into its decal.
        drawRegistration(markings.registration, frame.box(3.5, 0.28, 1.0, 0.39))

        // The logo where it sits, or the place for one.
        val placement = markings.logo
        val dashed = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        if (logo != null && logoImage != null && logo.width > 0) {
            val box = frame.box(placement.along, placement.height, placement.width, placement.width * logo.height / logo.width)
            drawImage(
                logoImage,
                dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                dstSize = IntSize(max(1, box.width.roundToInt()), max(1, box.height.roundToInt())),
            )
            val outline = box.inflate(2.dp.toPx())
            drawRoundRect(accent, outline.topLeft, outline.size, CornerRadius(3.dp.toPx()), style = dashed)
        } else {
            val box = frame.box(placement.along, placement.height, placement.width, placement.width * 0.6)
            drawRoundRect(accent, box.topLeft, box.size, CornerRadius(3.dp.toPx()), style = dashed)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(255, (accent.red * 255).roundToInt(), (accent.green * 255).roundToInt(), (accent.blue * 255).roundToInt())
                textSize = 10.sp.toPx(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            drawContext.canvas.nativeCanvas.drawText(placeholder, box.center.x, box.center.y - (paint.ascent() + paint.descent()) / 2, paint)
        }

        // The windshield, this side's three windows and the door.
        drawLine(glass, frame.point(-3.18, 0.41), frame.point(-2.92, 0.84), strokeWidth = max(2f, 0.07f * k), cap = StrokeCap.Round)
        for (z in listOf(-2.05, -1.2, -0.35)) {
            val box = frame.box(z, 0.32, 0.6, 0.48)
            drawRoundRect(glass, box.topLeft, box.size, CornerRadius(0.08f * k))
        }
        val door = frame.box(0.8, -0.12, 1.3, 1.15)
        drawRoundRect(Color(0xff3a3f47), door.topLeft, door.size, CornerRadius(0.05f * k))

        // The near wing: its root and its raised tip, over the fuselage.
        val dihedral = tan(5 * Math.PI / 180)
        drawPath(polygon(aerofoil(-1.85, -0.78, 2.2, 0.15)), livery)
        drawPath(polygon(aerofoil(-1.75, -0.78 + 3.8 * dihedral, 2.0, 0.12)), livery)

        // The gear this side, the spinner and the propeller's disc.
        val leg = max(2f, 0.12f * k)
        drawLine(dark, frame.point(-0.9, -0.84), frame.point(-0.9, -1.28), strokeWidth = leg, cap = StrokeCap.Round)
        drawLine(dark, frame.point(-4.1, -0.5), frame.point(-4.1, -1.34), strokeWidth = leg, cap = StrokeCap.Round)
        drawCircle(rubber, 0.32f * k, frame.point(-0.9, -1.28))
        drawCircle(rubber, 0.26f * k, frame.point(-4.1, -1.34))
        drawPath(polygon(listOf(-5.55 to 0.36, -6.1 to 0.06, -5.55 to -0.24)), dark)
        drawLine(dark.copy(alpha = 0.5f), frame.point(-5.62, 1.4), frame.point(-5.62, -1.28), strokeWidth = max(1.5f, 0.05f * k), cap = StrokeCap.Round)
    }
}

/** The registration in heavy black letters, fitted into [box]. */
private fun DrawScope.drawRegistration(text: String, box: Rect) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 100f; textAlign = Paint.Align.CENTER
    }
    val width = paint.measureText(text)
    val height = paint.descent() - paint.ascent()
    if (width <= 0 || height <= 0) return
    val fit = min(box.width * 0.92f / width, box.height * 0.9f / height)
    paint.textSize = 100f * fit
    drawContext.canvas.nativeCanvas.drawText(text, box.center.x, box.center.y - (paint.ascent() + paint.descent()) / 2, paint)
}

/** The outline of a wing or tailplane section seen from the side, after the 3D model's sections. */
private fun aerofoil(leadingZ: Double, y: Double, chord: Double, thickness: Double): List<Pair<Double, Double>> {
    val steps = 16
    val top = ArrayList<Pair<Double, Double>>()
    val bottom = ArrayList<Pair<Double, Double>>()
    for (k in 0..steps) {
        val u = k.toDouble() / steps
        val t = aerofoilThickness(u.toFloat(), thickness.toFloat()).toDouble()
        top += (leadingZ + u * chord) to (y + t * chord)
        bottom += (leadingZ + u * chord) to (y - 0.55 * t * chord)
    }
    return top + bottom.reversed()
}

/**
 * A ram-air canopy seen from the front, its cells across the arch and its
 * lines down to the jumper, in [color]: the badge of free practice on the
 * scene page. Port of CanopyParachuteIcon.swift.
 */
@Composable
fun ParachuteIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = min(size.width, size.height) / 24
        fun point(x: Float, y: Float) = Offset(size.width / 2 + (x - 12) * unit, size.height / 2 + (y - 13) * unit)
        val left = 2.5f; val right = 21.5f; val topEnds = 8.5f; val bottomEnds = 11.5f
        val topBow = 1.5f; val bottomBow = 4.5f
        fun edge(x: Float, ends: Float, bow: Float): Float {
            val t = (x - left) / (right - left)
            return (1 - t) * (1 - t) * ends + 2 * (1 - t) * t * bow + t * t * ends
        }
        val path = Path().apply {
            // The canopy: an arch, its top and bottom edges bowed the same way and its ends closed.
            point(left, topEnds).let { moveTo(it.x, it.y) }
            val c1 = point(12f, topBow); val e1 = point(right, topEnds)
            quadraticTo(c1.x, c1.y, e1.x, e1.y)
            point(right, bottomEnds).let { lineTo(it.x, it.y) }
            val c2 = point(12f, bottomBow); val e2 = point(left, bottomEnds)
            quadraticTo(c2.x, c2.y, e2.x, e2.y)
            close()
            // The cell walls across it.
            for (x in listOf(7.25f, 12f, 16.75f)) {
                point(x, edge(x, topEnds, topBow)).let { moveTo(it.x, it.y) }
                point(x, edge(x, bottomEnds, bottomBow)).let { lineTo(it.x, it.y) }
            }
            // The lines, from the bottom edge down to the jumper.
            for (x in listOf(4f, 12f, 20f)) {
                point(x, edge(x, bottomEnds, bottomBow)).let { moveTo(it.x, it.y) }
                point(12f, 18.2f).let { lineTo(it.x, it.y) }
            }
        }
        val line = size.minDimension / 12
        drawPath(path, color, style = Stroke(width = line, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(color, size.minDimension / 12, Offset(size.width / 2, size.height / 2 + size.minDimension * 7 / 24))
    }
}
