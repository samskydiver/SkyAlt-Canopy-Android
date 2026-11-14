package com.skyalt.canopy.app

import android.content.Context
import com.skyalt.canopy.sim.ProAccess
import com.skyalt.canopy.sim.SimulatorTrial
import com.skyalt.canopy.ui.LanguageSetting

/**
 * The app-wide state both activities share: AltiX Pro, the simulator's
 * free sessions, the language and the display units. Created on first use
 * with the application context.
 */
object AppServices {
    private var proAccess: ProAccess? = null
    private var simulatorTrial: SimulatorTrial? = null
    private var windTunnelTrial: SimulatorTrial? = null
    private var languageSetting: LanguageSetting? = null
    private var appSettings: AppSettings? = null
    private var toolSessions: com.skyalt.canopy.tools.planning.ToolSessions? = null

    fun pro(context: Context): ProAccess = proAccess ?: ProAccess(context.applicationContext).also {
        proAccess = it
        it.start()
    }

    fun trial(context: Context): SimulatorTrial =
        simulatorTrial ?: SimulatorTrial(context.applicationContext).also { simulatorTrial = it }

    /** The wind tunnel's own free sessions. */
    fun windTunnelTrial(context: Context): SimulatorTrial =
        windTunnelTrial ?: SimulatorTrial.windTunnel(context.applicationContext).also { windTunnelTrial = it }

    fun language(context: Context): LanguageSetting =
        languageSetting ?: LanguageSetting(context.applicationContext).also { languageSetting = it }

    fun settings(context: Context): AppSettings =
        appSettings ?: AppSettings(context.applicationContext).also { appSettings = it }

    /** The map tools' sessions, kept for the life of the process so a plan survives rotation and leaving a tool. */
    fun sessions(context: Context): com.skyalt.canopy.tools.planning.ToolSessions =
        toolSessions ?: com.skyalt.canopy.tools.planning.ToolSessions(context.applicationContext).also { toolSessions = it }
}
