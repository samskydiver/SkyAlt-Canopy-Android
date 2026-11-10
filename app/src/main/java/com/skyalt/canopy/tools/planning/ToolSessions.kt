package com.skyalt.canopy.tools.planning

import android.content.Context
import com.skyalt.canopy.tools.cutaway.CutawayFinderSession
import com.skyalt.canopy.tools.exitspot.ExitSpotSession
import com.skyalt.canopy.tools.landing.LandingPatternSession

/**
 * The map tools' sessions, kept for the life of the app so a plan survives
 * leaving a tool. The Landing Pattern Assistant and Exit Spot also keep
 * their setup between launches.
 */
class ToolSessions(context: Context) {
    private val store = SharedPreferencesSettingsStore(context)
    val landing = LandingPatternSession(store = store)
    val exitSpot = ExitSpotSession(store = store)
    val cutaway = CutawayFinderSession()
}

/** The planning tools' saved setups, one JSON string per tool. */
class SharedPreferencesSettingsStore(context: Context) : PlanningSettingsStore {
    private val prefs = context.getSharedPreferences("planning-tools", Context.MODE_PRIVATE)

    override fun load(key: String): String? = prefs.getString(key, null)

    override fun save(key: String, text: String) {
        prefs.edit().putString(key, text).apply()
    }
}
