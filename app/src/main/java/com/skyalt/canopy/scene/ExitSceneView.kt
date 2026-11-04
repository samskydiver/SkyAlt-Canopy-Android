package com.skyalt.canopy.scene

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.skyalt.canopy.sim.MultisampleConfigChooser
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The scene page's picture: the AFF exit, the student and two instructors
 * just out of the P750's door on jump run, its propeller turning. A drag
 * turns the view round them and a pinch zooms. Port of CanopyExitScene.swift.
 */
@SuppressLint("ViewConstructor")
class ExitSceneView(context: Context) : GLSurfaceView(context) {
    @Volatile private var azimuth = -PI / 4
    @Volatile private var elevation = 0.0
    @Volatile private var distance = sqrt(162.0)
    private var lastX = 0f
    private var lastY = 0f

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
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(keep); lastY = event.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
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

    private inner class ExitRenderer : Renderer {
        private lateinit var shader: SceneShader
        private lateinit var sky: SkyShader
        private var body: List<GpuPart> = emptyList()
        private var propeller: List<GpuPart> = emptyList()
        private var formation: List<GpuPart> = emptyList()
        private var hub = com.skyalt.canopy.model.V3(0f, 0f, 0f)
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
            val aircraft = Models.aircraft()
            body = GpuPart.of(aircraft.body)
            propeller = GpuPart.of(aircraft.propeller)
            hub = aircraft.hub
            formation = GpuPart.of(Models.exitFormation())
            start = System.nanoTime()
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w; height = h
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0.788f, 0.847f, 0.902f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            sky.draw()

            val target = Models.EXIT_TARGET
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

            for (part in body) draw(part, null)
            for (part in formation) draw(part, null)
            // A turn every 0.12 s, as the iOS picture.
            val seconds = (System.nanoTime() - start) / 1e9
            val angle = ((seconds / 0.12) % 1.0 * 360).toFloat()
            Matrix.setIdentityM(propellerModel, 0)
            Matrix.translateM(propellerModel, 0, hub.x, hub.y, hub.z)
            Matrix.rotateM(propellerModel, 0, angle, 0f, 0f, 1f)
            for (part in propeller) draw(part, propellerModel)
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
