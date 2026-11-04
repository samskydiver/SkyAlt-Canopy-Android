package com.skyalt.canopy.tools

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.ui.formatNumber
import com.skyalt.canopy.ui.parseNumber
import kotlin.math.roundToInt

/**
 * A number that can be typed, or adjusted by swiping left and right over
 * the row while no keyboard is up (10 dp per [step]), as on iOS.
 */
@Composable
fun SwipeNumberInput(
    title: String,
    value: Double,
    suffix: String,
    fractionDigits: Int,
    step: Double,
    onValue: (Double) -> Unit,
    range: ClosedFloatingPointRange<Double>? = null,
    dpPerStep: Float = 10f,
    valueFontSize: TextUnit = 24.sp,
    titleFontSize: TextUnit = 15.sp,
    titleColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(formatNumber(value, fractionDigits)) }
    LaunchedEffect(value, focused) { if (!focused) text = formatNumber(value, fractionDigits) }
    val current by rememberUpdatedState(value)
    val density = LocalDensity.current.density
    val focus = LocalFocusManager.current
    var carry by remember { mutableFloatStateOf(0f) }

    Column(
        modifier.fillMaxWidth().pointerInput(focused, step, dpPerStep) {
            if (!focused) {
                detectHorizontalDragGestures(onDragStart = { carry = 0f }) { _, dx ->
                    carry += dx / density
                    val steps = (carry / dpPerStep).toInt()
                    if (steps != 0) {
                        carry -= steps * dpPerStep
                        var next = (Math.round(current / step) + steps) * step
                        if (range != null) next = next.coerceIn(range)
                        onValue(next)
                    }
                }
            }
        },
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = titleFontSize, color = if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else titleColor, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = { t ->
                    text = t
                    parseNumber(t)?.let { v -> onValue(if (range != null) v.coerceIn(range) else v) }
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = valueFontSize, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            )
            Spacer(Modifier.width(6.dp))
            Text(suffix, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Rounds to [digits] decimals for display. */
fun Double.rounded(digits: Int): Double {
    var f = 1.0
    repeat(digits) { f *= 10 }
    return (this * f).roundToInt() / f
}
