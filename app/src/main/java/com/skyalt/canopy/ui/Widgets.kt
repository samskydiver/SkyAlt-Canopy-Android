package com.skyalt.canopy.ui

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

/** The lab page's light and dark colours. */
data class Palette(
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val foreground: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val left: Color,
    val right: Color,
    val warn: Color,
    val bad: Color,
    val good: Color,
    val accentHex: Int,
) {
    companion object {
        val ink = hex(0x0e1a28)
        val chip = Color.White.copy(alpha = 0.78f)

        val light = Palette(
            hex(0xeef1f4), hex(0xffffff), hex(0xf6f8fa), hex(0x1b2430), hex(0x5b6675), hex(0xd5dbe2),
            hex(0xd9532b), hex(0xd9532b), hex(0x2f6fd6), hex(0xb7791f), hex(0xb42318), hex(0x1f7a4d), 0xd9532b,
        )
        val dark = Palette(
            hex(0x0f1620), hex(0x182230), hex(0x1f2b3a), hex(0xe8edf3), hex(0x93a0b0), hex(0x2b3745),
            hex(0xe8784a), hex(0xd8683a), hex(0x5d8fef), hex(0xe0a84a), hex(0xf08f85), hex(0x5fcf96), 0xe8784a,
        )
    }
}

fun hex(value: Int, alpha: Float = 1f) = Color(
    red = ((value shr 16) and 0xff) / 255f,
    green = ((value shr 8) and 0xff) / 255f,
    blue = (value and 0xff) / 255f,
    alpha = alpha,
)

val LocalPalette = staticCompositionLocalOf { Palette.light }
val LocalStrings = staticCompositionLocalOf { Strings(false) }

@Composable
fun ProvideSimulatorLocals(palette: Palette, strings: Strings, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides palette, LocalStrings provides strings, content = content)
}

/** The condensed display face: the system font, tightened. */
fun displayStyle(size: TextUnit, weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontSize = size, fontWeight = weight, letterSpacing = 0.sp, fontFeatureSettings = "tnum")

enum class ChipStyle { PANEL, VIEWPORT, PRIMARY }

/** The small uppercase buttons of the panel and the viewport. */
@Composable
fun ChipButton(
    title: String,
    modifier: Modifier = Modifier,
    style: ChipStyle = ChipStyle.PANEL,
    isOn: Boolean = false,
    isDisabled: Boolean = false,
    icon: ImageVector? = null,
    /** A full-colour image drawn untinted in place of [icon]. */
    iconPainter: Painter? = null,
    fontSize: Int = 13,
    fillsWidth: Boolean = false,
    iconOnly: Boolean = false,
    stacked: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(if (style == ChipStyle.VIEWPORT) 4.dp else 5.dp)
    val foreground = when (style) {
        ChipStyle.VIEWPORT -> if (isOn) Color.White else Palette.ink
        ChipStyle.PRIMARY -> Color.White
        ChipStyle.PANEL -> if (isOn) p.background else p.foreground
    }
    val background = when (style) {
        ChipStyle.VIEWPORT -> if (isOn) Palette.ink else Color.White.copy(alpha = 0.85f)
        ChipStyle.PRIMARY -> p.accent
        ChipStyle.PANEL -> if (isOn) p.foreground else p.surface2
    }
    var m = modifier
        .alpha(if (isDisabled) 0.5f else 1f)
        .clip(shape)
        .background(background, shape)
    if (style == ChipStyle.PANEL && !isOn) m = m.border(1.dp, p.line, shape)
    m = m.clickable(enabled = !isDisabled, onClick = onClick)
    if (fillsWidth) m = m.fillMaxWidth()
    val hasIcon = icon != null || iconPainter != null
    @Composable
    fun IconImage(size: androidx.compose.ui.unit.Dp, description: String?) {
        if (iconPainter != null) Image(iconPainter, contentDescription = description, modifier = Modifier.size(size))
        else if (icon != null) Icon(icon, contentDescription = description, tint = foreground, modifier = Modifier.size(size))
    }
    if (stacked && hasIcon) {
        // Narrow viewports: the icon with its name underneath, so each button stays labelled.
        Column(
            m.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconImage((fontSize + 4).dp, null)
            Text(title.uppercase(), style = displayStyle((fontSize - 3).sp), color = foreground, maxLines = 1, letterSpacing = 0.4.sp)
        }
        return
    }
    Row(
        m.padding(horizontal = if (iconOnly) 9.dp else 11.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasIcon) {
            IconImage(if (iconOnly) (fontSize + 6).dp else (fontSize + 2).dp, title)
            if (!iconOnly) Spacer(Modifier.width(5.dp))
        }
        if (!iconOnly || !hasIcon) {
            Text(title.uppercase(), style = displayStyle(fontSize.sp), color = foreground, maxLines = 1, letterSpacing = 0.7.sp)
        }
    }
}

/** A rounded card of the settings drawer with an uppercase heading. */
@Composable
fun Block(title: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.surface, shape)
            .border(1.dp, p.line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), style = displayStyle(13.sp), color = p.muted, letterSpacing = 1.3.sp)
        content()
    }
}

@Composable
fun FieldLabel(title: String, value: String? = null) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), fontSize = 11.sp, color = p.muted, letterSpacing = 0.9.sp, maxLines = 1)
        if (value != null) {
            Spacer(Modifier.width(6.dp))
            Text(value, style = displayStyle(14.sp), color = p.foreground, maxLines = 1)
        }
    }
}

@Composable
fun Note(text: String) {
    Text(text, fontSize = 12.sp, color = LocalPalette.current.muted, lineHeight = 16.sp)
}

/** A row (or grid) of joined segment buttons. */
@Composable
fun <T> SegmentedButtons(options: List<T>, title: (T) -> String, selection: T, onSelect: (T) -> Unit, columns: Int? = null) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(5.dp)
    val rows = if (columns != null && options.size > columns) options.chunked(columns) else listOf(options)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, p.line, shape)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth()) {
                for (option in row) {
                    val on = option == selection
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (on) p.foreground else p.surface2)
                            .border(0.5.dp, if (on) p.foreground else p.line)
                            .clickable { onSelect(option) }
                            .padding(vertical = 7.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(title(option).uppercase(), style = displayStyle(12.sp), color = if (on) p.background else p.foreground, maxLines = 1, letterSpacing = 0.7.sp)
                    }
                }
            }
        }
    }
}

/**
 * A number field shown in the chosen units and stored in SI. It keeps
 * whatever is typed while focused; every readable number lands in the value
 * as typed, and the text is tidied when editing ends.
 */
@Composable
fun NumberField(title: String, unit: String, value: Double, onValue: (Double) -> Unit, fractionDigits: Int = 1, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(formatNumber(value, fractionDigits)) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value, focused) {
        if (!focused) text = formatNumber(value, fractionDigits)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        FieldLabel(title, unit)
        val shape = RoundedCornerShape(5.dp)
        BasicTextField(
            value = text,
            onValueChange = { new ->
                text = new
                parseNumber(new)?.let(onValue)
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.foreground, fontFeatureSettings = "tnum"),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(min = 60.dp)
                .background(p.surface2, shape)
                .border(1.dp, p.line, shape)
                .padding(horizontal = 8.dp, vertical = 7.dp)
                .onFocusChanged {
                    if (focused && !it.isFocused) {
                        parseNumber(text)?.let(onValue)
                    }
                    focused = it.isFocused
                },
        )
    }
}

fun formatNumber(value: Double, fractionDigits: Int): String {
    if (fractionDigits == 0) return Math.round(value).toString()
    val s = String.format(Locale.US, "%.${fractionDigits}f", value)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/** A comma or a full-width stop or comma from a Chinese keyboard counts as the decimal point. */
fun parseNumber(text: String): Double? {
    var cleaned = text.trim()
    for (separator in listOf(",", "。", "，", "．")) cleaned = cleaned.replace(separator, ".")
    if (cleaned.isEmpty()) return null
    val n = cleaned.toDoubleOrNull() ?: return null
    return if (n.isFinite()) n else null
}

/** The translucent white pill the viewport's small labels sit on. */
fun Modifier.chipBackground() = this
    .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
    .padding(horizontal = 8.dp, vertical = 3.dp)

fun Modifier.noRippleClick(onClick: () -> Unit) = this.clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = onClick,
)

fun signedPercent(fraction: Double): String {
    val percent = Math.round(fraction * 100).toInt()
    return when {
        percent > 0 -> "+$percent%"
        percent < 0 -> "−${abs(percent)}%"
        else -> "0%"
    }
}
