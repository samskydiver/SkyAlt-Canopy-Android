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
    private var languageSetting: LanguageSetting? = null
    private var appSettings: AppSettings? = null

    fun pro(context: Context): ProAccess = proAccess ?: ProAccess(context.applicationContext).also {
        proAccess = it
        it.start()
    }

    fun trial(context: Context): SimulatorTrial =
        simulatorTrial ?: SimulatorTrial(context.applicationContext).also { simulatorTrial = it }

    fun language(context: Context): LanguageSetting =
        languageSetting ?: LanguageSetting(context.applicationContext).also { languageSetting = it }

    fun settings(context: Context): AppSettings =
        appSettings ?: AppSettings(context.applicationContext).also { appSettings = it }
}
