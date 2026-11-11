package com.skyalt.canopy.scene

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.graphics.Bitmap
import com.skyalt.canopy.model.AircraftLiverySettings
import com.skyalt.canopy.model.AircraftLogoPlacement
import com.skyalt.canopy.model.AircraftMarkingsSettings
import com.skyalt.canopy.sim.MultisampleConfigChooser
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The scene page's picture: the AFF exit, the student and two instructors
 * just out of the P750XL's door on jump run, its propeller turning. One
 * finger turns the view round them, two fingers move it and a pinch zooms,
 * as SceneKit's camera control on iOS. Four quick taps open the aircraft's
 * markings and seven the hidden touchdown setting. Port of CanopyExitScene.swift.
 */
@SuppressLint("ViewConstructor")
class ExitSceneView(context: Context) : GLSurfaceView(context) {
    @Volatile private var azimuth = -PI / 4
    @Volatile private var elevation = 0.0
    @Volatile private var distance = sqrt(162.0)
    /** Where the camera looks, moved off the formation by two-finger drags. */
    @Volatile private var panX = 0.0
    @Volatile private var panY = 0.0
    @Volatile private var panZ = 0.0
    private var lastX = 0f
    private var lastY = 0f
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f

    /** The colour of the aircraft's wings and tail, 0xRRGGBB; the aircraft is built again when it changes. */
    @Volatile var liveryHex: Int = AircraftLiverySettings.DEFAULT_COLOR_HEX
    /** The registration on its tail and wings, and the logo on its sides with where it sits. */
    @Volatile var registration: String = AircraftMarkingsSettings.DEFAULT_REGISTRATION
    @Volatile var logo: Bitmap? = null
    @Volatile var logoPlacement: AircraftLogoPlacement = AircraftLogoPlacement()
    /**
     * Quick taps on the picture, the way in to the hidden settings: called
     * with 4 once the run of taps has ended there, so four taps on the way
     * to seven open nothing, and with 7 at the seventh.
     */
    var onQuickTaps: (Int) -> Unit = {}
    private val endOfRun = Runnable {
        if (taps == 4) onQuickTaps(4)
        taps = 0
    }
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L
    private var taps = 0
    private var moved = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            distance = (distance / detector.scaleFactor).coerceIn(3.0, 60.0)
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        // Above the simulator's own 3D view, which keeps its surface underneath the scene page.
        setZOrderMediaOverlay(true)
        setRenderer(ExitRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y; downTime = event.eventTime; moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true
                centroid(event, skip = -1).let { (x, y) -> lastCentroidX = x; lastCentroidY = y }
            }
            MotionEvent.ACTION_UP -> countTap(event)
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(keep); lastY = event.getY(keep)
                centroid(event, skip = event.actionIndex).let { (x, y) -> lastCentroidX = x; lastCentroidY = y }
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                // Two fingers drag the picture along with them.
                val (x, y) = centroid(event, skip = -1)
                val density = resources.displayMetrics.density
                val dx = (x - lastCentroidX) / density
                val dy = (y - lastCentroidY) / density
                lastCentroidX = x; lastCentroidY = y
                val k = distance * 0.0025
                val ca = cos(azimuth); val sa = sin(azimuth)
                val ce = cos(elevation); val se = sin(elevation)
                // The camera's right (cos a, 0, −sin a) and up (−sin e sin a, cos e, −sin e cos a).
                panX += (-ca * dx + -se * sa * dy) * k
                panY += (ce * dy) * k
                panZ += (sa * dx + -se * ca * dy) * k
            } else if (!scaleDetector.isInProgress) {
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) moved = true
                val density = resources.displayMetrics.density
                val dx = (event.x - lastX) / density
                val dy = (event.y - lastY) / density
                azimuth -= dx * 0.008
                elevation = (elevation + dy * 0.006).coerceIn(-1.3, 1.3)
                lastX = event.x; lastY = event.y
            }
        }
        return true
    }

    /**
     * Counts quick taps, each within 400 ms of the last: the seventh opens
     * the touchdown setting; a run that ends at four, the markings.
     */
    private fun countTap(event: MotionEvent) {
        removeCallbacks(endOfRun)
        if (moved || event.eventTime - downTime > 300) { taps = 0; return }
        taps = if (event.eventTime - lastTapTime <= 400) taps + 1 else 1
        lastTapTime = event.eventTime
        if (taps >= 7) {
            taps = 0
            onQuickTaps(7)
        } else {
            postDelayed(endOfRun, 400)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(endOfRun)
        super.onDetachedFromWindow()
    }

    /** The middle of the fingers on the screen, leaving out the one at [skip] (lifting). */
    private fun centroid(event: MotionEvent, skip: Int): Pair<Float, Float> {
        var x = 0f; var y = 0f; var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            x += event.getX(i); y += event.getY(i); n++
        }
        return if (n == 0) 0f to 0f else x / n to y / n
    }

    private inner class ExitRenderer : Renderer {
        private lateinit var shader: SceneShader
        private lateinit var sky: SkyShader
        private var aircraft = AircraftGpu()
        private var formation: List<GpuPart> = emptyList()
        private var width = 1
        private var height = 1
        private var start = 0L
        private val view = FloatArray(16)
        private val projection = FloatArray(16)
        private val viewProj = FloatArray(16)
        private val propellerModel = FloatArray(16)
        private val scratch = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            shader = SceneShader()
            sky = SkyShader()
            // A new context: the old one's parts went with it.
            aircraft = AircraftGpu()
            formation = GpuPart.of(Models.exitFormation())
            start = System.nanoTime()
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w; height = h
        }

        override fun onDrawFrame(gl: GL10?) {
            // The P750XL once its model has loaded, in the markings as they are now.
            val aircraftReady = aircraft.sync(liveryHex, registration, logo, logoPlacement)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0.788f, 0.847f, 0.902f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            sky.draw()

            val base = Models.EXIT_TARGET
            val target = com.skyalt.canopy.model.V3(base.x + panX.toFloat(), base.y + panY.toFloat(), base.z + panZ.toFloat())
            val ce = cos(elevation)
            val ex = (target.x + distance * sin(azimuth) * ce).toFloat()
            val ey = (target.y + distance * sin(elevation)).toFloat()
            val ez = (target.z + distance * cos(azimuth) * ce).toFloat()
            Matrix.perspectiveM(projection, 0, 50f, width.toFloat() / max(1, height), 0.3f, 3000f)
            Matrix.setLookAtM(view, 0, ex, ey, ez, target.x, target.y, target.z, 0f, 1f, 0f)
            Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)

            GLES30.glUseProgram(shader.program)
            GLES30.glUniformMatrix4fv(shader.uViewProj, 1, false, viewProj, 0)
            val l = sqrt(30f * 30 + 60f * 60 + 40f * 40)
            GLES30.glUniform3f(shader.uLightDir, -30f / l, 60f / l, 40f / l)
            GLES30.glUniform3f(shader.uAmbient, 0.62f, 0.68f, 0.7f)
            GLES30.glUniform3f(shader.uSun, 0.9f, 0.9f, 0.9f)
            GLES30.glUniform3f(shader.uEye, ex, ey, ez)
            GLES30.glUniform2f(shader.uFog, 250f, 2600f)
            GLES30.glUniform3f(shader.uFogColor, 0.788f, 0.847f, 0.902f)
            GLES30.glUniform1i(shader.uTexture, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)

            if (aircraftReady) {
                for (part in aircraft.body) draw(part, null)
                GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
                GLES30.glPolygonOffset(-1f, -2f)
                for (part in aircraft.decals) draw(part, null)
                GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            }
            for (part in formation) draw(part, null)
            if (aircraftReady) {
                // A turn every 0.12 s, as the iOS picture.
                val seconds = (System.nanoTime() - start) / 1e9
                val angle = ((seconds / 0.12) % 1.0 * 360).toFloat()
                val hub = aircraft.hub
                Matrix.setIdentityM(propellerModel, 0)
                Matrix.translateM(propellerModel, 0, hub.x, hub.y, hub.z)
                Matrix.rotateM(propellerModel, 0, angle, 0f, 0f, 1f)
                for (part in aircraft.propeller) draw(part, propellerModel)
            }
        }

        private fun draw(part: GpuPart, parent: FloatArray?) {
            if (part.mesh.isEmpty) return
            val model = if (parent != null) {
                Matrix.multiplyMM(scratch, 0, parent, 0, part.model, 0)
                scratch
            } else part.model
            if (part.texture != 0) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, part.texture)
            GLES30.glUniformMatrix4fv(shader.uModel, 1, false, model, 0)
            GLES30.glUniform4f(shader.uColor, part.color[0], part.color[1], part.color[2], part.color[3])
            GLES30.glUniform1i(shader.uUseVertexColor, 0)
            GLES30.glUniform1i(shader.uUseTexture, if (part.texture != 0) 1 else 0)
            GLES30.glUniform2f(shader.uUvScale, 1f, 1f)
            GLES30.glUniform1i(shader.uLit, if (part.lit && part.mesh.hasNormals) 1 else 0)
            part.mesh.draw(shader)
        }
    }
}
