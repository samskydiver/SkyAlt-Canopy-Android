package com.skyalt.canopy.tools.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.skyalt.canopy.scene.GpuMesh
import com.skyalt.canopy.scene.Primitives
import com.skyalt.canopy.scene.SceneShader
import com.skyalt.canopy.scene.Textures
import com.skyalt.canopy.sim.MultisampleConfigChooser
import com.skyalt.canopy.tools.planning.MercatorRect
import com.skyalt.canopy.tools.planning.Path3DFrame
import com.skyalt.canopy.tools.planning.Path3DMarkerStyle
import com.skyalt.canopy.tools.planning.Path3DMesh
import com.skyalt.canopy.tools.planning.Path3DMeshes
import com.skyalt.canopy.tools.planning.Path3DPoint
import com.skyalt.canopy.tools.planning.Path3DTopView
import com.skyalt.canopy.tools.planning.PlanningPath3DContent
import com.skyalt.canopy.tools.planning.WebMercator
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A picture of the map and the part of the world it covers. */
class Planning3DGround(val bitmap: Bitmap, val rect: MercatorRect, val style: PlanningMapStyle)

/**
 * The planned paths in 3D over the map, at their true heights. It opens
 * looking straight down with north up, like the map; one finger turns and
 * tilts it, a pinch zooms, two fingers pan, and [showTopView] brings the
 * top view back. Port of PlanningPath3DView.swift; x east, y up, z south,
 * metres.
 */
@SuppressLint("ViewConstructor")
class PlanningPath3DView(context: Context) : GLSurfaceView(context) {
    /** Where the camera looks from: a point on the ground, a distance and two angles. */
    private data class Camera(val targetX: Double, val targetZ: Double, val distance: Double, val azimuth: Double, val elevation: Double)

    private class Animation(val from: Camera, val to: Camera, val start: Long, val durationMs: Long)

    /** Everything to draw, built on the main thread and uploaded on the GL thread. */
    private class Prepared(
        val version: Int,
        val opaque: List<Pair<Path3DMesh, Int>>,
        val transparent: List<Triple<Path3DMesh, Int, Float>>,
        val balls: List<Triple<FloatArray, Float, Int>>,
        val tags: List<Tag>,
        val groundCorners: FloatArray,
        val groundBitmap: Bitmap?,
    )

    private class Tag(val position: FloatArray, val bitmap: Bitmap, val points: Float, val lift: Float)

    @Volatile private var camera = Camera(0.0, 0.0, 1_000.0, 0.0, MAX_ELEVATION)
    @Volatile private var animation: Animation? = null
    @Volatile private var prepared: Prepared? = null
    private var version = 0

    private var content = PlanningPath3DContent()
    private var ground: Planning3DGround? = null
    private var frame: Path3DFrame? = null
    private var hasContent = false
    /** While the person has not moved the camera since the last top view, the view follows changes. */
    private var isAtTopView = true

    private var lastX = 0f
    private var lastY = 0f
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    private val density = resources.displayMetrics.density

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val c = camera
            camera = c.copy(distance = (c.distance / detector.scaleFactor).coerceIn(30.0, 300_000.0))
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        // Above the map's own surface, under the buttons.
        setZOrderMediaOverlay(true)
        setRenderer(Renderer3D())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Shows [newContent] over [newGround]; rebuilt only when either changed. */
    fun update(newContent: PlanningPath3DContent, newGround: Planning3DGround?) {
        if (hasContent && newContent == content && newGround === ground) return
        val first = !hasContent
        hasContent = true
        content = newContent
        ground = newGround
        val needed = neededRect()
        if (frame == null) frame = Path3DFrame(needed.midX, needed.midY)
        prepare(needed)
        if (first) showTopView(animated = false) else if (isAtTopView) showTopView(animated = true)
    }

    /** Straight down on everything shown, north up, close enough that it all fits. */
    fun showTopView(animated: Boolean) {
        val f = frame ?: return
        val needed = neededRect()
        val aspect = if (height > 0) width.toDouble() / height else 0.5
        val target = Camera(
            targetX = f.x(needed.midX).toDouble(),
            targetZ = f.z(needed.midY).toDouble(),
            distance = Path3DTopView.distance(needed, f, aspect, content.highestAltitudeMeters),
            azimuth = 0.0,
            elevation = MAX_ELEVATION,
        )
        isAtTopView = true
        animation = if (animated) Animation(camera, target, SystemClock.uptimeMillis(), 550) else null
        if (!animated) camera = target
        requestRender()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (hasContent && isAtTopView) showTopView(animated = false)
    }

    private fun neededRect(): MercatorRect = content.neededRect ?: run {
        val f = frame
        val x = f?.originX ?: WebMercator.x(DEFAULT_MAP_CENTER.longitude)
        val y = f?.originY ?: WebMercator.y(DEFAULT_MAP_CENTER.latitude)
        val side = 400 / WebMercator.metersPerUnit(WebMercator.latitude(y))
        MercatorRect(x - side / 2, y - side / 2, side, side)
    }

    // Scene

    private fun prepare(needed: MercatorRect) {
        val f = frame ?: return
        val extent = (max(needed.width, needed.height) * f.metersPerUnit).toFloat()
        // Line thickness and ball size grow with the area shown.
        val radius = max(1f, extent * 0.002f)
        val opaque = mutableListOf<Pair<Path3DMesh, Int>>()
        val transparent = mutableListOf<Triple<Path3DMesh, Int, Float>>()
        val balls = mutableListOf<Triple<FloatArray, Float, Int>>()
        val tags = mutableListOf<Tag>()

        for (path in content.paths) {
            if (path.points.size < 2) continue
            val points = path.points.map { f.local(it) }
            opaque += Path3DMeshes.tube(points, radius) to path.argb
            transparent += Triple(Path3DMeshes.groundTrack(points, radius * 1.4f), path.argb, 0.6f)
            transparent += Triple(Path3DMeshes.curtain(points), path.argb, 0.16f)
        }
        for (ring in content.rings) {
            val center = f.local(ring.center)
            transparent += Triple(Path3DMeshes.disc(center, ring.radiusMeters.toFloat()), ring.argb, 0.2f)
            opaque += Path3DMeshes.ringOutline(center, ring.radiusMeters.toFloat(), radius * 1.6f) to ring.argb
        }
        for (marker in content.markers) {
            val position = f.local(marker.point)
            when (marker.style) {
                Path3DMarkerStyle.POINT -> {
                    balls += Triple(position, radius * 2.6f, marker.argb)
                    if (position[1] > radius) {
                        val foot = floatArrayOf(position[0], 0f, position[2])
                        transparent += Triple(Path3DMeshes.tube(listOf(foot, position), radius * 0.35f), marker.argb, 0.8f)
                    }
                    tags += Tag(position, tagBitmap(marker.title, marker.argb), 22f, 0.75f)
                }
                Path3DMarkerStyle.TAG -> tags += Tag(position, tagBitmap(marker.title, marker.argb), 16f, 0f)
            }
        }
        // North, just beyond the top of what is shown.
        val north = Path3DPoint(
            com.skyalt.canopy.tools.planning.Coordinate(WebMercator.latitude(needed.y - needed.height * 0.08), WebMercator.longitude(needed.midX)),
            0.0,
        )
        tags += Tag(f.local(north), tagBitmap("N", 0xFFFFFFFF.toInt()), 20f, 0f)

        val g = ground
        val groundRect = g?.rect ?: MercatorRect.squareGround(needed)
        val corners = floatArrayOf(
            f.x(groundRect.x), 0f, f.z(groundRect.y), // north-west
            f.x(groundRect.maxX), 0f, f.z(groundRect.y), // north-east
            f.x(groundRect.maxX), 0f, f.z(groundRect.maxY), // south-east
            f.x(groundRect.x), 0f, f.z(groundRect.maxY), // south-west
        )
        version += 1
        prepared = Prepared(version, opaque, transparent, balls, tags, corners, g?.bitmap)
        requestRender()
    }

    /** A dark pill with a coloured edge and white bold text, twice the size it is shown at. */
    private fun tagBitmap(text: String, argb: Int): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 60f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textWidth = textPaint.measureText(text)
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val w = (textWidth + 60).toInt()
        val h = (textHeight + 28).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shape = RectF(4f, 4f, w - 4f, h - 4f)
        val radius = h / 2f - 4f
        canvas.drawRoundRect(shape, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xBD000000.toInt() })
        canvas.drawRoundRect(shape, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb or 0xFF000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 6f
        })
        canvas.drawText(text, 30f, 14f - metrics.ascent, textPaint)
        return bitmap
    }

    // Touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                animation = null
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> centroid(event, -1).let { (x, y) -> lastCentroidX = x; lastCentroidY = y }
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(keep); lastY = event.getY(keep)
                centroid(event, event.actionIndex).let { (x, y) -> lastCentroidX = x; lastCentroidY = y }
            }
            MotionEvent.ACTION_MOVE -> {
                isAtTopView = false
                val c = camera
                if (event.pointerCount >= 2) {
                    val (x, y) = centroid(event, -1)
                    val dx = (x - lastCentroidX).toDouble()
                    val dy = (y - lastCentroidY).toDouble()
                    lastCentroidX = x; lastCentroidY = y
                    // Ground metres per pixel at the target.
                    val k = 2 * c.distance * tan(HALF_FOV) / max(width, 1)
                    val rightX = cos(c.azimuth); val rightZ = -sin(c.azimuth)
                    val forwardX = -sin(c.azimuth); val forwardZ = -cos(c.azimuth)
                    camera = c.copy(
                        targetX = c.targetX - rightX * dx * k + forwardX * dy * k,
                        targetZ = c.targetZ - rightZ * dx * k + forwardZ * dy * k,
                    )
                } else if (!scaleDetector.isInProgress) {
                    val dx = (event.x - lastX) / density
                    val dy = (event.y - lastY) / density
                    lastX = event.x; lastY = event.y
                    camera = c.copy(
                        azimuth = c.azimuth - dx * 0.008,
                        elevation = (c.elevation + dy * 0.006).coerceIn(MIN_ELEVATION, MAX_ELEVATION),
                    )
                }
                requestRender()
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

    // Drawing

    private inner class Renderer3D : Renderer {
        private lateinit var shader: SceneShader
        private var uploadedVersion = -1
        private val opaqueMeshes = mutableListOf<Pair<GpuMesh, FloatArray>>()
        private val transparentMeshes = mutableListOf<Pair<GpuMesh, FloatArray>>()
        private val balls = mutableListOf<Pair<FloatArray, FloatArray>>()
        private val ballRadii = mutableListOf<Float>()
        private var sphere: GpuMesh? = null
        private var groundMesh: GpuMesh? = null
        private var groundTexture = 0
        private var groundBitmap: Bitmap? = null
        private val tagMeshes = mutableListOf<GpuMesh>()
        private val tagTextures = mutableListOf<Int>()
        private var tags: List<Tag> = emptyList()
        private var w = 1
        private var h = 1
        private val view = FloatArray(16)
        private val projection = FloatArray(16)
        private val viewProj = FloatArray(16)
        private val model = FloatArray(16)
        private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            // A new context: everything is uploaded again.
            shader = SceneShader()
            opaqueMeshes.clear(); transparentMeshes.clear(); balls.clear(); ballRadii.clear(); tagMeshes.clear(); tagTextures.clear()
            sphere = GpuMesh().apply { set(Primitives.sphere(1f)) }
            groundMesh = null
            groundTexture = 0
            groundBitmap = null
            uploadedVersion = -1
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
            w = width; h = height
        }

        private fun upload(p: Prepared) {
            for ((m, _) in opaqueMeshes) m.release()
            for ((m, _) in transparentMeshes) m.release()
            for (m in tagMeshes) m.release()
            for (t in tagTextures) Textures.delete(t)
            opaqueMeshes.clear(); transparentMeshes.clear(); balls.clear(); ballRadii.clear(); tagMeshes.clear(); tagTextures.clear()
            for ((mesh, argb) in p.opaque) if (!mesh.isEmpty) opaqueMeshes += gpu(mesh) to rgba(argb, 1f)
            for ((mesh, argb, alpha) in p.transparent) if (!mesh.isEmpty) transparentMeshes += gpu(mesh) to rgba(argb, alpha)
            for ((position, radius, argb) in p.balls) { balls += position to rgba(argb, 1f); ballRadii += radius }
            tags = p.tags
            for (tag in p.tags) {
                tagMeshes += GpuMesh()
                tagTextures += Textures.upload(tag.bitmap, repeat = false, mipmap = true)
            }
            groundMesh?.release()
            groundMesh = GpuMesh().apply {
                set(p.groundCorners, uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), indices = intArrayOf(0, 2, 1, 0, 3, 2))
            }
            if (p.groundBitmap !== groundBitmap) {
                Textures.delete(groundTexture)
                groundTexture = p.groundBitmap?.let { Textures.upload(it, repeat = false, mipmap = true) } ?: 0
                groundBitmap = p.groundBitmap
            }
            uploadedVersion = p.version
        }

        private fun gpu(mesh: Path3DMesh) = GpuMesh().apply { set(mesh.positions, indices = mesh.indices) }

        private fun rgba(argb: Int, alpha: Float) = floatArrayOf(
            ((argb shr 16) and 0xff) / 255f, ((argb shr 8) and 0xff) / 255f, (argb and 0xff) / 255f, alpha,
        )

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            prepared?.let { if (it.version != uploadedVersion) upload(it) }

            // The camera, moving towards a top view if one was asked for.
            var c = camera
            animation?.let { a ->
                val t = ((SystemClock.uptimeMillis() - a.start).toDouble() / a.durationMs).coerceIn(0.0, 1.0)
                val e = t * t * (3 - 2 * t)
                var dAz = (a.to.azimuth - a.from.azimuth) % (2 * PI)
                if (dAz > PI) dAz -= 2 * PI
                if (dAz < -PI) dAz += 2 * PI
                c = Camera(
                    a.from.targetX + (a.to.targetX - a.from.targetX) * e,
                    a.from.targetZ + (a.to.targetZ - a.from.targetZ) * e,
                    a.from.distance + (a.to.distance - a.from.distance) * e,
                    a.from.azimuth + dAz * e,
                    a.from.elevation + (a.to.elevation - a.from.elevation) * e,
                )
                camera = c
                if (t >= 1.0) { animation = null; camera = a.to } else requestRender()
            }

            val ce = cos(c.elevation); val se = sin(c.elevation)
            val sa = sin(c.azimuth); val ca = cos(c.azimuth)
            val eye = floatArrayOf((c.targetX + c.distance * ce * sa).toFloat(), (c.distance * se).toFloat(), (c.targetZ + c.distance * ce * ca).toFloat())
            val up = floatArrayOf((-se * sa).toFloat(), ce.toFloat(), (-se * ca).toFloat())
            val aspect = w.toFloat() / max(h, 1)
            val fovy = Math.toDegrees(2 * atan(tan(HALF_FOV) / aspect)).toFloat()
            val near = max(0.5, c.distance * 0.002).toFloat()
            Matrix.perspectiveM(projection, 0, fovy, aspect, near, (c.distance * 30 + 60_000).toFloat())
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], c.targetX.toFloat(), 0f, c.targetZ.toFloat(), up[0], up[1], up[2])
            Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)

            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0.55f, 0.70f, 0.86f, 1f)
            GLES30.glDepthMask(true)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(shader.program)
            GLES30.glUniformMatrix4fv(shader.uViewProj, 1, false, viewProj, 0)
            GLES30.glUniform3f(shader.uLightDir, 0.35f, 0.8f, 0.48f)
            GLES30.glUniform3f(shader.uAmbient, 0.55f, 0.55f, 0.55f)
            GLES30.glUniform3f(shader.uSun, 0.55f, 0.55f, 0.55f)
            GLES30.glUniform3f(shader.uEye, eye[0], eye[1], eye[2])
            GLES30.glUniform2f(shader.uFog, 1e9f, 2e9f)
            GLES30.glUniform3f(shader.uFogColor, 1f, 1f, 1f)
            GLES30.glUniform2f(shader.uUvScale, 1f, 1f)
            GLES30.glUniform1i(shader.uUseVertexColor, 0)
            GLES30.glUniform1i(shader.uLit, 0)
            GLES30.glUniformMatrix4fv(shader.uModel, 1, false, identity, 0)

            // The map first; it never hides anything, nothing is below the ground.
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(false)
            groundMesh?.let { mesh ->
                if (groundTexture != 0) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, groundTexture)
                    GLES30.glUniform1i(shader.uTexture, 0)
                    GLES30.glUniform1i(shader.uUseTexture, 1)
                    GLES30.glUniform4f(shader.uColor, 1f, 1f, 1f, 1f)
                } else {
                    GLES30.glUniform1i(shader.uUseTexture, 0)
                    GLES30.glUniform4f(shader.uColor, 0.42f, 0.52f, 0.38f, 1f)
                }
                mesh.draw(shader)
            }
            GLES30.glUniform1i(shader.uUseTexture, 0)

            // Paths, ring outlines and balls.
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glDepthMask(true)
            for ((mesh, color) in opaqueMeshes) {
                GLES30.glUniform4fv(shader.uColor, 1, color, 0)
                mesh.draw(shader)
            }
            sphere?.let { s ->
                GLES30.glUniform1i(shader.uLit, 1)
                for (i in balls.indices) {
                    val (position, color) = balls[i]
                    val r = ballRadii[i]
                    Matrix.setIdentityM(model, 0)
                    Matrix.translateM(model, 0, position[0], position[1], position[2])
                    Matrix.scaleM(model, 0, r, r, r)
                    GLES30.glUniformMatrix4fv(shader.uModel, 1, false, model, 0)
                    GLES30.glUniform4fv(shader.uColor, 1, color, 0)
                    s.draw(shader)
                }
                GLES30.glUniform1i(shader.uLit, 0)
                GLES30.glUniformMatrix4fv(shader.uModel, 1, false, identity, 0)
            }

            // See-through walls, ground tracks, ring fills and drop lines.
            GLES30.glDepthMask(false)
            for ((mesh, color) in transparentMeshes) {
                GLES30.glUniform4fv(shader.uColor, 1, color, 0)
                mesh.draw(shader)
            }

            // Tags last, always on top, the same size on screen at any distance.
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUniform1i(shader.uUseTexture, 1)
            GLES30.glUniform4f(shader.uColor, 1f, 1f, 1f, 1f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glUniform1i(shader.uTexture, 0)
            val forward = normalize(floatArrayOf(c.targetX.toFloat() - eye[0], -eye[1], c.targetZ.toFloat() - eye[2]))
            val right = normalize(cross(forward, up))
            val widthPoints = max(w / density, 1f)
            val metersPerPoint = (2 * tan(HALF_FOV) / widthPoints).toFloat()
            for (i in tags.indices) {
                val tag = tags[i]
                val p = tag.position
                val dx = eye[0] - p[0]; val dy = eye[1] - p[1]; val dz = eye[2] - p[2]
                val distance = max(sqrt(dx * dx + dy * dy + dz * dz), 1f)
                val unit = distance * metersPerPoint * tag.points
                val halfH = unit / 2
                val halfW = halfH * tag.bitmap.width / tag.bitmap.height
                val lift = (tag.lift + 0.5f) * unit
                val cx = p[0] + up[0] * lift; val cy = p[1] + up[1] * lift; val cz = p[2] + up[2] * lift
                val q = FloatArray(12)
                fun corner(k: Int, sx: Float, sy: Float) {
                    q[k * 3] = cx + right[0] * sx * halfW + up[0] * sy * halfH
                    q[k * 3 + 1] = cy + right[1] * sx * halfW + up[1] * sy * halfH
                    q[k * 3 + 2] = cz + right[2] * sx * halfW + up[2] * sy * halfH
                }
                corner(0, -1f, 1f); corner(1, 1f, 1f); corner(2, 1f, -1f); corner(3, -1f, -1f)
                val mesh = tagMeshes[i]
                mesh.set(q, uvs = TAG_UVS, indices = TAG_INDICES)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tagTextures[i])
                mesh.draw(shader)
            }
            GLES30.glUniform1i(shader.uUseTexture, 0)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(true)
        }

        private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        private fun normalize(a: FloatArray): FloatArray {
            val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            return if (l < 1e-9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
        }
    }

    private companion object {
        val HALF_FOV = Math.toRadians(Path3DTopView.HORIZONTAL_FIELD_OF_VIEW_DEGREES / 2)
        /** A hair off the vertical, so north stays up. */
        const val MAX_ELEVATION = PI / 2 - 0.001
        const val MIN_ELEVATION = 0.06
        val TAG_UVS = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val TAG_INDICES = intArrayOf(0, 2, 1, 0, 3, 2)
    }
}
