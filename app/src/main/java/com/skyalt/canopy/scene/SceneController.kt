package com.skyalt.canopy.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import com.skyalt.canopy.model.AircraftLiverySettings
import com.skyalt.canopy.model.AircraftMarkingsSettings
import com.skyalt.canopy.model.AeroParameters
import com.skyalt.canopy.model.AircraftState
import com.skyalt.canopy.model.CanopyModel
import com.skyalt.canopy.model.CanopyRigGeometry
import com.skyalt.canopy.model.CanopyType
import com.skyalt.canopy.model.CloudSettings
import com.skyalt.canopy.model.TrafficAppearance
import com.skyalt.canopy.model.TrafficFrame
import com.skyalt.canopy.model.WakeSample
import com.skyalt.canopy.model.Compass
import com.skyalt.canopy.model.DEG
import com.skyalt.canopy.model.DropzoneSettings
import com.skyalt.canopy.model.FlightFrame
import com.skyalt.canopy.model.LineStyle
import com.skyalt.canopy.model.METERS_PER_FOOT
import com.skyalt.canopy.model.PathStyleSettings
import com.skyalt.canopy.model.SimConstants
import com.skyalt.canopy.model.SimulatorConfiguration
import com.skyalt.canopy.model.Vec3
import com.skyalt.canopy.model.groupedInt
import com.skyalt.canopy.model.patternGeometry
import com.skyalt.canopy.model.smoothStep
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** The views the camera button steps through. */
enum class CameraPreset(val azimuth: Double, val elevation: Double, val distance: Double) {
    CHASE(0.55, 0.26, 19.0),
    SIDE(Math.PI / 2, 0.08, 21.0),
    FRONT(Math.PI, 0.14, 19.0),
    TOP(0.0, 1.3, 32.0),
    FIRST_PERSON(0.55, 0.26, 19.0),
}

/**
 * Builds and draws the OpenGL scene: ground and target, the deforming
 * canopy and the jumper, the trail, the reference pattern, the windsock and
 * the dropzone picture, and the orbiting chase camera. Port of
 * CanopySceneController.swift. Every method runs on the GL thread.
 */
class SceneController {
    private lateinit var shader: SceneShader
    private lateinit var sky: SkyShader
    private var ready = false

    // Static meshes
    private lateinit var groundMesh: GpuMesh
    private var groundTexture = 0
    private lateinit var ringMeshes: List<Pair<GpuMesh, Int>>
    private lateinit var dropRingMesh: GpuMesh
    private lateinit var boxBody: GpuMesh
    private lateinit var boxContainer: GpuMesh
    private lateinit var boxFlap: GpuMesh
    private lateinit var headMesh: GpuMesh
    private lateinit var faceMesh: GpuMesh
    private lateinit var legMesh: GpuMesh
    private lateinit var armMesh: GpuMesh
    private lateinit var handMesh: GpuMesh

    // Dynamic meshes
    private lateinit var dropLineMesh: GpuMesh
    private lateinit var trailMesh: GpuMesh
    private val ghostMeshes = ArrayList<GpuMesh>()

    /** The chosen series' own model; see [setCanopy]. */
    private var canopyRig: GpuRig? = null
    private var canopyRigScale = 1f
    /** The series asked for, and its model on the way here from the thread that reads it. */
    private val canopyRequests = CanopyRigRequests()
    private val canopyRigModel = FloatArray(16)
    private var jumperAlpha = 1f

    private val rigModel = FloatArray(16)
    private val jumperModel = FloatArray(16)
    private val armModels = Array(4) { FloatArray(16) }
    private val handPositions = Array(2) { FloatArray(3) }
    private var dropPosition = floatArrayOf(0f, 0f, 0f)
    private val dropLine = FloatArray(6)

    // Trails
    private val trailPoints = FloatList(4096)
    private val trailColors = FloatList(4096)
    private var trailAccumulator = 0.0
    private var trailDirty = false
    private val ghostTrailPoints = ArrayList<FloatArray>()
    private var ghostDirty = false
    var pathStyle = PathStyleSettings()
        private set

    // Pattern
    private class Label(val texture: Int, val aspect: Float, val position: FloatArray, val small: Boolean)
    private class Part(val mesh: GpuMesh, val model: FloatArray, val color: FloatArray, val lit: Boolean, val vertexColor: Boolean = false)
    private var patternAir: FloatArray? = null
    private var patternGround: FloatArray? = null
    private val patternParts = ArrayList<Part>()
    private val labels = ArrayList<Label>()
    private lateinit var labelMesh: GpuMesh
    private var patternDirty = false
    private var windsockParts = ArrayList<Part>()
    private var windsockPosition: FloatArray? = null
    private var windsockScale = 1f

    // Dropzone
    private var dropzoneTexture = 0
    private var dropzoneMesh: GpuMesh? = null
    private val dropzoneModel = FloatArray(16)
    private var pendingDropzone: Pair<Bitmap?, DropzoneSettings>? = null
    private var dropzoneBitmap: Bitmap? = null
    private var dropzoneSettings = DropzoneSettings()

    // Camera
    private var azimuth = CameraPreset.CHASE.azimuth
    private var elevation = CameraPreset.CHASE.elevation
    var cameraDistance = CameraPreset.CHASE.distance
        private set
    private var currentAzimuth: Double? = null
    private var cameraTarget: Vec3? = null
    var headLookX = 0.0
    var headLookY = 0.0
    private var currentHeadX = 0.0
    private var currentHeadY = 0.0
    private val eye = FloatArray(3)
    private val lookAt = FloatArray(3)

    var firstPersonAltitude = DEFAULT_FIRST_PERSON_ALTITUDE
    var isFirstPersonAutomatic = true
    private var firstPersonChosen = false
    private var firstPersonBlend = 0.0
    private var firstPersonEngaged = false
    private var firstPersonArmed = true
    private var firstPersonLookX = 0.0
    private var firstPersonLookY = FIRST_PERSON_PITCH

    val isFirstPerson get() = firstPersonEngaged

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val scratch = FloatArray(16)
    private val scratch2 = FloatArray(16)

    // Clouds and fog
    private var cloudSettings = CloudSettings()
    private var cloudTextures = intArrayOf(0, 0)
    private var cloudTextureDensity = -1.0
    private var cloudMesh: GpuMesh? = null

    // Wakes: the youngest puffs, as x, y, z, radius and opacity.
    var showsWakes = true
    private var wakeMesh: GpuMesh? = null
    private val wakeData = FloatArray(WAKE_LIMIT * 5)
    private var wakeCount = 0

    /** False in the scenes that hide the pattern, the trail and the earlier runs. */
    var showsPaths = true

    // The scenes' airfield, the aircraft and the people on the ground
    private var airfieldVisible = false
    private class AirfieldGpu(val ground: List<GpuPart>, val paint: List<GpuPart>, val solids: List<GpuPart>)
    private var airfield: AirfieldGpu? = null
    private var aircraftVisible = false
    private var aircraftLivery = AircraftLiverySettings.DEFAULT_COLOR_HEX
    private var aircraftMarkings = AircraftMarkingsSettings()
    private var aircraftLogo: android.graphics.Bitmap? = null
    private var aircraftParts = AircraftGpu()
    private val aircraftModel = FloatArray(16)
    private val propellerModel = FloatArray(16)
    private var aircraftPlaced = false
    private var aircraftPresent = true
    private var propellerAngle = 0.0
    private var peoplePositions: List<Vec3> = emptyList()
    private var personParts: List<List<GpuPart>>? = null

    // The other canopies
    private class TrafficRig(val modelKey: String, val scale: Float, val student: Boolean) {
        val model = FloatArray(16)
        val jumper = FloatArray(16)
        val canopy = FloatArray(16)
        var variant = 0
        var visible = false
        var landed = false
    }
    /** Each series' model at no, half and full brake, built once off the GL thread and shared by every canopy flying it. */
    private val trafficShapes = java.util.concurrent.ConcurrentHashMap<String, List<List<RigPart>>>()
    private val trafficLoading = java.util.Collections.synchronizedSet(HashSet<String>())
    /** The same on the GPU, built on first draw. */
    private val trafficVariants = HashMap<String, List<GpuParts>>()
    private var trafficJumpers: List<List<GpuPart>>? = null
    private val trafficRigs = ArrayList<TrafficRig>()

    // Pending work from the engine, applied on the GL thread before drawing
    private var pendingPattern: Triple<SimulatorConfiguration, AeroParameters, Triple<String, String, String>>? = null

    /** Creates every GL object; called when the surface is created (again after a context loss). */
    fun onSurfaceCreated() {
        shader = SceneShader()
        sky = SkyShader()
        groundMesh = GpuMesh().apply { set(Primitives.groundGrid(GROUND_SIZE, GROUND_CELLS)) }
        groundTexture = Textures.upload(groundBitmap(), repeat = true, mipmap = true)
        ringMeshes = listOf(
            Triple(0f, 5f, 0xe4582b), Triple(5f, 10f, 0xf4f4f0), Triple(10f, 15f, 0xe4582b), Triple(15f, 20f, 0xf4f4f0),
        ).map { (inner, outer, hex) -> GpuMesh().apply { set(Primitives.flatRing(inner, outer)) } to hex }
        dropRingMesh = GpuMesh().apply { set(Primitives.flatRing(1.2f, 1.6f, 32)) }
        boxBody = GpuMesh().apply { set(Primitives.box(0.36f, 0.5f, 0.22f)) }
        boxContainer = GpuMesh().apply { set(Primitives.box(0.34f, 0.48f, 0.2f)) }
        boxFlap = GpuMesh().apply { set(Primitives.box(0.3f, 0.16f, 0.06f)) }
        headMesh = GpuMesh().apply { set(Primitives.sphere(0.13f)) }
        faceMesh = GpuMesh().apply { set(Primitives.sphere(0.09f)) }
        legMesh = GpuMesh().apply { set(Primitives.cone(0.06f, 0.05f, 0.7f, 16)) }
        armMesh = GpuMesh().apply { set(Primitives.cone(0.045f, 0.04f, 1f, 12)) }
        handMesh = GpuMesh().apply { set(Primitives.sphere(0.07f, 10, 14)) }
        labelMesh = GpuMesh().apply { set(Primitives.billboardQuad(4f)) }

        dropLineMesh = GpuMesh(GLES30.GL_LINES)
        trailMesh = GpuMesh()
        ghostMeshes.clear()
        labels.clear()
        windsockParts.clear()
        patternParts.clear()
        dropzoneMesh = null
        dropzoneTexture = 0
        // Everything that was built from data is rebuilt from it.
        trailDirty = true
        ghostDirty = true
        patternDirty = true
        if (pendingDropzone == null) pendingDropzone = dropzoneBitmap to dropzoneSettings
        // GL objects of the lost context are gone with it; they are built again on use.
        cloudMesh = null; cloudTextures = intArrayOf(0, 0); cloudTextureDensity = -1.0
        wakeMesh = null
        airfield = null
        aircraftParts = AircraftGpu()
        personParts = null
        trafficVariants.clear(); trafficJumpers = null
        // The flown model's buffers went with the old context; its shape stays.
        canopyRig = canopyRig?.let { GpuRig(it.rig) }
        ready = true
    }

    // MARK: - Per-frame update

    fun update(frame: FlightFrame, dt: Double) {
        if (!ready) return
        // Turbulence flutters the skins the way a stall's onset sags them.
        val flutter = frame.turbulence * (0.2 + 0.15 * sin(frame.time * 27) * sin(frame.time * 11 + 1))
        updateCanopy(frame.leftBrake, frame.rightBrake, max(frame.stall, flutter), frame.collapse)
        updateRig(frame)
        updateCamera(frame, dt)
    }

    /**
     * Flies the series' own model at the size chosen, scaled by the square
     * root of the area ratio about the harness, lines and all. The model
     * is built off the GL thread from the bundled file; until it is ready
     * the previous one keeps flying. Asked for again while it is being
     * read (every start asks twice), the series is still read only once.
     * GL thread.
     */
    fun setCanopy(model: CanopyModel, areaSquareFeet: Double) {
        canopyRigScale = sqrt(max(1.0, areaSquareFeet) / model.modelSize).toFloat()
        if (!canopyRequests.want(model.key, hung = canopyRig?.rig?.key)) return
        Thread {
            var rig: CanopyModelRig? = null
            try {
                rig = CanopyModelFiles.rig(model)
            } finally {
                canopyRequests.read(model.key, rig)
            }
        }.apply { name = "canopy-model"; start() }
    }

    private fun applyPendingRig() {
        val rig = canopyRequests.take() ?: return
        if (rig.key != canopyRig?.rig?.key) installCanopyRig(rig)
    }

    /** Hangs the model from the jumper's shoulders in place of the last. */
    private fun installCanopyRig(rig: CanopyModelRig) {
        canopyRig?.release()
        canopyRig = GpuRig(rig)
        Log.i("CanopyModel", "flying ${rig.key}")
    }

    private fun updateCanopy(dL: Double, dR: Double, stall: Double, collapse: Double) {
        applyPendingRig()
        val left = dL.toFloat()
        val right = dR.toFloat()
        val h = CanopyModelRig.HARNESS_HEIGHT
        val gpu = canopyRig
        if (gpu == null) {
            // Still loading: the hands where the toggles will be.
            val y = h + CanopyModelRig.RISER_LENGTH - CanopyModelRig.TOGGLE_DROP
            val travel = CanopyModelRig.TOGGLE_TRAVEL
            placeArms(
                floatArrayOf(-CanopyModelRig.LINK_X, y - travel * left.coerceIn(0f, 1f), 0.06f),
                floatArrayOf(CanopyModelRig.LINK_X, y - travel * right.coerceIn(0f, 1f), 0.06f),
            )
            return
        }
        val rig = gpu.rig
        rig.update(left, right, stall.toFloat(), collapse.toFloat())
        val s = canopyRigScale
        val l = rig.togglePosition(true, left)
        val r = rig.togglePosition(false, right)
        placeArms(
            floatArrayOf(l[0] * s, h + l[1] * s, l[2] * s),
            floatArrayOf(r[0] * s, h + r[1] * s, r[2] * s),
        )
    }

    /** Arms reach for the hands (in the jumper's frame): straight up with the toggles up, bending at the elbow as they come down. */
    private fun placeArms(lh: FloatArray, rh: FloatArray) {
        val le = elbow(SHOULDER_LEFT, lh, floatArrayOf(-0.3f, -1f, 0.2f))
        val re = elbow(SHOULDER_RIGHT, rh, floatArrayOf(0.3f, -1f, 0.2f))
        place(armModels[0], SHOULDER_LEFT, le)
        place(armModels[1], SHOULDER_RIGHT, re)
        place(armModels[2], le, lh)
        place(armModels[3], re, rh)
        lh.copyInto(handPositions[0]); rh.copyInto(handPositions[1])
    }

    private fun updateRig(frame: FlightFrame) {
        Matrix.setIdentityM(scratch, 0)
        Matrix.rotateM(scratch, 0, (-frame.heading / DEG).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(scratch, 0, (frame.pitch / DEG).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(scratch, 0, (-frame.bank / DEG).toFloat(), 0f, 0f, 1f)
        val l = CanopyRigGeometry.LINE_LENGTH.toFloat()
        // Offset = R·(0, L, 0): the second column of R times L.
        val ox = scratch[4] * l; val oy = scratch[5] * l; val oz = scratch[6] * l
        val px = frame.position.x.toFloat(); val py = frame.position.y.toFloat(); val pz = frame.position.z.toFloat()
        Matrix.setIdentityM(rigModel, 0)
        Matrix.translateM(rigModel, 0, px + ox, py + oy, pz + oz)
        Matrix.multiplyMM(scratch2, 0, rigModel, 0, scratch, 0)
        scratch2.copyInto(rigModel)
        Matrix.translateM(jumperModel.also { rigModel.copyInto(it) }, 0, 0f, -l, 0f)

        dropLine[0] = px; dropLine[1] = py - 1; dropLine[2] = pz
        dropLine[3] = px; dropLine[4] = 0.3f; dropLine[5] = pz
        dropLineMesh.set(dropLine, 6)
        dropPosition = floatArrayOf(px, 0.3f, pz)
    }

    private fun updateCamera(frame: FlightFrame, dt: Double) {
        currentHeadX += (headLookX - currentHeadX) * (1 - exp(-dt / 0.25))
        currentHeadY += (headLookY - currentHeadY) * (1 - exp(-dt / 0.25))
        val low = frame.altitude < firstPersonAltitude
        if (!low) firstPersonArmed = true
        val engage = firstPersonChosen || (isFirstPersonAutomatic && low && firstPersonArmed)
        if (engage && !firstPersonEngaged) {
            firstPersonLookX = 0.0; firstPersonLookY = FIRST_PERSON_PITCH
        }
        firstPersonEngaged = engage
        val step = dt / FIRST_PERSON_SECONDS
        firstPersonBlend = if (engage) min(1.0, firstPersonBlend + step) else max(0.0, firstPersonBlend - step)
        jumperAlpha = (1 - smoothStep(0.55, 0.85, firstPersonBlend)).toFloat()

        val wantedAzimuth = -frame.heading + azimuth + currentHeadX
        var current = currentAzimuth ?: wantedAzimuth
        var delta = wantedAzimuth - current
        delta = atan2(sin(delta), cos(delta))
        current += delta * (1 - exp(-dt / 0.6))
        currentAzimuth = current

        val wanted = Vec3(frame.position.x, frame.position.y + 1.5, frame.position.z)
        var target = cameraTarget ?: wanted
        target = target + (wanted - target) * (1 - exp(-dt / 0.12))
        cameraTarget = target

        val lookElevation = (elevation + currentHeadY).coerceIn(-0.05, 1.5)
        val ce = cos(lookElevation)
        var ex = target.x + cameraDistance * sin(current) * ce
        var ey = max(1.5, target.y + cameraDistance * sin(lookElevation))
        var ez = target.z + cameraDistance * cos(current) * ce
        var lx = target.x; var ly = target.y; var lz = target.z
        if (firstPersonBlend > 0) {
            val k = smoothStep(0.0, 1.0, firstPersonBlend)
            val yaw = frame.heading + firstPersonLookX - currentHeadX
            val pitch = (firstPersonLookY + currentHeadY).coerceIn(-1.3, 1.3)
            val hx = frame.position.x; val hy = frame.position.y + EYE_HEIGHT; val hz = frame.position.z
            val ahead = Compass.direction(yaw)
            val rx = ahead.x * cos(pitch); val ry = sin(pitch); val rz = ahead.z * cos(pitch)
            ex += (hx - ex) * k; ey += (hy - ey) * k; ez += (hz - ez) * k
            lx += (hx + rx * 60 - lx) * k; ly += (hy + ry * 60 - ly) * k; lz += (hz + rz * 60 - lz) * k
        }
        eye[0] = ex.toFloat(); eye[1] = ey.toFloat(); eye[2] = ez.toFloat()
        lookAt[0] = lx.toFloat(); lookAt[1] = ly.toFloat(); lookAt[2] = lz.toFloat()

        windsockPosition?.let { p ->
            val d = dist(eye, p)
            windsockScale = (d / 120).coerceIn(1f, 4f)
        }
    }

    // MARK: - Trail

    fun appendTrailPoint(position: Vec3, sinkRate: Double, dt: Double) {
        trailAccumulator += dt
        if (trailAccumulator < 0.08 || trailPoints.size / 3 >= TRAIL_MAXIMUM) return
        trailAccumulator = 0.0
        trailPoints.add(position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
        val c = sinkColor(sinkRate)
        trailColors.add(c[0]); trailColors.add(c[1]); trailColors.add(c[2]); trailColors.add(1f)
        trailDirty = true
    }

    fun resetTrail() {
        trailPoints.clear(); trailColors.clear(); trailAccumulator = 0.0; trailDirty = true
    }

    fun archiveTrail() {
        if (trailPoints.size < 6) return
        ghostTrailPoints += trailPoints.data.copyOf(trailPoints.size)
        if (ghostTrailPoints.size > SimConstants.MAXIMUM_STORED_RUNS) ghostTrailPoints.removeAt(0)
        ghostDirty = true
    }

    fun clearGhostTrails() {
        ghostTrailPoints.clear(); ghostDirty = true
    }

    fun applyPathStyle(style: PathStyleSettings) {
        if (style == pathStyle) return
        pathStyle = style
        trailDirty = true; ghostDirty = true; patternDirty = true
    }

    private fun rebuildTrails() {
        if (trailDirty) {
            trailDirty = false
            val points = trailPoints.data.copyOf(trailPoints.size)
            val colors = if (pathStyle.trailColoredBySink) listOf(trailColors.data.copyOf(trailColors.size)) else null
            val mesh = Primitives.tubes(listOf(points), (pathStyle.trail.widthMeters / 2).toFloat(), colors)
            if (mesh != null) trailMesh.set(mesh) else trailMesh.set(FloatArray(0), 0)
        }
        if (ghostDirty) {
            ghostDirty = false
            while (ghostMeshes.size < ghostTrailPoints.size) ghostMeshes += GpuMesh()
            for ((i, points) in ghostTrailPoints.withIndex()) {
                val mesh = Primitives.tubes(listOf(points), (pathStyle.history.widthMeters / 2).toFloat())
                if (mesh != null) ghostMeshes[i].set(mesh)
            }
            for (i in ghostTrailPoints.size until ghostMeshes.size) ghostMeshes[i].set(FloatArray(0), 0)
        }
    }

    // MARK: - Pattern and windsock

    fun rebuildPattern(configuration: SimulatorConfiguration, aero: AeroParameters, legLabels: Triple<String, String, String>) {
        pendingPattern = Triple(configuration, AeroParameters(configuration.performance), legLabels)
    }

    private fun applyPendingPattern() {
        val pending = pendingPattern
        if (pending != null) {
            pendingPattern = null
            val (configuration, aero, legLabels) = pending
            for (label in labels) Textures.delete(label.texture)
            labels.clear()
            for (part in windsockParts) part.mesh.release()
            windsockParts = ArrayList()
            windsockPosition = null
            if (configuration.windSpeed > 0.05) {
                windsockParts = windsock(configuration.windSpeed, configuration.windDirectionDegrees)
                windsockPosition = floatArrayOf(configuration.windsockEastMeters.toFloat(), 0f, configuration.windsockSouthMeters.toFloat())
            }
            val hand = configuration.referencePattern.hand
            if (hand == null) {
                patternAir = null; patternGround = null
            } else {
                val pattern = configuration.patternGeometry(aero, hand)
                val air = FloatArray(12); val ground = FloatArray(12)
                for ((i, p) in pattern.points.withIndex()) {
                    air[i * 3] = p.x.toFloat(); air[i * 3 + 1] = p.y.toFloat(); air[i * 3 + 2] = p.z.toFloat()
                    ground[i * 3] = p.x.toFloat(); ground[i * 3 + 1] = 0f; ground[i * 3 + 2] = p.z.toFloat()
                }
                patternAir = air; patternGround = ground
                for (i in 0 until 3) {
                    val y = pattern.points[i].y
                    val text = "${groupedInt((y / METERS_PER_FOOT).roundToLong())} ft · ${groupedInt(y.roundToLong())} m"
                    labels += makeLabel(text, floatArrayOf(air[i * 3], air[i * 3 + 1] + 8, air[i * 3 + 2]), small = false)
                }
                val names = listOf(legLabels.first, legLabels.second, legLabels.third)
                for (i in 0 until 3) {
                    val mx = (ground[i * 3] + ground[i * 3 + 3]) / 2
                    val mz = (ground[i * 3 + 2] + ground[i * 3 + 5]) / 2
                    labels += makeLabel(names[i], floatArrayOf(mx, 6f, mz), small = true)
                }
            }
            patternDirty = true
        }
        if (patternDirty) {
            patternDirty = false
            for (part in patternParts) part.mesh.release()
            patternParts.clear()
            val air = patternAir ?: return
            val groundRaw = patternGround ?: return
            val airStyle = pathStyle.pattern
            val groundStyle = pathStyle.patternGround
            val airRadius = (airStyle.widthMeters / 2).toFloat()
            val groundRadius = (groundStyle.widthMeters / 2).toFloat()
            val ground = groundRaw.copyOf().also { for (i in 0 until 4) it[i * 3 + 1] = groundRadius + 0.05f }
            fun add(mesh: MeshData?, style: LineStyle, alphaScale: Double = 1.0, model: FloatArray = identity) {
                if (mesh == null) return
                patternParts += Part(GpuMesh().apply { set(mesh) }, model, color(style, alphaScale), lit = false)
            }
            add(Primitives.tubes(listOf(air), airRadius), airStyle)
            add(Primitives.tubes(Primitives.dashedRuns(ground, 6f, 4f), groundRadius), groundStyle)
            for (i in 0 until 3) {
                val run = floatArrayOf(air[i * 3], air[i * 3 + 1], air[i * 3 + 2], ground[i * 3], ground[i * 3 + 1], ground[i * 3 + 2])
                add(Primitives.tubes(Primitives.dashedRuns(run, 6f, 4f), airRadius * 0.6f), airStyle, 0.6)
                val ringModel = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, ground[i * 3], ground[i * 3 + 1], ground[i * 3 + 2]) }
                patternParts += Part(patternRingMeshCopy(), ringModel, color(groundStyle, 1.0), lit = false)
                val dotModel = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, air[i * 3], air[i * 3 + 1], air[i * 3 + 2]) }
                patternParts += Part(dotMeshCopy(), dotModel, color(airStyle.copy(opacity = 1.0), 1.0), lit = false)
            }
        }
    }

    // Each pattern part owns its mesh, so a rebuild releases them all.
    private fun patternRingMeshCopy() = GpuMesh().apply { set(Primitives.flatRing(5f, 7f, 48)) }
    private fun dotMeshCopy() = GpuMesh().apply { set(Primitives.sphere(2f, 12, 16)) }

    private fun color(style: LineStyle, alphaScale: Double): FloatArray {
        val c = CanopyMeshBuilder.rgb(style.colorHex)
        c[3] = (style.opacity * alphaScale).toFloat()
        return c
    }

    /** A pole with a five-band orange and white sock, streaming downwind. */
    private fun windsock(speed: Double, directionFromDegrees: Double): ArrayList<Part> {
        val parts = ArrayList<Part>()
        val pole = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, 0f, (WINDSOCK_POLE / 2).toFloat(), 0f) }
        parts += Part(GpuMesh().apply { set(Primitives.cone(0.22f, 0.22f, WINDSOCK_POLE.toFloat(), 12)) }, pole, floatArrayOf(0.92f, 0.92f, 0.92f, 1f), lit = true)
        val theta = directionFromDegrees * DEG
        val sock = FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            Matrix.translateM(it, 0, 0f, WINDSOCK_POLE.toFloat(), 0f)
            Matrix.rotateM(it, 0, ((-Math.PI / 2 - theta) / DEG).toFloat(), 0f, 1f, 0f)
        }
        val bands = 5
        val droop = Math.PI / 2 * (1 - min(1.0, speed / WINDSOCK_FULL_SPEED))
        val bandLength = WINDSOCK_LENGTH / bands
        var parent = sock
        for (band in 0 until bands) {
            val pitch = if (band == 0) 0.5 * droop else 0.125 * droop
            val joint = parent.copyOf().also { Matrix.rotateM(it, 0, (-pitch / DEG).toFloat(), 0f, 0f, 1f) }
            if (band == 0) {
                val ring = joint.copyOf().also { Matrix.rotateM(it, 0, -90f, 0f, 0f, 1f) }
                parts += Part(GpuMesh().apply { set(Primitives.torus(WINDSOCK_MOUTH.toFloat(), 0.08f)) }, ring, floatArrayOf(0.85f, 0.85f, 0.85f, 1f), lit = true)
            }
            val startRadius = WINDSOCK_MOUTH * (1 - 0.5 * band / bands)
            val endRadius = WINDSOCK_MOUTH * (1 - 0.5 * (band + 1) / bands)
            val cone = joint.copyOf().also {
                Matrix.translateM(it, 0, (bandLength / 2).toFloat(), 0f, 0f)
                Matrix.rotateM(it, 0, -90f, 0f, 0f, 1f)
            }
            val mesh = Primitives.cone(endRadius.toFloat(), startRadius.toFloat(), bandLength.toFloat(), 20, capBottom = false, capTop = band == bands - 1)
            val cloth = if (band % 2 == 0) floatArrayOf(1f, 0.42f, 0.05f, 1f) else floatArrayOf(0.96f, 0.96f, 0.96f, 1f)
            parts += Part(GpuMesh().apply { set(mesh) }, cone, cloth, lit = true)
            parent = joint.copyOf().also { Matrix.translateM(it, 0, bandLength.toFloat(), 0f, 0f) }
        }
        return parts
    }

    private fun makeLabel(text: String, position: FloatArray, small: Boolean): Label {
        val bitmap = Bitmap.createBitmap(512, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(217, 14, 26, 40) }
        canvas.drawRoundRect(RectF(4f, 4f, 508f, 124f), 24f, 24f, bg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        var size = 64f
        while (paint.measureText(text) > 480 && size > 24) { size -= 2; paint.textSize = size }
        val y = 64f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, 256f, y, paint)
        val texture = Textures.upload(bitmap, repeat = false, mipmap = true)
        bitmap.recycle()
        return Label(texture, 4f, position, small)
    }

    // MARK: - Dropzone picture

    fun setDropzone(bitmap: Bitmap?, settings: DropzoneSettings) {
        pendingDropzone = bitmap to settings
    }

    private fun applyPendingDropzone() {
        val pending = pendingDropzone ?: return
        pendingDropzone = null
        val (bitmap, settings) = pending
        val newImage = bitmap !== dropzoneBitmap || dropzoneTexture == 0
        dropzoneBitmap = bitmap
        dropzoneSettings = settings
        if (bitmap == null) {
            Textures.delete(dropzoneTexture); dropzoneTexture = 0; dropzoneMesh = null
            return
        }
        if (newImage) {
            Textures.delete(dropzoneTexture)
            dropzoneTexture = Textures.upload(bitmap, repeat = false, mipmap = true)
        }
        val width = settings.widthMeters.toFloat()
        val height = width * bitmap.height / max(1, bitmap.width)
        dropzoneMesh = (dropzoneMesh ?: GpuMesh()).apply { set(Primitives.groundPlane(width, height)) }
        Matrix.setIdentityM(dropzoneModel, 0)
        Matrix.translateM(dropzoneModel, 0, settings.eastOffsetMeters.toFloat(), 0.04f, settings.southOffsetMeters.toFloat())
        // Counter-clockwise seen from above, as in the editor.
        Matrix.rotateM(dropzoneModel, 0, settings.rotationDegrees.toFloat(), 0f, 1f, 0f)
    }

    // MARK: - Camera control

    fun applyCameraPreset(preset: CameraPreset) {
        if (preset == CameraPreset.FIRST_PERSON) {
            firstPersonChosen = true
            if (!firstPersonEngaged) { firstPersonLookX = 0.0; firstPersonLookY = FIRST_PERSON_PITCH }
            return
        }
        firstPersonChosen = false
        if (firstPersonEngaged) firstPersonArmed = false
        azimuth = preset.azimuth
        elevation = preset.elevation
        cameraDistance = preset.distance
    }

    fun orbit(dx: Double, dy: Double) {
        if (firstPersonEngaged) {
            firstPersonLookX += dx * 0.006
            firstPersonLookY = (firstPersonLookY - dy * 0.005).coerceIn(-1.3, 1.3)
        } else {
            azimuth -= dx * 0.006
            elevation = (elevation + dy * 0.005).coerceIn(-0.05, 1.5)
        }
    }

    fun zoom(distance: Double) {
        cameraDistance = distance.coerceIn(6.0, 400.0)
    }

    // MARK: - Drawing

    fun render(width: Int, height: Int) {
        if (!ready) return
        applyPendingPattern()
        applyPendingDropzone()
        rebuildTrails()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.788f, 0.847f, 0.902f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        sky.draw()

        Matrix.perspectiveM(projection, 0, 50f, width.toFloat() / max(1, height), 0.25f, 8000f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], lookAt[0], lookAt[1], lookAt[2], 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)

        GLES30.glUseProgram(shader.program)
        GLES30.glUniformMatrix4fv(shader.uViewProj, 1, false, viewProj, 0)
        val l = sqrt(60f * 60 + 90f * 90 + 30f * 30)
        GLES30.glUniform3f(shader.uLightDir, 60f / l, 90f / l, 30f / l)
        GLES30.glUniform3f(shader.uAmbient, 0.62f, 0.68f, 0.64f)
        GLES30.glUniform3f(shader.uSun, 0.85f, 0.85f, 0.85f)
        GLES30.glUniform3f(shader.uEye, eye[0], eye[1], eye[2])
        // Inside the cloud layer the fog closes in with the density: a few
        // tens of metres in a solid overcast, a few hundred in a thin one.
        if (cloudSettings.contains(eye[1].toDouble())) {
            val thin = 1 - cloudSettings.density
            GLES30.glUniform2f(shader.uFog, 2f, (30 + 500 * thin * thin).toFloat())
            GLES30.glUniform3f(shader.uFogColor, 0.875f, 0.89f, 0.91f)
        } else {
            GLES30.glUniform2f(shader.uFog, 250f, 2600f)
            GLES30.glUniform3f(shader.uFogColor, 0.788f, 0.847f, 0.902f)
        }
        GLES30.glUniform1i(shader.uTexture, 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        // The ground.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, groundTexture)
        draw(groundMesh, identity, GROUND_WHITE, lit = true, texture = true, uvScale = 200f)

        // What lies flat on it, each painted over the last with no depth
        // test and no depth of its own. These are centimetres above the
        // ground and each other, less than the depth buffer tells apart
        // from flying height once a GPU has placed rectangles this large:
        // with the test on, the dropzone picture came and went from frame
        // to frame. Nothing is under the ground for them to cover wrongly,
        // and everything that stands on it is drawn after them.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val field = if (airfieldVisible) airfieldParts() else null
        if (field != null) for (part in field.ground) drawPart(part)
        dropzoneMesh?.takeIf { field == null }?.let {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dropzoneTexture)
            draw(it, dropzoneModel, WHITE, lit = false, texture = true)
        }
        val ringModel = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, 0f, 0.2f, 0f) }
        for ((mesh, hex) in ringMeshes) draw(mesh, ringModel, CanopyMeshBuilder.rgb(hex), lit = false)
        val dropModel = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, dropPosition[0], dropPosition[1], dropPosition[2]) }
        draw(dropRingMesh, dropModel, floatArrayOf(0.055f, 0.102f, 0.157f, 0.45f), lit = false)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // The runway's paint and the pattern: lines with a thickness, pulled forward in depth.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        if (field != null) {
            GLES30.glPolygonOffset(-1f, -2f)
            for (part in field.paint) drawPart(part)
        }
        GLES30.glPolygonOffset(-3f, -6f)
        if (showsPaths) for (part in patternParts) draw(part.mesh, part.model, part.color, part.lit)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)

        drawAirfieldAndCrowd()

        // Windsock
        windsockPosition?.let { p ->
            val root = FloatArray(16).also {
                Matrix.setIdentityM(it, 0)
                Matrix.translateM(it, 0, p[0], p[1], p[2])
                Matrix.scaleM(it, 0, windsockScale, windsockScale, windsockScale)
            }
            for (part in windsockParts) {
                Matrix.multiplyMM(scratch, 0, root, 0, part.model, 0)
                draw(part.mesh, scratch, part.color, part.lit)
            }
        }

        // Trails
        if (showsPaths && !trailMesh.isEmpty) {
            val style = pathStyle.trail
            val c = if (pathStyle.trailColoredBySink) floatArrayOf(1f, 1f, 1f, style.opacity.toFloat()) else color(style, 1.0)
            draw(trailMesh, identity, c, lit = false, vertexColor = pathStyle.trailColoredBySink)
        }
        if (showsPaths) for (i in ghostTrailPoints.indices) draw(ghostMeshes[i], identity, color(pathStyle.history, 1.0), lit = false)

        // The rig
        canopyRig?.let { drawCanopyRig(it) }
        draw(dropLineMesh, identity, floatArrayOf(0.055f, 0.102f, 0.157f, 0.35f), lit = false)
        if (jumperAlpha > 0.01f) drawJumper()
        drawTraffic()

        // See-through things last, without writing depth.
        drawWakes()
        drawClouds()

        // Labels on top, facing the camera.
        if (showsPaths && labels.isNotEmpty()) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            for (label in labels) {
                val d = dist(eye, label.position)
                val width = (d * if (label.small) 0.07f else 0.1f).coerceIn(6f, 120f)
                billboard(scratch, label.position, width)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, label.texture)
                draw(labelMesh, scratch, WHITE, lit = false, texture = true)
            }
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
    }

    /** The series' model under the jumper at the harness, scaled to the size flown; the toggles slide down the brake lines. */
    private fun drawCanopyRig(gpu: GpuRig) {
        gpu.sync()
        val s = canopyRigScale
        jumperModel.copyInto(canopyRigModel)
        Matrix.translateM(canopyRigModel, 0, 0f, CanopyModelRig.HARNESS_HEIGHT, 0f)
        Matrix.scaleM(canopyRigModel, 0, s, s, s)
        gpu.forEachPart { mesh, part ->
            val model = if (part.offsetY != 0f) {
                canopyRigModel.copyInto(scratch2)
                Matrix.translateM(scratch2, 0, 0f, part.offsetY, 0f)
                scratch2
            } else canopyRigModel
            draw(mesh, model, WHITE, lit = true, vertexColor = true)
        }
    }

    // MARK: - Clouds

    /** The cloud layer: a textured plane at its base and one at its top; nothing at zero density. */
    fun setClouds(settings: CloudSettings) {
        cloudSettings = settings
    }

    private fun drawClouds() {
        val settings = cloudSettings
        if (!settings.isVisible) return
        if (cloudTextureDensity != settings.density || cloudTextures[0] == 0) {
            for (t in cloudTextures) Textures.delete(t)
            cloudTextures = intArrayOf(
                Textures.uploadRaw(Models.cloudTexture(settings.density, floatArrayOf(0.83f, 0.85f, 0.88f)), repeat = true),
                Textures.uploadRaw(Models.cloudTexture(settings.density, floatArrayOf(0.97f, 0.97f, 0.98f)), repeat = true),
            )
            cloudTextureDensity = settings.density
        }
        val mesh = cloudMesh ?: GpuMesh().apply { set(Primitives.groundPlane(12_000f, 12_000f)) }.also { cloudMesh = it }
        val alpha = (0.65 + 0.35 * settings.density).toFloat()
        GLES30.glDepthMask(false)
        for ((i, altitude) in listOf(settings.baseAltitudeMeters, settings.topAltitudeMeters).withIndex()) {
            val model = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, 0f, altitude.toFloat(), 0f) }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cloudTextures[i])
            draw(mesh, model, floatArrayOf(1f, 1f, 1f, alpha), lit = false, texture = true, uvScale = 16f)
        }
        GLES30.glDepthMask(true)
    }

    // MARK: - Wakes

    /** The youngest wake puffs as faint grey spheres that grow and fade with age. */
    fun updateWakes(samples: List<WakeSample>, time: Double) {
        if (!showsWakes) { wakeCount = 0; return }
        val count = min(samples.size, WAKE_LIMIT)
        val offset = samples.size - count
        var n = 0
        for (i in 0 until count) {
            val sample = samples[offset + i]
            val strength = sample.strength(time)
            if (strength <= 0.03) continue
            val c = sample.centre(time)
            wakeData[n * 5] = c.x.toFloat(); wakeData[n * 5 + 1] = c.y.toFloat(); wakeData[n * 5 + 2] = c.z.toFloat()
            wakeData[n * 5 + 3] = sample.radius(time).toFloat()
            wakeData[n * 5 + 4] = (0.16 * strength).toFloat()
            n++
        }
        wakeCount = n
    }

    private fun drawWakes() {
        if (!showsWakes || wakeCount == 0) return
        val mesh = wakeMesh ?: GpuMesh().apply { set(Primitives.sphere(1f, 6, 10)) }.also { wakeMesh = it }
        GLES30.glDepthMask(false)
        val model = FloatArray(16)
        for (i in 0 until wakeCount) {
            val r = wakeData[i * 5 + 3]
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, wakeData[i * 5], wakeData[i * 5 + 1], wakeData[i * 5 + 2])
            Matrix.scaleM(model, 0, r, r, r)
            draw(mesh, model, floatArrayOf(0.62f, 0.62f, 0.62f, wakeData[i * 5 + 4]), lit = false)
        }
        GLES30.glDepthMask(true)
    }

    // MARK: - Airfield, aircraft and people

    /** Shows the scenes' airfield in place of the dropzone picture, or hides it again. */
    fun setAirfield(visible: Boolean) {
        airfieldVisible = visible
    }

    /** The colour of the aircraft's wings and tail, 0xRRGGBB, painted on before the next frame. GL thread. */
    fun setAircraftLivery(hex: Int) {
        aircraftLivery = hex
    }

    /** The registration and the logo on the aircraft's sides, put on before the next frame. GL thread. */
    fun setAircraftMarkings(markings: AircraftMarkingsSettings, logo: android.graphics.Bitmap?) {
        aircraftMarkings = markings
        aircraftLogo = logo
    }

    fun setAircraft(visible: Boolean) {
        aircraftVisible = visible
        if (!visible) aircraftPlaced = false
    }

    fun updateAircraft(state: AircraftState) {
        aircraftPresent = state.isPresent
        Matrix.setIdentityM(aircraftModel, 0)
        Matrix.translateM(aircraftModel, 0, state.position.x.toFloat(), state.position.y.toFloat(), state.position.z.toFloat())
        Matrix.rotateM(aircraftModel, 0, (-state.heading / DEG).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(aircraftModel, 0, (state.pitch / DEG).toFloat(), 1f, 0f, 0f)
        propellerAngle = (propellerAngle + state.speed * 0.04) % (2 * Math.PI)
        aircraftPlaced = true
    }

    /** People standing on the landing area: the first on the target in red, the rest in yellow. */
    fun setPeople(positions: List<Vec3>) {
        peoplePositions = positions
    }

    /** The airfield on the GPU, built on first use. */
    private fun airfieldParts(): AirfieldGpu = airfield ?: Models.airfield().let {
        AirfieldGpu(GpuPart.of(it.ground), GpuPart.of(it.paint), GpuPart.of(it.solids))
    }.also { airfield = it }

    private fun drawAirfieldAndCrowd() {
        if (airfieldVisible) for (part in airfieldParts().solids) drawPart(part)
        if (peoplePositions.isNotEmpty()) {
            val kinds = personParts ?: listOf(GpuPart.of(Models.person(0xe4582b)), GpuPart.of(Models.person(0xf2c12e))).also { personParts = it }
            val root = FloatArray(16)
            for ((i, p) in peoplePositions.withIndex()) {
                Matrix.setIdentityM(root, 0)
                Matrix.translateM(root, 0, p.x.toFloat(), 0f, p.z.toFloat())
                for (part in kinds[if (i == 0) 0 else 1]) drawPart(part, root)
            }
        }
        // The P750XL once its model has loaded (it loads in the background), the registration and logo pulled forward over its skin.
        val aircraft = aircraftParts
        if (aircraftVisible && aircraftPlaced && aircraftPresent &&
            aircraft.sync(aircraftLivery, aircraftMarkings.registration, aircraftLogo, aircraftMarkings.logo)
        ) {
            for (part in aircraft.body) drawPart(part, aircraftModel)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-1f, -2f)
            for (part in aircraft.decals) drawPart(part, aircraftModel)
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            aircraftModel.copyInto(propellerModel)
            Matrix.translateM(propellerModel, 0, aircraft.hub.x, aircraft.hub.y, aircraft.hub.z)
            Matrix.rotateM(propellerModel, 0, Math.toDegrees(propellerAngle).toFloat(), 0f, 0f, 1f)
            for (part in aircraft.propeller) drawPart(part, propellerModel)
        }
    }

    // MARK: - Traffic

    /** A rig for each of the other canopies: its series' model at its size, a jumper under it. */
    fun setTraffic(appearances: List<TrafficAppearance>) {
        trafficRigs.clear()
        for (appearance in appearances) {
            val model = CanopyModel.forKey(appearance.modelKey) ?: CanopyModel.fallback(CanopyType.STUDENT)
            trafficRigs += TrafficRig(model.key, sqrt(max(1.0, appearance.areaSquareFeet) / model.modelSize).toFloat(), appearance.isStudent)
            loadTrafficModel(model)
        }
    }

    /** Builds the series' three brake positions off the GL thread, once. */
    private fun loadTrafficModel(model: CanopyModel) {
        if (trafficShapes.containsKey(model.key) || !trafficLoading.add(model.key)) return
        Thread {
            val rig = CanopyModelFiles.rig(model)
            if (rig != null) {
                trafficShapes[model.key] = listOf(0f, 0.5f, 1f).map { brake ->
                    rig.update(brake, brake, 0f, 0f)
                    rig.snapshot()
                }
            }
            trafficLoading.remove(model.key)
        }.apply { name = "canopy-traffic"; start() }
    }

    /**
     * Places every other canopy for the frame: the rig at the canopy's
     * centre, turned and banked like the jumper's, the wing at the nearest
     * of three brake positions; a landed one keeps only its jumper.
     */
    fun updateTraffic(frames: List<TrafficFrame>) {
        val l = CanopyRigGeometry.LINE_LENGTH.toFloat()
        val r = FloatArray(16)
        for ((i, rig) in trafficRigs.withIndex()) {
            val frame = frames.getOrNull(i)
            if (frame == null) { rig.visible = false; continue }
            rig.visible = true
            rig.landed = frame.landed
            Matrix.setIdentityM(r, 0)
            Matrix.rotateM(r, 0, (-frame.heading / DEG).toFloat(), 0f, 1f, 0f)
            if (!frame.landed) {
                Matrix.rotateM(r, 0, (frame.pitch / DEG).toFloat(), 1f, 0f, 0f)
                Matrix.rotateM(r, 0, (-frame.bank / DEG).toFloat(), 0f, 0f, 1f)
            }
            val px = frame.position.x.toFloat() + r[4] * l
            val py = frame.position.y.toFloat() + r[5] * l
            val pz = frame.position.z.toFloat() + r[6] * l
            Matrix.setIdentityM(scratch, 0)
            Matrix.translateM(scratch, 0, px, py, pz)
            Matrix.multiplyMM(rig.model, 0, scratch, 0, r, 0)
            rig.model.copyInto(rig.jumper)
            Matrix.translateM(rig.jumper, 0, 0f, -l, 0f)
            rig.jumper.copyInto(rig.canopy)
            Matrix.translateM(rig.canopy, 0, 0f, CanopyModelRig.HARNESS_HEIGHT, 0f)
            Matrix.scaleM(rig.canopy, 0, rig.scale, rig.scale, rig.scale)
            val brake = (frame.leftBrake + frame.rightBrake) / 2
            rig.variant = if (brake < 0.25) 0 else if (brake < 0.75) 1 else 2
        }
    }

    private fun drawTraffic() {
        if (trafficRigs.isEmpty()) return
        val jumpers = trafficJumpers ?: listOf(GpuPart.of(Models.trafficJumper(0x3b4f6b)), GpuPart.of(Models.trafficJumper(0x1a1f26))).also { trafficJumpers = it }
        for (rig in trafficRigs) {
            if (!rig.visible) continue
            if (!rig.landed) {
                val variants = trafficVariants[rig.modelKey]
                    ?: trafficShapes[rig.modelKey]?.map { shape -> GpuParts(shape).also { it.sync() } }?.also { trafficVariants[rig.modelKey] = it }
                variants?.getOrNull(rig.variant)?.forEachPart { mesh, part ->
                    val model = if (part.offsetY != 0f) {
                        rig.canopy.copyInto(scratch2)
                        Matrix.translateM(scratch2, 0, 0f, part.offsetY, 0f)
                        scratch2
                    } else rig.canopy
                    draw(mesh, model, WHITE, lit = true, vertexColor = true)
                }
            }
            for (part in jumpers[if (rig.student) 0 else 1]) drawPart(part, rig.jumper)
        }
    }

    private val partScratch = FloatArray(16)

    private fun drawPart(part: GpuPart, parent: FloatArray? = null) {
        val model = if (parent != null) {
            Matrix.multiplyMM(partScratch, 0, parent, 0, part.model, 0)
            partScratch
        } else part.model
        if (part.texture != 0) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, part.texture)
        draw(part.mesh, model, part.color, part.lit, texture = part.texture != 0)
    }

    private fun drawJumper() {
        val a = jumperAlpha
        val suit = floatArrayOf(0.169f, 0.227f, 0.31f, a)
        draw(boxBody, jumperModel, suit, lit = true)
        draw(headMesh, child(jumperModel, 0f, 0.42f, 0f), CanopyMeshBuilder.rgb(0xe4582b).also { it[3] = a }, lit = true)
        draw(faceMesh, child(jumperModel, 0f, 0.4f, -0.08f), CanopyMeshBuilder.rgb(0xd9a98a).also { it[3] = a }, lit = true)
        draw(legMesh, child(jumperModel, -0.1f, -0.6f, 0f), suit, lit = true)
        draw(legMesh, child(jumperModel, 0.1f, -0.6f, 0f), suit, lit = true)
        draw(boxContainer, child(jumperModel, 0f, -0.02f, 0.21f), CanopyMeshBuilder.rgb(0x3a3f47).also { it[3] = a }, lit = true)
        draw(boxFlap, child(jumperModel, 0f, 0.13f, 0.33f), CanopyMeshBuilder.rgb(0x8a9099).also { it[3] = a }, lit = true)
        for (m in armModels) {
            Matrix.multiplyMM(scratch, 0, jumperModel, 0, m, 0)
            draw(armMesh, scratch, suit, lit = true)
        }
        val yellow = CanopyMeshBuilder.rgb(0xffd23f).also { it[3] = a }
        for (h in handPositions) draw(handMesh, child(jumperModel, h[0], h[1], h[2]), yellow, lit = true)
    }

    private val childScratch = FloatArray(16)
    private fun child(parent: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        parent.copyInto(childScratch)
        Matrix.translateM(childScratch, 0, x, y, z)
        return childScratch
    }

    private fun billboard(out: FloatArray, p: FloatArray, width: Float) {
        // The view's rotation inverted: its rows are the camera's axes.
        Matrix.setIdentityM(out, 0)
        out[0] = view[0] * width; out[1] = view[4] * width; out[2] = view[8] * width
        out[4] = view[1] * width; out[5] = view[5] * width; out[6] = view[9] * width
        out[8] = view[2]; out[9] = view[6]; out[10] = view[10]
        out[12] = p[0]; out[13] = p[1]; out[14] = p[2]
    }

    private fun draw(
        mesh: GpuMesh,
        model: FloatArray,
        color: FloatArray,
        lit: Boolean,
        vertexColor: Boolean = false,
        texture: Boolean = false,
        uvScale: Float = 1f,
    ) {
        if (mesh.isEmpty) return
        GLES30.glUniformMatrix4fv(shader.uModel, 1, false, model, 0)
        GLES30.glUniform4f(shader.uColor, color[0], color[1], color[2], color[3])
        GLES30.glUniform1i(shader.uUseVertexColor, if (vertexColor && mesh.hasColors) 1 else 0)
        GLES30.glUniform1i(shader.uUseTexture, if (texture) 1 else 0)
        GLES30.glUniform2f(shader.uUvScale, uvScale, uvScale)
        GLES30.glUniform1i(shader.uLit, if (lit && mesh.hasNormals) 1 else 0)
        mesh.draw(shader)
    }

    companion object {
        val DEFAULT_FIRST_PERSON_ALTITUDE = 100 * METERS_PER_FOOT
        const val FIRST_PERSON_MIN = 3.0
        const val FIRST_PERSON_MAX = 300.0
        const val FIRST_PERSON_SECONDS = 3.0
        val FIRST_PERSON_PITCH = -10 * DEG
        const val EYE_HEIGHT = 0.45
        const val TRAIL_MAXIMUM = 4000
        const val WAKE_LIMIT = 900
        const val WINDSOCK_POLE = 10.0
        const val WINDSOCK_LENGTH = 10.0
        const val WINDSOCK_MOUTH = 1.4
        const val WINDSOCK_FULL_SPEED = 7.7
        /** The ground: ten kilometres square, in squares of 100 m (see [Primitives.groundGrid]). */
        private const val GROUND_SIZE = 10_000f
        private const val GROUND_CELLS = 100
        private const val UPPER_ARM = 0.3f
        private const val FOREARM = 0.3f
        private val SHOULDER_LEFT = floatArrayOf(-0.2f, 0.2f, 0f)
        private val SHOULDER_RIGHT = floatArrayOf(0.2f, 0.2f, 0f)
        private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
        private val GROUND_WHITE = floatArrayOf(1f, 1f, 1f, 1f)

        fun dist(a: FloatArray, b: FloatArray): Float {
            val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun sinkColor(sinkRate: Double): FloatArray {
            val t = (sinkRate / 9).coerceIn(0.0, 1.0).toFloat()
            val a = if (t < 0.45f) floatArrayOf(1f, 0.88f, 0.7f) else floatArrayOf(0.89f, 0.35f, 0.17f)
            val b = if (t < 0.45f) floatArrayOf(0.89f, 0.35f, 0.17f) else floatArrayOf(0.35f, 0.1f, 0.06f)
            val u = if (t < 0.45f) t / 0.45f else (t - 0.45f) / 0.55f
            return floatArrayOf(a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u)
        }

        /** Where the elbow goes for a hand: bones meet on the side [bend] points to. */
        fun elbow(shoulder: FloatArray, hand: FloatArray, bend: FloatArray): FloatArray {
            val a = UPPER_ARM; val b = FOREARM
            val vx = hand[0] - shoulder[0]; val vy = hand[1] - shoulder[1]; val vz = hand[2] - shoulder[2]
            val reach = sqrt(vx * vx + vy * vy + vz * vz)
            val dx: Float; val dy: Float; val dz: Float
            if (reach > 1e-6f) { dx = vx / reach; dy = vy / reach; dz = vz / reach } else { dx = 0f; dy = 1f; dz = 0f }
            if (reach >= a + b - 0.002f) return floatArrayOf(shoulder[0] + dx * a, shoulder[1] + dy * a, shoulder[2] + dz * a)
            val d = reach.coerceIn(abs(a - b) + 0.001f, a + b - 0.001f)
            val along = (a * a - b * b + d * d) / (2 * d)
            val out = sqrt(max(0f, a * a - along * along))
            val bd = bend[0] * dx + bend[1] * dy + bend[2] * dz
            var sx = bend[0] - bd * dx; var sy = bend[1] - bd * dy; var sz = bend[2] - bd * dz
            var sl = sqrt(sx * sx + sy * sy + sz * sz)
            if (sl < 1e-4f) { sx = if (bend[0] >= 0) 1f else -1f; sy = 0f; sz = 0f; sl = 1f }
            sx /= sl; sy /= sl; sz /= sl
            return floatArrayOf(
                shoulder[0] + dx * along + sx * out,
                shoulder[1] + dy * along + sy * out,
                shoulder[2] + dz * along + sz * out,
            )
        }

        /** A unit-height y-axis mesh stretched from [a] to [b]. */
        fun place(out: FloatArray, a: FloatArray, b: FloatArray) {
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            Matrix.setIdentityM(out, 0)
            Matrix.translateM(out, 0, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)
            if (length > 1e-6f) {
                val ux = dx / length; val uy = dy / length; val uz = dz / length
                // Rotate y onto u: axis y × u = (uz, 0, −ux), angle acos(uy).
                val ax = uz; val az = -ux
                val al = sqrt(ax * ax + az * az)
                if (al > 1e-6f) {
                    Matrix.rotateM(out, 0, Math.toDegrees(acos(uy.coerceIn(-1f, 1f).toDouble())).toFloat(), ax / al, 0f, az / al)
                } else if (uy < 0) {
                    Matrix.rotateM(out, 0, 180f, 1f, 0f, 0f)
                }
            }
            Matrix.scaleM(out, 0, 1f, max(length, 0.001f), 1f)
        }

        private fun groundBitmap(): Bitmap {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(0x7d, 0x9a, 0x63))
            val thin = Paint().apply { color = Color.argb(71, 40, 60, 30); strokeWidth = 1f }
            for (k in 0..5) {
                val p = k * 51.2f
                canvas.drawLine(p, 0f, p, 256f, thin)
                canvas.drawLine(0f, p, 256f, p, thin)
            }
            val edge = Paint().apply { color = Color.argb(153, 40, 60, 30); strokeWidth = 3f; style = Paint.Style.STROKE }
            canvas.drawRect(0f, 0f, 256f, 256f, edge)
            return bitmap
        }
    }
}
