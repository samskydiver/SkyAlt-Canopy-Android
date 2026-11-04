package com.skyalt.canopy.tools.map

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.skyalt.canopy.tools.AltixRed
import com.skyalt.canopy.tools.FootnoteText
import com.skyalt.canopy.tools.FormDivider
import com.skyalt.canopy.tools.FormSection
import com.skyalt.canopy.tools.LabeledRow
import com.skyalt.canopy.tools.SegmentedPicker
import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.ManualWindLayer
import com.skyalt.canopy.tools.planning.WindMath
import com.skyalt.canopy.tools.planning.WindProfile
import com.skyalt.canopy.ui.LocalStrings
import com.skyalt.canopy.ui.Strings
import com.skyalt.canopy.ui.formatNumber
import com.skyalt.canopy.ui.parseNumber
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** "1,200", the iOS grouped integer format. */
fun grouped(value: Double): String = String.format(Locale.US, "%,d", value.roundToInt())

/** Month, day, hour and minute in the app language, as iOS .dateTime.month().day().hour().minute(). */
fun formatMonthDayTime(instant: Instant, s: Strings): String {
    val pattern = if (s.chinese) "M月d日 HH:mm" else "MMM d, HH:mm"
    val locale = if (s.chinese) Locale.SIMPLIFIED_CHINESE else Locale.US
    return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(ZoneId.systemDefault()))
}

/** The error a wind fetch left, as shown on the map card and the sheet. */
fun windErrorText(wind: WindController, s: Strings): String? =
    wind.errorKey?.let { s.format("%@ Switch to Manual to enter observed winds.", s(it)) }

/** What the wind sheet says about the tool it belongs to. */
data class WindSheetText(
    val dateTitleKey: String,
    val coordinateTitleKey: String,
    val noCoordinateKey: String,
    val autoEmptyKey: String,
    val historicalNoteKey: String? = null,
)

/**
 * The wind sheet shared by the planning tools: forecast time, the fetched
 * profile, or the manual layers that [manualContent] edits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindDataSheet(
    wind: WindController,
    coordinate: Coordinate?,
    maximumAltitudeFeet: Double,
    text: WindSheetText,
    latestDate: Instant,
    onDismiss: () -> Unit,
    cutawayRows: Boolean = false,
    manualContent: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalStrings.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s("Wind Data"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(s("Done"), fontWeight = FontWeight.SemiBold) }
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SegmentedPicker(WindSourceMode.entries, wind.mode, { s(it.titleKey) }, wind::selectMode)

                if (wind.mode == WindSourceMode.FORECAST) {
                    FormSection(s("Wind Query Time"), footer = text.historicalNoteKey?.let { s(it) }) {
                        ForecastDateRow(s(text.dateTitleKey), wind.forecastDate, latestDate, wind::selectForecastDate)
                        FormDivider()
                        val current = WindProfile.forecastHour(Instant.now())
                        TextButton(onClick = { wind.selectForecastDate(current) }, enabled = wind.forecastDate != current) {
                            Icon(Icons.Filled.History, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s("Use Current Time"))
                        }
                    }
                }

                if (coordinate != null) {
                    FormSection(s(text.coordinateTitleKey)) {
                        LabeledRow(s("Latitude"), String.format(Locale.US, "%.6f", coordinate.latitude))
                        FormDivider()
                        LabeledRow(s("Longitude"), String.format(Locale.US, "%.6f", coordinate.longitude))
                    }
                } else {
                    FormSection { FootnoteText(s(text.noCoordinateKey)) }
                }

                if (wind.mode == WindSourceMode.FORECAST) {
                    FormSection(s("Auto Wind Aloft")) {
                        val profile = wind.profile
                        when {
                            wind.isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(s("Fetching wind aloft…"))
                            }
                            wind.errorKey != null -> Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Filled.Warning, null, tint = AltixRed, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(windErrorText(wind, s) ?: "", color = AltixRed)
                            }
                            profile != null -> {
                                LabeledRow(s("Source"), s(profile.source))
                                FormDivider()
                                LabeledRow(s("Model"), s(profile.model))
                                FormDivider()
                                LabeledRow(s("Valid"), formatMonthDayTime(profile.validAt, s))
                            }
                            else -> FootnoteText(s(text.autoEmptyKey))
                        }
                    }
                    wind.profile?.let { profile ->
                        FormSection(s("25 hPa Wind Profile")) {
                            displayLayers(profile, maximumAltitudeFeet).forEachIndexed { index, layer ->
                                if (index > 0) FormDivider()
                                WindLayerRow(layer, cutawayRows, s)
                            }
                        }
                    }
                    FootnoteText(s("Forecast data: Open-Meteo and its national weather-service sources (CC BY 4.0)."))
                } else {
                    manualContent()
                }
                Spacer(Modifier.size(12.dp))
            }
        }
    }
}

private data class WindLayerDisplay(val pressure: Int?, val altitudeAGLFeet: Int, val speedKnots: Double, val directionFromDegrees: Double)

/** Every level up to the top altitude, plus the first pressure level above it. */
private fun displayLayers(profile: WindProfile, maximumAltitudeFeet: Double): List<WindLayerDisplay> {
    val maximumMSL = profile.terrainElevationMeters + maximumAltitudeFeet * WindMath.METERS_PER_FOOT
    val sorted = profile.samples.sortedBy { it.altitudeMSLMeters }
    val visible = sorted.filter { it.altitudeMSLMeters <= maximumMSL }.toMutableList()
    sorted.firstOrNull { it.pressureHectopascals != null && it.altitudeMSLMeters > maximumMSL }?.let { visible += it }
    return visible.map {
        WindLayerDisplay(
            it.pressureHectopascals,
            max(((it.altitudeMSLMeters - profile.terrainElevationMeters) / WindMath.METERS_PER_FOOT).roundToInt(), 0),
            it.wind.speedKnots,
            it.wind.directionFromDegrees,
        )
    }
}

@Composable
private fun WindLayerRow(layer: WindLayerDisplay, cutaway: Boolean, s: Strings) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(layer.pressure?.let { "$it hPa" } ?: s("Surface"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            val altitude = if (cutaway && layer.pressure == null) s("10 m AGL") else s.format("%@ ft AGL", grouped(layer.altitudeAGLFeet.toDouble()))
            Text(altitude, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(s.format("%@ kt", formatNumber(layer.speedKnots, if (cutaway) 0 else 1)))
            val direction = formatNumber(layer.directionFromDegrees, 0)
            Text(if (cutaway) s.format("%@° from", direction) else s.format("%@°", direction), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A date and time row that opens the system date then time pickers. */
@Composable
private fun ForecastDateRow(title: String, value: Instant, latest: Instant, onSelect: (Instant) -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            val current = value.atZone(zone)
            val dialog = DatePickerDialog(context, { _, y, m, d ->
                TimePickerDialog(context, { _, h, min ->
                    var picked = ZonedDateTime.of(y, m + 1, d, h, min, 0, 0, zone).toInstant()
                    if (picked.isBefore(WindProfile.HISTORICAL_COVERAGE_START)) picked = WindProfile.HISTORICAL_COVERAGE_START
                    if (picked.isAfter(latest)) picked = latest
                    onSelect(picked)
                }, current.hour, current.minute, true).show()
            }, current.year, current.monthValue - 1, current.dayOfMonth)
            dialog.datePicker.minDate = WindProfile.HISTORICAL_COVERAGE_START.toEpochMilli()
            dialog.datePicker.maxDate = latest.toEpochMilli()
            dialog.show()
        }) { Text(formatMonthDayTime(value, s)) }
    }
}

/** The planning tools' manual section: ground elevation and the layer editor. */
@Composable
fun PlanningManualWindSection(wind: WindController) {
    val s = LocalStrings.current
    var editing by remember { mutableStateOf(false) }
    FormSection(s("Manual Winds"), footer = s("Manual wind changes are applied automatically.")) {
        LabeledRow(s("Ground elevation MSL"), s.format("%@ ft", formatNumber(wind.manualTerrainFeet, 0)))
        FormDivider()
        TextButton(onClick = { editing = true }) {
            Icon(Icons.Filled.Tune, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.format("Edit %lld Wind Layers", wind.manualLayers.size))
        }
    }
    if (editing) {
        ManualWindEditor(wind) { editing = false }
    }
}

/** Editor for the manual wind column: terrain elevation plus any number of layers. */
@Composable
private fun ManualWindEditor(wind: WindController, onDone: () -> Unit) {
    val s = LocalStrings.current
    val finish = {
        wind.updateManualLayers(wind.manualLayers.sortedBy { it.altitudeAGLFeet })
        onDone()
    }
    Dialog(onDismissRequest = finish, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s("Manual Winds"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = finish) { Text(s("Done"), fontWeight = FontWeight.SemiBold) }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        FormSection(s("Terrain")) {
                            ValueSlider(s("Ground elevation MSL"), s.format("%@ ft", formatNumber(wind.manualTerrainFeet, 0)), wind.manualTerrainFeet, -1_000.0..15_000.0, 50.0) {
                                wind.updateManualTerrain(it)
                            }
                        }
                        FormSection(s("Wind Layers")) {
                            wind.manualLayers.forEachIndexed { index, layer ->
                                if (index > 0) FormDivider()
                                fun update(changed: ManualWindLayer) = wind.updateManualLayers(wind.manualLayers.map { if (it.id == layer.id) changed else it })
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.format("%@ ft AGL", formatNumber(layer.altitudeAGLFeet, 0)), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { wind.updateManualLayers(wind.manualLayers.filter { it.id != layer.id }) }) {
                                        Icon(Icons.Filled.Delete, s("Delete"), tint = AltixRed)
                                    }
                                }
                                ValueSlider(s("Altitude"), s.format("%@ ft", formatNumber(layer.altitudeAGLFeet, 0)), layer.altitudeAGLFeet, 0.0..30_000.0, 500.0) { update(layer.copy(altitudeAGLFeet = it)) }
                                ValueSlider(s("Speed"), s.format("%@ kt", formatNumber(layer.speedKnots, 0)), layer.speedKnots, 0.0..100.0, 1.0) { update(layer.copy(speedKnots = it)) }
                                ValueSlider(s("Direction from"), s.format("%@°", formatNumber(layer.directionFromDegrees, 0)), layer.directionFromDegrees, 0.0..359.0, 1.0) { update(layer.copy(directionFromDegrees = it)) }
                            }
                            FormDivider()
                            TextButton(onClick = {
                                val top = wind.manualLayers.maxOfOrNull { it.altitudeAGLFeet } ?: 0.0
                                wind.updateManualLayers(wind.manualLayers + ManualWindLayer(altitudeAGLFeet = top + 3_000, speedKnots = 10.0, directionFromDegrees = 270.0))
                            }) {
                                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s("Add Layer"))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A labelled slider row with the current value on the trailing edge. */
@Composable
fun ValueSlider(title: String, valueText: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, onValue: (Double) -> Unit) {
    Column {
        Row {
            Text(title, modifier = Modifier.weight(1f))
            Text(valueText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val steps = ((range.endInclusive - range.start) / step).roundToInt() - 1
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue((Math.round(it / step) * step).coerceIn(range.start, range.endInclusive)) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = if (steps in 1..400) steps else 0,
        )
    }
}

/** A fixed stack of 1,000 ft layers typed in as speed and direction, for the Cutaway Finder. */
@Composable
fun CutawayManualWindSection(wind: WindController) {
    val s = LocalStrings.current
    FootnoteText(s("Enter speed and direction-from for each 1,000-foot AGL layer."))
    for (layer in wind.manualLayers) {
        val title = if (layer.altitudeAGLFeet.roundToInt() == 0) s("Surface") else s.format("%@ ft AGL", grouped(layer.altitudeAGLFeet))
        fun update(changed: ManualWindLayer) = wind.updateManualLayers(wind.manualLayers.map { if (it.id == layer.id) changed else it })
        FormSection(title) {
            LayerNumberField(s("Speed"), layer.speedKnots, "kt", 1) { update(layer.copy(speedKnots = it.coerceIn(0.0, 200.0))) }
            FormDivider()
            LayerNumberField(s("Direction from"), layer.directionFromDegrees, "°", 0) { update(layer.copy(directionFromDegrees = it.coerceIn(0.0, 360.0))) }
        }
    }
}

@Composable
private fun LayerNumberField(title: String, value: Double, suffix: String, digits: Int, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(formatNumber(value, digits)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = { t -> text = t; parseNumber(t)?.let(onValue) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(suffix, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
