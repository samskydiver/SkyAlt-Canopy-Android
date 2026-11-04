package com.skyalt.canopy.tools.cutaway

import androidx.compose.runtime.Composable
import com.skyalt.canopy.tools.FormColumn
import com.skyalt.canopy.tools.ToolScaffold

class CutawayFinderSession

@Composable
fun CutawayFinderScreen(session: CutawayFinderSession, settings: com.skyalt.canopy.app.AppSettings, onBack: () -> Unit) {
    ToolScaffold("CutawayFinder", onBack) { padding -> FormColumn(padding) {} }
}
