package com.skyalt.canopy.sim

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * The 3D view: draws continuously, stepping the engine once per frame. One
 * finger orbits the camera (or turns the look in first person), a pinch
 * zooms.
 */
@SuppressLint("ViewConstructor")
class CanopyGLView(context: Context, private val engine: SimulatorEngine) : GLSurfaceView(context) {
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            engine.zoomBy(detector.scaleFactor.toDouble())
            engine.userMovedCamera()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            private var width = 1
            private var height = 1
            private var lastNanos = 0L

            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                engine.onSurfaceCreated()
                lastNanos = 0L
            }

            override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
                width = w; height = h
            }

            override fun onDrawFrame(gl: GL10?) {
                val now = System.nanoTime()
                val dt = if (lastNanos == 0L) 1.0 / 60 else ((now - lastNanos) / 1e9).coerceAtMost(0.1)
                lastNanos = now
                engine.tick(dt)
                engine.scene.render(width, height)
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; dragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!dragging && dx * dx + dy * dy > 16) dragging = true
                    if (dragging) {
                        // Points, like the iOS gesture deltas.
                        val density = resources.displayMetrics.density
                        engine.orbit((dx / density).toDouble(), (dy / density).toDouble())
                        engine.userMovedCamera()
                        lastX = event.x; lastY = event.y
                    }
                } else {
                    lastX = event.x; lastY = event.y
                }
            }
        }
        return true
    }
}

/** 4× multisampling with a 24-bit depth buffer, falling back to none. */
private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val withMsaa = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
            EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE,
        )
        val plain = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, 0x40, EGL10.EGL_NONE,
        )
        val minimal = intArrayOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 0x40, EGL10.EGL_NONE)
        for (spec in listOf(withMsaa, plain, minimal)) {
            val count = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(1)
            if (egl.eglChooseConfig(display, spec, configs, 1, count) && count[0] > 0 && configs[0] != null) {
                return configs[0]!!
            }
        }
        throw IllegalStateException("No OpenGL ES 3 configuration")
    }
}
