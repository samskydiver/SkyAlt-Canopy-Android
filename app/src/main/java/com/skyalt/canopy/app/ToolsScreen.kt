package com.skyalt.canopy.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Paragliding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.sim.ProAccess
import com.skyalt.canopy.sim.SimulatorTrial
import com.skyalt.canopy.tools.AltixOrange
import com.skyalt.canopy.tools.FootnoteText
import com.skyalt.canopy.tools.FormColumn
import com.skyalt.canopy.tools.FormDivider
import com.skyalt.canopy.tools.FormSection
import com.skyalt.canopy.tools.WarningLabel
import com.skyalt.canopy.ui.LocalStrings

/** The tools that open in place of the tab bar. */
enum class Tool { LANDING_PATTERN, EXIT_SPOT, REPACK, WINGLOAD, CUTAWAY_FINDER }

/** The AltiX Pro features, as AltiXProFeature on iOS. */
enum class ProFeature(val title: String, val description: String) {
    EXIT_SPOT("Exit Spot", "Calculate wind-adjusted exit ranges and suggested jump runs."),
    CUTAWAY_FINDER("Cutaway Finder", "Model a cutaway canopy path and possible landing area."),
    SWOOP_MODE("Swoop Mode", "Build a fully customized swoop pattern with draggable waypoints."),
    CANOPY_SIMULATOR("Canopy Simulator", "Fly the 3D canopy simulator as often as you like."),
    WIND_TUNNEL("Wind Tunnel", "Fly the 3D wind tunnel and body-flight simulator as often as you like."),
}

/** The Tools tab: flight-planning and canopy tools, Pro-gated where needed. Port of PhoneToolsView.swift. */
@Composable
fun ToolsScreen(
    padding: PaddingValues,
    pro: ProAccess,
    trial: SimulatorTrial,
    windTunnelTrial: SimulatorTrial,
    trialEnforced: Boolean,
    onOpen: (Tool) -> Unit,
    onSimulator: () -> Unit,
    onWindTunnel: () -> Unit,
    onLocked: (ProFeature) -> Unit,
) {
    val s = LocalStrings.current
    // The development build unlocks the Pro tools, as it lifts the free-session limit.
    val proUnlocked = pro.isPro || !trialEnforced
    FormColumn(padding) {
        FormSection(s("Simulator")) {
            val subtitle = when {
                pro.isPro || !trialEnforced -> s("Practice brakes, flares and the pattern in 3D")
                trial.hasSessionsLeft -> s.format("%lld of %lld free sessions left", trial.sessionsLeft, SimulatorTrial.FREE_SESSIONS)
                else -> s("Free sessions used up. Subscribe to AltiX Pro to keep flying.")
            }
            val locked = !pro.isPro && trialEnforced && trial.isLoaded && !trial.hasSessionsLeft
            ToolRow(s("Canopy Simulator"), subtitle, Icons.Filled.Paragliding, locked = locked, onClick = onSimulator)
            FormDivider()
            val tunnelSubtitle = when {
                pro.isPro || !trialEnforced -> s("Body-flight poses, forces and stability in 3D")
                windTunnelTrial.hasSessionsLeft -> s.format("%lld of %lld free sessions left", windTunnelTrial.sessionsLeft, windTunnelTrial.freeSessions)
                else -> s("Free sessions used up. Subscribe to AltiX Pro to keep flying.")
            }
            val tunnelLocked = !pro.isPro && trialEnforced && windTunnelTrial.isLoaded && !windTunnelTrial.hasSessionsLeft
            ToolRow(s("Wind Tunnel"), tunnelSubtitle, Icons.Filled.Air, locked = tunnelLocked, onClick = onWindTunnel)
        }
        FormSection(s("Flight Planning")) {
            ToolRow(s("Landing Pattern Assistant"), s("Plan wind-adjusted canopy landing patterns"), Icons.Filled.Route) { onOpen(Tool.LANDING_PATTERN) }
            FormDivider()
            ToolRow(s("Exit Spot"), s("Calculate a wind-adjusted exit window"), Icons.Filled.Flight, locked = !proUnlocked) {
                if (proUnlocked) onOpen(Tool.EXIT_SPOT) else onLocked(ProFeature.EXIT_SPOT)
            }
        }
        FormSection(s("My Canopy")) {
            ToolRow(s("Repack Reminder"), s("Track reserve due dates for every rig"), Icons.Filled.EventRepeat) { onOpen(Tool.REPACK) }
            FormDivider()
            ToolRow(s("Wingload Calculator"), s("Balance exit weight and canopy size"), Icons.Filled.MonitorWeight) { onOpen(Tool.WINGLOAD) }
            FormDivider()
            ToolRow(s("Cutaway Finder"), s("Model a main-canopy search area"), Icons.Filled.GpsFixed, locked = !proUnlocked) {
                if (proUnlocked) onOpen(Tool.CUTAWAY_FINDER) else onLocked(ProFeature.CUTAWAY_FINDER)
            }
        }
        FormSection(s("Safety")) {
            WarningLabel(Icons.Filled.Shield, s("Planning aid only"))
            FootnoteText(s("Wind forecasts and canopy models contain uncertainty. Verify all plans with current observations and qualified local guidance."))
        }
    }
}

@Composable
private fun ToolRow(title: String, subtitle: String, icon: ImageVector, locked: Boolean = false, onClick: () -> Unit) {
    val s = LocalStrings.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AltixOrange, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (locked) {
            Row(
                Modifier.background(AltixOrange.copy(alpha = 0.12f), CircleShape).padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, null, tint = AltixOrange, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(s("PRO"), color = AltixOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
