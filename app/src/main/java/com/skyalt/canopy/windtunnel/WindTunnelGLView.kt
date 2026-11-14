package com.skyalt.canopy.windtunnel

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import com.skyalt.canopy.sim.MultisampleConfigChooser

/**
 * The wind tunnel's 3D view. It draws all the time and steps the engine
 * once per frame. Drag to orbit, pinch to zoom, two fingers to pan, tap a
 * body part to jump to its joints, double-tap for the home view: the
 * gestures of WindTunnelSceneView on the iPhone.
 */
@SuppressLint("ViewConstructor")
class WindTunnelGLView(context: Context, private val engine: WindTunnelEngine) : GLSurfaceView(context) {
    /** Called on the main thread with the group of the body part tapped ("armL", "spine", …). */
    var onPick: (String) -> Unit = {}
    /** Called on a double tap. */
    var onHome: () -> Unit = {}

    // The picture belongs to the drawing thread: every change to it is queued there.
    private val model = WindTunnelSceneModel()
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor.toDouble()
            if (factor > 0 && factor.isFinite()) queueEvent { model.zoom(factor) }
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val x = e.x / density.toDouble(); val y = e.y / density.toDouble()
            queueEvent {
                val grp = model.group(x, y)
                if (grp != null) post { onPick(grp) }
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onHome()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(WindTunnelRenderer(engine, model, density))
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** One of the named views, taken relative to where the body heads now. */
    fun setView(view: WTView) {
        val yaw = engine.liveYaw()
        queueEvent { model.setView(view, yaw) }
    }

    /** Lights up the parts of these groups ("armL", "legR", "spine", "head"). */
    fun highlight(groups: Set<String>) {
        queueEvent { model.highlight(groups) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = false
                centroid(event, -1)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                centroid(event, event.actionIndex)
                // The finger that stays does not start an orbit from where the pair was.
                val keep = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(keep); lastY = event.getY(keep)
                downX = lastX; downY = lastY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val oldX = lastCentroidX; val oldY = lastCentroidY
                    centroid(event, -1)
                    val dx = ((lastCentroidX - oldX) / density).toDouble(); val dy = ((lastCentroidY - oldY) / density).toDouble()
                    if (dx != 0.0 || dy != 0.0) queueEvent { model.pan(dx, dy) }
                } else {
                    if (!dragging) {
                        val mx = event.x - downX; val my = event.y - downY
                        if (mx * mx + my * my > slop * slop) {
                            dragging = true
                            lastX = event.x; lastY = event.y
                        }
                    }
                    if (dragging) {
                        // Points, like the iPhone's gesture.
                        val dx = ((event.x - lastX) / density).toDouble(); val dy = ((event.y - lastY) / density).toDouble()
                        lastX = event.x; lastY = event.y
                        if (dx != 0.0 || dy != 0.0) queueEvent { model.orbit(dx, dy) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    /** The middle of the fingers on the glass, leaving out the one that is lifting. */
    private fun centroid(event: MotionEvent, skip: Int) {
        var x = 0f; var y = 0f; var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            x += event.getX(i); y += event.getY(i); n++
        }
        if (n > 0) {
            lastCentroidX = x / n; lastCentroidY = y / n
        }
    }
}
