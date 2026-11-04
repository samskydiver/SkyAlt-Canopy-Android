package com.skyalt.canopy.tools.planning

import android.content.Context
import com.skyalt.canopy.tools.cutaway.CutawayFinderSession
import com.skyalt.canopy.tools.exitspot.ExitSpotSession
import com.skyalt.canopy.tools.landing.LandingPatternSession

/** The map tools' sessions, kept for the life of the app so a plan survives leaving a tool. */
class ToolSessions(context: Context) {
    val landing = LandingPatternSession()
    val exitSpot = ExitSpotSession()
    val cutaway = CutawayFinderSession()
}
