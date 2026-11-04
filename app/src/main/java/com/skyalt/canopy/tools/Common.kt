package com.skyalt.canopy.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.ui.LocalStrings
import com.skyalt.canopy.ui.hex

/** AltiX orange, as the iOS app's tint. */
val AltixOrange = hex(0xf08a24)
val AltixGreen = hex(0x2e9e5b)
val AltixRed = hex(0xd93a2b)

fun altixColors(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(primary = AltixOrange, onPrimary = Color.White, secondary = AltixOrange, background = hex(0x000000), surface = hex(0x000000), surfaceContainer = hex(0x1c1c1e), surfaceContainerHigh = hex(0x1c1c1e), surfaceContainerLow = hex(0x1c1c1e))
} else {
    lightColorScheme(primary = AltixOrange, onPrimary = Color.White, secondary = AltixOrange, background = hex(0xf2f2f7), surface = hex(0xf2f2f7), surfaceContainer = hex(0xffffff), surfaceContainerHigh = hex(0xffffff), surfaceContainerLow = hex(0xffffff))
}

/** A pushed tool screen: the title bar with a back arrow over the content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val s = LocalStrings.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, s("Back")) }
                },
                actions = actions,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding -> content(padding) }
}

/** The grouped list of a form, as an iOS inset grouped list. */
@Composable
fun FormColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

/** One section of a form: an optional heading, a rounded card, an optional footer. */
@Composable
fun FormSection(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(title.uppercase(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
        }
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
        if (footer != null) {
            Text(footer, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
fun FormDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** A label with its value on the right, as LabeledContent. */
@Composable
fun LabeledRow(label: String, value: String, valueColor: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            value,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else valueColor,
            fontWeight = if (bold) FontWeight.Bold else null,
        )
    }
}

@Composable
fun FootnoteText(text: String, color: Color = Color.Unspecified) {
    Text(text, fontSize = 13.sp, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color, lineHeight = 17.sp)
}

/** A warning label: an icon and a title in orange. */
@Composable
fun WarningLabel(icon: ImageVector, title: String, color: Color = AltixOrange) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** A segmented picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedPicker(options: List<T>, selection: T, title: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selection,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(title(option), maxLines = 1) },
            )
        }
    }
}

/** A stepper row: the label and value with − and + buttons. */
@Composable
fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusEnabled: Boolean = true, plusEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.OutlinedButton(onClick = onMinus, enabled = minusEnabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(width = 44.dp, height = 34.dp)) { Text("−") }
        Spacer(Modifier.width(6.dp))
        androidx.compose.material3.OutlinedButton(onClick = onPlus, enabled = plusEnabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(width = 44.dp, height = 34.dp)) { Text("+") }
    }
}
