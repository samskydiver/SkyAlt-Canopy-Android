package com.skyalt.canopy.sim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The joints the overlay draws and the pull estimate uses; left is the person's own left. */
enum class PoseJoint(val landmark: Int) {
    LEFT_SHOULDER(PoseLandmark.LEFT_SHOULDER), RIGHT_SHOULDER(PoseLandmark.RIGHT_SHOULDER),
    LEFT_ELBOW(PoseLandmark.LEFT_ELBOW), RIGHT_ELBOW(PoseLandmark.RIGHT_ELBOW),
    LEFT_WRIST(PoseLandmark.LEFT_WRIST), RIGHT_WRIST(PoseLandmark.RIGHT_WRIST),
    LEFT_HIP(PoseLandmark.LEFT_HIP), RIGHT_HIP(PoseLandmark.RIGHT_HIP),
    NOSE(PoseLandmark.NOSE), LEFT_EYE(PoseLandmark.LEFT_EYE), RIGHT_EYE(PoseLandmark.RIGHT_EYE),
}

/**
 * One processed camera frame: the picture (mirrored when the mirror is on),
 * where the joints are in it (normalised, y down), the pull range guides and
 * the brake estimate.
 */
data class PosePreview(
    val image: ImageBitmap,
    val joints: Map<PoseJoint, Offset>,
    val pullTop: Float?,
    val pullBottom: Float?,
    val isTracked: Boolean,
    val leftPull: Double,
    val rightPull: Double,
)

/** What the engine reads from motion capture on every physics step. */
interface MotionInput {
    val isActive: Boolean
    val isTracked: Boolean
    val leftPull: Double
    val rightPull: Double
    val headLookX: Double
    val headLookY: Double
    var fullBrakeDepth: Double
}

/**
 * Motion capture: the front camera and ML Kit's body pose turn the arms into
 * the toggles. Arms straight up releases; a hand pulled down to
 * [fullBrakeDepth] torso lengths below the shoulders brakes that side fully.
 * Turning the head can swing the camera. Port of CanopyMotionCapture.swift.
 */
class MotionCaptureController(private val context: Context) : MotionInput {
    sealed class Status {
        data object Idle : Status()
        data object Asking : Status()
        data object Tracking : Status()
        data object Stopped : Status()
        data object NoCamera : Status()
        data object Refused : Status()
        data class Failed(val message: String) : Status()
    }

    private val prefs = context.getSharedPreferences("canopy-simulator", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    // Interface state (main thread)
    var active by mutableStateOf(false)
        private set
    var status by mutableStateOf<Status>(Status.Idle)
        private set
    var preview by mutableStateOf<PosePreview?>(null)
        private set
    var isMirrored by mutableStateOf(prefs.getBoolean(MIRROR_KEY, true))
        private set
    var isHeadCameraOn by mutableStateOf(prefs.getBoolean(HEAD_CAMERA_KEY, false))
        private set
    var isCalibratingHead by mutableStateOf(false)
        private set

    // Read by the engine on the GL thread
    @Volatile override var isActive = false; private set
    @Volatile override var isTracked = false; private set
    @Volatile override var leftPull = 0.0; private set
    @Volatile override var rightPull = 0.0; private set
    @Volatile override var headLookX = 0.0; private set
    @Volatile override var headLookY = 0.0; private set
    @Volatile override var fullBrakeDepth = 1.12

    // Head look (main thread)
    private var headBaseline: DoubleArray? = null
    private val headCalibration = ArrayList<DoubleArray>()
    private var lastHeadSeen = 0.0

    // Camera
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private val detector by lazy {
        PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
    }
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            analysis?.targetRotation = displayRotation()
        }
    }

    // Frame processing state (analysis thread)
    private var lastFrameTime = 0.0
    private var lastSeen = 0.0
    private var framePullLeft = 0.0
    private var framePullRight = 0.0
    @Volatile private var mirrorForFrames = isMirrored

    fun changeMirrored(on: Boolean) {
        isMirrored = on
        mirrorForFrames = on
        prefs.edit().putBoolean(MIRROR_KEY, on).apply()
    }

    fun setHeadCamera(on: Boolean) {
        isHeadCameraOn = on
        prefs.edit().putBoolean(HEAD_CAMERA_KEY, on).apply()
        recentreHead()
    }

    /** Called when the camera permission was asked for. */
    fun markAsking() {
        status = Status.Asking
    }

    fun markRefused() {
        status = Status.Refused
    }

    /** Starts the camera; the permission must already be granted. */
    fun start(lifecycleOwner: LifecycleOwner) {
        if (active) return
        status = Status.Asking
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = try {
                future.get()
            } catch (e: Exception) {
                status = Status.Failed(e.message ?: e.javaClass.simpleName)
                return@addListener
            }
            provider = cameraProvider
            val selector = when {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> null
            }
            if (selector == null) {
                status = Status.NoCamera
                return@addListener
            }
            val useCase = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(displayRotation())
                .build()
            lastFrameTime = 0.0; lastSeen = 0.0; framePullLeft = 0.0; framePullRight = 0.0
            useCase.setAnalyzer(analysisExecutor, ::analyze)
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, useCase)
            } catch (e: Exception) {
                status = Status.Failed(e.message ?: e.javaClass.simpleName)
                return@addListener
            }
            analysis = useCase
            displayManager.registerDisplayListener(displayListener, main)
            active = true
            isActive = true
            status = Status.Tracking
            recentreHead()
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop(silently: Boolean) {
        if (!active && status != Status.Asking) return
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        analysis = null
        displayManager.unregisterDisplayListener(displayListener)
        active = false
        isActive = false
        isTracked = false
        leftPull = 0.0
        rightPull = 0.0
        preview = null
        recentreHead()
        status = if (!silently) Status.Stopped else if (status == Status.Tracking || status == Status.Asking) Status.Idle else status
    }

    /** Takes the current head pose as the new neutral one. */
    fun recentreHead() {
        headBaseline = null
        headCalibration.clear()
        headLookX = 0.0; headLookY = 0.0
        isCalibratingHead = isHeadCameraOn && active
    }

    private fun displayRotation(): Int {
        @Suppress("DEPRECATION")
        return (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay?.rotation ?: Surface.ROTATION_90
    }

    // MARK: - Frames (analysis thread)

    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime() / 1000.0
        if (now - lastFrameTime < FRAME_INTERVAL) {
            proxy.close(); return
        }
        lastFrameTime = now
        val upright = try {
            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } catch (e: Exception) {
            proxy.close(); return
        }
        proxy.close()
        val pose = try {
            com.google.android.gms.tasks.Tasks.await(detector.process(InputImage.fromBitmap(upright, 0)))
        } catch (e: Exception) {
            null
        }
        handleFrame(upright, pose, now)
    }

    private fun handleFrame(image: Bitmap, pose: Pose?, now: Double) {
        val w = image.width.toFloat()
        val h = max(1, image.height).toFloat()
        val mirrored = mirrorForFrames
        // Joints in the camera's own (unmirrored) picture, normalised.
        val joints = HashMap<PoseJoint, Offset>()
        val likelihood = HashMap<PoseJoint, Float>()
        if (pose != null) {
            for (joint in PoseJoint.entries) {
                val landmark = pose.getPoseLandmark(joint.landmark) ?: continue
                joints[joint] = Offset(landmark.position.x / w, landmark.position.y / h)
                likelihood[joint] = landmark.inFrameLikelihood
            }
        }
        fun visible(j: PoseJoint) = (likelihood[j] ?: 0f) > 0.5f

        var tracked = false
        var pullTop: Float? = null
        var pullBottom: Float? = null
        val ls = joints[PoseJoint.LEFT_SHOULDER]
        val rs = joints[PoseJoint.RIGHT_SHOULDER]
        if (visible(PoseJoint.LEFT_SHOULDER) && visible(PoseJoint.RIGHT_SHOULDER) && ls != null && rs != null) {
            // Both shoulders are needed; the hips give the torso length, or it
            // is guessed from the shoulder width when they are out of frame.
            val shoulderY = (ls.y + rs.y) / 2
            val hips = listOf(PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP).filter(::visible).mapNotNull { joints[it] }
            val torso = if (hips.isEmpty()) {
                max(0.05f, 1.3f * abs(ls.x - rs.x) * w / h)
            } else {
                max(0.05f, hips.map { it.y }.average().toFloat() - shoulderY)
            }
            val top = shoulderY - 0.75f * torso
            val bottom = shoulderY + fullBrakeDepth.toFloat() * torso
            fun pull(wrist: Offset) = ((wrist.y - top) / (bottom - top)).coerceIn(0f, 1f).toDouble()
            joints[PoseJoint.LEFT_WRIST]?.takeIf { visible(PoseJoint.LEFT_WRIST) }?.let { framePullLeft = pull(it) }
            joints[PoseJoint.RIGHT_WRIST]?.takeIf { visible(PoseJoint.RIGHT_WRIST) }?.let { framePullRight = pull(it) }
            tracked = true
            lastSeen = now
            pullTop = top
            pullBottom = bottom
        } else if (joints.isEmpty()) {
            tracked = lastSeen > 0 && now - lastSeen <= 0.6
        }

        // The head: the nose against the eyes, in eye spans so it does not
        // change with the distance from the camera. x grows as the person
        // turns to their own left (the picture's right, unmirrored).
        var head: DoubleArray? = null
        val nose = joints[PoseJoint.NOSE]
        val le = joints[PoseJoint.LEFT_EYE]
        val re = joints[PoseJoint.RIGHT_EYE]
        if ((likelihood[PoseJoint.NOSE] ?: 0f) > 0.4f && (likelihood[PoseJoint.LEFT_EYE] ?: 0f) > 0.4f &&
            (likelihood[PoseJoint.RIGHT_EYE] ?: 0f) > 0.4f && nose != null && le != null && re != null
        ) {
            val span = hypot(((le.x - re.x) * w / h).toDouble(), (le.y - re.y).toDouble())
            if (span > 0.005) {
                val midX = (le.x + re.x) / 2
                val midY = (le.y + re.y) / 2
                head = doubleArrayOf((nose.x - midX) * w / h / span, (nose.y - midY) / span)
            }
        }

        // The picture the person sees: small, and mirrored when asked.
        val scale = PREVIEW_WIDTH / max(1f, w)
        val matrix = Matrix().apply {
            postScale(if (mirrored) -scale else scale, scale)
            if (mirrored) postTranslate(PREVIEW_WIDTH, 0f)
        }
        val small = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
        val shown = joints.filterKeys(::visible).mapValues { (_, p) -> if (mirrored) Offset(1 - p.x, p.y) else p }
        val frame = PosePreview(small.asImageBitmap(), shown, pullTop, pullBottom, tracked, framePullLeft, framePullRight)
        main.post { handle(frame, head) }
    }

    // MARK: - Main thread

    private fun handle(frame: PosePreview, head: DoubleArray?) {
        if (!active) return
        preview = frame
        isTracked = frame.isTracked
        if (frame.isTracked) {
            leftPull = frame.leftPull
            rightPull = frame.rightPull
        }
        updateHeadLook(head)
    }

    private fun updateHeadLook(head: DoubleArray?) {
        val now = SystemClock.elapsedRealtime() / 1000.0
        if (!isHeadCameraOn || head == null) {
            // A short loss (a blink, a hand across the face) holds the view;
            // a longer one lets it drift back to the centre.
            if (!isHeadCameraOn || now - lastHeadSeen > 0.8) { headLookX = 0.0; headLookY = 0.0 }
            return
        }
        lastHeadSeen = now
        val baseline = headBaseline
        if (baseline == null) {
            headCalibration += head
            if (headCalibration.size >= HEAD_CALIBRATION_FRAMES) {
                headBaseline = doubleArrayOf(headCalibration.map { it[0] }.average(), headCalibration.map { it[1] }.average())
                headCalibration.clear()
                isCalibratingHead = false
            }
            return
        }
        fun swing(value: Double, deadZone: Double, travel: Double, full: Double): Double {
            val past = max(0.0, abs(value) - deadZone)
            val fraction = min(1.0, past / travel)
            // Eased so small turns stay gentle and the end is reached smoothly.
            val eased = fraction * fraction * (3 - 2 * fraction)
            return (if (value < 0) -1 else 1) * eased * full
        }
        headLookX = HEAD_YAW_SIGN * swing(head[0] - baseline[0], 0.04, 0.22, Math.PI / 2)
        // Looking up lifts the camera to look down on the canopy.
        headLookY = -swing(head[1] - baseline[1], 0.04, 0.16, 0.6)
    }

    companion object {
        private const val MIRROR_KEY = "mocap.mirror"
        private const val HEAD_CAMERA_KEY = "mocap.head-camera"
        private const val FRAME_INTERVAL = 1.0 / 15
        private const val PREVIEW_WIDTH = 240f
        /** Frames averaged for the neutral pose (about half a second). */
        private const val HEAD_CALIBRATION_FRAMES = 8
        /** Which way the camera goes for a head turn; flip if it feels backwards. */
        private const val HEAD_YAW_SIGN = 1.0
    }
}
