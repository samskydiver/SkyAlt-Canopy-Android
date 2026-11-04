package com.skyalt.canopy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.skyalt.canopy.app.AppServices
import com.skyalt.canopy.app.ProFeature
import com.skyalt.canopy.app.SettingsScreen
import com.skyalt.canopy.app.Tool
import com.skyalt.canopy.app.ToolsScreen
import com.skyalt.canopy.tools.RepackScreen
import com.skyalt.canopy.tools.RepackStore
import com.skyalt.canopy.tools.WingLoadScreen
import com.skyalt.canopy.tools.altixColors
import com.skyalt.canopy.tools.cutaway.CutawayFinderScreen
import com.skyalt.canopy.tools.exitspot.ExitSpotScreen
import com.skyalt.canopy.tools.landing.LandingPatternScreen
import com.skyalt.canopy.tools.planning.ToolSessions
import com.skyalt.canopy.ui.Palette
import com.skyalt.canopy.ui.ProUpgradeScreen
import com.skyalt.canopy.ui.ProvideSimulatorLocals
import com.skyalt.canopy.ui.Strings
import kotlinx.coroutines.launch

/**
 * AltiX Skydiving: the Tools and Settings tabs, as the iPhone app without
 * the Apple Watch parts. The canopy simulator opens in its own landscape
 * activity.
 */
class MainActivity : ComponentActivity() {
    private enum class Tab { TOOLS, SETTINGS }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val pro = AppServices.pro(this)
        val trial = AppServices.trial(this)
        val language = AppServices.language(this)
        val settings = AppServices.settings(this)
        val repack = RepackStore(applicationContext)
        // Tool sessions live here so each tool keeps its state across tab switches.
        val sessions = ToolSessions(applicationContext)

        setContent {
            val chinese = language.value.isChinese(LocalConfiguration.current.locales[0])
            val strings = Strings(chinese)
            val dark = isSystemInDarkTheme()
            var tab by rememberSaveable { mutableStateOf(Tab.TOOLS) }
            var tool by rememberSaveable { mutableStateOf<Tool?>(null) }
            var proSheet by remember { mutableStateOf(false) }
            var proFocus by remember { mutableStateOf<ProFeature?>(null) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { trial.load() }

            fun showPro(feature: ProFeature?) {
                proFocus = feature
                proSheet = true
            }

            fun openSimulator() {
                scope.launch {
                    if (!trial.isLoaded) trial.load()
                    when {
                        pro.isPro || !BuildConfig.TRIAL_ENFORCED -> startActivity(Intent(this@MainActivity, SimulatorActivity::class.java))
                        trial.hasSessionsLeft -> {
                            trial.recordSession()
                            startActivity(Intent(this@MainActivity, SimulatorActivity::class.java))
                        }
                        else -> showPro(ProFeature.CANOPY_SIMULATOR)
                    }
                }
            }

            ProvideSimulatorLocals(if (dark) Palette.dark else Palette.light, strings) {
                MaterialTheme(colorScheme = altixColors(dark)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        BackHandler(enabled = proSheet || tool != null || tab != Tab.TOOLS) {
                            when {
                                proSheet -> proSheet = false
                                tool != null -> tool = null
                                else -> tab = Tab.TOOLS
                            }
                        }
                        val back = { tool = null }
                        when {
                            proSheet -> ProUpgradeScreen(pro, trialUsedUp = proFocus == ProFeature.CANOPY_SIMULATOR, onClose = { proSheet = false }, focus = proFocus)
                            tool == Tool.WINGLOAD -> WingLoadScreen(back)
                            tool == Tool.REPACK -> RepackScreen(repack, back)
                            tool == Tool.LANDING_PATTERN -> LandingPatternScreen(sessions.landing, settings, pro, back, onLocked = ::showPro)
                            tool == Tool.EXIT_SPOT -> ExitSpotScreen(sessions.exitSpot, settings, back)
                            tool == Tool.CUTAWAY_FINDER -> CutawayFinderScreen(sessions.cutaway, settings, back)
                            else -> Scaffold(
                                topBar = { TopAppBar(title = { Text(if (tab == Tab.TOOLS) strings("Tools") else strings("Settings")) }) },
                                bottomBar = {
                                    NavigationBar {
                                        NavigationBarItem(tab == Tab.TOOLS, { tab = Tab.TOOLS }, { Icon(Icons.Filled.Handyman, null) }, label = { Text(strings("Tools")) })
                                        NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Filled.Settings, null) }, label = { Text(strings("Settings")) })
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.background,
                            ) { padding ->
                                if (tab == Tab.TOOLS) {
                                    ToolsScreen(padding, pro, trial, BuildConfig.TRIAL_ENFORCED, onOpen = { tool = it }, onSimulator = ::openSimulator, onLocked = ::showPro)
                                } else {
                                    SettingsScreen(padding, pro, language, settings, onUnlock = { showPro(null) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppServices.pro(this).refresh()
    }
}
