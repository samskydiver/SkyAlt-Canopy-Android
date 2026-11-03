package com.skyalt.canopy.sim

import android.graphics.Bitmap
import com.skyalt.canopy.model.AeroParameters
import com.skyalt.canopy.model.BrakeInputs
import com.skyalt.canopy.model.Compass
import com.skyalt.canopy.model.DEG
import com.skyalt.canopy.model.DropzoneSettings
import com.skyalt.canopy.model.FlightFrame
import com.skyalt.canopy.model.FlightRecording
import com.skyalt.canopy.model.FlightState
import com.skyalt.canopy.model.PathStyleSettings
import com.skyalt.canopy.model.PatternGeometry
import com.skyalt.canopy.model.ProfileSample
import com.skyalt.canopy.model.RunRecord
import com.skyalt.canopy.model.SimConstants
import com.skyalt.canopy.model.SimulatorConfiguration
import com.skyalt.canopy.model.StartPlacement
import com.skyalt.canopy.model.Touchdown
import com.skyalt.canopy.model.Units
import com.skyalt.canopy.model.patternGeometry
import com.skyalt.canopy.model.start
import com.skyalt.canopy.scene.CameraPreset
import com.skyalt.canopy.scene.SceneController
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** The badge in the instrument bar. */
sealed class FlightStatus {
    data object FullFlight : FlightStatus()
    data object Landed : FlightStatus()
    data object Replay : FlightStatus()
    data object StallOnset : FlightStatus()
    data object FullStall : FlightStatus()
    data object DeepBrakes : FlightStatus()
    data object LeftTurn : FlightStatus()
    data object RightTurn : FlightStatus()
    data class Brakes(val percent: Int) : FlightStatus()
}

/** The instruments, refreshed ten times a second. */
data class Display(
    val altitude: Double = 0.0,
    val airspeed: Double = 0.0,
    val groundSpeed: Double = 0.0,
    val sinkRate: Double = 0.0,
    val glideRatio: Double = 0.0,
    val headingDegrees: Double = 0.0,
    val bankDegrees: Double = 0.0,
    val turnRateDegrees: Double = 0.0,
    val loadFactor: Double = 1.0,
    val liftCoefficient: Double = 0.0,
    /** null beyond 5 km. */
    val glideRange: Double? = null,
    val distanceToTarget: Double = 0.0,
    val time: Double = 0.0,
    val status: FlightStatus = FlightStatus.FullFlight,
    val leftBrake: Double = 0.0,
    val rightBrake: Double = 0.0,
    val leftTarget: Double = 0.0,
    val rightTarget: Double = 0.0,
    val replayTime: Double = 0.0,
    val replayDuration: Double = 0.0,
    val profile: List<ProfileSample> = emptyList(),
    val currentDistance: Double = 0.0,
    val currentAltitude: Double = 0.0,
)

data class ReplayState(val isPlaying: Boolean, val speed: Double)

/** Everything the interface draws, published from the simulation. */
data class UiState(
    val configuration: SimulatorConfiguration = SimulatorConfiguration(),
    val units: Units = Units.IMPERIAL,
    val display: Display = Display(),
    val isPaused: Boolean = true,
    val touchdown: Touchdown? = null,
    val showsLandingCard: Boolean = false,
    val runs: List<RunRecord> = emptyList(),
    val replay: ReplayState? = null,
    val canReplay: Boolean = false,
    val cameraPreset: CameraPreset? = CameraPreset.CHASE,
    val fullToggleTurnSeconds: Double = 0.0,
    val autoFirstPerson: Boolean = true,
    val firstPersonAltitudeMeters: Double = SceneController.DEFAULT_FIRST_PERSON_ALTITUDE,
    val dropzone: DropzoneSettings = DropzoneSettings(),
    val dropzoneImage: Bitmap? = null,
    val pathStyle: PathStyleSettings = PathStyleSettings(),
    val startPlacement: StartPlacement? = null,
    val referencePattern: PatternGeometry? = null,
)

/** Where the engine keeps what outlives a visit. */
interface SimulatorStore {
    fun loadConfiguration(): SimulatorConfiguration
    fun saveConfiguration(c: SimulatorConfiguration)
    fun loadUnits(): Units
    fun saveUnits(u: Units)
    fun loadPathStyle(): PathStyleSettings
    fun savePathStyle(s: PathStyleSettings)
    fun loadDropzone(): DropzoneSettings
    fun saveDropzone(s: DropzoneSettings)
    fun loadDropzoneImage(): Bitmap?
    fun saveDropzoneImage(b: Bitmap): Bitmap?
    fun removeDropzoneImage()
    fun loadAutoFirstPerson(): Boolean
    fun saveAutoFirstPerson(on: Boolean)
    fun loadFirstPersonAltitude(): Double?
    fun saveFirstPersonAltitude(m: Double)
}

/**
 * Runs the simulation, records and replays runs, keeps the run history and
 * feeds the scene and the instruments. Port of CanopySimulatorEngine.swift.
 *
 * Threading: [tick] and the scene run on the GL thread; the interface calls
 * the other methods from the main thread. Every method takes the engine's
 * lock, and anything the scene must do is queued and run inside [tick].
 */
class SimulatorEngine(private val store: SimulatorStore, private val publish: (UiState) -> Unit) {
    val scene = SceneController()

    private var configuration = store.loadConfiguration()
    private var units = store.loadUnits()
    private var pathStyle = store.loadPathStyle()
    private var dropzone = store.loadDropzone()
    private var dropzoneImage: Bitmap? = store.loadDropzoneImage()
    private var autoFirstPerson = store.loadAutoFirstPerson()
    private var firstPersonAltitude = (store.loadFirstPersonAltitude() ?: SceneController.DEFAULT_FIRST_PERSON_ALTITUDE)
        .coerceIn(SceneController.FIRST_PERSON_MIN, SceneController.FIRST_PERSON_MAX)
    private var legLabels = Triple("downwind", "base", "final")

    private var aero = AeroParameters(configuration.performance)
    private var state = FlightState(configuration, aero)
    private var fullToggleTurnSeconds = FlightState.fullToggleTurnSeconds(configuration, aero)
    private val inputs = BrakeInputs()
    private val recording = ArrayList<FlightFrame>()
    private var recordingAccumulator = 0.0
    private var currentProfile = ArrayList<ProfileSample>()
    private var profileAccumulator = 0.0
    private var readoutAccumulator = 0.0
    private var replayTime = 0.0
    private var runCounter = 0
    private var isPaused = true
    private var touchdown: Touchdown? = null
    private var showsLandingCard = false
    private val runs = ArrayList<RunRecord>()
    private var replay: ReplayState? = null
    private var canReplay = false
    private var cameraPreset: CameraPreset? = CameraPreset.CHASE
    private var display = Display()

    private val sceneCommands = ArrayList<(SceneController) -> Unit>()

    init {
        queueSceneSetup()
        display = computeDisplay(currentFrame())
    }

    /** Everything the scene is built from, queued again after a GL context loss. */
    private fun queueSceneSetup() {
        val config = configuration
        val style = pathStyle
        val image = dropzoneImage
        val dz = dropzone
        val auto = autoFirstPerson
        val fp = firstPersonAltitude
        val labels = legLabels
        val a = aero
        sceneCommands += { s ->
            s.applyPathStyle(style)
            s.rebuildPattern(config, a, labels)
            s.setCanopySize(config.canopyAreaSquareFeet)
            s.isFirstPersonAutomatic = auto
            s.firstPersonAltitude = fp
            s.setDropzone(image, dz)
        }
    }

    fun onSurfaceCreated() = synchronized(this) {
        scene.onSurfaceCreated()
        queueSceneSetup()
    }

    // MARK: - Running (GL thread)

    fun tick(dt: Double) = synchronized(this) {
        for (command in sceneCommands) command(scene)
        sceneCommands.clear()

        simulate(dt)
        val r = replay
        val frame = if (r != null && recording.isNotEmpty()) {
            if (r.isPlaying) {
                replayTime += dt * r.speed
                val end = recording.last().time
                if (replayTime >= end) {
                    replayTime = end
                    replay = r.copy(isPlaying = false)
                }
            }
            FlightRecording.frame(replayTime, recording) ?: currentFrame()
        } else currentFrame()

        scene.update(frame, dt)
        if (!isPaused && !state.landed && replay == null) {
            scene.appendTrailPoint(state.position, -state.velocity.y, dt)
        }
        readoutAccumulator += dt
        if (readoutAccumulator > 0.1) {
            readoutAccumulator = 0.0
            display = computeDisplay(frame)
            publishLocked()
        }
    }

    private fun simulate(frameDt: Double) {
        if (isPaused || state.landed || replay != null) return
        val wind = currentWind
        var remaining = frameDt
        while (remaining > 0) {
            val h = min(SimConstants.PHYSICS_STEP, remaining)
            remaining -= h
            inputs.update(h)
            state.step(h, inputs.left, inputs.right, aero, wind, configuration.studentMode)
            profileAccumulator += h
            recordingAccumulator += h
            if (recordingAccumulator >= SimConstants.RECORDING_INTERVAL) {
                recordingAccumulator -= SimConstants.RECORDING_INTERVAL
                record()
            }
            if (profileAccumulator >= SimConstants.PROFILE_INTERVAL) {
                profileAccumulator -= SimConstants.PROFILE_INTERVAL
                currentProfile.add(ProfileSample(state.distance, state.position.y - 1, (inputs.left + inputs.right) / 2))
            }
            if (state.landed) {
                record()
                touchdown = Touchdown(
                    -state.velocity.y,
                    hypot(state.velocity.x, state.velocity.z),
                    state.frame(0.0, 0.0).distanceToTarget,
                )
                showsLandingCard = true
                display = computeDisplay(currentFrame())
                publishLocked()
                break
            }
        }
    }

    private fun record() {
        recording.add(currentFrame())
        canReplay = recording.size >= SimConstants.MINIMUM_REPLAY_FRAMES
    }

    private fun currentFrame() = state.frame(inputs.left, inputs.right)

    private val currentWind get() = Compass.wind(configuration.windSpeed, configuration.windDirectionDegrees)

    // MARK: - Transport

    fun togglePause() = mutate {
        if (replay == null) isPaused = !isPaused
    }

    fun pause() = mutate {
        if (replay == null) isPaused = true
    }

    fun resetRun(keepPaused: Boolean = false) = mutate { resetLocked(keepPaused) }

    private fun resetLocked(keepPaused: Boolean) {
        if (replay != null) exitReplayLocked()
        if (currentProfile.size > 20) {
            runCounter += 1
            runs.add(RunRecord(runCounter, currentProfile, touchdown))
            if (runs.size > SimConstants.MAXIMUM_STORED_RUNS) runs.removeAt(0)
            sceneCommands += { it.archiveTrail() }
        }
        state = FlightState(configuration, aero)
        currentProfile = ArrayList()
        profileAccumulator = 0.0
        touchdown = null
        showsLandingCard = false
        recording.clear()
        recordingAccumulator = 0.0
        canReplay = false
        inputs.release()
        val config = configuration
        val a = aero
        val labels = legLabels
        sceneCommands += { it.resetTrail(); it.rebuildPattern(config, a, labels) }
        if (isPaused && !keepPaused) isPaused = false
        display = computeDisplay(currentFrame())
    }

    fun clearHistory() = mutate {
        runs.clear()
        sceneCommands += { it.clearGhostTrails() }
    }

    // MARK: - Replay

    fun enterReplay() = mutate {
        if (recording.size >= 2) {
            replayTime = recording[0].time
            replay = ReplayState(true, replay?.speed ?: 1.0)
            showsLandingCard = false
        }
    }

    fun exitReplay() = mutate { exitReplayLocked() }

    private fun exitReplayLocked() {
        replay = null
        if (touchdown != null) showsLandingCard = true
    }

    fun toggleReplayPlayback() = mutate {
        val r = replay ?: return@mutate
        if (!r.isPlaying && recording.isNotEmpty() && replayTime >= recording.last().time - 1e-3) {
            replayTime = recording.first().time
        }
        replay = r.copy(isPlaying = !r.isPlaying)
    }

    fun setReplaySpeed(speed: Double) = mutate {
        replay = replay?.copy(speed = speed)
    }

    fun scrubReplay(fraction: Double) = mutate {
        if (replay != null && recording.isNotEmpty()) {
            val first = recording.first().time
            val last = recording.last().time
            replayTime = first + (last - first) * fraction.coerceIn(0.0, 1.0)
            replay = replay?.copy(isPlaying = false)
            display = computeDisplay(FlightRecording.frame(replayTime, recording) ?: currentFrame())
        }
    }

    // MARK: - Brakes

    fun setBrakeTarget(left: Double? = null, right: Double? = null) = synchronized(this) {
        if (left != null) inputs.targetLeft = left.coerceIn(0.0, 1.0)
        if (right != null) inputs.targetRight = right.coerceIn(0.0, 1.0)
    }

    // MARK: - Setup

    fun updateConfiguration(change: (SimulatorConfiguration) -> SimulatorConfiguration) = mutate {
        val updated = change(configuration).clamped()
        if (updated == configuration) return@mutate
        val live = updated.affectsLiveFlight(configuration)
        val windsockMoved = updated.windsockEastMeters != configuration.windsockEastMeters ||
            updated.windsockSouthMeters != configuration.windsockSouthMeters
        configuration = updated
        store.saveConfiguration(updated)
        if (live) {
            aero = AeroParameters(updated.performance)
            fullToggleTurnSeconds = FlightState.fullToggleTurnSeconds(updated, aero)
        }
        if (live || windsockMoved) {
            val a = aero
            val labels = legLabels
            sceneCommands += { it.rebuildPattern(updated, a, labels); it.setCanopySize(updated.canopyAreaSquareFeet) }
        }
    }

    /** Apply in Settings: the run starts over with the new setup and waits for Play. */
    fun applyAndReset() = mutate {
        resetLocked(keepPaused = true)
        isPaused = true
    }

    fun setUnits(u: Units) = mutate {
        units = u
        store.saveUnits(u)
    }

    fun setAutoFirstPerson(on: Boolean) = mutate {
        autoFirstPerson = on
        store.saveAutoFirstPerson(on)
        sceneCommands += { it.isFirstPersonAutomatic = on }
    }

    fun setFirstPersonAltitude(meters: Double) = mutate {
        val m = meters.coerceIn(SceneController.FIRST_PERSON_MIN, SceneController.FIRST_PERSON_MAX)
        firstPersonAltitude = m
        store.saveFirstPersonAltitude(m)
        sceneCommands += { it.firstPersonAltitude = m }
    }

    fun setLegLabels(downwind: String, base: String, final: String) = mutate {
        val labels = Triple(downwind, base, final)
        if (labels == legLabels) return@mutate
        legLabels = labels
        val config = configuration
        val a = aero
        sceneCommands += { it.rebuildPattern(config, a, labels) }
    }

    // MARK: - Camera

    fun userMovedCamera() = mutate {
        if (cameraPreset != null && !scene.isFirstPerson) cameraPreset = null
    }

    fun orbit(dx: Double, dy: Double) = synchronized(this) {
        sceneCommands += { it.orbit(dx, dy) }
    }

    fun zoomBy(factor: Double) = synchronized(this) {
        sceneCommands += { it.zoom(it.cameraDistance / factor) }
    }

    fun cycleCameraPreset() = mutate {
        val presets = CameraPreset.entries
        val current = cameraPreset
        val next = if (current != null) presets[(presets.indexOf(current) + 1) % presets.size] else presets[0]
        cameraPreset = next
        sceneCommands += { it.applyCameraPreset(next) }
    }

    // MARK: - Dropzone picture

    fun setDropzoneImage(bitmap: Bitmap) = mutate {
        val saved = store.saveDropzoneImage(bitmap) ?: return@mutate
        dropzoneImage = saved
        val dz = dropzone
        sceneCommands += { it.setDropzone(saved, dz) }
    }

    fun removeDropzoneImage() = mutate {
        store.removeDropzoneImage()
        dropzoneImage = null
        val dz = dropzone
        sceneCommands += { it.setDropzone(null, dz) }
    }

    fun updateDropzone(change: (DropzoneSettings) -> DropzoneSettings) = mutate {
        val updated = change(dropzone).clamped()
        if (updated == dropzone) return@mutate
        dropzone = updated
        store.saveDropzone(updated)
        val image = dropzoneImage
        sceneCommands += { it.setDropzone(image, updated) }
    }

    // MARK: - Path styles

    fun updatePathStyle(change: (PathStyleSettings) -> PathStyleSettings) = mutate {
        val updated = change(pathStyle).clamped()
        if (updated == pathStyle) return@mutate
        pathStyle = updated
        store.savePathStyle(updated)
        sceneCommands += { it.applyPathStyle(updated) }
    }

    fun resetPathStyle() = updatePathStyle { PathStyleSettings() }

    // MARK: - Publishing

    private inline fun mutate(block: () -> Unit) = synchronized(this) {
        block()
        publishLocked()
    }

    fun publishNow() = synchronized(this) { publishLocked() }

    private fun publishLocked() {
        val r = replay
        val displayNow = if (r != null && recording.isNotEmpty()) {
            display.copy(replayTime = replayTime, replayDuration = recording.last().time - recording.first().time)
        } else display
        publish(
            UiState(
                configuration = configuration,
                units = units,
                display = displayNow,
                isPaused = isPaused,
                touchdown = touchdown,
                showsLandingCard = showsLandingCard,
                runs = runs.toList(),
                replay = replay,
                canReplay = canReplay,
                cameraPreset = cameraPreset,
                fullToggleTurnSeconds = fullToggleTurnSeconds,
                autoFirstPerson = autoFirstPerson,
                firstPersonAltitudeMeters = firstPersonAltitude,
                dropzone = dropzone,
                dropzoneImage = dropzoneImage,
                pathStyle = pathStyle,
                startPlacement = configuration.start(aero),
                referencePattern = configuration.referencePattern.hand?.let { configuration.patternGeometry(aero, it) },
            ),
        )
    }

    private fun computeDisplay(frame: FlightFrame): Display {
        val wind = currentWind
        val airspeed = frame.airspeed(wind)
        val groundSpeed = frame.groundSpeed
        val sinkRate = frame.sinkRate
        val brake = (frame.leftBrake + frame.rightBrake) / 2
        val range = if (sinkRate > 0.05) frame.altitude * groundSpeed / sinkRate else 9999.0
        val status = when {
            replay != null -> FlightStatus.Replay
            frame.landed -> FlightStatus.Landed
            frame.collapse > 0.02 -> FlightStatus.FullStall
            frame.stall > 0.02 -> FlightStatus.StallOnset
            brake > 0.7 -> FlightStatus.DeepBrakes
            abs(frame.leftBrake - frame.rightBrake) > 0.3 ->
                if (frame.leftBrake > frame.rightBrake) FlightStatus.LeftTurn else FlightStatus.RightTurn
            brake > 0.08 -> FlightStatus.Brakes((brake * 100).roundToInt())
            else -> FlightStatus.FullFlight
        }
        return Display(
            altitude = frame.altitude,
            airspeed = airspeed,
            groundSpeed = groundSpeed,
            sinkRate = sinkRate,
            glideRatio = if (sinkRate > 0.05) groundSpeed / sinkRate else 99.0,
            headingDegrees = ((frame.heading / DEG).roundToInt() % 360).toDouble(),
            bankDegrees = frame.bank / DEG,
            turnRateDegrees = frame.turnRate / DEG,
            loadFactor = frame.loadFactor,
            liftCoefficient = frame.liftCoefficient,
            glideRange = if (range > 5000) null else range,
            distanceToTarget = frame.distanceToTarget,
            time = frame.time,
            status = status,
            leftBrake = frame.leftBrake,
            rightBrake = frame.rightBrake,
            leftTarget = if (replay != null) frame.leftBrake else inputs.targetLeft,
            rightTarget = if (replay != null) frame.rightBrake else inputs.targetRight,
            profile = currentProfile.toList(),
            currentDistance = frame.distance,
            currentAltitude = frame.altitude,
        )
    }
}
