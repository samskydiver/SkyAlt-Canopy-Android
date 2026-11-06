package com.skyalt.canopy.sim

import android.graphics.Bitmap
import com.skyalt.canopy.model.Accident
import com.skyalt.canopy.model.AeroParameters
import com.skyalt.canopy.model.Airfield
import com.skyalt.canopy.model.AircraftFlight
import com.skyalt.canopy.model.AircraftLiverySettings
import com.skyalt.canopy.model.AircraftMarkingsSettings
import com.skyalt.canopy.model.AircraftTouchdownSettings
import com.skyalt.canopy.model.CloudSettings
import com.skyalt.canopy.model.METERS_PER_FOOT
import com.skyalt.canopy.model.People
import com.skyalt.canopy.model.Scenario
import com.skyalt.canopy.model.SimulatorMode
import com.skyalt.canopy.model.TrafficFrame
import com.skyalt.canopy.model.TrafficSettings
import com.skyalt.canopy.model.TrafficSimulation
import com.skyalt.canopy.model.TrafficSpawn
import com.skyalt.canopy.model.TrafficStudentGroup
import com.skyalt.canopy.model.Vec3
import com.skyalt.canopy.model.WakeField
import com.skyalt.canopy.model.WakeProfile
import com.skyalt.canopy.model.placedStart
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
    /** Wake turbulence shaking the canopy. */
    data object Turbulence : FlightStatus()
    /** A wake is letting the pressure out of the cells. */
    data object Deflating : FlightStatus()
    /** The wake has collapsed part of the canopy. */
    data object WakeCollapse : FlightStatus()
    /** A collision: the run is over. */
    data object Collision : FlightStatus()
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
    /** Free practice with the saved setup, or a scene with its own. */
    val mode: SimulatorMode = SimulatorMode.Free,
    val clouds: CloudSettings = CloudSettings(),
    val traffic: TrafficSettings = TrafficSettings(),
    /** Set when the run ended in a collision; the accident card shows it. */
    val accident: Accident? = null,
    /** The switch for an aircraft landing on the runway. */
    val aircraftLanding: Boolean = false,
    /** The paint on the aircraft's wings and tail. */
    val aircraftLivery: AircraftLiverySettings = AircraftLiverySettings(),
    /** The hidden setting: where every aircraft touches down. */
    val aircraftTouchdown: AircraftTouchdownSettings = AircraftTouchdownSettings(),
    /** The registration on the aircraft and where its logo sits. */
    val aircraftMarkings: AircraftMarkingsSettings = AircraftMarkingsSettings(),
    /** The logo on the aircraft's sides, once one has been picked. */
    val aircraftLogo: Bitmap? = null,
) {
    val scenario: Scenario? get() = mode.scenario
}

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
    fun loadClouds(): CloudSettings
    fun saveClouds(c: CloudSettings)
    fun loadTraffic(): TrafficSettings
    fun saveTraffic(t: TrafficSettings)
    fun loadAircraftLivery(): AircraftLiverySettings
    fun saveAircraftLivery(l: AircraftLiverySettings)
    fun loadAircraftTouchdown(): AircraftTouchdownSettings
    fun saveAircraftTouchdown(t: AircraftTouchdownSettings)
    fun loadAircraftMarkings(): AircraftMarkingsSettings
    fun saveAircraftMarkings(m: AircraftMarkingsSettings)
    /** The logo on the P750's sides, if one has been picked. */
    fun loadAircraftLogo(): Bitmap?
    /** Keeps [b] as the logo under a megabyte and returns it as kept, or null when it could not be. */
    fun saveAircraftLogo(b: Bitmap): Bitmap?
    fun removeAircraftLogo()
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

    /** Motion capture, when the arms fly the canopy; set once by the activity. */
    @Volatile var motionInput: MotionInput? = null

    private var configuration = store.loadConfiguration()
    private var units = store.loadUnits()
    private var pathStyle = store.loadPathStyle()
    private var dropzone = store.loadDropzone()
    private var dropzoneImage: Bitmap? = store.loadDropzoneImage()
    private var autoFirstPerson = store.loadAutoFirstPerson()
    private var firstPersonAltitude = (store.loadFirstPersonAltitude() ?: SceneController.DEFAULT_FIRST_PERSON_ALTITUDE)
        .coerceIn(SceneController.FIRST_PERSON_MIN, SceneController.FIRST_PERSON_MAX)
    private var legLabels = Triple("downwind", "base", "final")
    private var mode: SimulatorMode = SimulatorMode.Free
    private var clouds = store.loadClouds()
    private var traffic = store.loadTraffic()
    private var accident: Accident? = null
    private var aircraftLivery = store.loadAircraftLivery()
    private var aircraftTouchdown = store.loadAircraftTouchdown()
    private var aircraftMarkings = store.loadAircraftMarkings()
    private var aircraftLogo: Bitmap? = store.loadAircraftLogo()
    private val touchdownMeters get() = aircraftTouchdown.metersFromThreshold
    /** Kept with the traffic settings in free practice, off on entering a scene; the aircraft scene always has one. */
    private var aircraftLanding = traffic.aircraftLanding
    private val scenario get() = mode.scenario
    /** The setting, except in a scene that keeps the outside view. */
    private val firstPersonIsAutomatic get() = autoFirstPerson && scenario?.allowsAutomaticFirstPerson != false

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

    // The computer-flown canopies, their 30 Hz recording for the replay, the
    // wakes everything leaves, the run clock they share and the turbulence
    // the jumper's canopy is in; the scenes' aircraft and people.
    private var npcs = TrafficSimulation()
    private var trafficAccumulator = 0.0
    private val trafficSnapshots = ArrayList<List<TrafficFrame>>()
    private val wakeField = WakeField()
    private var runClock = 0.0
    private var playerTurbulence = 0.0
    private var aircraft: AircraftFlight? = null
    private var people: List<Vec3> = emptyList()

    private val sceneCommands = ArrayList<(SceneController) -> Unit>()

    init {
        aircraft = freeAircraft()
        regenerateTraffic()
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
        val cloudLayer = clouds
        val wakes = traffic.showsWakes
        val airfield = scenario != null
        val hasAircraft = aircraft != null
        val paths = scenario?.showsPaths ?: true
        val crowd = people
        val appearances = npcs.appearances
        val autoFirst = firstPersonIsAutomatic
        val livery = aircraftLivery.colorHex
        val markings = aircraftMarkings
        val logo = aircraftLogo
        sceneCommands += { s ->
            s.setAircraftLivery(livery)
            s.setAircraftMarkings(markings, logo)
            s.applyPathStyle(style)
            s.rebuildPattern(config, a, labels)
            s.setCanopy(config.canopyModel, config.canopyAreaSquareFeet)
            s.isFirstPersonAutomatic = autoFirst && auto
            s.firstPersonAltitude = fp
            s.setDropzone(image, dz)
            s.setClouds(cloudLayer)
            s.showsWakes = wakes
            s.setAirfield(airfield)
            s.setAircraft(hasAircraft)
            s.showsPaths = paths
            s.setPeople(crowd)
            s.setTraffic(appearances)
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

        val mocap = motionInput
        val capturing = mocap != null && mocap.isActive
        mocap?.fullBrakeDepth = configuration.motionCaptureFullBrakeDepth
        scene.headLookX = if (capturing) mocap!!.headLookX else 0.0
        scene.headLookY = if (capturing) mocap!!.headLookY else 0.0

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
        scene.updateTraffic(if (replay != null) replayTrafficFrames(replayTime) else npcs.frames)
        scene.updateWakes(wakeField.samples, runClock)
        aircraft?.let { scene.updateAircraft(it.state(frame.time)) }
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
        if (isPaused || state.landed || state.accident || replay != null) return
        val wind = currentWind
        var remaining = frameDt
        while (remaining > 0) {
            val h = min(SimConstants.PHYSICS_STEP, remaining)
            remaining -= h
            val mocap = motionInput
            inputs.update(h, if (mocap != null && mocap.isActive && mocap.isTracked) mocap.leftPull to mocap.rightPull else null)
            state.step(h, inputs.left, inputs.right, aero, wind, configuration.studentMode, playerTurbulence)
            runClock += h
            // The other canopies fly at 60 Hz alongside.
            trafficAccumulator += h
            while (trafficAccumulator >= TrafficSimulation.STEP_INTERVAL) {
                trafficAccumulator -= TrafficSimulation.STEP_INTERVAL
                npcs.step(TrafficSimulation.STEP_INTERVAL, wind)
            }
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
        updateAir()
    }

    /**
     * Once a frame: the wakes everything leaves, the turbulence the jumper's
     * canopy is flying through, and whether it has hit something.
     */
    private fun updateAir() {
        if (state.landed || state.accident) return
        val time = runClock
        wakeField.prune(time)
        val centre = state.canopyCentre
        val wind = currentWind
        // The jumper's own wake troubles no one and is not drawn, so it is not kept.
        for (other in npcs.canopies) {
            if (!other.isFlying) continue
            wakeField.emit(
                other.id, other.canopyCentre, WakeProfile.canopy(other.span),
                WakeProfile.strengthScale(other.performance.wingLoading), wind, time,
            )
        }
        // An aircraft that has taxied off leaves nothing to fly into.
        val aircraftState = aircraft?.state(time)?.takeIf { it.isPresent }
        if (aircraftState != null) {
            wakeField.emit(WakeField.AIRCRAFT_SOURCE, aircraftState.position, WakeProfile.AIRCRAFT, if (aircraftState.onGround) 0.3 else 1.0, wind, time)
        }
        playerTurbulence = wakeField.intensity(centre, time, WakeField.PLAYER_SOURCE)

        val radius = TrafficSimulation.canopyRadius(configuration.canopyAreaSquareFeet)
        val body = state.position
        if (npcs.collides(centre, radius, body)) return endInAccident(Accident.Kind.CANOPY)
        if (aircraftState != null) {
            for ((sphere, r) in aircraftState.collisionSpheres) {
                if ((centre - sphere).length < radius + r || (body - sphere).length < 1 + r) return endInAccident(Accident.Kind.AIRCRAFT)
            }
        }
        if (people.isNotEmpty() && People.touches(people, body)) return endInAccident(Accident.Kind.PERSON)
        if (scenario != null && (Airfield.hangarTouches(centre, radius) || Airfield.hangarTouches(body, 1.0))) {
            endInAccident(Accident.Kind.BUILDING)
        }
    }

    /** A collision ends the run where it is: the flight freezes and the accident card takes the landing card's place. */
    private fun endInAccident(kind: Accident.Kind) {
        state.accident = true
        accident = Accident(kind, state.time, kotlin.math.max(0.0, state.position.y - 1))
        showsLandingCard = false
        record()
        display = computeDisplay(currentFrame())
        publishLocked()
    }

    /**
     * The other canopies, and the scene's people, at each reset. In free
     * practice the traffic setting's canopies go in the air around the
     * pattern's entry, above and below the jumper's start; the traffic scene
     * opens twenty around the jumper, six of them students at the jumper's
     * level; the aircraft and crosswind scenes take the setting; the other
     * scenes fly alone.
     */
    private fun regenerateTraffic() {
        val wind = currentWind
        wakeField.removeAll()
        runClock = 0.0
        trafficAccumulator = 0.0
        playerTurbulence = 0.0
        trafficSnapshots.clear()
        people = if (scenario?.hasPeople == true) People.obstacleCrowd() else emptyList()
        // The trail the aircraft has already left: the aircraft scene opens with the jumper in it.
        aircraft?.let { flight ->
            val profile = WakeProfile.AIRCRAFT
            var born = -AircraftFlight.TRAIL_SECONDS_AT_START
            while (born < 0) {
                val passed = flight.state(born)
                if (passed.isPresent && !passed.onGround) {
                    wakeField.seed(WakeField.AIRCRAFT_SOURCE, passed.position, profile, 1.0, wind, born)
                }
                born += profile.interval
            }
        }
        val entry = configuration.patternGeometry(aero).points[0]
        fun aroundEntry(low: Double, high: Double) = TrafficSpawn(entry, 200.0..700.0, low..high)
        npcs = when (scenario) {
            null -> {
                val start = configuration.startAltitudeMeters
                val low = kotlin.math.max(150.0, 0.8 * start)
                val high = kotlin.math.max(low + 10, kotlin.math.min(1_500.0, 1.4 * start))
                TrafficSimulation.generate(traffic.count, configuration, wind, aroundEntry(low, high))
            }
            Scenario.TRAFFIC -> {
                val start = configuration.start(aero).position
                val altitude = start.y - 1
                val radius = 1_000 * METERS_PER_FOOT
                TrafficSimulation.generate(
                    20, configuration, wind,
                    TrafficSpawn(start, 0.0..radius, kotlin.math.max(20.0, altitude - radius)..(altitude + radius)),
                    students = TrafficStudentGroup(6, 0.6..1.0, altitude),
                )
            }
            Scenario.AIRCRAFT, Scenario.CROSSWIND -> TrafficSimulation.generate(traffic.count, configuration, wind, aroundEntry(150.0, 450.0))
            Scenario.DOWNWIND, Scenario.OBSTACLES -> TrafficSimulation()
        }
        val crowd = people
        val appearances = npcs.appearances
        sceneCommands += { it.setPeople(crowd); it.setTraffic(appearances) }
    }

    /** The other canopies as recorded alongside the jumper's frames. */
    private fun replayTrafficFrames(time: Double): List<TrafficFrame> {
        if (trafficSnapshots.size != recording.size || recording.isEmpty()) return npcs.frames
        if (time <= recording.first().time) return trafficSnapshots.first()
        if (time >= recording.last().time) return trafficSnapshots.last()
        var low = 0
        var high = recording.size - 1
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (recording[middle].time <= time) low = middle else high = middle
        }
        val u = (time - recording[low].time) / kotlin.math.max(1e-6, recording[high].time - recording[low].time)
        return TrafficFrame.interpolated(trafficSnapshots[low], trafficSnapshots[high], u)
    }

    // MARK: - Modes

    /**
     * Free practice or a scene. A scene brings its own setup, airfield,
     * traffic, aircraft or people, and starts over, waiting for Play; free
     * practice goes back to the saved setup. Choosing the scene being flown
     * starts it over.
     */
    fun enter(newMode: SimulatorMode) = mutate {
        if (replay != null) exitReplayLocked()
        if (newMode == mode && newMode == SimulatorMode.Free) return@mutate
        mode = newMode
        val chosen = newMode.scenario
        if (chosen == null) {
            configuration = store.loadConfiguration()
            aircraftLanding = traffic.aircraftLanding
        } else {
            configuration = chosen.configuration(touchdownMeters).clamped()
            aircraftLanding = false
            aircraft = if (chosen.hasAircraft) AircraftFlight.scenario(touchdownMeters) else null
        }
        aero = AeroParameters(configuration.performance)
        if (chosen == null) aircraft = freeAircraft()
        fullToggleTurnSeconds = FlightState.fullToggleTurnSeconds(configuration, aero)
        val area = configuration.canopyAreaSquareFeet
        val canopyModel = configuration.canopyModel
        val airfield = chosen != null
        val hasAircraft = aircraft != null
        val paths = chosen?.showsPaths ?: true
        val autoFirst = firstPersonIsAutomatic
        sceneCommands += {
            it.setCanopy(canopyModel, area)
            it.setAirfield(airfield)
            it.setAircraft(hasAircraft)
            it.showsPaths = paths
            it.isFirstPersonAutomatic = autoFirst
        }
        runs.clear()
        sceneCommands += { it.clearGhostTrails() }
        resetLocked(keepPaused = true)
        isPaused = true
    }

    /**
     * The switch: an aircraft landing on the runway, again and again, from
     * now on; or none. Free practice keeps the choice, and needs the runway
     * drawn on the dropzone picture first.
     */
    fun setAircraftLanding(on: Boolean) = mutate {
        if (scenario?.choosesAircraft == false || on == aircraftLanding) return@mutate
        if (mode == SimulatorMode.Free && on && dropzone.runwayGeometry == null) return@mutate
        aircraftLanding = on
        if (mode == SimulatorMode.Free && traffic.aircraftLanding != on) {
            traffic = traffic.copy(aircraftLanding = on)
            store.saveTraffic(traffic)
        }
        aircraft = if (mode == SimulatorMode.Free) freeAircraft() else if (on) AircraftFlight.circuit(touchdownMeters) else null
        val visible = aircraft != null
        sceneCommands += { it.setAircraft(visible) }
    }

    /** Whether a runway is drawn on the dropzone picture, for the aircraft switch. */
    fun hasRunway() = synchronized(this) { dropzone.runwayGeometry != null }

    /** Free practice's aircraft: landing on the runway drawn on the dropzone picture while the switch is on. */
    private fun freeAircraft(): AircraftFlight? {
        if (mode != SimulatorMode.Free || !aircraftLanding) return null
        val runway = dropzone.runwayGeometry ?: return null
        return AircraftFlight.landing(runway, configuration.patternGeometry(aero).finalHeading, touchdownMeters)
    }

    /** The paint on the aircraft's wings and tail, in the simulator and on the scene page's picture. */
    fun setAircraftLiveryColor(hex: Int) = mutate {
        val updated = AircraftLiverySettings(hex and 0xffffff)
        if (updated == aircraftLivery) return@mutate
        aircraftLivery = updated
        store.saveAircraftLivery(updated)
        sceneCommands += { it.setAircraftLivery(updated.colorHex) }
    }

    /**
     * Changes the registration or where the logo sits, keeps the result,
     * and redraws the aircraft in the simulator; the scene page's picture
     * follows the published state.
     */
    fun updateAircraftMarkings(change: (AircraftMarkingsSettings) -> AircraftMarkingsSettings) = mutate {
        val updated = change(aircraftMarkings).clamped()
        if (updated == aircraftMarkings) return@mutate
        aircraftMarkings = updated
        store.saveAircraftMarkings(updated)
        val logo = aircraftLogo
        sceneCommands += { it.setAircraftMarkings(updated, logo) }
    }

    /** Keeps a picked picture as the logo on the aircraft's sides, under a megabyte, and puts it on. Off the main thread: it encodes. */
    fun setAircraftLogo(image: Bitmap) {
        val saved = store.saveAircraftLogo(image) ?: return
        mutate {
            aircraftLogo = saved
            val markings = aircraftMarkings
            sceneCommands += { it.setAircraftMarkings(markings, saved) }
        }
    }

    fun removeAircraftLogo() = mutate {
        store.removeAircraftLogo()
        aircraftLogo = null
        val markings = aircraftMarkings
        sceneCommands += { it.setAircraftMarkings(markings, null) }
    }

    /**
     * The hidden setting: every aircraft touches down [meters] past the
     * threshold it crosses. The aircraft of the moment is built again and
     * the run starts over.
     */
    fun setAircraftTouchdown(meters: Double) = mutate {
        val updated = AircraftTouchdownSettings(meters).clamped()
        if (updated == aircraftTouchdown) return@mutate
        aircraftTouchdown = updated
        store.saveAircraftTouchdown(updated)
        val chosen = scenario
        aircraft = when {
            chosen == null -> freeAircraft()
            chosen.hasAircraft -> AircraftFlight.scenario(touchdownMeters)
            aircraftLanding -> AircraftFlight.circuit(touchdownMeters)
            else -> null
        }
        val visible = aircraft != null
        sceneCommands += { it.setAircraft(visible) }
        resetLocked(keepPaused = true)
    }

    /** Free practice's aircraft follows the runway and the landing direction; without a runway the switch goes off. */
    private fun refreshFreeAircraft() {
        if (mode != SimulatorMode.Free) return
        if (aircraftLanding && dropzone.runwayGeometry == null) {
            aircraftLanding = false
            traffic = traffic.copy(aircraftLanding = false)
            store.saveTraffic(traffic)
        }
        aircraft = freeAircraft()
        val visible = aircraft != null
        sceneCommands += { it.setAircraft(visible) }
    }

    // MARK: - Clouds and traffic

    fun updateClouds(change: (CloudSettings) -> CloudSettings) = mutate {
        val updated = change(clouds).clamped()
        if (updated == clouds) return@mutate
        clouds = updated
        store.saveClouds(updated)
        sceneCommands += { it.setClouds(updated) }
    }

    /** The count takes effect at the next reset or Apply. */
    fun updateTraffic(change: (TrafficSettings) -> TrafficSettings) = mutate {
        val updated = change(traffic).clamped()
        if (updated == traffic) return@mutate
        traffic = updated
        store.saveTraffic(updated)
        val wakes = updated.showsWakes
        sceneCommands += { it.showsWakes = wakes }
    }

    private fun record() {
        recording.add(currentFrame())
        trafficSnapshots.add(npcs.frames)
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
        // A scene's start follows the wind of the moment, and free practice's aircraft the landing direction.
        scenario?.let { configuration = configuration.placedStart(it, touchdownMeters).clamped() }
        refreshFreeAircraft()
        state = FlightState(configuration, aero)
        accident = null
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
        regenerateTraffic()
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
        // A scene's setup is its own; only free practice is kept.
        if (mode == SimulatorMode.Free) store.saveConfiguration(updated)
        if (live) {
            aero = AeroParameters(updated.performance)
            fullToggleTurnSeconds = FlightState.fullToggleTurnSeconds(updated, aero)
        }
        if (live || windsockMoved) {
            val a = aero
            val labels = legLabels
            sceneCommands += { it.rebuildPattern(updated, a, labels); it.setCanopy(updated.canopyModel, updated.canopyAreaSquareFeet) }
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
        val automatic = firstPersonIsAutomatic
        sceneCommands += { it.isFirstPersonAutomatic = automatic }
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
        refreshFreeAircraft()
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
                mode = mode,
                clouds = clouds,
                traffic = traffic,
                accident = accident,
                aircraftLanding = aircraftLanding,
                aircraftLivery = aircraftLivery,
                aircraftTouchdown = aircraftTouchdown,
                aircraftMarkings = aircraftMarkings,
                aircraftLogo = aircraftLogo,
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
            frame.accident -> FlightStatus.Collision
            frame.landed -> FlightStatus.Landed
            frame.wakeCollapse > 0.3 -> FlightStatus.WakeCollapse
            frame.collapse > maxOf(0.02, frame.wakeCollapse + 0.01) -> FlightStatus.FullStall
            frame.stall > 0.02 -> FlightStatus.StallOnset
            frame.wakeCollapse > 0.08 -> FlightStatus.Deflating
            frame.turbulence > 0.25 -> FlightStatus.Turbulence
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
