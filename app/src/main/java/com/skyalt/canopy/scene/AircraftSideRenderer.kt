package com.skyalt.canopy.scene

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.skyalt.canopy.model.AircraftLogoPlacement
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * The P750XL from its left side, nose to the left, for the markings page:
 * the model in an orthographic view, painted and lettered as it flies, the
 * logo on its skin, on a clear ground. Pictures are rendered off screen on a
 * thread of their own, only the latest request each time, and handed to
 * [onPicture] on the main thread. Port of the SceneKit side view in
 * CanopyAircraftMarkingsView.swift.
 */
class AircraftSideRenderer(private val onPicture: (Bitmap) -> Unit) {
    /**
     * A picture [width] by [height] pixels taking in [zMin]…[zMax] metres
     * along the aircraft (nose to the left) and [yMin]…[yMax] up, in these
     * markings.
     */
    data class Request(
        val width: Int,
        val height: Int,
        val zMin: Float,
        val zMax: Float,
        val yMin: Float,
        val yMax: Float,
        val livery: Int,
        val registration: String,
        val logo: Bitmap?,
        val placement: AircraftLogoPlacement,
    )

    private val thread = HandlerThread("aircraft-side").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicReference<Request?>(null)
    @Volatile private var released = false

    // The render thread's own.
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var failed = false
    private var shader: SceneShader? = null
    private val aircraft = AircraftGpu()
    private var pixels: ByteBuffer? = null
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val propellerModel = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Asks for a picture; one already waiting is dropped for it. */
    fun render(request: Request) {
        if (released || request.width <= 0 || request.height <= 0) return
        if (pending.getAndSet(request) == null) handler.post(::drain)
    }

    /** Stops the thread and lets its GL objects go; no picture comes after this. */
    fun release() {
        released = true
        pending.set(null)
        handler.post {
            try {
                if (context != EGL14.EGL_NO_CONTEXT) {
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglMakeCurrent(display, surface, surface, context)
                    aircraft.release()
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                }
            } catch (e: Exception) {
                Log.e(TAG, "release", e)
            }
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
            thread.quitSafely()
        }
    }

    private fun drain() {
        val request = pending.getAndSet(null) ?: return
        if (released) return
        val picture = try {
            draw(request)
        } catch (e: Exception) {
            Log.e(TAG, "side view", e)
            null
        } ?: return
        main.post { if (!released) onPicture(picture) }
    }

    private fun draw(r: Request): Bitmap? {
        if (!ensureContext() || !ensureSurface(r.width, r.height)) return null
        val shader = shader ?: SceneShader().also { shader = it }
        // Off screen there is time to wait for the model.
        if (!aircraft.sync(r.livery, r.registration, r.logo, r.placement, wait = true)) return null

        GLES30.glViewport(0, 0, r.width, r.height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        // Alpha adds up to opaque over the skin, so the picture is solid wherever the aircraft is.
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        // The rows come back bottom first, so the view is drawn upside down to read top first;
        // that turns the triangles round, and the front faces with them.
        GLES30.glFrontFace(GLES30.GL_CW)

        // The camera off the left side looking straight across: up the picture +y, across it +z.
        Matrix.setLookAtM(view, 0, -40f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.orthoM(projection, 0, r.zMin, r.zMax, r.yMax, r.yMin, 1f, 100f)
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)

        GLES30.glUseProgram(shader.program)
        GLES30.glUniformMatrix4fv(shader.uViewProj, 1, false, viewProj, 0)
        // Light as the iOS view's: ambient and a sun up, ahead and to the left.
        val l = sqrt(30f * 30 + 40f * 40 + 20f * 20)
        GLES30.glUniform3f(shader.uLightDir, -30f / l, 40f / l, -20f / l)
        GLES30.glUniform3f(shader.uAmbient, 0.55f, 0.55f, 0.55f)
        GLES30.glUniform3f(shader.uSun, 0.75f, 0.75f, 0.75f)
        GLES30.glUniform3f(shader.uEye, -40f, 0f, 0f)
        GLES30.glUniform2f(shader.uFog, 1e5f, 2e5f)
        GLES30.glUniform3f(shader.uFogColor, 1f, 1f, 1f)
        GLES30.glUniform1i(shader.uTexture, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        for (part in aircraft.body) drawPart(shader, part, identity)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(-1f, -2f)
        for (part in aircraft.decals) drawPart(shader, part, identity)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        val hub = aircraft.hub
        Matrix.setIdentityM(propellerModel, 0)
        Matrix.translateM(propellerModel, 0, hub.x, hub.y, hub.z)
        for (part in aircraft.propeller) drawPart(shader, part, propellerModel)
        GLES30.glFrontFace(GLES30.GL_CCW)

        val size = r.width * r.height * 4
        val buffer = pixels?.takeIf { it.capacity() >= size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { pixels = it }
        buffer.clear()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, r.width, r.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        buffer.position(0)
        buffer.limit(size)
        // Premultiplied already: the ground is clear and the edges are averaged into it.
        return Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buffer) }
    }

    private val scratch = FloatArray(16)

    private fun drawPart(shader: SceneShader, part: GpuPart, parent: FloatArray) {
        if (part.mesh.isEmpty) return
        Matrix.multiplyMM(scratch, 0, parent, 0, part.model, 0)
        if (part.texture != 0) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, part.texture)
        GLES30.glUniformMatrix4fv(shader.uModel, 1, false, scratch, 0)
        GLES30.glUniform4f(shader.uColor, part.color[0], part.color[1], part.color[2], part.color[3])
        GLES30.glUniform1i(shader.uUseVertexColor, 0)
        GLES30.glUniform1i(shader.uUseTexture, if (part.texture != 0) 1 else 0)
        GLES30.glUniform2f(shader.uUvScale, 1f, 1f)
        GLES30.glUniform1i(shader.uLit, if (part.lit && part.mesh.hasNormals) 1 else 0)
        part.mesh.draw(shader)
    }

    /** An OpenGL ES 3 context of its own, 4× multisampled where pbuffers allow it. */
    private fun ensureContext(): Boolean {
        if (context != EGL14.EGL_NO_CONTEXT) return true
        if (failed) return false
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
            failed = true
            return false
        }
        val base = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        )
        val multisampled = base + intArrayOf(EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, 4, EGL14.EGL_NONE)
        for (spec in listOf(multisampled, base + EGL14.EGL_NONE)) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (EGL14.eglChooseConfig(display, spec, 0, configs, 0, 1, count, 0) && count[0] > 0 && configs[0] != null) {
                config = configs[0]
                break
            }
        }
        val chosen = config ?: run { failed = true; return false }
        context = EGL14.eglCreateContext(display, chosen, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            failed = true
            return false
        }
        return true
    }

    /** A pbuffer the picture's size, current on this thread. */
    private fun ensureSurface(width: Int, height: Int): Boolean {
        if (surface != EGL14.EGL_NO_SURFACE && width == surfaceWidth && height == surfaceHeight) return true
        val old = surface
        val made = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        if (made == null || made == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, made, made, context)) {
            EGL14.eglDestroySurface(display, made)
            return false
        }
        if (old != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, old)
        surface = made
        surfaceWidth = width
        surfaceHeight = height
        return true
    }

    private companion object {
        const val TAG = "AircraftSide"
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
