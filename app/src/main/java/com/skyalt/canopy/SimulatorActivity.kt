package com.skyalt.canopy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.skyalt.canopy.sim.CanopyGLView
import com.skyalt.canopy.sim.CastController
import com.skyalt.canopy.sim.MotionCaptureController
import com.skyalt.canopy.sim.PreferencesStore
import com.skyalt.canopy.sim.ProAccess
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.SimulatorTrial
import com.skyalt.canopy.sim.UiState
import com.skyalt.canopy.ui.ExternalStage
import com.skyalt.canopy.ui.LanguageSetting
import com.skyalt.canopy.ui.LocalPalette
import com.skyalt.canopy.ui.Palette
import com.skyalt.canopy.ui.ProUpgradeScreen
import com.skyalt.canopy.ui.ProvideSimulatorLocals
import com.skyalt.canopy.ui.SimDrawer
import com.skyalt.canopy.ui.SimulatorHost
import com.skyalt.canopy.ui.SimulatorScreen
import com.skyalt.canopy.app.AppServices
import com.skyalt.canopy.ui.Strings
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The canopy simulator: one landscape activity, full screen, the screen
 * kept on (a flight leaves it untouched for minutes). The Tools tab checks
 * AltiX Pro or uses a free session before it opens this.
 */
class SimulatorActivity : ComponentActivity() {
    private enum class Gate { LOADING, OPEN, LOCKED }

    private val main = Handler(Looper.getMainLooper())
    private var uiState by mutableStateOf(UiState())
    private var drawer by mutableStateOf<SimDrawer?>(SimDrawer.SETTINGS)
    private var editingDropzone by mutableStateOf(false)
    private var gate by mutableStateOf(Gate.LOADING)
    private lateinit var engine: SimulatorEngine
    private lateinit var glView: CanopyGLView
    private lateinit var capture: MotionCaptureController
    private lateinit var cast: CastController
    private lateinit var pro: ProAccess
    private lateinit var trial: SimulatorTrial
    private lateinit var language: LanguageSetting
    private var presentation: CastPresentation? = null
    private val background = Executors.newSingleThreadExecutor()

    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        background.execute {
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                engine.setDropzoneImage(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                main.post { editingDropzone = true }
            }
        }
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) capture.start(this) else capture.markRefused()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        engine = SimulatorEngine(PreferencesStore(this)) { state -> main.post { uiState = state } }
        glView = CanopyGLView(this, engine)
        capture = MotionCaptureController(this)
        engine.motionInput = capture
        cast = CastController(this)
        cast.activate()
        pro = AppServices.pro(this)
        trial = AppServices.trial(this)
        language = AppServices.language(this)
        // A visit begins at the setup, with the run waiting for Play.
        engine.pause()
        gate = Gate.OPEN

        val host = SimulatorHost(
            engine = engine,
            glView = glView,
            capture = capture,
            pro = pro,
            trial = trial,
            trialEnforced = BuildConfig.TRIAL_ENFORCED,
            language = language,
            onChoosePicture = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onToggleCamera = ::toggleCamera,
            onOpenAppSettings = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
            onOpenCastSettings = {
                try {
                    startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            },
        )

        setContent {
            val chinese = language.value.isChinese(LocalConfiguration.current.locales[0])
            val strings = Strings(chinese)
            val palette = if (isSystemInDarkTheme()) Palette.dark else Palette.light
            LaunchedEffect(chinese) { engine.setLegLabels(strings("downwind"), strings("base"), strings("final")) }
            // Subscribing while locked opens the simulator straight away.
            LaunchedEffect(pro.isPro) { if (pro.isPro && gate == Gate.LOCKED) gate = Gate.OPEN }
            ProvideSimulatorLocals(palette, strings) {
                when (gate) {
                    Gate.LOADING -> Box(Modifier.fillMaxSize().background(LocalPalette.current.background))
                    Gate.LOCKED -> ProUpgradeScreen(pro, trialUsedUp = true, onClose = null)
                    Gate.OPEN -> {
                        val display = cast.display
                        val castingToDisplay = cast.isCasting && display != null
                        BackHandler(enabled = drawer != null || cast.isCasting) {
                            if (drawer != null) drawer = null else cast.isCasting = false
                        }
                        DisposableEffect(castingToDisplay, display?.displayId) {
                            if (castingToDisplay) {
                                val shown = CastPresentation(this@SimulatorActivity, display!!, engine) {
                                    ProvideSimulatorLocals(Palette.light, strings) { ExternalStage(uiState, engine, capture) }
                                }
                                shown.setOnDismissListener { if (presentation === shown) presentation = null }
                                try {
                                    shown.show()
                                    presentation = shown
                                } catch (e: WindowManager.InvalidDisplayException) {
                                    cast.isCasting = false
                                }
                            }
                            onDispose {
                                presentation?.dismiss()
                                presentation = null
                            }
                        }
                        SimulatorScreen(
                            state = uiState,
                            host = host,
                            drawer = drawer,
                            onDrawer = { drawer = it },
                            editingDropzone = editingDropzone,
                            onEditDropzone = { editingDropzone = it },
                            isCasting = cast.isCasting,
                            isDisplayConnected = display != null,
                            onCasting = { on ->
                                if (on) editingDropzone = false
                                cast.isCasting = on
                            },
                        )
                    }
                }
            }
        }
    }

    private fun toggleCamera() {
        when {
            capture.active -> capture.stop(silently = false)
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> capture.start(this)
            else -> {
                capture.markAsking()
                askCamera.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        presentation?.glView?.onResume()
        pro.refresh()
    }

    override fun onPause() {
        glView.onPause()
        presentation?.glView?.onPause()
        engine.pause()
        super.onPause()
    }

    override fun onDestroy() {
        cast.deactivate()
        capture.stop(silently = true)
        presentation?.dismiss()
        super.onDestroy()
    }
}
