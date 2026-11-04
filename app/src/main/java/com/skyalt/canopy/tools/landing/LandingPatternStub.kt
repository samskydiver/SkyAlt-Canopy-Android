package com.skyalt.canopy.tools.landing

import androidx.compose.runtime.Composable
import com.skyalt.canopy.tools.FormColumn
import com.skyalt.canopy.tools.ToolScaffold

class LandingPatternSession

@Composable
fun LandingPatternScreen(session: LandingPatternSession, settings: com.skyalt.canopy.app.AppSettings, pro: com.skyalt.canopy.sim.ProAccess, onBack: () -> Unit, onLocked: (com.skyalt.canopy.app.ProFeature) -> Unit) {
    ToolScaffold("LandingPattern", onBack) { padding -> FormColumn(padding) {} }
}
