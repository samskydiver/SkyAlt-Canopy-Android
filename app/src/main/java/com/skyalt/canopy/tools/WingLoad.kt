package com.skyalt.canopy.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.skyalt.canopy.ui.LocalStrings
import kotlin.math.ceil
import kotlin.math.max

private const val POUNDS_PER_KILOGRAM = 2.2046226218

/** Exit weight ÷ canopy area, with any of the three values editable. Port of WingLoadCalculatorView.swift. */
@Composable
fun WingLoadScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    var pounds by rememberSaveable { mutableStateOf(false) }
    var weightKg by rememberSaveable { mutableDoubleStateOf(100.0) }
    var canopy by rememberSaveable { mutableDoubleStateOf(170.0) }
    var loading by rememberSaveable { mutableDoubleStateOf(100.0 * POUNDS_PER_KILOGRAM / 170.0) }

    fun recalc() {
        loading = if (canopy == 0.0) 0.0 else (weightKg * POUNDS_PER_KILOGRAM / canopy).takeIf { it.isFinite() } ?: loading
    }

    ToolScaffold(s("Wingload"), onBack) { padding ->
        FormColumn(padding) {
            FormSection {
                SegmentedPicker(listOf(false, true), pounds, { if (it) "lb" else "kg" }, { pounds = it })
                SwipeNumberInput(
                    s("Weight"),
                    if (pounds) weightKg * POUNDS_PER_KILOGRAM else weightKg,
                    if (pounds) "lb" else "kg",
                    if (pounds) 0 else 1,
                    if (pounds) 1.0 else 0.5,
                    { v -> weightKg = if (pounds) v / POUNDS_PER_KILOGRAM else v; recalc() },
                )
                FootnoteText(s("Exit weight includes the jumper and all worn equipment."))
            }
            FormSection {
                SwipeNumberInput(s("Canopy Size"), canopy, "ft²", 1, 1.0, { v -> canopy = v; recalc() })
            }
            FormSection {
                SwipeNumberInput(s("Wing Loading"), loading, "lb/ft²", 2, 0.01, { v ->
                    loading = v
                    if (v != 0.0) (weightKg * POUNDS_PER_KILOGRAM / v).takeIf { it.isFinite() }?.let { canopy = it }
                })
                WarningLabel(Icons.Filled.Tag, experienceReference(loading, s))
                FootnoteText(s("Jump count alone cannot determine canopy suitability. Confirm canopy design, size, currency, and demonstrated skills with a qualified canopy coach."))
            }
            FormSection(s("Important")) {
                WarningLabel(Icons.Filled.Warning, s("Calculation only"))
                FootnoteText(s("This tool does not determine whether a canopy is suitable. Follow manufacturer guidance and consult a qualified canopy coach."))
            }
        }
    }
}

/** Rough jump-count guidance: +100 jumps per 0.1 lb/ft² above 0.9. */
private fun experienceReference(loading: Double, s: com.skyalt.canopy.ui.Strings): String {
    if (!loading.isFinite() || loading <= 0) return s("Experience reference unavailable")
    if (loading <= 0.9) return s("Experience reference: student / instructor-assigned")
    if (loading > 1.9) return s("Experience reference: advanced canopy coaching required")
    val steps = ceil((loading - 0.9) / 0.1 - 0.000001).toInt()
    return s.format("Experience reference: about %lld+ jumps", max(steps, 1) * 100)
}
