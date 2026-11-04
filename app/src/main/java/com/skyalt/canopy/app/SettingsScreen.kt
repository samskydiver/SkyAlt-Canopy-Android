package com.skyalt.canopy.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.skyalt.canopy.sim.ProAccess
import com.skyalt.canopy.tools.AltixGreen
import com.skyalt.canopy.tools.AltixRed
import com.skyalt.canopy.tools.FootnoteText
import com.skyalt.canopy.tools.FormColumn
import com.skyalt.canopy.tools.FormSection
import com.skyalt.canopy.tools.SegmentedPicker
import com.skyalt.canopy.tools.WarningLabel
import com.skyalt.canopy.ui.AppLanguage
import com.skyalt.canopy.ui.LanguageSetting
import com.skyalt.canopy.ui.LocalStrings
import com.skyalt.canopy.ui.SimIcons

/** The Settings tab: plan, language, units and the safety note. Port of PhoneSettingsView.swift without the watch sections. */
@Composable
fun SettingsScreen(padding: PaddingValues, pro: ProAccess, language: LanguageSetting, settings: AppSettings, onUnlock: () -> Unit) {
    val s = LocalStrings.current
    FormColumn(padding) {
        FormSection(s("Access Plan")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s("Current plan"), modifier = Modifier.weight(1f))
                Icon(if (pro.isPro) Icons.Filled.Verified else Icons.Filled.Person, null, tint = if (pro.isPro) AltixGreen else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (pro.isPro) s("AltiX Pro") else s("Free"), color = if (pro.isPro) AltixGreen else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FootnoteText(
                if (pro.isPro) s("Every AltiX feature is unlocked.")
                else s("Free includes Standard Landing Pattern Assistance, Wingload Calculator, and Repack Reminder."),
            )
            if (!pro.isPro) {
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Icon(SimIcons.Crown, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s("Unlock AltiX Pro"), color = Color.White)
                }
            }
            TextButton(onClick = { pro.restore() }, enabled = !pro.isRestoring && !pro.isPurchasing, contentPadding = PaddingValues(0.dp)) {
                Text(if (pro.isRestoring) s("Restoring purchases…") else s("Restore Purchases"))
            }
            pro.statusMessage?.let { FootnoteText(s(it.key), color = AltixGreen) }
            pro.errorMessage?.let { FootnoteText(s(it.key), color = AltixRed) }
        }
        FormSection(s("Language")) {
            SegmentedPicker(
                AppLanguage.entries.toList(), language.value,
                { when (it) { AppLanguage.SYSTEM -> s("System Default"); AppLanguage.CHINESE -> "简体中文"; AppLanguage.ENGLISH -> "English" } },
                { language.select(it) },
            )
        }
        FormSection(s("Display")) {
            Text(s("Altitude unit"))
            SegmentedPicker(AltitudeUnit.entries.toList(), settings.altitudeUnit, { if (it == AltitudeUnit.FEET) s("Feet") else s("Meters") }, { settings.selectAltitudeUnit(it) })
            Text(s("Distance unit"))
            SegmentedPicker(DistanceUnit.entries.toList(), settings.distanceUnit, { s(it.title) }, { settings.selectDistanceUnit(it) })
        }
        FormSection(s("Safety")) {
            WarningLabel(Icons.Filled.Shield, s("Backup display only"))
            FootnoteText(s("AltiX is not certified life-safety equipment."))
        }
    }
}
