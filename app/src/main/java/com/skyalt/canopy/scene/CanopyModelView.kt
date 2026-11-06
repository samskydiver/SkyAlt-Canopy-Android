package com.skyalt.canopy.scene

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.skyalt.canopy.model.CanopyModel
import com.skyalt.canopy.sim.MultisampleConfigChooser
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A series' 3D model on the canopy page: one finger turns it, a pinch
 * zooms, two fingers pan. The key light rides with the camera, so
 * whichever side is looked at is lit. The model is built at one size and
 * scaled to the size chosen; it opens framed whole from the front, the
 * left and above. Port of CanopyModelSceneView in CanopyModelPickerView.swift.
 */
@SuppressLint("ViewConstructor")
class CanopyModelView(context: Context) : GLSurfaceView(context) {
    @Volatile private var azimuth = START_AZIMUTH
    @Volatile private var elevation = START_ELEVATION
    /** Multiples of the framing distance. */
    @Volatile private var zoom = 1.0
    @Volatile private var panX = 0.0
    @Volatile private var panY = 0.0
    @Volatile private var panZ = 0.0
    private var lastX = 0f
    private var lastY = 0f
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f

    /** The page's background behind the model, 0xRRGGBB. */
    @Volatile var backgroundHex = 0xffffff
    /** Called on the main thread once the model is on screen. */
    var onLoaded: () -> Unit = {}

    @Volatile private var key: String? = null
    @Volatile private var scale = 1f
    @Volatile private var loaded: CanopyModelRig? = null
    private var generation = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom / detector.scaleFactor).coerceIn(0.25, 3.0)
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        // Above the simulator's own 3D view, which keeps its surface underneath the page.
        setZOrderMediaOverlay(true)
        setRenderer(ModelRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Shows the series at the size, loading it off the main thread when it changes. */
    fun show(model: CanopyModel, sizeSquareFeet: Double) {
        scale = sqrt(max(1.0, sizeSquareFeet) / model.modelSize).toFloat()
        if (key == model.key) return
        key = model.key
        loaded = null
        azimuth = START_AZIMUTH; elevation = START_ELEVATION; zoom = 1.0; panX = 0.0; panY = 0.0; panZ = 0.0
        generation += 1
        val ticket = generation
        Thread {
            val rig = CanopyModelFiles.rig(model)
            post {
                if (ticket != generation || rig == null) return@post
                loaded = rig
                onLoaded()
            }
        }.apply { name = "canopy-preview"; start() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> centroid(event, -1).let { (x, y) -> lastCentroidX = x; lastCentroidY = y }
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(keep); lastY = event.getY(keep)
                centroid(event, event.actionIndex).let { (x, y) -> lastCentroidX = x; lastCentroidY = y }
            }
            MotionEvent.ACTION_MOVE -> {
                val density = resources.displayMetrics.density
                if (event.pointerCount >= 2) {
                    val (x, y) = centroid(event, -1)
                    val dx = (x - lastCentroidX) / density
                    val dy = (y - lastCentroidY) / density
                    lastCentroidX = x; lastCentroidY = y
                    val k = framing * zoom * 0.003
                    val ca = cos(azimuth); val sa = sin(azimuth)
                    val ce = cos(elevation); val se = sin(elevation)
                    panX += (-ca * dx + -se * sa * dy) * k
                    panY += (ce * dy) * k
                    panZ += (sa * dx + -se * ca * dy) * k
                } else if (!scaleDetector.isInProgress) {
                    val dx = (event.x - lastX) / density
                    val dy = (event.y - lastY) / density
                    azimuth -= dx * 0.01
                    elevation = (elevation + dy * 0.008).coerceIn(-1.4, 1.4)
                    lastX = event.x; lastY = event.y
                }
            }
        }
        return true
    }

    private fun centroid(event: MotionEvent, skip: Int): Pair<Float, Float> {
        var x = 0f; var y = 0f; var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            x += event.getX(i); y += event.getY(i); n++
        }
        return if (n == 0) 0f to 0f else x / n to y / n
    }

    /** The distance that frames the whole rig at the chosen size, metres. */
    @Volatile private var framing = 10.0

    private inner class ModelRenderer : Renderer {
        private lateinit var shader: SceneShader
        private var gpu: GpuRig? = null
        private val center = FloatArray(3)
        private var radius = 1f
        private var width = 1
        private var height = 1
        private val view = FloatArray(16)
        private val projection = FloatArray(16)
        private val viewProj = FloatArray(16)
        private val model = FloatArray(16)
        private val partModel = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            shader = SceneShader()
            gpu = null
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w; height = h
        }

        override fun onDrawFrame(gl: GL10?) {
            val rig = loaded
            if (rig != null && gpu?.rig !== rig) {
                gpu?.release()
                gpu = GpuRig(rig)
                bounds(rig)
            } else if (rig == null && gpu != null) {
                gpu?.release()
                gpu = null
            }
            val bg = backgroundHex
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(((bg shr 16) and 0xff) / 255f, ((bg shr 8) and 0xff) / 255f, (bg and 0xff) / 255f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            val parts = gpu ?: return
            parts.sync()
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

            val s = scale
            val fov = 40f
            framing = radius * s / sin(Math.toRadians(fov / 2.0)) * 1.05
            val distance = framing * zoom
            val tx = center[0] * s + panX; val ty = center[1] * s + panY; val tz = center[2] * s + panZ
            val ce = cos(elevation)
            val dx = sin(azimuth) * ce; val dy = sin(elevation); val dz = cos(azimuth) * ce
            val ex = (tx + distance * dx).toFloat(); val ey = (ty + distance * dy).toFloat(); val ez = (tz + distance * dz).toFloat()
            Matrix.perspectiveM(projection, 0, fov, width.toFloat() / max(1, height), 0.05f, 500f)
            Matrix.setLookAtM(view, 0, ex, ey, ez, tx.toFloat(), ty.toFloat(), tz.toFloat(), 0f, 1f, 0f)
            Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)

            GLES30.glUseProgram(shader.program)
            GLES30.glUniformMatrix4fv(shader.uViewProj, 1, false, viewProj, 0)
            // The key light with the camera, a little above and to its left.
            val lx = dx - 0.35 * cos(azimuth); val ly = dy + 0.45; val lz = dz + 0.35 * sin(azimuth)
            val ll = sqrt(lx * lx + ly * ly + lz * lz)
            GLES30.glUniform3f(shader.uLightDir, (lx / ll).toFloat(), (ly / ll).toFloat(), (lz / ll).toFloat())
            GLES30.glUniform3f(shader.uAmbient, 0.5f, 0.5f, 0.5f)
            GLES30.glUniform3f(shader.uSun, 0.75f, 0.75f, 0.75f)
            GLES30.glUniform3f(shader.uEye, ex, ey, ez)
            GLES30.glUniform2f(shader.uFog, 1e5f, 2e5f)
            GLES30.glUniform3f(shader.uFogColor, 1f, 1f, 1f)
            GLES30.glUniform1i(shader.uUseTexture, 0)
            GLES30.glUniform2f(shader.uUvScale, 1f, 1f)
            GLES30.glUniform4f(shader.uColor, 1f, 1f, 1f, 1f)
            GLES30.glUniform1i(shader.uUseVertexColor, 1)
            Matrix.setIdentityM(model, 0)
            Matrix.scaleM(model, 0, s, s, s)
            parts.forEachPart { mesh, part ->
                val m = if (part.offsetY != 0f) {
                    model.copyInto(partModel)
                    Matrix.translateM(partModel, 0, 0f, part.offsetY, 0f)
                    partModel
                } else model
                GLES30.glUniformMatrix4fv(shader.uModel, 1, false, m, 0)
                GLES30.glUniform1i(shader.uLit, if (mesh.hasNormals) 1 else 0)
                mesh.draw(shader)
            }
        }

        /** The box round the whole rig, in the model's metres. */
        private fun bounds(rig: CanopyModelRig) {
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (part in rig.parts) {
                val p = part.positions
                for (i in 0 until p.size / 3) for (c in 0..2) {
                    lo[c] = minOf(lo[c], p[i * 3 + c]); hi[c] = maxOf(hi[c], p[i * 3 + c])
                }
            }
            if (lo[0] > hi[0]) return
            var d = 0f
            for (c in 0..2) { center[c] = (lo[c] + hi[c]) / 2; d += (hi[c] - lo[c]) * (hi[c] - lo[c]) }
            radius = max(0.5f, sqrt(d) / 2)
        }
    }

    private companion object {
        /** From the front (the model flies toward −z), the left and above, as on iOS. */
        val START_AZIMUTH = atan2(-0.6, -0.65)
        val START_ELEVATION = asin(0.5 / sqrt(0.6 * 0.6 + 0.5 * 0.5 + 0.65 * 0.65))
    }
}
