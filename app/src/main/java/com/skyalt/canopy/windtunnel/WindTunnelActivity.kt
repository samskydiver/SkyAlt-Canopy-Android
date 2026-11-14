package com.skyalt.canopy.windtunnel

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.skyalt.canopy.app.AppServices

/** What the screen shows of the engine, kept as Compose state so that it redraws. */
class WTScreenState : WTScreen {
    override var settings by mutableStateOf(WTSettings())
    override var shownPose by mutableStateOf(WTPose())
    override var panel by mutableStateOf(WTPanel())
    override var hud by mutableStateOf(WTHud())
    override var note by mutableStateOf<String?>(null)
}

/**
 * The wind tunnel simulator: one landscape activity, full screen, the
 * screen kept on. The Tools tab checks AltiX Pro or uses one of the wind
 * tunnel's own free sessions before it opens this.
 */
class WindTunnelActivity : ComponentActivity() {
    private lateinit var engine: WindTunnelEngine
    private lateinit var glView: WindTunnelGLView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val state = WTScreenState()
        engine = WindTunnelEngine(state)
        glView = WindTunnelGLView(this, engine)
        val language = AppServices.language(this)

        setContent {
            val zh = language.value.isChinese(LocalConfiguration.current.locales[0])
            WindTunnelScreen(engine, state, glView, zh, onBack = { finish() })
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }
}
