package com.skyalt.canopy.windtunnel

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** The named views of the 3D picture, taken relative to where the body heads. */
enum class WTView { HOME, SIDE, FRONT, TOP }

/**
 * The 3D picture of the wind tunnel simulator, worked out once a frame:
 * the mannequin, the force arrows, the air streaks, the path flown and the
 * surroundings (the tunnel's net and glass, or reference levels in open
 * air). Everything is drawn relative to the body, which sits at the origin
 * of the scene. A port of WindTunnelSceneController.swift without the
 * drawing itself: this class holds the numbers, [WindTunnelRenderer] puts
 * them on the screen. It is used from one thread (the 3D view's).
 */
class WindTunnelSceneModel(private val random: Random = Random.Default) {
    /** A shaft and a head along a force, as two placements of a unit cylinder and a unit cone. */
    class Arrow(val radius: Double, val rgb: Int) {
        var visible = false
            private set
        val shaft = FloatArray(16)
        val head = FloatArray(16)
        private val headLength = radius * 7

        fun set(origin: WTVec, vector: WTVec, scale: Double, show: Boolean) {
            val vl = WTCore.len(vector)
            val length = vl * scale
            if (!show || !(length > 0.03)) {
                visible = false
                return
            }
            visible = true
            val d = vector / vl
            // any two directions square to the arrow and to each other
            val helper = if (abs(d.y) < 0.9) WTVec(0.0, 1.0, 0.0) else WTVec(1.0, 0.0, 0.0)
            var x = WTCore.cross(helper, d)
            x = x / WTCore.len(x)
            val z = WTCore.cross(x, d)
            val hl = min(headLength, length * 0.55); val sl = length - hl
            val hw = radius * 2.7 * (hl / headLength)
            WTMat.place(shaft, x * radius, d * max(sl, 0.0001), z * radius, origin + d * (sl / 2))
            WTMat.place(head, x * hw, d * hl, z * hw, origin + d * (sl + hl / 2))
        }
    }

    /** One reference level in open air: a fine and a coarse grid that slide under the body. */
    class Level {
        var y = 0.0
        var fineX = 0.0
        var fineZ = 0.0
        var coarseX = 0.0
        var coarseZ = 0.0
        /** 0 when the grid is not drawn. */
        var fineOpacity = 0.0
        var coarseOpacity = 0.0
    }

    // Camera: it looks at the body and travels with it
    var theta = 0.95
        private set
    var phi = 1.3
        private set
    var radius = 7.5
        private set
    private var zoomed = false
    /** Where two fingers moved the point the camera looks at, from the body. */
    private var panOffset = WTVec.ZERO
    private var lead = WTVec.ZERO
    /** The view's size in points (dp), for the camera distance and the fingers' scale. */
    var viewWidth = 600.0
    var viewHeight = 400.0

    var eye = WTVec.ZERO
        private set
    var target = WTVec.ZERO
        private set
    var right = WTVec(1.0, 0.0, 0.0)
        private set
    var up = WTVec(0.0, 1.0, 0.0)
        private set
    private var forward = WTVec(0.0, 0.0, -1.0)
    private val view = DoubleArray(16)
    private val projection = DoubleArray(16)
    val viewProjection = FloatArray(16)

    // Mannequin
    var parts: List<WTFigurePart> = emptyList()
        private set
    /** Goes up whenever [parts] are made anew, so their triangles are sent to the GPU again. */
    var figureVersion = 0
        private set
    private var figureKey = ""
    /** Where each body segment and each joint is drawn: a column-major matrix each. */
    var segmentModels: Array<FloatArray> = emptyArray()
        private set
    var jointModels: Array<FloatArray> = emptyArray()
        private set
    var highlighted: Set<String> = emptySet()
        private set
    private var body: WTBody? = null
    private var bodyR = WTQuat.IDENTITY

    // Arrows
    var segmentArrows: List<Arrow> = emptyList()
        private set
    val netArrow = Arrow(0.019, FORCE)
    val weightArrow = Arrow(0.019, WEIGHT)
    var showsArrows = true
    var showsFlow = true
    var showsTrail = true

    // Surroundings, all placed relative to the body
    var tunnelOn = false
        private set
    /** Where the world's origin (the middle of the net, or the start point) is in the picture. */
    var world = WTVec.ZERO
        private set
    val levels = Array(3) { Level() }
    var ringsOpacity = 0.0
        private set
    var shadowY = 0.0
        private set
    var shadowScale = 0.95
        private set
    var shadowOpacity = 0.0
        private set
    /** Length of the line from the body down to the level below; 0 when it is not drawn. */
    var dropLength = 0.0
        private set
    var dropY = 0.0
        private set

    // Air streaks: pairs of points, x y z each
    private val streakSeeds = Array(STREAK_COUNT) {
        WTVec(random.nextDouble(-1.0, 1.0) * STREAK_BOX.x, random.nextDouble(-1.0, 1.0) * STREAK_BOX.y, random.nextDouble(-1.0, 1.0) * STREAK_BOX.z)
    }
    val streakPoints = FloatArray(STREAK_COUNT * 6)
    var streaksVisible = false
        private set

    // The path flown: a strip of points relative to the body
    private val trail = ArrayList<WTVec>()
    private var trailClock = 0.0
    private var trailReset = -1
    val trailPoints = FloatArray((TRAIL_MAX + 1) * 3)
    /** Number of points in [trailPoints]; the strip is drawn when there are at least two. */
    var trailCount = 0
        private set

    /** False until the engine has worked out its first frame. */
    var ready = false
        private set

    init {
        placeCamera()
    }

    // Frame

    /** Call with the engine's lock held. */
    fun update(engine: WindTunnelEngine, dt: Double) {
        val live = engine.live ?: return
        val sim = engine.sim; val body = engine.body; val st = engine.settings
        placeFigure(body, if (st.mode == WTMode.FREE) sim.R else live.a.R, "${st.H}|${st.M}|${st.legs}")

        // force arrows: the total at 1 m per 450 N, each part at twice that
        if (segmentArrows.size != live.a.per.size) segmentArrows = live.a.per.map { Arrow(0.009, FORCE) }
        for ((j, pe) in live.a.per.withIndex()) {
            val start = WTFigure.surfacePoint(body.segs[j], live.a.R, pe.r, pe.f, live.s * SEG_SCALE)
            segmentArrows[j].set(start, pe.f * live.s, SEG_SCALE, showsArrows)
        }
        netArrow.set(live.a.cp, live.F, NET_SCALE, showsArrows)
        weightArrow.set(WTVec.ZERO, WTVec(0.0, -live.W, 0.0), NET_SCALE, showsArrows)

        val pin = st.mode == WTMode.PIN
        val pos = sim.pos
        val vel = if (pin) WTVec.ZERO else sim.vel
        val tun = st.env == WTEnvKind.TUNNEL
        updateStreaks(live, tun, pos, dt)

        // reference levels; they fade when the body crosses them too fast to read
        val vh = sqrt(vel.x * vel.x + vel.z * vel.z)
        val fv = WTCore.clamp(1 - (abs(vel.y) - 6) / 10, 0.0, 1.0)
        val fFine = fv * WTCore.clamp(1 - (vh - 6) / 10, 0.0, 1.0); val fCoarse = fv * WTCore.clamp(1 - (vh - 30) / 30, 0.0, 1.0)
        val base = floor((pos.y - LEVEL_BASE) / LEVEL_STEP)
        for (j in 0 until 3) {
            val y = LEVEL_BASE + (base - 1 + j) * LEVEL_STEP - pos.y
            val near = if (y <= 0) WTCore.clamp((16 + y) / 8, 0.0, 1.0) else WTCore.clamp(1 - (y - 2) / 4, 0.0, 1.0)
            val level = levels[j]
            level.y = y
            level.fineX = -pmod(pos.x, 1.0); level.fineZ = -pmod(pos.z, 1.0)
            level.coarseX = -pmod(pos.x, 5.0); level.coarseZ = -pmod(pos.z, 5.0)
            level.fineOpacity = if (tun || near * fFine < 0.01) 0.0 else 0.32 * near * fFine
            level.coarseOpacity = if (tun || near * fCoarse < 0.01) 0.0 else 0.55 * near * fCoarse
        }
        tunnelOn = tun
        world = -pos
        ringsOpacity = if (tun) 0.0 else fv * WTCore.clamp(1 - (abs(LEVEL_BASE - pos.y) - 4) / 7, 0.0, 1.0) * 0.9 + 0.1

        val ly = if (tun) 0.015 - pos.y else LEVEL_BASE + floor((pos.y - LEVEL_BASE) / LEVEL_STEP) * LEVEL_STEP - pos.y
        val dist = -ly
        shadowScale = WTCore.clamp(0.95 - dist * 0.04, 0.55, 0.95)
        shadowY = ly + 0.012
        shadowOpacity = WTCore.clamp(0.5 - dist * 0.045, 0.07, 0.5) * fv
        dropLength = if (dist <= 0.6) 0.0 else max(dist, 0.001)
        dropY = ly

        updateTrail(pos, dt, engine.trailResetCount)

        // the camera hangs back a little, so the body sits ahead of the middle of the picture when it travels
        val vl = WTCore.len(vel); val kl = if (vl > 1e-6) min(vl * 0.1, 1.0) / vl else 0.0; val kk = min(1.0, dt * 2)
        lead += (vel * kl - lead) * kk
        placeCamera()
        ready = true
    }

    // Camera

    private fun fitRadius(): Double {
        val aspect = if (viewHeight > 0) viewWidth / viewHeight else 1.5
        return max(7.5, 2.0 / (tan(FOV * Math.PI / 360) * aspect))
    }

    private fun placeCamera() {
        if (!zoomed) radius = fitRadius()
        val sp = sin(phi)
        target = WTVec(-lead.x, 0.1 - lead.y, -lead.z) + panOffset
        eye = target + WTVec(radius * sp * sin(theta), radius * cos(phi), radius * sp * cos(theta))
        forward = (target - eye) / radius
        var r = WTCore.cross(forward, WTVec(0.0, 1.0, 0.0))
        val rl = WTCore.len(r)
        r = if (rl > 1e-9) r / rl else WTVec(1.0, 0.0, 0.0)
        right = r
        up = WTCore.cross(r, forward)
        val aspect = if (viewHeight > 0) viewWidth / viewHeight else 1.5
        WTMat.lookAt(view, eye, r, up, forward)
        WTMat.perspective(projection, FOV, aspect, Z_NEAR, Z_FAR)
        WTMat.multiply(viewProjection, projection, view)
    }

    fun orbit(dx: Double, dy: Double) {
        theta -= dx * 0.008
        phi = WTCore.clamp(phi - dy * 0.008, 0.06, 2.6)
        placeCamera()
    }

    /**
     * Two fingers slide the picture: the point looked at moves across the
     * screen with the fingers, at the scale of the body's distance.
     */
    fun pan(dx: Double, dy: Double) {
        val sp = sin(phi)
        val toEye = WTVec(sp * sin(theta), cos(phi), sp * cos(theta))
        var r = WTCore.cross(-toEye, WTVec(0.0, 1.0, 0.0))
        val rl = WTCore.len(r)
        if (!(rl > 1e-6)) return
        r = r / rl
        val u = WTCore.cross(r, -toEye)
        val metersPerPoint = 2 * radius * tan(FOV * Math.PI / 360) / max(viewHeight, 1.0)
        panOffset = panOffset - r * (dx * metersPerPoint) + u * (dy * metersPerPoint)
        val l = WTCore.len(panOffset)
        if (l > 8) panOffset = panOffset * (8 / l)
        placeCamera()
    }

    fun zoom(factor: Double) {
        if (!(factor > 0)) return
        radius = WTCore.clamp(radius / factor, 3.5, 40.0)
        zoomed = true
        placeCamera()
    }

    /** The named views are taken relative to where the body heads now ([yaw], degrees). */
    fun setView(view: WTView, yaw: Double) {
        val y = yaw * WTCore.D2R
        when (view) {
            WTView.SIDE -> { theta = y + Math.PI / 2; phi = 1.42 }
            WTView.FRONT -> { theta = y; phi = 1.42 }
            WTView.TOP -> { theta = y + Math.PI; phi = 0.06 }
            WTView.HOME -> { theta = y + 0.95; phi = 1.3 }
        }
        zoomed = false
        panOffset = WTVec.ZERO
        placeCamera()
    }

    /** Sets the view's size in points (dp). */
    fun resize(width: Double, height: Double) {
        viewWidth = width
        viewHeight = height
        placeCamera()
    }

    // Mannequin

    private fun placeFigure(body: WTBody, R: WTQuat, key: String) {
        if (key != figureKey || segmentModels.size != body.segs.size || jointModels.size != body.joints.size) {
            parts = WTFigure.build(body)
            segmentModels = Array(body.segs.size) { FloatArray(16) }
            jointModels = Array(body.joints.size) { FloatArray(16) }
            figureKey = key
            figureVersion += 1
        }
        for ((j, sg) in body.segs.withIndex()) {
            WTMat.rigid(segmentModels[j], WTCore.qMul(R, sg.q), WTCore.qRot(R, sg.c - body.com))
        }
        for ((j, jt) in body.joints.withIndex()) {
            WTMat.rigid(jointModels[j], R, WTCore.qRot(R, jt.p - body.com))
        }
        this.body = body
        bodyR = R
    }

    /** Lights up the parts of these groups ("armL", "legR", "spine", "head"). */
    fun highlight(groups: Set<String>) {
        highlighted = groups
    }

    /**
     * The group of the body part under a point of the view, if any; [x]
     * and [y] in points from its top left. A part a finger just misses
     * still counts: next to it within a few points.
     */
    fun group(x: Double, y: Double): String? {
        val body = body ?: return null
        if (!(viewWidth > 0 && viewHeight > 0)) return null
        val half = tan(FOV * Math.PI / 360)
        val sx = (2 * x / viewWidth - 1) * half * (viewWidth / viewHeight)
        val sy = (1 - 2 * y / viewHeight) * half
        var d = forward + right * sx + up * sy
        d = d / WTCore.len(d)
        WTFigure.group(body, bodyR, eye, d, 0.0)?.let { return it }
        val metersPerPoint = 2 * radius * half / viewHeight
        return WTFigure.group(body, bodyR, eye, d, min(metersPerPoint * PICK_SLACK_POINTS, 0.16))
    }

    // Surroundings

    private fun updateStreaks(live: WTLive, tunnelOn: Boolean, pos: WTVec, dt: Double) {
        val sp = live.speed
        val show = showsFlow && sp > 0.5
        streaksVisible = show
        if (!show) return
        val U = live.U; val b = STREAK_BOX
        val kv = 0.12 * dt; val ln = WTCore.clamp(sp / 50 * 0.42, 0.05, 0.8) / sp
        for (j in streakSeeds.indices) {
            val seed = streakSeeds[j]
            val s = WTVec(wrapBox(seed.x + U.x * kv, b.x), wrapBox(seed.y + U.y * kv, b.y), wrapBox(seed.z + U.z * kv, b.z))
            streakSeeds[j] = s
            // fade with distance from the body: short streaks far out
            val fade = WTCore.clamp(1 - (sqrt(s.x * s.x + s.z * s.z) - 3.4) / 2, 0.0, 1.0) * WTCore.clamp(1 - (abs(s.y) - 3) / 1.5, 0.0, 1.0)
            var e = s + U * (ln * fade)
            if (tunnelOn) {
                // in the tunnel the air runs inside the glass and below the top of the diffuser only
                val w = s + pos
                if (sqrt(w.x * w.x + w.z * w.z) > WTCore.TUNNEL.radius - 0.05 || w.y < -0.5 || w.y > WTCore.TUNNEL.glassTop + 3) e = s
            }
            val k = j * 6
            streakPoints[k] = s.x.toFloat(); streakPoints[k + 1] = s.y.toFloat(); streakPoints[k + 2] = s.z.toFloat()
            streakPoints[k + 3] = e.x.toFloat(); streakPoints[k + 4] = e.y.toFloat(); streakPoints[k + 5] = e.z.toFloat()
        }
    }

    private fun updateTrail(pos: WTVec, dt: Double, reset: Int) {
        if (reset != trailReset) {
            trailReset = reset
            trail.clear()
        }
        trailClock += dt
        val last = trail.lastOrNull()
        if (last != null) {
            if (trailClock > 0.06 && WTCore.len(pos - last) > 0.05) {
                trail.add(pos)
                if (trail.size > TRAIL_MAX) trail.removeAt(0)
                trailClock = 0.0
            }
        } else {
            trail.add(pos)
        }
        if (!showsTrail || trail.size < 2) {
            trailCount = 0
            return
        }
        // the points flown through, then where the body is now, which is the origin
        for ((i, p) in trail.withIndex()) {
            trailPoints[i * 3] = (p.x - pos.x).toFloat(); trailPoints[i * 3 + 1] = (p.y - pos.y).toFloat(); trailPoints[i * 3 + 2] = (p.z - pos.z).toFloat()
        }
        val k = trail.size * 3
        trailPoints[k] = 0f; trailPoints[k + 1] = 0f; trailPoints[k + 2] = 0f
        trailCount = trail.size + 1
    }

    companion object {
        const val SUIT = 0xf4aa2e
        const val FORCE = 0x5aa2f2
        const val WEIGHT = 0xc9d4dc
        const val LINE = 0x6f8799
        const val WIND = 0x86c5f2
        const val GLASS = 0x7fb3dd
        const val NET = 0x8a9aa6
        const val HIGHLIGHT = 0x5aa2f2
        const val TOP = 0x0a1118
        const val BOTTOM = 0x172530
        const val LABEL = 0xa9b6c0

        /** The camera's field of view, top to bottom, in degrees. */
        const val FOV = 36.0
        const val Z_NEAR = 0.1
        const val Z_FAR = 400.0
        const val FOG_START = 16.0
        const val FOG_END = 70.0
        const val LEVEL_BASE = -2.0
        const val LEVEL_STEP = 10.0
        const val STREAK_COUNT = 280
        val STREAK_BOX = WTVec(5.6, 4.6, 5.6)
        const val TRAIL_MAX = 520
        const val NET_SCALE = 1.0 / 450
        const val SEG_SCALE = 2.0 / 450
        /** How far beside a body part a tap still picks it, in points. */
        const val PICK_SLACK_POINTS = 9.0

        private fun wrapBox(v: Double, h: Double): Double = if (v > h) v - 2 * h else if (v < -h) v + 2 * h else v

        fun pmod(a: Double, m: Double): Double = (a % m + m) % m
    }
}

/** 4 × 4 matrices the way OpenGL takes them: sixteen numbers, column after column. */
object WTMat {
    /** Three axes and a place: a point (x, y, z) of the shape goes to [at] + x·[x] + y·[y] + z·[z]. */
    fun place(m: FloatArray, x: WTVec, y: WTVec, z: WTVec, at: WTVec) {
        m[0] = x.x.toFloat(); m[1] = x.y.toFloat(); m[2] = x.z.toFloat(); m[3] = 0f
        m[4] = y.x.toFloat(); m[5] = y.y.toFloat(); m[6] = y.z.toFloat(); m[7] = 0f
        m[8] = z.x.toFloat(); m[9] = z.y.toFloat(); m[10] = z.z.toFloat(); m[11] = 0f
        m[12] = at.x.toFloat(); m[13] = at.y.toFloat(); m[14] = at.z.toFloat(); m[15] = 1f
    }

    /** A turn by [q], then a move to [at]. */
    fun rigid(m: FloatArray, q: WTQuat, at: WTVec) {
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        m[0] = (1 - 2 * (y * y + z * z)).toFloat(); m[1] = (2 * (x * y + z * w)).toFloat(); m[2] = (2 * (x * z - y * w)).toFloat(); m[3] = 0f
        m[4] = (2 * (x * y - z * w)).toFloat(); m[5] = (1 - 2 * (x * x + z * z)).toFloat(); m[6] = (2 * (y * z + x * w)).toFloat(); m[7] = 0f
        m[8] = (2 * (x * z + y * w)).toFloat(); m[9] = (2 * (y * z - x * w)).toFloat(); m[10] = (1 - 2 * (x * x + y * y)).toFloat(); m[11] = 0f
        m[12] = at.x.toFloat(); m[13] = at.y.toFloat(); m[14] = at.z.toFloat(); m[15] = 1f
    }

    /** The camera at [eye] with its right, up and forward directions (unit length, square to each other). */
    fun lookAt(m: DoubleArray, eye: WTVec, right: WTVec, up: WTVec, forward: WTVec) {
        m[0] = right.x; m[1] = up.x; m[2] = -forward.x; m[3] = 0.0
        m[4] = right.y; m[5] = up.y; m[6] = -forward.y; m[7] = 0.0
        m[8] = right.z; m[9] = up.z; m[10] = -forward.z; m[11] = 0.0
        m[12] = -WTCore.dot(right, eye); m[13] = -WTCore.dot(up, eye); m[14] = WTCore.dot(forward, eye); m[15] = 1.0
    }

    /** A perspective [fovy] degrees high, from [near] to [far]. */
    fun perspective(m: DoubleArray, fovy: Double, aspect: Double, near: Double, far: Double) {
        val f = 1 / tan(fovy * Math.PI / 360)
        m.fill(0.0)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1.0
        m[14] = 2 * far * near / (near - far)
    }

    /** [out] = [a] · [b]. */
    fun multiply(out: FloatArray, a: DoubleArray, b: DoubleArray) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum.toFloat()
            }
        }
    }

    /** Where a point lands on the screen: x and y from −1 to 1, and its depth; w is the distance ahead of the eye. */
    fun project(m: FloatArray, p: WTVec): DoubleArray {
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        return doubleArrayOf(x / w, y / w, z / w, w)
    }
}
