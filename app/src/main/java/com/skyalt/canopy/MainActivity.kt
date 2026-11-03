package com.skyalt.canopy

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.skyalt.canopy.sim.CanopyGLView
import com.skyalt.canopy.sim.PreferencesStore
import com.skyalt.canopy.sim.SimulatorEngine
import com.skyalt.canopy.sim.UiState
import com.skyalt.canopy.ui.Palette
import com.skyalt.canopy.ui.ProvideSimulatorLocals
import com.skyalt.canopy.ui.SimulatorScreen
import com.skyalt.canopy.ui.Strings
import java.util.concurrent.Executors

/**
 * The app is the simulator: one landscape activity, full screen, the
 * screen kept on (a flight leaves it untouched for minutes).
 */
class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var uiState by mutableStateOf(UiState())
    private var settingsOpen by mutableStateOf(true)
    private var editingDropzone by mutableStateOf(false)
    private lateinit var engine: SimulatorEngine
    private lateinit var glView: CanopyGLView
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
        // A visit begins at the setup, with the run waiting for Play.
        engine.pause()

        setContent {
            val chinese = LocalConfiguration.current.locales[0].language.startsWith("zh")
            val strings = Strings(chinese)
            LaunchedEffect(chinese) { engine.setLegLabels(strings("downwind"), strings("base"), strings("final")) }
            ProvideSimulatorLocals(if (isSystemInDarkTheme()) Palette.dark else Palette.light, strings) {
                SimulatorScreen(
                    state = uiState,
                    engine = engine,
                    glView = glView,
                    settingsOpen = settingsOpen,
                    onSettingsOpen = { settingsOpen = it },
                    onChoosePicture = {
                        pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    editingDropzone = editingDropzone,
                    onEditDropzone = { editingDropzone = it },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        glView.onPause()
        engine.pause()
        super.onPause()
    }
}
