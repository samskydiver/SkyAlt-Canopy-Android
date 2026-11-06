package com.skyalt.canopy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.skyalt.canopy.model.CanopyModel
import com.skyalt.canopy.model.CanopyPerformance
import com.skyalt.canopy.scene.CanopyModelFiles
import com.skyalt.canopy.scene.CanopyModelView
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private const val COLUMNS = 4
private const val ROWS = 2

/**
 * The canopy page of Flight setup: the Performance Designs series on sale,
 * eight to a page, and behind each one its 3D model to turn, zoom and pan,
 * its description and its sizes. Confirm puts the choice into the setup
 * and goes back to the drawer; the back button goes back without one.
 * Port of CanopyModelPickerView.swift.
 */
@Composable
fun CanopyPicker(state: UiState, engine: SimulatorEngine, onClose: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val configuration = state.configuration
    /** The series whose page is open, or null for the grid. */
    var detail by remember { mutableStateOf<CanopyModel?>(null) }
    /** The size chosen on the open page, square feet. */
    var sizeDraft by remember { mutableStateOf(0.0) }
    var showsSizes by remember { mutableStateOf(false) }
    var modelLoaded by remember { mutableStateOf(false) }

    fun open(model: CanopyModel) {
        sizeDraft = if (configuration.canopyModelKey == model.key) configuration.canopyAreaSquareFeet
        else model.nearestSize(configuration.canopyAreaSquareFeet)
        modelLoaded = false
        showsSizes = false
        detail = model
    }

    fun back() {
        if (detail != null) detail = null else onClose()
    }

    BackHandler { back() }

    Box(Modifier.fillMaxSize().background(p.background).noRippleClick {}) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The heading with the back button: out of the page, or back to the grid from a series' page.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(30.dp).background(p.surface2, CircleShape).border(1.dp, p.line, CircleShape).noRippleClick { back() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s("Back"), tint = p.foreground, modifier = Modifier.size(20.dp))
                }
                val shown = detail
                Text((shown?.name ?: s("Choose a canopy")).uppercase(), style = displayStyle(16.sp), color = p.muted, letterSpacing = 1.3.sp, maxLines = 1)
                if (shown != null) {
                    FamilyBadge(shown, onPicture = false)
                    if (shown.isForStudents) StudentBadge()
                    Text(cellsText(shown, s), fontSize = 12.sp, color = p.muted, maxLines = 1)
                }
            }
            AnimatedContent(detail, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "canopy-page", modifier = Modifier.weight(1f)) { shown ->
                if (shown == null) {
                    Grid(configuration.canopyModelKey) { open(it) }
                } else {
                    DetailPage(
                        state, shown, sizeDraft, showsSizes, modelLoaded,
                        onLoaded = { modelLoaded = true },
                        onToggleSizes = { showsSizes = !showsSizes },
                        onSize = { sizeDraft = it; showsSizes = false },
                        onConfirm = {
                            val size = sizeDraft
                            engine.updateConfiguration { it.copy(canopyModelKey = shown.key, canopyType = shown.type, canopyAreaSquareFeet = size) }
                            onClose()
                        },
                    )
                }
            }
        }
    }
}

/** Four tiles across and two down on each page; a swipe turns the page. Opens on the page of the series flown. */
@Composable
private fun Grid(currentKey: String, onOpen: (CanopyModel) -> Unit) {
    val p = LocalPalette.current
    val pages = remember { CanopyModel.ALL.chunked(COLUMNS * ROWS) }
    val startPage = remember { (CanopyModel.ALL.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)) / (COLUMNS * ROWS) }
    val pager = rememberPagerState(initialPage = startPage) { pages.size }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(), pageSpacing = 16.dp) { index ->
            val models = pages[index]
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in 0 until ROWS) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (column in 0 until COLUMNS) {
                            val model = models.getOrNull(row * COLUMNS + column)
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (model != null) Tile(model, model.key == currentKey) { onOpen(model) }
                            }
                        }
                    }
                }
            }
        }
        if (pages.size > 1) {
            Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                for (i in pages.indices) {
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(7.dp)
                            .background(if (i == pager.currentPage) p.foreground else p.line, CircleShape),
                    )
                }
            }
        }
    }
}

/** A series' preview, name and cells; the one flown has the accent border. */
@Composable
private fun Tile(model: CanopyModel, isCurrent: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(p.surface, shape)
            .border(if (isCurrent) 2.dp else 1.dp, if (isCurrent) p.accent else p.line, shape)
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.surface2)) {
            Preview(model)
            Box(Modifier.padding(6.dp)) { FamilyBadge(model, onPicture = true) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(model.name, style = displayStyle(14.sp), color = p.foreground, maxLines = 1)
            if (model.isForStudents) StudentBadge()
        }
        Text(cellsText(model, s), fontSize = 11.sp, color = p.muted, maxLines = 1)
    }
}

/** The bundled picture of the series from the front and above, decoded off the main thread. */
@Composable
private fun Preview(model: CanopyModel) {
    val image by produceState<ImageBitmap?>(null, model.key) {
        value = withContext(Dispatchers.IO) { CanopyModelFiles.preview(model)?.asImageBitmap() }
    }
    image?.let { Image(it, contentDescription = model.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
}

/** "MAIN CANOPY", "CRW", … in a small chip: translucent white over a picture, the panel's surface elsewhere. */
@Composable
private fun FamilyBadge(model: CanopyModel, onPicture: Boolean) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val shape = RoundedCornerShape(50)
    var m = Modifier.background(if (onPicture) Palette.chip else p.surface2, shape)
    if (!onPicture) m = m.border(1.dp, p.line, shape)
    Text(
        s(model.family.titleKey).uppercase(),
        style = displayStyle(10.sp),
        letterSpacing = 0.8.sp,
        color = if (onPicture) Palette.ink else p.foreground,
        maxLines = 1,
        modifier = m.padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** "STUDENT" in the accent colour, after the name of a series PD offers to students. */
@Composable
private fun StudentBadge() {
    val s = LocalStrings.current
    val p = LocalPalette.current
    Text(
        s("Student-friendly").uppercase(),
        style = displayStyle(10.sp),
        letterSpacing = 0.8.sp,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier.background(p.accent, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** "9 cells", "7 cells · cross-braced". */
private fun cellsText(model: CanopyModel, s: Strings): String {
    val cells = s.format("%lld cells", model.cells)
    return if (model.isCrossBraced) "$cells · ${s("cross-braced")}" else cells
}

/** "150 ft²". */
private fun sizeText(size: Double) = "${size.roundToInt()} ft²"

/** The 3D model on the left, to turn, zoom and pan; on the right the description, the size and Confirm. */
@Composable
private fun DetailPage(
    state: UiState,
    model: CanopyModel,
    sizeDraft: Double,
    showsSizes: Boolean,
    modelLoaded: Boolean,
    onLoaded: () -> Unit,
    onToggleSizes: () -> Unit,
    onSize: (Double) -> Unit,
    onConfirm: () -> Unit,
) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val column = minOf(340.dp, maxOf(250.dp, maxWidth * 0.4f))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val shape = RoundedCornerShape(12.dp)
            Box(Modifier.weight(1f).fillMaxHeight().clip(shape).border(1.dp, p.line, shape)) {
                val background = p.surface.toArgb() and 0xffffff
                AndroidView(
                    factory = { context -> CanopyModelView(context) },
                    update = { view ->
                        view.backgroundHex = background
                        view.onLoaded = onLoaded
                        view.show(model, sizeDraft)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!modelLoaded) {
                    CircularProgressIndicator(color = p.muted, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(28.dp))
                }
                Text(
                    s("Drag to turn, pinch to zoom, two fingers to pan"),
                    fontSize = 11.sp, color = p.muted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
            Column(Modifier.width(column).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(CanopyModel.masked(s(model.leadKey)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.foreground)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (showsSizes) SizeList(model, sizeDraft, onSize)
                    else Text(
                        CanopyModel.masked(s(model.descriptionKey)), fontSize = 13.sp, lineHeight = 18.sp, color = p.foreground,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                }
                // The size field: a tap opens the list of the sizes PD makes, in the description's place.
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    FieldLabel(s("Size"))
                    val boxShape = RoundedCornerShape(5.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(boxShape)
                            .background(p.surface2, boxShape)
                            .border(1.dp, if (showsSizes) p.accent else p.line, boxShape)
                            .clickable(onClick = onToggleSizes)
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(sizeText(sizeDraft), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.foreground, modifier = Modifier.weight(1f))
                        Icon(
                            if (showsSizes) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null, tint = p.muted, modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(loadingSummary(state, model, sizeDraft, s), fontSize = 12.sp, color = p.muted, maxLines = 1)
                ChipButton(s("Confirm"), style = ChipStyle.PRIMARY, fillsWidth = true, onClick = onConfirm)
            }
        }
    }
}

/** The sizes, one row each, the chosen one marked. */
@Composable
private fun SizeList(model: CanopyModel, chosenSize: Double, onSize: (Double) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (size in model.sizes) {
            val chosen = size == chosenSize
            val shape = RoundedCornerShape(5.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (chosen) p.foreground else p.surface2, shape)
                    .border(1.dp, if (chosen) p.foreground else p.line, shape)
                    .clickable { onSize(size) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    sizeText(size), fontSize = 14.sp,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (chosen) p.background else p.foreground,
                    modifier = Modifier.weight(1f),
                )
                if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = p.background, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** "Wing loading 1.21 · 24 mph · 2.5 : 1" for the size chosen, at the exit weight of the setup. */
private fun loadingSummary(state: UiState, model: CanopyModel, size: Double, s: Strings): String {
    val u = state.units
    val performance = CanopyPerformance(state.configuration.exitWeightKilograms, size, model.type, model.coefficients)
    return s.format(
        "Wing loading %@ · %@ · %@ : 1",
        String.format(Locale.US, "%.2f", performance.wingLoading),
        "${u.speedText(performance.airspeed)} ${u.speedSymbol}",
        String.format(Locale.US, "%.1f", performance.glideRatio),
    )
}
