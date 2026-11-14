package com.skyalt.canopy.windtunnel

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

enum class WTMode { PIN, FLY, FREE }

enum class WTEnvKind { FREE, TUNNEL }

enum class WTUnit(val factor: Double, val label: String, val decimals: Int) {
    KMH(3.6, "km/h", 0),
    MPH(2.236936, "mph", 0),
    MS(1.0, "m/s", 1),
}

enum class WTMeasure { DRAG, LIFT, PITCH, ROLL, YAW }

/** The user's settings (the web page's `st`). */
data class WTSettings(
    /** cm */
    val H: Int = 175,
    /** kg */
    val M: Int = 75,
    /** leg length, % above or below standard */
    val legs: Int = 0,
    val suit: String = "std",
    /** m */
    val alt: Int = 0,
    val cal: Double = 1.0,
    /** stream speed at the net, m/s */
    val V: Double = 0.0,
    val unit: WTUnit = WTUnit.KMH,
    val mode: WTMode = WTMode.FLY,
    val link: Set<String> = emptySet(),
    val autoMatch: Boolean = true,
    val center: Boolean = true,
    val env: WTEnvKind = WTEnvKind.FREE,
    val preset: String = "box",
    val modified: Boolean = false,
    val holdP: Boolean = false,
    val holdR: Boolean = false,
    val measure: WTMeasure = WTMeasure.DRAG,
)

/**
 * The forces in the stream the body feels right now, computed at the
 * reference speed and scaled by s.
 */
class WTLive(
    val a: WTAnalysis,
    val s: Double,
    val U: WTVec,
    val U50: WTVec,
    val speed: Double,
    val tilt: Double,
    val W: Double,
    val F: WTVec,
    val D: Double,
    val L: WTVec,
    val Lmag: Double,
    val ratio: Double,
    val Fy: Double,
    val mPitch: Double,
    val mRoll: Double,
    val mYaw: Double,
)

/** The same pose at rest in a vertical stream: hover speed, drag area. */
class WTRest(val a: WTAnalysis, val veq: Double, val cda: Double, val ratio: Double, val shape: WTShape?)

data class WTHud(val vy: Double = 0.0, val vh: Double = 0.0, val glide: Double? = null)

/**
 * What the screen shows. The engine writes these from whichever thread
 * holds it, always in the order things happened; the screen keeps them in
 * a form that redraws it (Compose state).
 */
interface WTScreen {
    var settings: WTSettings
    /** The pose the knobs show (the target, rounded on display). Never changed once set. */
    var shownPose: WTPose
    var panel: WTPanel
    var hud: WTHud
    /** A short note over the 3D view. */
    var note: String?
}

/**
 * Runs the wind tunnel simulator: the pose, the flight, and the analysis
 * shown in the panels. A port of WindTunnelEngine.swift (the web page's
 * app.js state and loop).
 *
 * On the iPhone everything runs on the main thread. Here the frame runs
 * on the 3D view's own thread, so the touch that turns a knob never
 * waits for the physics; every entry point takes the engine's lock, and
 * the picture reads the engine under the same lock ([frame], then the
 * scene's update inside one `synchronized (engine)`).
 */
class WindTunnelEngine(
    private val screen: WTScreen,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
    /** Preset fitting, rest numbers and stability curves. */
    private val work: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "wind-tunnel.analysis").apply { isDaemon = true } },
    /**
     * The steady-state prediction. On the iPhone it shares the one queue;
     * here it has its own, so a long prediction on a slow phone does not
     * keep a preset or the rest numbers waiting behind it.
     */
    private val steadyWork: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "wind-tunnel.steady").apply { isDaemon = true } },
) {
    var settings = WTSettings()
        private set(value) {
            field = value
            screen.settings = value
        }
    var shownPose: WTPose = WTPose()
        private set(value) {
            field = value
            screen.shownPose = value
        }
    var panel = WTPanel()
        private set(value) {
            field = value
            screen.panel = value
        }
    var hud = WTHud()
        private set(value) {
            field = value
            screen.hud = value
        }
    var note: String? = null
        private set(value) {
            field = value
            screen.note = value
        }

    @Volatile
    var zh = true

    // simulation state
    var target: WTPose
        private set
    var cur: WTPose
        private set
    var body: WTBody
        private set
    val sim = WTSim()
    var live: WTLive? = null
        private set
    var rest: WTRest? = null
        private set
    var curves: WTCurves? = null
        private set
    var flyInfo: WTFlyInfo? = null
        private set
    private var assistEff = ASSIST_MAX
    private val touched = HashMap<String, Double>()
    private var dimsDirty = false
    private var poseChanged = true
    private var freezeUntil = 0.0
    private var trimHoldUntil = 0.0
    private var lastPanel = -1e9
    private var lastHud = -1e9
    private var noteUntil = 0.0
    private var holdNoteAt = -1e9

    // the steady-state prediction and the slow panel numbers, worked out off the frame's thread
    var steady: WTSteady? = null
        private set
    var steadySym = false
        private set
    private var ssDirty = true
    private var ssAt = 0.0
    private var ssRunning = false
    private var ssGeneration = 0
    private var ssToken: AtomicBoolean? = null
    private var restBusy = false
    private var presetGeneration = 0

    private var lastTick = 0.0

    /** Bumped whenever the flight starts over, so the picture clears its trail. */
    var trailResetCount = 0
        private set

    init {
        val p = WTCore.presets[0].pose
        target = p.copy()
        cur = p.copy()
        body = WTCore.buildBody(p, 1.75, 75.0, 1.0)
        // The fitted preset arrives from the analysis thread; it waits for the lock until the engine is whole.
        synchronized(this) {
            shownPose = p.copy()
            sim.mode = WTSimMode.FLY
            screen.settings = settings
            applyPreset("box")
        }
    }

    // Loop

    /** One frame at the clock's time now. Returns the time step it took, in seconds. */
    @Synchronized
    fun frame(): Double {
        val now = clock()
        var dt = min(now - lastTick, 1.0 / 30)
        lastTick = now
        if (!(dt > 0)) dt = 1.0 / 60
        tick(now, dt)
        return dt
    }

    private val keys: List<String> get() = WTCore.poseKeys

    @Synchronized
    fun tick(now: Double, dt: Double) {
        val e = envNow()
        val a = 1 - exp(-dt * 9)
        var moved = false
        for (k in keys) {
            var d = target[k] - cur[k]
            if (k == "pitch" || k == "yaw") d = WTCore.wrap180(d)
            if (d == 0.0) continue
            if (abs(d) > 0.03) cur[k] += d * a else cur[k] = target[k]
            moved = true
        }
        cur.pitch = WTCore.wrap180(cur.pitch)
        if (moved || dimsDirty || poseChanged) {
            body = bodyOf(cur)
            dimsDirty = false
            poseChanged = false
        }
        flyInfo = null
        val mode = settings.mode
        when {
            mode == WTMode.PIN -> {
                sim.vel = WTVec.ZERO
                sim.yawRate = 0.0
                sim.yaw = cur.yaw
                sim.R = WTCore.orientQuat(cur.pitch, cur.roll, cur.yaw)
            }
            now < freezeUntil -> {
                // a preset is still morphing into place: it starts from rest once it is there
                sim.vel = WTVec.ZERO
                sim.yawRate = 0.0
                sim.omega = WTVec.ZERO
                sim.anchor = null
                if (mode == WTMode.FLY) {
                    sim.R = WTCore.orientQuat(cur.pitch, cur.roll, sim.yaw)
                    cur.yaw = sim.yaw
                    target.yaw = sim.yaw
                }
            }
            mode == WTMode.FLY -> {
                val wait = now < trimHoldUntil
                val info = WTCore.flyFrame(sim, body, cur, e, dt, 3, WTHold(pitch = settings.holdP || wait, roll = settings.holdR || wait))
                flyInfo = info
                if (info.freeP) target.pitch = cur.pitch
                if (info.freeR) target.roll = cur.roll
                cur.yaw = sim.yaw
                target.yaw = sim.yaw
            }
            else -> {
                val n = 8
                for (pass in 0 until n) WTCore.stepSim(sim, body, e, dt / n.toDouble(), cur)
                val eu = WTCore.eulerOf(sim.R)
                cur.pitch = eu.pitch; target.pitch = eu.pitch
                cur.roll = eu.roll; target.roll = eu.roll
                cur.yaw = eu.yaw; target.yaw = eu.yaw
            }
        }
        val check = sim.pos.x + sim.pos.y + sim.pos.z + sim.vel.x + sim.vel.y + sim.vel.z + sim.yaw + cur.pitch + cur.roll
        if (!check.isFinite() || !sim.R.w.isFinite()) {
            recenter()
            sim.yaw = 0.0
            sim.R = WTCore.orientQuat(0.0, 0.0, 0.0)
            sim.omega = WTVec.ZERO
            if (!(cur.pitch + cur.roll + cur.yaw).isFinite()) {
                for (k in listOf("pitch", "roll", "yaw")) {
                    cur[k] = 0.0
                    target[k] = 0.0
                }
            }
        }
        computeLive(e)
        ssKick(now)
        if (now - lastPanel > 0.15) {
            lastPanel = now
            refreshSlow()
            refreshPanel()
            syncShownPose(now)
        }
        if (now - lastHud > 1.0 / 15) {
            lastHud = now
            refreshHud()
        }
        if (note != null && now >= noteUntil) note = null
    }

    // Environment

    @Synchronized
    fun envBase(): WTEnv = WTEnv(
        rho = WTCore.airDensity(settings.alt.toDouble()), k = settings.cal,
        suit = WTCore.suits[settings.suit] ?: WTCore.suits.getValue("std"), V = settings.V,
    )

    private fun envNow(): WTEnv = envBase().copy(assist = assistEff, bounded = settings.env == WTEnvKind.TUNNEL)

    /** The hold fades out as the pose's own drive grows, so it never fights a pose meant to travel. */
    private fun assistFor(ratio: Double): Double =
        if (settings.center) ASSIST_MAX * WTCore.clamp((0.07 - ratio) / 0.03, 0.0, 1.0) else 0.0

    val legScale: Double get() = 1 + settings.legs.toDouble() / 100

    private fun bodyOf(p: WTPose): WTBody = WTCore.buildBody(p, settings.H.toDouble() / 100, settings.M.toDouble(), legScale)

    /** The speed at the net that holds a pose of this fall rate at chest height. */
    private fun tunnelHover(veq: Double): Double = veq / (1 - WTCore.TUNNEL.gradient * WTCore.TUNNEL.hoverY)

    @Synchronized
    fun liveYaw(): Double = when (settings.mode) {
        WTMode.PIN -> cur.yaw
        WTMode.FLY -> sim.yaw
        WTMode.FREE -> WTCore.eulerOf(sim.R).yaw
    }

    // Input

    @Synchronized
    fun setJoint(key: String, value: Double) {
        val now = clock()
        fun put(k: String) {
            touched[k] = now
            target[k] = value
            cur[k] = value
        }
        put(key)
        val base = limbBase(key)
        if (base != null && settings.link.contains(base)) put(base + (if (key.endsWith("L")) "R" else "L"))
        if (key == "yaw") {
            sim.yaw = value
            sim.yawRate = 0.0
        }
        // an angle set by hand is an angle the flier holds
        if (settings.mode != WTMode.FREE && key == "pitch" && !settings.holdP) setHold("pitch", true, fromDrag = true)
        if (settings.mode != WTMode.FREE && key == "roll" && !settings.holdR) setHold("roll", true, fromDrag = true)
        if (settings.mode == WTMode.FREE && (key == "pitch" || key == "roll" || key == "yaw")) {
            sim.R = WTCore.orientQuat(target.pitch, target.roll, target.yaw)
            sim.omega = WTVec.ZERO
        }
        poseChanged = true
        ssTouch(now)
        if (!settings.modified) settings = settings.copy(modified = true)
        shownPose = target.copy()
    }

    @Synchronized
    fun toggleLink(base: String) {
        settings = settings.copy(link = if (settings.link.contains(base)) settings.link - base else settings.link + base)
    }

    fun resetJoint(key: String) = setJoint(key, 0.0)

    @Synchronized
    fun setHold(axis: String, on: Boolean, fromDrag: Boolean = false) {
        settings = if (axis == "pitch") settings.copy(holdP = on) else settings.copy(holdR = on)
        ssTouch(clock())
        val t = WTText(zh)
        val name = if (axis == "pitch") t.s("俯仰", "Pitch") else t.s("侧倾", "Roll")
        if (settings.mode == WTMode.PIN) {
            if (!fromDrag) {
                showNote(
                    if (on) t.s("${name}按住：推算飞起来以后的运动时，这个角度保持不动。", "$name held: the prediction of the flight keeps this angle.")
                    else t.s("${name}松开：推算飞起来以后的运动时，让它自己找平衡。", "$name released: the prediction lets it find its own balance."),
                )
            }
        } else if (fromDrag) {
            val now = clock()
            if (now - holdNoteAt > 9) {
                holdNoteAt = now
                showNote(t.s("已按住这个${name}角度。点一下「保持」松开，身体会回到自己的平衡角度。", "$name is now held at this angle. Tap Hold to release it and the body returns to its own balance."))
            }
        } else if (on) {
            showNote(t.s("${name}按住在设定的角度，像飞行者一直用力稳住。", "$name is held at the set angle, as if the flier keeps it there."))
        } else {
            showNote(
                if (axis == "pitch") t.s("俯仰松开了。能自己回正的话，会回到平衡角度。", "Pitch released. If it rights itself, it returns to its balance angle.")
                else t.s("侧倾松开了。身体回到自己的平衡，两侧受力不一样时会被带着倾斜。", "Roll released. The body finds its own balance and leans when the two sides carry different loads."),
            )
        }
    }

    @Synchronized
    fun applyPreset(id: String) {
        val pr = WTCore.preset(id)
        val e = envBase()
        val H = settings.H.toDouble() / 100; val M = settings.M.toDouble(); val legs = legScale
        presetGeneration += 1
        val generation = presetGeneration
        launch(work) {
            try {
                val p = WTCore.presetPose(pr, H = H, M = M, legs = legs, env = e)
                val a = WTCore.analyse(WTCore.buildBody(p, H, M, legs), p, 50.0, e)
                synchronized(this) {
                    if (generation == presetGeneration) finishPreset(pr, p, a)
                }
            } catch (_: RuntimeException) {
                // The pose stays as it is.
            }
        }
    }

    private fun finishPreset(pr: WTPreset, pose: WTPose, a: WTAnalysis) {
        val now = clock()
        val p = pose.copy()
        p.yaw = liveYaw()
        target = p
        settings = settings.copy(preset = pr.id, modified = false, holdP = pr.hold.pitch, holdR = pr.hold.roll)
        sim.yawRate = 0.0
        if (settings.mode == WTMode.FREE) {
            sim.R = WTCore.orientQuat(p.pitch, p.roll, p.yaw)
            sim.omega = WTVec.ZERO
        }
        assistEff = assistFor(a.ratio)
        trimHoldUntil = now + 0.65
        if (settings.autoMatch && settings.env == WTEnvKind.TUNNEL) {
            if (a.veq.isFinite()) setWind(tunnelHover(a.veq))
            recenter()
            freezeUntil = trimHoldUntil
        }
        touched.clear()
        poseChanged = true
        ssTouch(now)
        shownPose = target.copy()
        lastPanel = -1e9
    }

    @Synchronized
    fun recenter() {
        sim.pos = if (settings.env == WTEnvKind.TUNNEL) WTVec(0.0, WTCore.TUNNEL.hoverY, 0.0) else WTVec.ZERO
        sim.vel = WTVec.ZERO
        sim.yawRate = 0.0
        sim.anchor = null
        if (settings.mode == WTMode.FREE) sim.omega = WTVec.ZERO
        trailResetCount += 1
    }

    @Synchronized
    fun setWind(v: Double) {
        settings = settings.copy(V = WTCore.clamp(v, 0.0, WIND_MAX))
    }

    @Synchronized
    fun setMode(m: WTMode) {
        if (m == settings.mode) return
        val y = liveYaw()
        if (settings.mode == WTMode.FREE) {
            val eu = WTCore.eulerOf(sim.R)
            target.pitch = eu.pitch; cur.pitch = eu.pitch
            target.roll = eu.roll; cur.roll = eu.roll
        }
        target.yaw = y; cur.yaw = y; sim.yaw = y
        if (m == WTMode.FREE) {
            sim.R = WTCore.orientQuat(cur.pitch, cur.roll, y)
            sim.omega = WTVec(0.0, if (settings.mode == WTMode.FLY) sim.yawRate else 0.0, 0.0)
        }
        if (m == WTMode.PIN) sim.vel = WTVec.ZERO
        sim.yawRate = 0.0
        sim.anchor = null
        settings = settings.copy(mode = m)
        sim.mode = when (m) {
            WTMode.PIN -> WTSimMode.PIN
            WTMode.FLY -> WTSimMode.FLY
            WTMode.FREE -> WTSimMode.FREE
        }
        showNote(WTText(zh).modeNote(m))
        ssTouch(clock())
    }

    @Synchronized
    fun setEnv(v: WTEnvKind) {
        if (v == settings.env) return
        if (v == WTEnvKind.FREE) {
            setWind(0.0)
        } else {
            val a = WTCore.analyse(bodyOf(target), target, 50.0, envBase())
            setWind(if (a.veq.isFinite()) tunnelHover(a.veq) else 0.0)
        }
        settings = settings.copy(env = v)
        recenter()
        sim.onNet = false
        sim.onWall = false
        ssTouch(clock())
        lastPanel = -1e9
    }

    @Synchronized
    fun setUnit(u: WTUnit) {
        settings = settings.copy(unit = u)
        lastPanel = -1e9
    }

    @Synchronized
    fun setMeasure(m: WTMeasure) {
        settings = settings.copy(measure = m)
        refreshPanel()
    }

    @Synchronized
    fun setAutoMatch(on: Boolean) {
        settings = settings.copy(autoMatch = on)
    }

    @Synchronized
    fun setCenter(on: Boolean) {
        settings = settings.copy(center = on)
        sim.anchor = null
        ssTouch(clock())
    }

    @Synchronized
    fun setBody(H: Int? = null, M: Int? = null, legs: Int? = null, alt: Int? = null, cal: Double? = null, suit: String? = null) {
        var st = settings
        if (H != null) { st = st.copy(H = H); dimsDirty = true }
        if (M != null) { st = st.copy(M = M); dimsDirty = true }
        if (legs != null) { st = st.copy(legs = legs); dimsDirty = true }
        if (alt != null) st = st.copy(alt = alt)
        if (cal != null) st = st.copy(cal = cal)
        if (suit != null) st = st.copy(suit = suit)
        settings = st
        ssTouch(clock())
    }

    private fun showNote(text: String) {
        note = text
        noteUntil = clock() + 5.2
    }

    @Synchronized
    fun dismissNote() {
        note = null
    }

    // Analysis

    private fun computeLive(e: WTEnv) {
        val pin = settings.mode == WTMode.PIN
        val U: WTVec = if (pin) {
            val veq = rest?.veq?.takeIf { it.isFinite() } ?: 50.0
            WTVec(0.0, if (settings.env == WTEnvKind.TUNNEL) settings.V else veq, 0.0)
        } else {
            WTCore.relWind(sim, e)
        }
        val sp = WTCore.len(U)
        val U50 = if (sp > 0.05) U * (50 / sp) else WTVec(0.0, 50.0, 0.0); val s = (sp / 50) * (sp / 50)
        val a = WTCore.analyse(body, cur, 50.0, e, U50, if (settings.mode == WTMode.FREE) sim.R else null)
        live = WTLive(
            a = a, s = s, U = U, U50 = U50, speed = sp,
            tilt = if (sp > 0.05) acos(WTCore.clamp(U.y / sp, -1.0, 1.0)) * WTCore.R2D else 0.0, W = a.W,
            F = a.F * s, D = a.D * s, L = a.L * s, Lmag = a.Lmag * s, ratio = a.ratio, Fy = a.Fy * s,
            mPitch = a.mPitch * s, mRoll = a.mRoll * s, mYaw = a.mYaw * s,
        )
    }

    /** Rest numbers and the stability curves, off the frame's thread. */
    private fun refreshSlow() {
        val live = live ?: return
        if (restBusy) return
        restBusy = true
        val body = body; val pose = cur.copy(); val e = envBase(); val mode = settings.mode
        val hold = WTHold(pitch = settings.holdP, roll = settings.holdR); val R = sim.R; val U50 = live.U50
        launch(work) {
            var restValue: WTRest? = null
            var curvesValue: WTCurves? = null
            try {
                val q = pose.copy()
                if (mode == WTMode.FLY) {
                    val t = WTCore.findTrim(body, pose, e, 40, null, hold)
                    if (t.freeP) q.pitch = t.pitch
                    if (t.freeR) q.roll = t.roll
                }
                val a = WTCore.analyse(body, q, 50.0, e, null, if (mode == WTMode.FREE) R else null)
                restValue = WTRest(a = a, veq = a.veq, cda = a.cda, ratio = a.ratio, shape = a.shape)
                curvesValue = WTCore.stabilityCurves(body, pose, 50.0, e, 60.0, 3.0, U50)
            } catch (_: RuntimeException) {
                // The panel keeps the numbers it has.
            } finally {
                synchronized(this) {
                    val r = restValue; val c = curvesValue
                    if (r != null && c != null) {
                        rest = r
                        curves = c
                        assistEff = assistFor(r.ratio)
                    }
                    restBusy = false
                }
            }
        }
    }

    /** Runs [block] on [executor]; after [close] nothing more is started. */
    private fun launch(executor: Executor, block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Closed: the screen has gone.
        }
    }

    /** Stops the background threads. Call once the 3D view no longer draws. */
    fun close() {
        (work as? ExecutorService)?.shutdownNow()
        (steadyWork as? ExecutorService)?.shutdownNow()
        ssToken?.set(true)
    }

    private fun ssTouch(now: Double) {
        ssDirty = true
        ssAt = now
    }

    val ssStale: Boolean get() = ssDirty || ssRunning

    private fun ssKick(now: Double) {
        if (!(ssDirty && now - ssAt >= 0.28 && now >= trimHoldUntil)) return
        ssDirty = false
        ssGeneration += 1
        val generation = ssGeneration
        ssToken?.set(true)
        val token = AtomicBoolean(false)
        ssToken = token
        if (settings.mode == WTMode.FREE) {
            steady = null
            ssRunning = false
            return
        }
        // a pinned pose is predicted the way it would be flown: only the held angles stay as they are
        val e = envBase()
        val hold = WTHold(pitch = settings.holdP, roll = settings.holdR)
        val q = target.copy()
        q.yaw = 0.0
        val b = bodyOf(q)
        steadySym = mirrorSame(q)
        ssRunning = true
        val center = settings.center
        launch(steadyWork) {
            var res: WTSteady? = null
            var cancelled = false
            try {
                val t = WTCore.findTrim(b, q, e, 40, null, hold)
                if (t.freeP) q.pitch = t.pitch
                if (t.freeR) q.roll = t.roll
                val ratio = WTCore.analyse(b, q, 50.0, e).ratio
                val e2 = e.copy(assist = if (center) ASSIST_MAX * WTCore.clamp((0.07 - ratio) / 0.03, 0.0, 1.0) else 0.0)
                val job = WTCore.steadyBegin(b, q, e2, hold = hold)
                if (job != null) {
                    // a newer request makes this run pointless
                    while (!WTCore.steadyRun(job, 30)) {
                        if (token.get()) {
                            cancelled = true
                            break
                        }
                    }
                    if (!cancelled) res = WTCore.steadyResult(job)
                }
            } catch (_: RuntimeException) {
                // No prediction for this pose.
            } finally {
                if (!cancelled) synchronized(this) { finishSteady(res, generation) }
            }
        }
    }

    private fun finishSteady(res: WTSteady?, generation: Int) {
        if (generation != ssGeneration) return
        steady = res
        ssRunning = false
        lastPanel = -1e9
    }

    /** True when the roll is balanced by the flier around level and leans with the pose. */
    fun rollLeans(): Boolean {
        val info = flyInfo
        if (info != null && info.rest != null) return info.leanR
        return !((curves?.stiffRoll ?: 0.0) >= WTCore.LEAN.stiff)
    }

    private fun syncShownPose(now: Double) {
        val p = target.copy()
        for (k in keys) {
            val t = touched[k]
            if (t != null && now - t < 0.4) p[k] = shownPose[k]
        }
        if (p != shownPose) shownPose = p
    }

    private fun refreshHud() {
        val pin = settings.mode == WTMode.PIN
        val vy = if (pin) 0.0 else sim.vel.y
        val vh = if (pin) 0.0 else sqrt(sim.vel.x * sim.vel.x + sim.vel.z * sim.vel.z)
        val h = WTHud(vy = vy, vh = vh, glide = if (vy < -0.3) vh / -vy else null)
        if (h != hud) hud = h
    }

    private fun refreshPanel() {
        val live = live ?: return
        val built = WTPanelBuilder(this, live, WTText(zh)).build()
        if (built != panel) panel = built
    }

    companion object {
        const val ASSIST_MAX = 0.06
        const val WIND_MAX = 320 / 3.6

        /** "shAbd" for "shAbdL", null for a core key. */
        fun limbBase(key: String): String? {
            if (!(key.endsWith("L") || key.endsWith("R"))) return null
            val base = key.dropLast(1)
            return if (WTCore.armKeys.contains(base) || WTCore.legKeys.contains(base)) base else null
        }

        /** A pose that is the same on the left and on the right. */
        fun mirrorSame(p: WTPose): Boolean {
            if (abs(p.roll) > 0.05 || abs(p["neckSide"]) > 0.05 || abs(p["neckTurn"]) > 0.05 || abs(p["spineSide"]) > 0.05 || abs(p["spineTwist"]) > 0.05) return false
            for (k in WTCore.armKeys + WTCore.legKeys) if (abs(p[k + "L"] - p[k + "R"]) > 0.05) return false
            return true
        }
    }
}
