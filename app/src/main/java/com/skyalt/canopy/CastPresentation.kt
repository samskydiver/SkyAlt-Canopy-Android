package com.skyalt.canopy

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.skyalt.canopy.sim.CanopyGLView
import com.skyalt.canopy.sim.SimulatorEngine

/**
 * The connected display while casting: its own 3D view of the same engine,
 * with the enlarged instruments over it. Only one 3D view runs at a time;
 * the phone's leaves the screen while this one shows.
 */
class CastPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val engine: SimulatorEngine,
    private val stageContent: @Composable () -> Unit,
) : Presentation(activity, display) {
    lateinit var glView: CanopyGLView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(activity)
            it.setViewTreeViewModelStoreOwner(activity)
            it.setViewTreeSavedStateRegistryOwner(activity)
        }
        glView = CanopyGLView(context, engine, interactive = false)
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(stageContent)
        }
        setContentView(
            FrameLayout(context).apply {
                addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
    }
}
