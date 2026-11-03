package com.skyalt.canopy.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skyalt.canopy.sim.ProAccess
import com.skyalt.canopy.sim.ProPlan
import com.skyalt.canopy.sim.SimulatorTrial

private const val PRIVACY_POLICY_URL = "https://websams.org/privacy/canopy-simulator/"
private const val PLAY_TERMS_URL = "https://play.google.com/about/play-terms/"

/**
 * AltiX Pro: shown in place of the simulator once the free sessions are
 * used up, and from the settings drawer. [onClose] is null when there is
 * nothing to go back to.
 */
@Composable
fun ProUpgradeScreen(pro: ProAccess, trialUsedUp: Boolean, onClose: (() -> Unit)?) {
    val s = LocalStrings.current
    val p = LocalPalette.current
    val context = LocalContext.current
    var plan by remember { mutableStateOf(ProPlan.ANNUAL) }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Box(Modifier.fillMaxSize().background(p.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(
                    if (pro.isPro) Icons.Filled.CheckCircle else SimIcons.Crown, null,
                    tint = if (pro.isPro) p.good else hex(0xf08a24),
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    if (pro.isPro) s("AltiX Pro Active") else s("AltiX Pro"),
                    fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p.foreground,
                )
                if (!pro.isPro && trialUsedUp) {
                    Text(s("Free sessions used up. Subscribe to AltiX Pro to keep flying."), fontSize = 15.sp, color = p.foreground, textAlign = TextAlign.Center)
                }
                Card {
                    Text(s("Canopy Simulator"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.foreground)
                    Text(s("Fly the 3D canopy simulator as often as you like."), fontSize = 13.sp, color = p.muted)
                    Spacer(Modifier.width(4.dp))
                    for (benefit in listOf("Unlimited Canopy Simulator sessions", "Motion capture and casting to a TV", "All future simulator updates")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = p.good, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s(benefit), fontSize = 14.sp, color = p.foreground)
                        }
                    }
                }
                if (!pro.isPro) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        for (option in ProPlan.entries) {
                            val selected = option == plan
                            val shape = RoundedCornerShape(10.dp)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .background(p.surface, shape)
                                    .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.line, shape)
                                    .clickable { plan = option }
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(s(option.titleKey).uppercase(), style = displayStyle(13.sp), color = p.muted, letterSpacing = 1.sp)
                                Text(pro.price(option), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.foreground)
                                Text(s(if (option == ProPlan.ANNUAL) "per year" else "per month"), fontSize = 12.sp, color = p.muted)
                            }
                        }
                    }
                    ChipButton(
                        if (pro.isPurchasing) s("Purchasing…") else s("Subscribe"),
                        style = ChipStyle.PRIMARY,
                        fontSize = 15,
                        fillsWidth = true,
                        isDisabled = pro.isPurchasing,
                    ) { (context as? Activity)?.let { pro.purchase(it, plan) } }
                    ChipButton(if (pro.isRestoring) s("Restoring…") else s("Restore Purchases"), fillsWidth = true, isDisabled = pro.isRestoring) { pro.restore() }
                    Note(s("The subscription renews automatically through Google Play until you cancel it. Cancel at any time in Google Play › Payments & subscriptions."))
                } else if (onClose != null) {
                    ChipButton(s("Continue"), style = ChipStyle.PRIMARY, fontSize = 15, fillsWidth = true, onClick = onClose)
                }
                pro.errorMessage?.let { Text(s(it.key), fontSize = 13.sp, color = p.bad, textAlign = TextAlign.Center) }
                pro.statusMessage?.let { Text(s(it.key), fontSize = 13.sp, color = p.good, textAlign = TextAlign.Center) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = p.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s("The simulator is a training aid, not a substitute for instruction from a qualified coach."), fontSize = 12.sp, color = p.muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(s("Privacy Policy"), fontSize = 12.sp, color = p.muted, textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { open(PRIVACY_POLICY_URL) })
                    Text(s("Terms of Use"), fontSize = 12.sp, color = p.muted, textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { open(PLAY_TERMS_URL) })
                    if (pro.isPro) {
                        Text(
                            s("Manage subscription"), fontSize = 12.sp, color = p.muted, textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { open("https://play.google.com/store/account/subscriptions?package=${context.packageName}") },
                        )
                    }
                }
            }
        }
        if (onClose != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                ChipButton(s("Close"), onClick = onClose)
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().background(p.surface, shape).border(1.dp, p.line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** The settings drawer's AltiX Pro block: Pro, or the free sessions left. */
@Composable
fun ProBlock(pro: ProAccess, trial: SimulatorTrial, trialEnforced: Boolean, onOpen: () -> Unit) {
    val s = LocalStrings.current
    Block(s("AltiX Pro")) {
        when {
            pro.isPro -> Note(s("AltiX Pro is active: unlimited sessions."))
            !trialEnforced -> Note(s("Development build: the free-session limit is off."))
            else -> Note(s.format("%1\$d of %2\$d free sessions left", trial.sessionsLeft, SimulatorTrial.FREE_SESSIONS))
        }
        ChipButton(if (pro.isPro) s("Manage") else s("Unlock AltiX Pro"), icon = SimIcons.Crown, onClick = onOpen)
    }
}
