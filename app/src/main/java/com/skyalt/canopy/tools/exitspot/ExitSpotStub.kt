package com.skyalt.canopy.tools.exitspot

import androidx.compose.runtime.Composable
import com.skyalt.canopy.tools.FormColumn
import com.skyalt.canopy.tools.ToolScaffold

class ExitSpotSession

@Composable
fun ExitSpotScreen(session: ExitSpotSession, settings: com.skyalt.canopy.app.AppSettings, onBack: () -> Unit) {
    ToolScaffold("ExitSpot", onBack) { padding -> FormColumn(padding) {} }
}
