package com.skyalt.canopy.windtunnel

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Body-flight model for a vertical air stream: a 1:1 port of the iPhone
 * app's WindTunnelCore.swift, itself a port of the web prototype's
 * TunnelCore (core.js). Pure maths with no UI, so the numbers match the
 * other two (see WindTunnelCoreParityTest).
 *
 * Axes. World: +Y up (the wind blows +Y). Body (pelvis) frame: +X the
 * flier's left, +Y towards the head, +Z out of the chest. Angles are in
 * degrees at the interface. All coefficients are engineering estimates.
 */

/** A plain 3-vector. It never changes once made. */
class WTVec(val x: Double, val y: Double, val z: Double) {
    operator fun get(i: Int): Double = if (i == 0) x else if (i == 1) y else z
    operator fun plus(b: WTVec) = WTVec(x + b.x, y + b.y, z + b.z)
    operator fun minus(b: WTVec) = WTVec(x - b.x, y - b.y, z - b.z)
    operator fun unaryMinus() = WTVec(-x, -y, -z)
    operator fun times(s: Double) = WTVec(x * s, y * s, z * s)
    operator fun div(s: Double) = WTVec(x / s, y / s, z / s)

    val isZero: Boolean get() = x == 0.0 && y == 0.0 && z == 0.0

    override fun equals(other: Any?): Boolean = other is WTVec && other.x == x && other.y == y && other.z == z
    override fun hashCode(): Int = (x.hashCode() * 31 + y.hashCode()) * 31 + z.hashCode()
    override fun toString(): String = "($x, $y, $z)"

    companion object {
        val ZERO = WTVec(0.0, 0.0, 0.0)
    }
}

/** A quaternion [x, y, z, w]. `WTCore.qMul(a, b)` applies b first, then a. */
class WTQuat(val x: Double, val y: Double, val z: Double, val w: Double) {
    companion object {
        val IDENTITY = WTQuat(0.0, 0.0, 0.0, 1.0)
    }
}

/**
 * A pose: every joint angle in degrees, by the web page's keys ("pitch",
 * "shAbdL", "kneeR", …). Missing keys read as 0. Swift's is a value;
 * here [copy] makes the copy where one is meant.
 */
class WTPose(val values: HashMap<String, Double> = HashMap()) {
    operator fun get(key: String): Double = values[key] ?: 0.0
    operator fun set(key: String, value: Double) {
        values[key] = value
    }

    var pitch: Double
        get() = this["pitch"]
        set(value) {
            this["pitch"] = value
        }
    var roll: Double
        get() = this["roll"]
        set(value) {
            this["roll"] = value
        }
    var yaw: Double
        get() = this["yaw"]
        set(value) {
            this["yaw"] = value
        }

    fun copy(): WTPose = WTPose(HashMap(values))

    override fun equals(other: Any?): Boolean = other is WTPose && other.values == values
    override fun hashCode(): Int = values.hashCode()
}

data class WTSuit(val cross: Double, val fric: Double)

/**
 * The air and the flier's gear. [V] is the stream speed (m/s), [assist]
 * the flier's own position-holding corrections as a share of the weight,
 * [bounded] the tunnel's net and glass.
 */
data class WTEnv(
    val rho: Double,
    val k: Double = 1.0,
    val suit: WTSuit = WTCore.suits.getValue("std"),
    val V: Double = 0.0,
    val assist: Double = 0.0,
    val bounded: Boolean = false,
    val reflex: Double = 1.0,
)

data class WTHold(val pitch: Boolean = false, val roll: Boolean = false)

class WTSegment(
    val id: String,
    val part: String,
    val grp: String,
    val m: Double,
    val c: WTVec,
    val q: WTQuat,
    val kind: Kind,
) {
    enum class Kind { CYL, BOX, ELL }

    var zone = ""
    var r = 0.0
    var L = 0.0
    var cn = 0.0
    var cloth = false
    var size = WTVec.ZERO
    var area = WTVec.ZERO
    var coef = WTVec.ZERO
    var wet = 0.0
    var semi = WTVec.ZERO
    var coefP = WTVec.ZERO
    var coefN = WTVec.ZERO
    var hx = WTVec.ZERO
    var pts: List<WTVec> = listOf(WTVec.ZERO)
}

class WTJoint(val id: String, val grp: String, val p: WTVec, val r: Double)

class WTContact(val p: WTVec, val r: Double)

/** A body in one pose. Nothing in it changes once [WTCore.buildBody] returns it. */
class WTBody(
    val H: Double,
    val M: Double,
    val legs: Double,
    val hip: Double,
    val segs: List<WTSegment>,
    val joints: List<WTJoint>,
    val contacts: List<WTContact>,
    val com: WTVec,
    val I: List<WTVec>,
    val Iinv: List<WTVec>,
    val chestD: Double,
    val headR: Double,
) {
    val iPelvis = 0
    val iChest = 1
}

class WTPer(val id: String, val part: String, val zone: String, val r: WTVec, val f: WTVec, val t: WTVec, val q: Double)

class WTShape(
    val angle: Double,
    val flat: Double,
    var gain: Double,
    val nrm: WTVec,
    val face: Double,
    var gains: DoubleArray?,
    var left: Double,
    var right: Double,
)

class WTLead(val shift: Double, val angle: Double, val chord: Double, val dir: WTVec)

class WTAero(val F: WTVec, val T: WTVec, val per: List<WTPer>, val shape: WTShape?, val lead: WTLead?)

class WTAxes(val lat: WTVec, val head: WTVec, val chest: WTVec, val heading: WTVec)

class WTZone {
    var drag = 0.0
    var lift = WTVec.ZERO
    var mPitch = 0.0
    var mRoll = 0.0
    var mYaw = 0.0
    var clean = 0.0
    var liftMag = 0.0
    var wake = 0.0
}

class WTAnalysis(
    val R: WTQuat,
    val F: WTVec,
    val T: WTVec,
    val per: List<WTPer>,
    val W: Double,
    val wind: WTVec,
    val speed: Double,
    val D: Double,
    val L: WTVec,
    val Lmag: Double,
    val ratio: Double,
    val Fy: Double,
    val veq: Double,
    val cda: Double,
    val mPitch: Double,
    val mRoll: Double,
    val mYaw: Double,
    val parts: Map<String, Double>,
    val detail: Map<String, WTZone>,
    val cp: WTVec,
    val axes: WTAxes,
    val shape: WTShape?,
    val lead: WTLead?,
    val wake: Double,
)

class WTMoments(val mp: Double, val mr: Double, val my: Double, val F: WTVec)

class WTTrimStep(
    val passiveP: Boolean,
    val passiveR: Boolean,
    var dp: Double,
    var dr: Double,
    val stiffPitch: Double,
    val stiffRoll: Double,
    val slopePitch: Double,
    val slopeRoll: Double,
)

class WTTrim(
    val pitch: Double,
    val roll: Double,
    val passiveP: Boolean,
    val passiveR: Boolean,
    val freeP: Boolean,
    val freeR: Boolean,
    val leanR: Boolean,
    val ok: Boolean,
)

class WTCurves(
    val x: DoubleArray,
    val pitch: DoubleArray,
    val roll: DoubleArray,
    val trimPitch: Double?,
    val trimRoll: Double?,
    val slopePitch: Double,
    val slopeRoll: Double,
    val stiffPitch: Double,
    val stiffRoll: Double,
    val stablePitch: Boolean,
    val stableRoll: Boolean,
)

enum class WTSimMode { PIN, FLY, FREE }

/** Where the flier's own small corrections pull back to (x and z, metres). */
class WTAnchor(val x: Double, val z: Double)

/** The flier in the frame of the stream's source. */
class WTSim {
    var mode = WTSimMode.FLY
    var pos = WTVec.ZERO
    var vel = WTVec.ZERO
    var yaw = 0.0
    var yawRate = 0.0
    var R = WTCore.orientQuat(0.0, 0.0, 0.0)
    var omega = WTVec.ZERO
    var anchor: WTAnchor? = null
    var onNet = false
    var onWall = false
}

class WTFlyInfo(val rest: WTTrimStep?, val trim: WTTrimStep?, val freeP: Boolean, val freeR: Boolean, val leanR: Boolean)

class WTSample(val fall: Double, val fwd: Double, val left: Double, val yawRate: Double, val pitch: Double, val roll: Double)

class WTSteady(
    val fall: Double,
    val fwd: Double,
    val left: Double,
    val yawRate: Double,
    val pitch: Double,
    val roll: Double,
    val speed: Double,
    val glide: Double,
    val settled: Boolean,
    val time: Double,
    val veq: Double,
    val radius: Double,
    val heldP: Boolean,
    val heldR: Boolean,
    var mPitch: Double,
    var mRoll: Double,
)

/** A steady-state run that can be advanced in slices. */
class WTSteadyJob(val body: WTBody, val Q: WTPose, val veq: Double, val hold: WTHold, val env: WTEnv, val maxTime: Double) {
    val sim = WTSim()
    val dt = 1.0 / 30
    var t = 0.0
    var n = 0
    var calm = 0
    val hist = ArrayList<WTSample>()
    var cur: WTSample? = null
    var done = false
    var failed = false
    var freeP = false
    var freeR = false
}

class WTPreset(
    val id: String,
    val name: String,
    val en: String,
    val neutral: Boolean,
    val fitJoint: String? = null,
    val fitSpan: Double? = null,
    val hold: WTHold = WTHold(),
    val pose: WTPose,
    val note: String,
    val noteEn: String,
)

object WTCore {
    const val D2R = Math.PI / 180
    const val R2D = 180 / Math.PI
    const val G = 9.80665

    // Vectors and quaternions

    fun clamp(x: Double, a: Double, b: Double): Double = if (x < a) a else if (x > b) b else x
    fun dot(a: WTVec, b: WTVec): Double = a.x * b.x + a.y * b.y + a.z * b.z
    fun cross(a: WTVec, b: WTVec): WTVec = WTVec(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    fun len(a: WTVec): Double = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)

    fun qAxis(ax: WTVec, ang: Double): WTQuat {
        val s = sin(ang / 2)
        return WTQuat(ax.x * s, ax.y * s, ax.z * s, cos(ang / 2))
    }

    private val AXIS_X = WTVec(1.0, 0.0, 0.0)
    private val AXIS_Y = WTVec(0.0, 1.0, 0.0)
    private val AXIS_Z = WTVec(0.0, 0.0, 1.0)

    fun QX(deg: Double): WTQuat = qAxis(AXIS_X, deg * D2R)
    fun QY(deg: Double): WTQuat = qAxis(AXIS_Y, deg * D2R)
    fun QZ(deg: Double): WTQuat = qAxis(AXIS_Z, deg * D2R)

    fun qMul(a: WTQuat, b: WTQuat): WTQuat = WTQuat(
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
    )

    fun qMul3(a: WTQuat, b: WTQuat, c: WTQuat): WTQuat = qMul(qMul(a, b), c)

    fun qRot(q: WTQuat, v: WTVec): WTVec {
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        val tx = 2 * (y * v.z - z * v.y); val ty = 2 * (z * v.x - x * v.z); val tz = 2 * (x * v.y - y * v.x)
        return WTVec(v.x + w * tx + (y * tz - z * ty), v.y + w * ty + (z * tx - x * tz), v.z + w * tz + (x * ty - y * tx))
    }

    fun qInvRot(q: WTQuat, v: WTVec): WTVec = qRot(WTQuat(-q.x, -q.y, -q.z, q.w), v)

    fun qNorm(q: WTQuat): WTQuat {
        var n = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
        if (n == 0.0) n = 1.0
        return WTQuat(q.x / n, q.y / n, q.z / n, q.w / n)
    }

    /** Rows of the rotation matrix. */
    fun qToMat(q: WTQuat): List<WTVec> {
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        return listOf(
            WTVec(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
            WTVec(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
            WTVec(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)),
        )
    }

    fun mat3Inv(m: List<WTVec>): List<WTVec> {
        val a = m[0].x; val b = m[0].y; val c = m[0].z
        val d = m[1].x; val e = m[1].y; val f = m[1].z
        val g = m[2].x; val h = m[2].y; val i = m[2].z
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        var det = a * A + b * B + c * C
        if (det == 0.0) det = 1e-9
        return listOf(
            WTVec(A / det, (c * h - b * i) / det, (b * f - c * e) / det),
            WTVec(B / det, (a * i - c * g) / det, (c * d - a * f) / det),
            WTVec(C / det, (b * g - a * h) / det, (a * e - b * d) / det),
        )
    }

    fun mat3Vec(m: List<WTVec>, v: WTVec): WTVec = WTVec(dot(m[0], v), dot(m[1], v), dot(m[2], v))

    fun wrap180(a: Double): Double = ((a + 180) % 360 + 360) % 360 - 180

    // Anthropometry and coefficients

    /** Mass fractions after de Leva (1996), lengths as fractions of stature after Drillis & Contini. */
    val mass: Map<String, Double> = mapOf(
        "head" to 0.0694, "chest" to 0.3229, "pelvis" to 0.1117, "uarm" to 0.0271, "farm" to 0.0162,
        "hand" to 0.0061, "thigh" to 0.1416, "shank" to 0.0433, "foot" to 0.0137,
    )
    val length: Map<String, Double> = mapOf(
        "pelvisUp" to 0.09, "pelvisDown" to 0.03, "pelvisW" to 0.185, "hipX" to 0.052,
        "chest" to 0.20, "chestW" to 0.215, "shoulderX" to 0.112, "shoulderY" to 0.178, "neck" to 0.115,
        "uarm" to 0.186, "farm" to 0.146, "hand" to 0.10, "handW" to 0.05, "handT" to 0.016,
        "thigh" to 0.245, "shank" to 0.246, "footL" to 0.152, "footH" to 0.04, "footW" to 0.055,
    )

    const val cnArm = 0.80
    const val cnThigh = 0.80
    const val cnShank = 0.90
    const val cf = 0.03
    val chestCoef = WTVec(0.7, 0.8, 1.15)
    val pelvisCoef = WTVec(0.7, 0.8, 1.15)
    val headSize = WTVec(0.90, 1.12, 1.08)
    val headPlus = WTVec(0.50, 0.70, 0.50)
    val headMinus = WTVec(0.50, 0.45, 0.55)
    val handCoef = WTVec(1.0, 1.0, 1.17)
    val footCoef = WTVec(1.0, 0.95, 0.9)

    val suits: Map<String, WTSuit> = mapOf(
        "tight" to WTSuit(cross = 0.94, fric = 0.7),
        "std" to WTSuit(cross = 1.0, fric = 1.0),
        "baggy" to WTSuit(cross = 1.10, fric = 1.7),
    )

    /** Overall shape (arched or de-arched) as the stream sees it. */
    object SHAPE {
        const val ref = -22.4
        const val lo = 0.7
        const val hi = 1.6
        const val soft = 25.0
        const val side = 0.07
        const val tie = 0.05
    }

    /** Wake of one segment on the segments downstream of it. */
    object WAKE {
        const val cap = 0.8
        const val gain = 1.0
        const val spread = 0.08
        const val inner = 0.6
        const val outer = 1.05
    }

    /** Load towards the leading edge. */
    object LEAD {
        const val k = 4.5
        const val broad = 0.5
        const val a0 = 50.0
        const val a1 = 80.0
        const val e0 = 15.0
        const val e1 = 35.0
    }

    /** Limbs that lie side by side. */
    object PAIR {
        const val k = 1.0
        const val near = 1.1
        const val far = 2.5
    }

    object TUNNEL {
        const val radius = 4.0
        const val glassTop = 5.0
        const val hoverY = 1.8
        const val gradient = 0.015
    }

    const val stableStiff = 3e-4

    object LEAN {
        const val stiff = 6e-4
        const val max = 12.0
    }

    val armKeys = listOf("shAbd", "shFlex", "shRot", "elbow", "pron", "wrist")
    val legKeys = listOf("hipFlex", "hipAbd", "hipRot", "knee", "ankle")
    val coreKeys = listOf("pitch", "roll", "yaw", "neckFlex", "neckSide", "neckTurn", "spineFlex", "spineSide", "spineTwist")
    val limits: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "pitch" to -180.0..180.0, "roll" to -90.0..90.0, "yaw" to -180.0..180.0,
        "neckFlex" to -60.0..60.0, "neckSide" to -40.0..40.0, "neckTurn" to -70.0..70.0,
        "spineFlex" to -40.0..60.0, "spineSide" to -30.0..30.0, "spineTwist" to -40.0..40.0,
        "shAbd" to 0.0..180.0, "shFlex" to -60.0..180.0, "shRot" to -90.0..120.0, "elbow" to 0.0..145.0,
        "pron" to -90.0..180.0, "wrist" to -70.0..70.0,
        "hipFlex" to -40.0..120.0, "hipAbd" to -20.0..70.0, "hipRot" to -45.0..45.0, "knee" to 0.0..150.0, "ankle" to -25.0..70.0,
    )
    val poseKeys: List<String> = coreKeys + armKeys.flatMap { listOf(it + "L", it + "R") } + legKeys.flatMap { listOf(it + "L", it + "R") }
    val partKeys = listOf("head", "torso", "arm", "hand", "thigh", "shank")
    val zones = listOf("head", "torso", "armL", "armR", "thighL", "thighR", "shankL", "shankR")

    fun makePose(
        core: Map<String, Double>,
        arms: Map<String, Double>,
        legs: Map<String, Double>,
        armsR: Map<String, Double>? = null,
        legsR: Map<String, Double>? = null,
    ): WTPose {
        val p = WTPose()
        for (k in coreKeys) p[k] = core[k] ?: 0.0
        for (k in armKeys) {
            p[k + "L"] = arms[k] ?: 0.0
            p[k + "R"] = armsR?.get(k) ?: p[k + "L"]
        }
        for (k in legKeys) {
            p[k + "L"] = legs[k] ?: 0.0
            p[k + "R"] = legsR?.get(k) ?: p[k + "L"]
        }
        return p
    }

    /**
     * World orientation of the body frame. pitch 0 = belly to earth with
     * the head towards +Z, +90 head up, -90 head down, 180 on the back.
     * roll + = leaning right, yaw + = turning left.
     */
    fun orientQuat(pitch: Double, roll: Double, yaw: Double): WTQuat = qMul3(QY(yaw), QZ(roll), QX(90 - pitch))

    // Skeleton

    class Proportions(val legs: Double, val trunk: Double, val mass: Double, val hip: Double)

    fun proportions(legs: Double): Proportions {
        val Ln = length; val Ms = mass; val ls = if (legs == 0.0) 1.0 else legs
        val legLen = Ln.getValue("thigh") + Ln.getValue("shank")
        val trunkLen = Ln.getValue("pelvisUp") + Ln.getValue("chest") + Ln.getValue("neck")
        val ts = (trunkLen - legLen * (ls - 1)) / trunkLen
        val sum = Ms.getValue("head") + (Ms.getValue("chest") + Ms.getValue("pelvis")) * ts +
            2 * (Ms.getValue("uarm") + Ms.getValue("farm") + Ms.getValue("hand") + Ms.getValue("foot")) +
            2 * (Ms.getValue("thigh") + Ms.getValue("shank")) * ls
        return Proportions(legs = ls, trunk = ts, mass = 1 / sum, hip = Ln.getValue("footH") + legLen * ls)
    }

    private val legLenKeys = setOf("thigh", "shank")
    private val trunkLenKeys = setOf("pelvisUp", "pelvisDown", "chest", "shoulderY", "neck")
    private val trunkMassKeys = setOf("chest", "pelvis")

    private class CylEnd(val end: WTVec, val r: Double)

    fun buildBody(P: WTPose, H: Double, M: Double, legs: Double): WTBody {
        val pp = proportions(legs)
        fun L(k: String): Double =
            length.getValue(k) * H * (if (k in legLenKeys) pp.legs else if (k in trunkLenKeys) pp.trunk else 1.0)

        fun m(k: String): Double =
            mass.getValue(k) * M * pp.mass * (if (k in legLenKeys) pp.legs else if (k in trunkMassKeys) pp.trunk else 1.0)

        val QI = WTQuat.IDENTITY
        val segs = ArrayList<WTSegment>()
        val joints = ArrayList<WTJoint>()
        val contacts = ArrayList<WTContact>()

        fun addCyl(id: String, part: String, grp: String, mass: Double, top: WTVec, q: WTQuat, length: Double, cn: Double): CylEnd {
            val r = sqrt(mass / (1060 * Math.PI * length))
            val c = top + qRot(q, WTVec(0.0, -length / 2, 0.0))
            val end = top + qRot(q, WTVec(0.0, -length, 0.0))
            val s = WTSegment(id, part, grp, mass, c, q, WTSegment.Kind.CYL)
            s.r = r; s.L = length; s.cn = cn; s.cloth = true
            s.hx = WTVec(r, length / 2, r)
            s.pts = listOf(WTVec(0.0, -length / 3, 0.0), WTVec.ZERO, WTVec(0.0, length / 3, 0.0))
            segs.add(s)
            contacts.add(WTContact(top, r))
            contacts.add(WTContact(end, r))
            return CylEnd(end, r)
        }

        fun addTorso(id: String, grp: String, mass: Double, c: WTVec, q: WTQuat, w: Double, length: Double, density: Double, coef: WTVec): Double {
            val d = clamp(4 * mass / (density * Math.PI * w * length), 0.5 * w, 0.78 * w)
            val s = WTSegment(id, "torso", grp, mass, c, q, WTSegment.Kind.BOX)
            s.size = WTVec(w, length, d)
            s.area = WTVec(d * length, Math.PI / 4 * w * d, w * length)
            s.coef = coef
            s.wet = Math.PI * (w + d) / 2 * length
            s.cloth = true
            s.hx = WTVec(w / 2, length / 2, d / 2)
            s.pts = listOf(
                WTVec.ZERO, WTVec(w / 4, length / 4, 0.0), WTVec(-w / 4, length / 4, 0.0),
                WTVec(w / 4, -length / 4, 0.0), WTVec(-w / 4, -length / 4, 0.0),
            )
            segs.add(s)
            val off = (w - d) / 2
            contacts.add(WTContact(c + qRot(q, WTVec(off, 0.0, 0.0)), d / 2))
            contacts.add(WTContact(c + qRot(q, WTVec(-off, 0.0, 0.0)), d / 2))
            return d
        }

        // pelvis (the root)
        val pelL = L("pelvisUp") + L("pelvisDown")
        addTorso("pelvis", "spine", m("pelvis"), WTVec(0.0, (L("pelvisUp") - L("pelvisDown")) / 2, 0.0), QI, L("pelvisW"), pelL, 1010.0, pelvisCoef)

        // waist joint and chest
        val pW = WTVec(0.0, L("pelvisUp"), 0.0)
        val qW = qMul3(QZ(P["spineSide"]), QX(P["spineFlex"]), QY(P["spineTwist"]))
        val chestL = L("chest")
        val chestD = addTorso("chest", "spine", m("chest"), pW + qRot(qW, WTVec(0.0, chestL / 2, 0.0)), qW, L("chestW"), chestL, 900.0, chestCoef)
        joints.add(WTJoint("waist", "spine", pW, 0.062 * H))

        // neck and head
        val pN = pW + qRot(qW, WTVec(0.0, chestL, 0.0))
        val qN = qMul(qW, qMul3(QZ(P["neckSide"]), QX(P["neckFlex"]), QY(P["neckTurn"])))
        val headR = Math.cbrt(3 * m("head") / (4 * Math.PI * 1100)) * 1.1
        val semi = headSize * headR
        val headC = pN + qRot(qN, WTVec(0.0, L("neck"), 0.0))
        val head = WTSegment("head", "head", "head", m("head"), headC, qN, WTSegment.Kind.ELL)
        head.semi = semi
        head.area = WTVec(Math.PI * semi.y * semi.z, Math.PI * semi.x * semi.z, Math.PI * semi.x * semi.y)
        head.coefP = headPlus
        head.coefN = headMinus
        head.hx = semi
        segs.add(head)
        contacts.add(WTContact(headC, headR))
        joints.add(WTJoint("neck", "head", pN, 0.03 * H))

        // arms and legs. s = +1 for the left side, -1 for the right; right-side angles are mirrored.
        for (s in doubleArrayOf(1.0, -1.0)) {
            val sd = if (s > 0) "L" else "R"
            // arm
            val pS = pW + qRot(qW, WTVec(s * L("shoulderX"), L("shoulderY"), 0.0))
            val qS = qMul(qW, qMul3(QZ(s * P["shAbd$sd"]), QX(-P["shFlex$sd"]), QY(s * P["shRot$sd"])))
            val ua = addCyl("uarm$sd", "arm", "arm$sd", m("uarm"), pS, qS, L("uarm"), cnArm)
            val qE = qMul(qS, QX(-P["elbow$sd"]))
            val fa = addCyl("farm$sd", "arm", "arm$sd", m("farm"), ua.end, qE, L("farm"), cnArm)
            val qHd = qMul3(qE, QY(-s * P["pron$sd"]), QX(-P["wrist$sd"]))
            val hl = L("hand"); val hw = L("handW"); val ht = L("handT")
            val hc = fa.end + qRot(qHd, WTVec(0.0, -hl / 2, 0.0))
            val hand = WTSegment("hand$sd", "hand", "arm$sd", m("hand"), hc, qHd, WTSegment.Kind.BOX)
            hand.size = WTVec(hw, hl, ht)
            hand.area = WTVec(hl * ht, hw * ht, hw * hl)
            hand.coef = handCoef
            hand.hx = WTVec(hw / 2, hl / 2, ht / 2)
            segs.add(hand)
            contacts.add(WTContact(fa.end + qRot(qHd, WTVec(0.0, -hl, 0.0)), ht))
            joints.add(WTJoint("shoulder$sd", "arm$sd", pS, ua.r * 1.22))
            joints.add(WTJoint("elbow$sd", "arm$sd", ua.end, ua.r * 1.1))
            joints.add(WTJoint("wrist$sd", "arm$sd", fa.end, fa.r * 0.9))

            // leg: flexion, then abduction, then rotation about the thigh
            val pH = WTVec(s * L("hipX"), 0.0, 0.0)
            val qH = qMul3(QX(-P["hipFlex$sd"]), QZ(s * P["hipAbd$sd"]), QY(s * P["hipRot$sd"]))
            val th = addCyl("thigh$sd", "thigh", "leg$sd", m("thigh"), pH, qH, L("thigh"), cnThigh)
            val qK = qMul(qH, QX(P["knee$sd"]))
            val sh = addCyl("shank$sd", "shank", "leg$sd", m("shank"), th.end, qK, L("shank"), cnShank)
            val qA = qMul(qK, QX(P["ankle$sd"]))
            val fl = L("footL"); val fh = L("footH"); val fw = L("footW")
            val fc = sh.end + qRot(qA, WTVec(0.0, -fh / 2, fl * 0.28))
            val foot = WTSegment("foot$sd", "shank", "leg$sd", m("foot"), fc, qA, WTSegment.Kind.BOX)
            foot.size = WTVec(fw, fh, fl)
            foot.area = WTVec(fh * fl, fw * fl, fw * fh)
            foot.coef = footCoef
            foot.hx = WTVec(fw / 2, fh / 2, fl / 2)
            segs.add(foot)
            contacts.add(WTContact(sh.end + qRot(qA, WTVec(0.0, -fh / 2, fl * 0.78)), fh / 2))
            contacts.add(WTContact(sh.end + qRot(qA, WTVec(0.0, -fh / 2, -fl * 0.22)), fh / 2))
            joints.add(WTJoint("hip$sd", "leg$sd", pH, th.r * 0.86))
            joints.add(WTJoint("knee$sd", "leg$sd", th.end, sh.r * 1.12))
            joints.add(WTJoint("ankle$sd", "leg$sd", sh.end, sh.r * 0.72))
        }

        // zone: the part of the body a segment is reported under
        for (sg in segs) {
            val zid = sg.id; val zsd = zid.takeLast(1)
            sg.zone = if (zid == "head") "head" else if (zid == "chest" || zid == "pelvis") "torso"
            else if (sg.grp.startsWith("arm")) "arm$zsd" else if (zid.startsWith("thigh")) "thigh$zsd" else "shank$zsd"
        }

        // centre of mass and inertia tensor about it (pelvis frame)
        var com = WTVec.ZERO
        for (sg in segs) com += sg.c * sg.m
        com /= M
        val I = Array(3) { DoubleArray(3) }
        for (sg in segs) {
            val d = sg.c - com; val d2 = dot(d, d)
            val pr: WTVec = when (sg.kind) {
                WTSegment.Kind.CYL -> {
                    val t = sg.m * (3 * sg.r * sg.r + sg.L * sg.L) / 12
                    WTVec(t, sg.m * sg.r * sg.r / 2, t)
                }
                WTSegment.Kind.BOX -> {
                    val sz = sg.size
                    WTVec(sg.m * (sz.y * sz.y + sz.z * sz.z) / 12, sg.m * (sz.x * sz.x + sz.z * sz.z) / 12, sg.m * (sz.x * sz.x + sz.y * sz.y) / 12)
                }
                WTSegment.Kind.ELL -> {
                    val se = sg.semi; val me = sg.m / 5
                    WTVec(me * (se.y * se.y + se.z * se.z), me * (se.x * se.x + se.z * se.z), me * (se.x * se.x + se.y * se.y))
                }
            }
            val Rm = qToMat(sg.q)
            for (a in 0 until 3) {
                for (b in 0 until 3) {
                    val own = Rm[a].x * pr.x * Rm[b].x + Rm[a].y * pr.y * Rm[b].y + Rm[a].z * pr.z * Rm[b].z
                    I[a][b] += own + sg.m * ((if (a == b) d2 else 0.0) - d[a] * d[b])
                }
            }
        }
        val Iv = I.map { WTVec(it[0], it[1], it[2]) }
        return WTBody(
            H = H, M = M, legs = pp.legs, hip = pp.hip, segs = segs, joints = joints, contacts = contacts,
            com = com, I = Iv, Iinv = mat3Inv(Iv), chestD = chestD, headR = headR,
        )
    }

    // Aerodynamics

    /**
     * Force on one segment in its own frame. u is the air velocity
     * relative to the segment; qd = 0.5 * rho * calibration.
     */
    fun segLocalForce(seg: WTSegment, u: WTVec, qd: Double, suit: WTSuit): WTVec {
        val ua = len(u)
        when (seg.kind) {
            WTSegment.Kind.CYL -> {
                val un = sqrt(u.x * u.x + u.z * u.z)
                val kn = qd * seg.cn * suit.cross * 2 * seg.r * seg.L * un
                val ka = qd * cf * suit.fric * 2 * Math.PI * seg.r * seg.L * ua
                return WTVec(kn * u.x, ka * u.y, kn * u.z)
            }
            WTSegment.Kind.BOX -> {
                val s = if (seg.cloth) suit.cross else 1.0
                val k = qd * ua
                var fy = k * seg.coef.y * seg.area.y * u.y
                if (seg.wet > 0) fy += k * cf * suit.fric * seg.wet * u.y
                return WTVec(k * seg.coef.x * s * seg.area.x * u.x, fy, k * seg.coef.z * s * seg.area.z * u.z)
            }
            WTSegment.Kind.ELL -> {
                val ke = qd * ua; val cp = seg.coefP; val cm = seg.coefN; val ar = seg.area
                return WTVec(
                    ke * (if (u.x >= 0) cp.x else cm.x) * ar.x * u.x,
                    ke * (if (u.y >= 0) cp.y else cm.y) * ar.y * u.y,
                    ke * (if (u.z >= 0) cp.z else cm.z) * ar.z * u.z,
                )
            }
        }
    }

    /**
     * Solves the augmented system in place by Gauss-Jordan elimination with
     * partial pivoting. Returns false when it is singular.
     */
    private fun gaussJordan(A: Array<DoubleArray>, n: Int): Boolean {
        for (j in 0 until n) {
            var p = j
            for (k in j + 1 until n) if (abs(A[k][j]) > abs(A[p][j])) p = k
            if (abs(A[p][j]) < 1e-14) return false
            val row = A[j]; A[j] = A[p]; A[p] = row
            for (k in 0 until n) {
                if (k == j) continue
                val g = A[k][j] / A[j][j]
                for (i in j..n) A[k][i] -= g * A[j][i]
            }
        }
        return true
    }

    /**
     * Overall shape: fits a bowl to the segment centres weighted by their
     * drag; its rim angle scales the whole force. Also gives the plane the
     * loaded segments lie in (for leadLoad) and a factor per side.
     */
    fun shapeOf(body: WTBody, R: WTQuat, rs: Array<WTVec>, fs: Array<WTVec>, um: WTVec): WTShape? {
        val us = len(um)
        if (!(us > 1e-6)) return null
        val wd = um / us
        val nz = qRot(qMul(R, body.segs[body.iChest].q), AXIS_Z) + qRot(qMul(R, body.segs[body.iPelvis].q), AXIS_Z)
        val nl = len(nz)
        if (!(nl > 1e-6)) return null
        val cs = dot(nz, wd) / nl; val sg = if (cs < 0) -1 / nl else 1 / nl
        val ax = nz * sg
        var e1 = cross(ax, if (abs(ax.y) < 0.9) AXIS_Y else AXIS_X)
        e1 /= len(e1)
        val e2 = cross(ax, e1)
        val n = rs.size
        var W = 0.0; var cx = 0.0; var cz = 0.0
        val w = DoubleArray(n); val xs = DoubleArray(n); val zs = DoubleArray(n); val az = DoubleArray(n)
        for (i in 0 until n) {
            var wi = dot(fs[i], wd)
            if (!(wi > 0)) wi = 0.0
            w[i] = wi; xs[i] = dot(rs[i], e1); zs[i] = dot(rs[i], e2); az[i] = -dot(rs[i], ax)
            W += wi; cx += wi * xs[i]; cz += wi * zs[i]
        }
        if (!(W > 1e-9)) return null
        cx /= W; cz /= W
        val A = Array(4) { DoubleArray(5) }
        var q2 = 0.0
        val v = DoubleArray(4)
        for (i in 0 until n) {
            val x = xs[i] - cx; val z = zs[i] - cz; val q = x * x + z * z
            v[0] = 1.0; v[1] = x; v[2] = z; v[3] = q
            q2 += w[i] * q
            for (j in 0 until 4) {
                A[j][4] += w[i] * v[j] * az[i]
                for (k in 0 until 4) A[j][k] += w[i] * v[j] * v[k]
            }
        }
        q2 /= W
        if (!(q2 > 1e-9)) return null
        A[1][1] += 1e-3 * W * q2; A[2][2] += 1e-3 * W * q2; A[3][3] += 1e-3 * W * q2 * q2
        if (!gaussJordan(A, 4)) return null
        val angle = atan(2 * (A[3][4] / A[3][3]) * sqrt(q2)) * R2D
        val c = abs(cs)
        val t = clamp((c - 0.35) / 0.5, 0.0, 1.0); val flat = t * t * (3 - 2 * t)
        val c1 = A[1][4] / A[1][1]; val c2 = A[2][4] / A[2][2]
        val nr = ax + e1 * c1 + e2 * c2; val nn = len(nr)
        val out = WTShape(
            angle = angle, flat = flat, gain = shapeGain(flat * (angle - SHAPE.ref)), nrm = nr / nn, face = c,
            gains = null, left = angle, right = angle,
        )

        // the two sides, each with its own depth
        var lx = qRot(R, AXIS_X)
        val la = dot(lx, ax)
        lx -= ax * la
        val ll = len(lx)
        if (SHAPE.side > 0 && ll > 0.2) {
            val B = Array(5) { DoubleArray(6) }
            val sl = DoubleArray(n)
            val half = SHAPE.side * body.H
            val bv = DoubleArray(5)
            for (i in 0 until n) {
                sl[i] = clamp(0.5 + dot(rs[i], lx) / ll / (2 * half), 0.0, 1.0)
                val bx = xs[i] - cx; val bz = zs[i] - cz; val bq = bx * bx + bz * bz
                bv[0] = 1.0; bv[1] = bx; bv[2] = bz; bv[3] = bq * sl[i]; bv[4] = bq * (1 - sl[i])
                for (j in 0 until 5) {
                    B[j][5] += w[i] * bv[j] * az[i]
                    for (k in 0 until 5) B[j][k] += w[i] * bv[j] * bv[k]
                }
            }
            val rg = 1e-3 * W * q2; val tie = SHAPE.tie * W * q2 * q2
            B[1][1] += rg; B[2][2] += rg; B[3][3] += rg * q2 + tie; B[4][4] += rg * q2 + tie; B[3][4] -= tie; B[4][3] -= tie
            if (gaussJordan(B, 5)) {
                val rq = sqrt(q2)
                out.left = atan(2 * (B[3][5] / B[3][3]) * rq) * R2D
                out.right = atan(2 * (B[4][5] / B[4][4]) * rq) * R2D
                val gl = shapeGain(flat * (out.left - SHAPE.ref)); val gr = shapeGain(flat * (out.right - SHAPE.ref))
                val gs = DoubleArray(n); var sw = 0.0
                for (i in 0 until n) {
                    gs[i] = gl * sl[i] + gr * (1 - sl[i])
                    sw += w[i] * gs[i]
                }
                out.gains = gs
                out.gain = sw / W
            }
        }
        return out
    }

    /**
     * Factor on the aerodynamic force for a rim angle d degrees more hollow
     * (+) or more arched (-) than the reference box.
     */
    fun shapeGain(d: Double): Double {
        val c = (SHAPE.hi - SHAPE.lo) / (1 - SHAPE.lo) - 1
        return SHAPE.lo + (SHAPE.hi - SHAPE.lo) / (1 + c * exp(-d / SHAPE.soft))
    }

    private class WakeSource(val c: WTVec, val ea: WTVec, val eb: WTVec, val ha: Double, val hb: Double, val hw: Double, val d0: Double)

    /**
     * Share of the dynamic pressure left on each segment behind the
     * others (1 = clean air), or null without a stream.
     */
    fun wakeFactors(body: WTBody, rs: Array<WTVec>, qs: Array<WTQuat>, us: Array<WTVec>, fs: Array<WTVec>, um: WTVec, qd: Double): DoubleArray? {
        val sp = len(um)
        if (!(sp > 1e-6)) return null
        val wd = um / sp; val segs = body.segs; val n = segs.size
        val src = arrayOfNulls<WakeSource>(n)
        for (j in 0 until n) {
            val sg = segs[j]; val q = qs[j]; val hx = sg.hx
            val ax = arrayOf(qRot(q, AXIS_X), qRot(q, AXIS_Y), qRot(q, AXIS_Z))
            val nl = WTVec(dot(ax[0], wd), dot(ax[1], wd), dot(ax[2], wd))
            val A: Double
            var ea: WTVec?
            var ha: Double
            var hb: Double
            val hw: Double
            when (sg.kind) {
                WTSegment.Kind.CYL -> {
                    val sn = sqrt(max(0.0, 1 - nl.y * nl.y))
                    A = 2 * sg.r * sg.L * sn + Math.PI * sg.r * sg.r * abs(nl.y)
                    val ap = ax[1] - wd * nl.y; val al = len(ap)
                    ea = if (al > 1e-6) ap / al else null
                    ha = sg.L / 2 * sn + sg.r; hb = sg.r; hw = sg.L / 2 * abs(nl.y) + sg.r
                }
                WTSegment.Kind.BOX -> {
                    A = 4 * (hx.y * hx.z * abs(nl.x) + hx.x * hx.z * abs(nl.y) + hx.x * hx.y * abs(nl.z))
                    val pk = ArrayList<WTVec>(3); var best = -1.0
                    ea = null
                    for (k in 0 until 3) {
                        val v = (ax[k] - wd * nl[k]) * hx[k]; val vl = len(v)
                        pk.add(v)
                        if (vl > best) {
                            best = vl
                            if (vl > 1e-9) ea = v / vl
                        }
                    }
                    val e0 = ea ?: perpTo(wd)
                    ea = e0
                    val eb0 = cross(wd, e0)
                    ha = abs(dot(pk[0], e0)) + abs(dot(pk[1], e0)) + abs(dot(pk[2], e0))
                    hb = abs(dot(pk[0], eb0)) + abs(dot(pk[1], eb0)) + abs(dot(pk[2], eb0))
                    hw = hx.x * abs(nl.x) + hx.y * abs(nl.y) + hx.z * abs(nl.z)
                }
                WTSegment.Kind.ELL -> {
                    val a1 = hx.y * hx.z * nl.x; val a2 = hx.x * hx.z * nl.y; val a3 = hx.x * hx.y * nl.z
                    A = Math.PI * sqrt(a1 * a1 + a2 * a2 + a3 * a3)
                    ha = sqrt(A) / 2; hb = ha
                    val b1 = hx.x * nl.x; val b2 = hx.y * nl.y; val b3 = hx.z * nl.z
                    hw = sqrt(b1 * b1 + b2 * b2 + b3 * b3)
                    ea = null
                }
            }
            val e = ea ?: perpTo(wd)
            if (!(A > 1e-9 && ha > 1e-9 && hb > 1e-9)) {
                src[j] = null
                continue
            }
            val fa = sqrt(A / (4 * ha * hb))
            ha *= fa; hb *= fa
            val uj = us[j]; val u2 = dot(uj, uj)
            var d0 = if (u2 > 1e-9) WAKE.gain * dot(fs[j], uj) / (sqrt(u2) * qd * u2 * A) else 0.0
            if (d0 > WAKE.cap) d0 = WAKE.cap
            src[j] = if (d0 > 0.01) WakeSource(c = rs[j], ea = e, eb = cross(wd, e), ha = ha, hb = hb, hw = hw, d0 = d0) else null
        }
        val out = DoubleArray(n) { 1.0 }
        val o4 = WAKE.outer.pow(4); val wi = WAKE.inner; val wo = WAKE.outer; val ks = WAKE.spread
        for (i in 0 until n) {
            val pts = segs[i].pts
            var sum = 0.0
            for (pt in pts) {
                val p = if (pt.isZero) rs[i] else rs[i] + qRot(qs[i], pt)
                var keep = 1.0
                for (j in 0 until n) {
                    if (j == i) continue
                    val s = src[j] ?: continue
                    // d = p - s.c, written out: this loop runs some 700 times for one evaluation of the body.
                    val dx = p.x - s.c.x; val dy = p.y - s.c.y; val dz = p.z - s.c.z
                    val t = dx * wd.x + dy * wd.y + dz * wd.z
                    if (t <= 0) continue
                    val sa = s.ha + ks * t; val sb = s.hb + ks * t
                    val xa = (dx * s.ea.x + dy * s.ea.y + dz * s.ea.z) / sa; val xb = (dx * s.eb.x + dy * s.eb.y + dz * s.eb.z) / sb
                    val x4 = xa * xa * xa * xa + xb * xb * xb * xb
                    if (x4 >= o4) continue
                    val x = sqrt(sqrt(x4))
                    var g = 1.0
                    if (x > wi) {
                        val e = (wo - x) / (wo - wi)
                        g = e * e * (3 - 2 * e)
                    }
                    keep *= 1 - s.d0 * (if (t < s.hw) t / s.hw else 1.0) * (s.ha * s.hb) / (sa * sb) * g
                }
                sum += keep * keep
            }
            out[i] = sum / pts.size.toDouble()
        }
        return out
    }

    fun perpTo(w: WTVec): WTVec {
        val e = cross(w, if (abs(w.y) < 0.9) AXIS_Y else AXIS_X)
        return e / len(e)
    }

    /**
     * Moves the load across the body plane towards the edge the stream
     * reaches first. Changes fs in place; the total force stays the same.
     */
    fun leadLoad(nb: WTVec, face: Double, rs: Array<WTVec>, fs: Array<WTVec>, um: WTVec): WTLead? {
        val us = len(um)
        if (!(us > 1e-6)) return null
        val wd = um / us
        var sa = dot(wd, nb)
        val tv = wd - nb * sa; val ct = len(tv)
        sa = abs(sa)
        val s0 = sin(LEAD.e0 * D2R); val s1 = sin(LEAD.e1 * D2R)
        val el = clamp((min(sa, face) - s0) / (s1 - s0), 0.0, 1.0); val plate = el * el * (3 - 2 * el)
        if (ct < 1e-4 || plate <= 0) return null
        val td = tv / ct
        val n = rs.size
        var W = 0.0; var xm = 0.0
        val N = DoubleArray(n); val xs = DoubleArray(n)
        for (i in 0 until n) {
            N[i] = dot(fs[i], nb); xs[i] = dot(rs[i], td)
            val aw = abs(N[i])
            W += aw; xm += aw * xs[i]
        }
        if (!(W > 1e-9)) return null
        xm /= W
        var v2 = 0.0
        for (i in 0 until n) v2 += abs(N[i]) * (xs[i] - xm) * (xs[i] - xm)
        val sg = sqrt(v2 / W)
        if (!(sg > 1e-4)) return null
        val c0 = cos(LEAD.a0 * D2R); val c1 = cos(LEAD.a1 * D2R)
        val e = clamp((ct - c1) / (c0 - c1), 0.0, 1.0); val full = e * e * (3 - 2 * e)
        var g = LEAD.k * ct / (4 + Math.PI * sa) * (LEAD.broad + (1 - LEAD.broad) * full) * plate
        val half = sqrt(3.0) * sg
        var um2 = 0.0
        for (i in 0 until n) if (abs(N[i]) > 1e-3 * W) um2 = max(um2, abs(xs[i] - xm) / half)
        if (um2 > 1e-9 && g * um2 > 0.95) g = 0.95 / um2
        val d = DoubleArray(n); var res = 0.0; var mom = 0.0; var tot = 0.0
        for (i in 0 until n) {
            d[i] = -g * (xs[i] - xm) / half * N[i]
            res += d[i]
        }
        for (i in 0 until n) {
            d[i] -= abs(N[i]) / W * res
            fs[i] = fs[i] + nb * d[i]
            mom += d[i] * xs[i]; tot += N[i]
        }
        return WTLead(shift = if (abs(tot) > 1e-9) -mom / tot else 0.0, angle = asin(clamp(sa, 0.0, 1.0)) * R2D, chord = 2 * half, dir = -td)
    }

    /**
     * Limbs side by side: a tilted pair is pushed towards its upstream edge.
     * Calls put(i, point, force) for every extra force.
     */
    fun pairLoads(body: WTBody, rs: Array<WTVec>, qs: Array<WTQuat>, fs: Array<WTVec>, um: WTVec, put: (Int, WTVec, WTVec) -> Unit) {
        val us = len(um)
        if (us < 1e-6) return
        val wd = um / us; val segs = body.segs; val n = segs.size
        val idx = ArrayList<Int>()
        val ax = Array(n) { WTVec.ZERO }; val nf = DoubleArray(n)
        for (i in 0 until n) {
            if (segs[i].kind != WTSegment.Kind.CYL) continue
            idx.add(i); ax[i] = qRot(qs[i], AXIS_Y); nf[i] = len(fs[i])
        }

        fun one(i: Int, j: Int) {
            val si = segs[i]; val sj = segs[j]; val N = min(nf[i], nf[j]) / 3; val rr = si.r + sj.r
            val ai = ax[i]; val aj = ax[j]; val ci = rs[i]; val cj = rs[j]
            val twin = si.id.dropLast(1) == sj.id.dropLast(1)
            for (p in -1..1) {
                val e = p.toDouble() * si.L / 3
                val pt = ci + ai * e
                val t = if (twin) p.toDouble() * sj.L / 3 else clamp(dot(pt - cj, aj), -sj.L / 2, sj.L / 2)
                val dv = cj + aj * t - pt
                val d2 = dot(dv, dv); val d = sqrt(d2); val pd = d / rr
                if (pd >= PAIR.far || d < 1e-6) continue
                val h = dot(dv, wd)
                val sv = dv - wd * h; val s = len(sv)
                if (s < 1e-6) continue
                val ei = dot(dv, ai) / d; val ej = dot(dv, aj) / d
                val g = -PAIR.k * clamp((PAIR.far - pd) / (PAIR.far - PAIR.near), 0.0, 1.0) * (1 - ei * ei) * (1 - ej * ej) * (h * s / d2) * N / s
                if (g != 0.0) put(i, pt, sv * g)
            }
        }
        for (a in 0 until idx.size) {
            for (b in a + 1 until idx.size) {
                val i = idx[a]; val j = idx[b]
                if (segs[i].grp == segs[j].grp || nf[i] < 1e-9 || nf[j] < 1e-9) continue
                val cv = rs[j] - rs[i]; val reach = (segs[i].L + segs[j].L) / 2 + PAIR.far * (segs[i].r + segs[j].r)
                if (dot(cv, cv) > reach * reach) continue
                one(i, j); one(j, i)
            }
        }
    }

    class AeroOptions(
        val rho: Double,
        val k: Double = 1.0,
        val suit: WTSuit = suits.getValue("std"),
        val U: WTVec? = null,
        val V: Double = 0.0,
        val profile: ((Double) -> Double)? = null,
        val pos: WTVec? = null,
        val vel: WTVec? = null,
        val omega: WTVec? = null,
        val collect: Boolean = false,
    )

    /** Total aerodynamic force and torque about the centre of mass, world frame. */
    fun evalAero(body: WTBody, R: WTQuat, o: AeroOptions): WTAero {
        val qd = 0.5 * o.rho * (if (o.k == 0.0) 1.0 else o.k); val suit = o.suit
        val posY = o.pos?.y ?: 0.0
        val n = body.segs.size
        val rs = Array(n) { WTVec.ZERO }; val fs = Array(n) { WTVec.ZERO }; val us = Array(n) { WTVec.ZERO }
        val qs = Array(n) { WTQuat.IDENTITY }
        val profile = o.profile; val vel = o.vel; val om = o.omega
        for (i in 0 until n) {
            val sg = body.segs[i]
            val r = qRot(R, sg.c - body.com)
            val qw = qMul(R, sg.q)
            var u = o.U ?: WTVec(0.0, if (profile != null) profile(posY + r.y) else o.V, 0.0)
            if (vel != null) u -= vel
            if (om != null) u -= cross(om, r)
            rs[i] = r; qs[i] = qw; us[i] = u
            fs[i] = qRot(qw, segLocalForce(sg, qInvRot(qw, u), qd, suit))
        }
        var um = o.U ?: WTVec(0.0, if (profile != null) profile(posY) else o.V, 0.0)
        if (vel != null) um -= vel
        val wk = wakeFactors(body, rs, qs, us, fs, um, qd)
        if (wk != null) for (i in 0 until n) if (wk[i] != 1.0) fs[i] = fs[i] * wk[i]
        val sh = shapeOf(body, R, rs, fs, um)
        val ld = if (sh != null) leadLoad(sh.nrm, sh.face, rs, fs, um) else null
        var gk = sh?.gain ?: 1.0
        val gs = sh?.gains
        val ts = Array(n) { WTVec.ZERO }
        for (i in 0 until n) {
            if (gs != null) gk = gs[i]
            if (gk != 1.0) fs[i] = fs[i] * gk
            ts[i] = cross(rs[i], fs[i])
        }
        pairLoads(body, rs, qs, fs, um) { k, pt, df ->
            fs[k] = fs[k] + df
            ts[k] = ts[k] + cross(pt, df)
        }
        var F = WTVec.ZERO; var T = WTVec.ZERO
        val per = ArrayList<WTPer>(if (o.collect) n else 0)
        for (i in 0 until n) {
            F += fs[i]; T += ts[i]
            if (o.collect) {
                val sg = body.segs[i]
                per.add(WTPer(id = sg.id, part = sg.part, zone = sg.zone, r = rs[i], f = fs[i], t = ts[i], q = wk?.get(i) ?: 1.0))
            }
        }
        return WTAero(F = F, T = T, per = per, shape = sh, lead = ld)
    }

    // The stream

    fun windProfile(V0: Double, y0: Double): Double {
        val y = max(0.0, y0)
        if (y <= TUNNEL.glassTop) return V0 * (1 - TUNNEL.gradient * y)
        val e = 1 + 0.35 * (y - TUNNEL.glassTop)
        return V0 * (1 - TUNNEL.gradient * TUNNEL.glassTop) / (e * e)
    }

    /** International Standard Atmosphere density, kg/m³. */
    fun airDensity(altM: Double): Double = 1.225 * (1 - 2.25577e-5 * altM).pow(4.25588)

    // Analysis

    fun axesOf(P: WTPose, R: WTQuat): WTAxes =
        WTAxes(lat = qRot(R, AXIS_X), head = qRot(R, AXIS_Y), chest = qRot(R, AXIS_Z), heading = qRot(QY(P.yaw), AXIS_Z))

    /**
     * The body at rest relative to the frame in a stream U (default
     * straight up at speed V). Drag along the stream, lift across it.
     */
    fun analyse(body: WTBody, P: WTPose, V: Double, env: WTEnv, U: WTVec? = null, Rin: WTQuat? = null): WTAnalysis {
        val R = Rin ?: orientQuat(P.pitch, P.roll, P.yaw)
        val res = evalAero(body, R, AeroOptions(rho = env.rho, k = env.k, suit = env.suit, U = U, V = V, collect = true))
        val W = body.M * G; val ax = axesOf(P, R)
        val wv = U ?: WTVec(0.0, V, 0.0); val ws = len(wv); val wd = if (ws > 1e-9) wv / ws else AXIS_Y
        val D = dot(res.F, wd); val Lv = res.F - wd * D
        val parts = LinkedHashMap<String, Double>()
        for (k in partKeys) parts[k] = 0.0
        val detail = LinkedHashMap<String, WTZone>()
        for (z in zones) detail[z] = WTZone()
        var lost = 0.0; var clean = 0.0
        for (pe in res.per) {
            val d = dot(pe.f, wd)
            parts[pe.part] = (parts[pe.part] ?: 0.0) + d
            val dt = detail.getValue(pe.zone)
            dt.drag += d
            dt.lift += pe.f - wd * d
            dt.mPitch -= dot(pe.t, ax.lat)
            dt.mRoll += dot(pe.t, ax.heading)
            dt.mYaw += pe.t.y
            val dc = if (pe.q > 0.02) d / pe.q else d * 50
            dt.clean += dc; clean += dc; lost += dc - d
        }
        for (k in zones) {
            val z = detail.getValue(k)
            z.liftMag = len(z.lift)
            z.wake = if (z.clean > 1e-9) clamp(1 - z.drag / z.clean, 0.0, 1.0) else 0.0
        }
        val F2 = dot(res.F, res.F); val cp = if (F2 > 1e-9) cross(res.F, res.T) / F2 else WTVec.ZERO
        val Lmag = len(Lv)
        return WTAnalysis(
            R = R, F = res.F, T = res.T, per = res.per, W = W, wind = wv, speed = ws,
            D = D, L = Lv, Lmag = Lmag, ratio = if (D > 1e-9) Lmag / D else 0.0,
            Fy = res.F.y, veq = if (D > 1e-6) ws * sqrt(W / D) else Double.POSITIVE_INFINITY,
            cda = if (ws > 1e-9) D / (0.5 * env.rho * ws * ws) else 0.0,
            mPitch = -dot(res.T, ax.lat), mRoll = dot(res.T, ax.heading), mYaw = res.T.y,
            parts = parts, detail = detail, cp = cp, axes = ax, shape = res.shape, lead = res.lead,
            wake = if (clean > 1e-9) clamp(lost / clean, 0.0, 1.0) else 0.0,
        )
    }

    /** A stream direction scaled to the reference speed of 50 m/s; null means straight up. */
    fun refWind(U: WTVec?): WTVec? {
        if (U == null) return null
        val n = len(U)
        return if (n > 1e-6) U * (50 / n) else null
    }

    fun momentsAt(body: WTBody, pitch: Double, roll: Double, yaw: Double, env: WTEnv, U50: WTVec?): WTMoments {
        val R = orientQuat(pitch, roll, yaw)
        val res = evalAero(body, R, AeroOptions(rho = env.rho, k = env.k, suit = env.suit, U = U50, V = 50.0))
        return WTMoments(mp = -dot(res.T, qRot(R, AXIS_X)), mr = dot(res.T, qRot(QY(yaw), AXIS_Z)), my = res.T.y, F = res.F)
    }

    fun stabilityCurves(body: WTBody, P: WTPose, V: Double, env: WTEnv, span: Double = 60.0, step: Double = 3.0, U: WTVec? = null): WTCurves {
        val xs = ArrayList<Double>(); val mp = ArrayList<Double>(); val mr = ArrayList<Double>()
        val sc = V * V / 2500; val U50 = refWind(U)
        var d = -span
        while (d <= span + 1e-9) {
            xs.add(d)
            mp.add(momentsAt(body, P.pitch + d, P.roll, P.yaw, env, U50).mp * sc)
            mr.add(momentsAt(body, P.pitch, P.roll + d, P.yaw, env, U50).mr * sc)
            d += step
        }

        fun trim(ys: List<Double>): Double? {
            var best: Double? = null
            for (i in 0 until xs.size - 1) {
                if (!(ys[i] > 0 && ys[i + 1] <= 0)) continue
                val x0 = xs[i] + (xs[i + 1] - xs[i]) * ys[i] / (ys[i] - ys[i + 1])
                val b = best
                if (b == null || abs(x0) < abs(b)) best = x0
            }
            return best
        }

        val t = trimStep(body, P, env, U)
        return WTCurves(
            x = xs.toDoubleArray(), pitch = mp.toDoubleArray(), roll = mr.toDoubleArray(), trimPitch = trim(mp), trimRoll = trim(mr),
            slopePitch = t.slopePitch * sc, slopeRoll = t.slopeRoll * sc,
            stiffPitch = t.stiffPitch, stiffRoll = t.stiffRoll, stablePitch = t.passiveP, stableRoll = t.passiveR,
        )
    }

    /** Where the attitude wants to go, axis by axis. */
    fun trimStep(body: WTBody, P: WTPose, env: WTEnv, U: WTVec? = null): WTTrimStep {
        val U50 = refWind(U)
        val m0 = momentsAt(body, P.pitch, P.roll, P.yaw, env, U50)
        val sp = (momentsAt(body, P.pitch + 2, P.roll, P.yaw, env, U50).mp - momentsAt(body, P.pitch - 2, P.roll, P.yaw, env, U50).mp) / 4
        val sr = (momentsAt(body, P.pitch, P.roll + 2, P.yaw, env, U50).mr - momentsAt(body, P.pitch, P.roll - 2, P.yaw, env, U50).mr) / 4
        val ref = max(len(m0.F), 1e-6) * body.H
        val kp = -sp / ref; val kr = -sr / ref
        val pp = kp > stableStiff; val pr = kr > stableStiff
        return WTTrimStep(
            passiveP = pp, passiveR = pr, dp = if (pp) -m0.mp / sp else 0.0, dr = if (pr) -m0.mr / sr else 0.0,
            stiffPitch = kp, stiffRoll = kr, slopePitch = sp, slopeRoll = sr,
        )
    }

    /** The lean of a roll the flier balances around level (+ = right). */
    fun leanOf(body: WTBody, P: WTPose, env: WTEnv, U: WTVec?): Double {
        var W = U
        if (U != null) {
            val y = P.yaw * D2R; val lx = cos(y); val lz = -sin(y); val sl = U.x * lx + U.z * lz
            W = WTVec(U.x - sl * lx, U.y, U.z - sl * lz)
        }
        val m = momentsAt(body, P.pitch, 0.0, P.yaw, env, refWind(W))
        return clamp(m.mr / (LEAN.stiff * max(len(m.F), 1e-6) * body.H), -LEAN.max, LEAN.max)
    }

    fun findTrim(body: WTBody, P: WTPose, env: WTEnv, maxIter: Int = 40, U: WTVec? = null, hold: WTHold = WTHold()): WTTrim {
        val Q = P.copy()
        val n = maxIter; val hp = hold.pitch; val hr = hold.roll
        var lean = false
        var t = trimStep(body, Q, env, U)
        var i = 0
        while (i < n) {
            t = trimStep(body, Q, env, U)
            lean = !hr && !(t.stiffRoll >= LEAN.stiff)
            if (hp) t.dp = 0.0
            if (hr) t.dr = 0.0 else if (lean) t.dr = leanOf(body, Q, env, U) - Q.roll
            if (abs(t.dp) < 0.02 && abs(t.dr) < 0.02) break
            Q.pitch += clamp(t.dp, -8.0, 8.0)
            Q.roll = clamp(Q.roll + clamp(t.dr, -8.0, 8.0), -89.0, 89.0)
            i += 1
        }
        val ok = i < n
        val pitch = wrap180(if (ok) Q.pitch else P.pitch)
        return WTTrim(
            pitch = pitch, roll = if (ok) Q.roll else P.roll, passiveP = t.passiveP, passiveR = t.passiveR,
            freeP = ok && !hp && t.passiveP, freeR = ok && !hr, leanR = lean, ok = ok,
        )
    }

    /** Pitch near the pose's own at which the force along the heading vanishes. */
    fun neutralPitch(body: WTBody, P: WTPose, V: Double, env: WTEnv, span: Double = 25.0): Double? {
        val hd = qRot(QY(P.yaw), AXIS_Z)
        fun fh(p: Double): Double = dot(momentsAt(body, p, P.roll, P.yaw, env, null).F, hd)
        var best: Double? = null
        var prev = fh(P.pitch - span); var pp = P.pitch - span
        var p = P.pitch - span + 1
        while (p <= P.pitch + span) {
            val cur = fh(p)
            if ((prev <= 0 && cur > 0) || (prev >= 0 && cur < 0)) {
                val x0 = pp + (p - pp) * prev / (prev - cur)
                val b = best
                if (b == null || abs(x0 - P.pitch) < abs(b - P.pitch)) best = x0
            }
            prev = cur; pp = p
            p += 1
        }
        return best
    }

    class NeutralFit(val pose: WTPose, val offset: Double, val drive: Double)

    private class FitPoint(val d: Double, val f: Double, val Q: WTPose)

    /**
     * Fits a pose that should hold its place to one body by moving one
     * joint on both sides until the pose has no drive along its heading.
     */
    fun fitNeutral(P: WTPose, H: Double, M: Double, env: WTEnv, hold: WTHold, joint: String = "knee", span: Double = 20.0, legs: Double = 1.0): NeutralFit {
        val lm = limits.getValue(joint)
        fun at(d: Double): FitPoint {
            val Q = P.copy()
            Q[joint + "L"] = clamp(P[joint + "L"] + d, lm.start, lm.endInclusive)
            Q[joint + "R"] = clamp(P[joint + "R"] + d, lm.start, lm.endInclusive)
            val b = buildBody(Q, H, M, legs); val t = findTrim(b, Q, env, 40, null, hold)
            if (t.freeP) Q.pitch = t.pitch
            if (t.freeR) Q.roll = t.roll
            val a = analyse(b, Q, 50.0, env)
            return FitPoint(d, if (a.D > 1e-9) dot(a.F, a.axes.heading) / a.D else 0.0, Q)
        }

        var x0 = at(0.0)
        var best = x0
        if (abs(x0.f) < 0.002) return NeutralFit(x0.Q, 0.0, x0.f)
        var x1 = at(if (x0.f > 0) 3.0 else -3.0)
        for (pass in 0 until 8) {
            if (abs(x1.f) < abs(best.f)) best = x1
            if (abs(x1.f) < 0.002 || abs(x1.f - x0.f) < 1e-9) break
            val d = clamp(x1.d - x1.f * (x1.d - x0.d) / (x1.f - x0.f), -span, span)
            if (abs(d - x1.d) < 0.05) break
            x0 = x1; x1 = at(d)
        }
        if (abs(x1.f) < abs(best.f)) best = x1
        return NeutralFit(best.Q, best.d, best.f)
    }

    // Flight dynamics

    /** Air velocity of the stream relative to the body as a whole (world frame). */
    fun relWind(sim: WTSim, env: WTEnv): WTVec = WTVec(-sim.vel.x, env.V - sim.vel.y, -sim.vel.z)

    fun stepSim(sim: WTSim, body: WTBody, env: WTEnv, dt: Double, held: WTPose) {
        if (sim.mode == WTSimMode.PIN) return
        val M = body.M
        if (sim.mode == WTSimMode.FLY) {
            sim.R = orientQuat(held.pitch, held.roll, sim.yaw)
            sim.omega = WTVec(0.0, sim.yawRate, 0.0)
        }
        val R = sim.R
        val V0 = env.V
        val opts = AeroOptions(
            rho = env.rho, k = env.k, suit = env.suit, V = env.V,
            profile = if (env.bounded) { y -> windProfile(V0, y) } else null,
            pos = sim.pos, vel = sim.vel, omega = sim.omega,
        )
        val res = evalAero(body, R, opts)
        var Fx = res.F.x; var Fy = res.F.y - M * G; var Fz = res.F.z
        var T = res.T
        sim.onNet = false; sim.onWall = false
        if (env.bounded) {
            // contact with the net and the glass: penalty springs at the ends of the segments
            val kc = 400 * M; val cc = 20 * M
            for (ct in body.contacts) {
                val r = qRot(R, ct.p - body.com)
                val x = sim.pos + r
                val vp = sim.vel + cross(sim.omega, r)
                var f: WTVec? = null
                val pen = ct.r - x.y
                if (pen > 0) {
                    val N = max(0.0, kc * pen - cc * vp.y)
                    val vt = sqrt(vp.x * vp.x + vp.z * vp.z); val mu = 0.7 * N / max(vt, 0.25)
                    f = WTVec(-mu * vp.x, N, -mu * vp.z)
                    sim.onNet = true
                }
                val rho = sqrt(x.x * x.x + x.z * x.z); val penW = rho + ct.r - TUNNEL.radius
                if (penW > 0 && rho > 1e-6 && x.y < TUNNEL.glassTop + 4.5) {
                    val nx = -x.x / rho; val nz = -x.z / rho
                    val Nw = max(0.0, kc * penW - cc * (vp.x * nx + vp.z * nz))
                    val ff = f ?: WTVec.ZERO
                    f = WTVec(ff.x + Nw * nx, ff.y, ff.z + Nw * nz)
                    sim.onWall = true
                }
                if (f != null) {
                    Fx += f.x; Fy += f.y; Fz += f.z
                    T += cross(r, f)
                }
            }
        }
        // the flier's own small corrections in 'fly': a capped spring to an anchor that is dragged along
        if (sim.mode == WTSimMode.FLY && env.assist > 0) {
            val lim = env.assist * M * G; val reach = 0.15; val ks = lim / reach; val cdm = 1.4 * sqrt(ks * M)
            var anchor = sim.anchor ?: WTAnchor(sim.pos.x, sim.pos.z).also { sim.anchor = it }
            var dx = sim.pos.x - anchor.x; var dz = sim.pos.z - anchor.z
            val dd = sqrt(dx * dx + dz * dz)
            if (dd > reach) {
                dx *= reach / dd; dz *= reach / dd
                anchor = WTAnchor(sim.pos.x - dx, sim.pos.z - dz)
                sim.anchor = anchor
            }
            var ax = -ks * dx - cdm * sim.vel.x; var az = -ks * dz - cdm * sim.vel.z
            val am = sqrt(ax * ax + az * az)
            if (am > lim) {
                ax *= lim / am; az *= lim / am
            }
            Fx += ax; Fz += az
        } else {
            sim.anchor = null
        }
        val F = WTVec(Fx, Fy, Fz)
        sim.vel += F * (dt / M)
        sim.pos += sim.vel * dt
        if (sim.mode == WTSimMode.FLY) {
            val e = qInvRot(R, AXIS_Y)
            val Iyy = dot(e, mat3Vec(body.I, e))
            sim.yawRate += (T.y - 0.4 * Iyy * sim.yawRate) / Iyy * dt
            sim.yaw += sim.yawRate * R2D * dt
            if (sim.yaw > 180) sim.yaw -= 360.0 else if (sim.yaw < -180) sim.yaw += 360.0
        } else {
            // Euler's equations in the body frame, plus a little damping for the flier's reflexes
            var wb = qInvRot(R, sim.omega)
            val Tb = qInvRot(R, T)
            val Iw = mat3Vec(body.I, wb)
            val rhs = Tb - cross(wb, Iw) - Iw * env.reflex
            wb += mat3Vec(body.Iinv, rhs) * dt
            val wl = len(wb)
            if (wl > 25) wb *= 25 / wl
            sim.omega = qRot(R, wb)
            val o = sim.omega; val dq = qMul(WTQuat(o.x, o.y, o.z, 0.0), R)
            sim.R = qNorm(WTQuat(R.x + 0.5 * dt * dq.x, R.y + 0.5 * dt * dq.y, R.z + 0.5 * dt * dq.z, R.w + 0.5 * dt * dq.w))
        }
    }

    /** One frame of 'fly' for a pose Q (pitch and roll updated in place). */
    fun flyFrame(sim: WTSim, body: WTBody, Q: WTPose, env: WTEnv, dt: Double, substeps: Int, hold: WTHold): WTFlyInfo {
        Q.yaw = sim.yaw
        val U = relWind(sim, env)
        var rest: WTTrimStep? = null; var tr: WTTrimStep? = null; var lean = false
        var fp = !hold.pitch; var fr = !hold.roll
        if (len(U) > 3 && (fp || fr)) {
            val r0 = trimStep(body, Q, env)
            rest = r0
            fp = fp && r0.passiveP
            if (fp || fr) {
                val y = sim.yaw * D2R; val lx = cos(y); val lz = -sin(y); val sl = U.x * lx + U.z * lz
                val Ua = WTVec(U.x - sl * lx, U.y, U.z - sl * lz)
                val up = sqrt(Ua.x * Ua.x + Ua.z * Ua.z) < 0.009 * abs(Ua.y) && Ua.y > 0
                val t = if (up) r0 else trimStep(body, Q, env, Ua)
                tr = t
                val kf = 1 - exp(-dt / 0.3); val cap = 90 * dt
                fp = fp && t.passiveP
                if (fp) Q.pitch += clamp(t.dp * kf, -cap, cap)
                if (fr) {
                    lean = !(r0.stiffRoll >= LEAN.stiff && t.stiffRoll >= LEAN.stiff)
                    val dr = if (lean) leanOf(body, Q, env, if (up) null else Ua) - Q.roll else t.dr
                    Q.roll = clamp(Q.roll + clamp(dr * kf, -cap, cap), -89.0, 89.0)
                }
                if (Q.pitch > 180) Q.pitch -= 360.0 else if (Q.pitch < -180) Q.pitch += 360.0
            }
        } else {
            fp = false; fr = false
        }
        val n = max(1, substeps)
        for (pass in 0 until n) stepSim(sim, body, env, dt / n.toDouble(), Q)
        return WTFlyInfo(rest = rest, trim = tr, freeP = fp, freeR = fr, leanR = lean)
    }

    // Steady state

    fun steadyBegin(body: WTBody, P: WTPose, env: WTEnv, hold: WTHold, maxTime: Double = 45.0): WTSteadyJob? {
        val a0 = analyse(body, P, 50.0, env)
        if (!a0.veq.isFinite()) return null
        val e = WTEnv(rho = env.rho, k = env.k, suit = env.suit, V = a0.veq, assist = env.assist)
        val job = WTSteadyJob(body = body, Q = P.copy(), veq = a0.veq, hold = hold, env = e, maxTime = maxTime)
        job.sim.yaw = P.yaw
        return job
    }

    fun steadySample(s: WTSteadyJob): WTSample {
        val sim = s.sim; val y = sim.yaw * D2R; val hx = sin(y); val hz = cos(y)
        return WTSample(
            fall = s.env.V - sim.vel.y, fwd = sim.vel.x * hx + sim.vel.z * hz, left = sim.vel.x * hz - sim.vel.z * hx,
            yawRate = sim.yawRate * R2D, pitch = s.Q.pitch, roll = s.Q.roll,
        )
    }

    /** Advances by at most maxFrames frames of 1/30 s. Returns true when the run is over. */
    fun steadyRun(s: WTSteadyJob, maxFrames: Int): Boolean {
        var f = 0
        while (f < maxFrames && !s.done) {
            val fi = flyFrame(s.sim, s.body, s.Q, s.env, s.dt, 2, s.hold)
            s.freeP = fi.freeP; s.freeR = fi.freeR
            s.t += s.dt; s.n += 1
            if (s.n % 15 == 0) {
                val c = steadySample(s)
                if (!(c.fall + c.fwd + c.left + c.yawRate + c.pitch + c.roll).isFinite()) {
                    s.failed = true; s.done = true
                    break
                }
                val last = s.hist.lastOrNull()
                if (last != null && abs(c.fall - last.fall) < 0.03 && abs(c.fwd - last.fwd) < 0.03 && abs(c.left - last.left) < 0.03 &&
                    abs(c.yawRate - last.yawRate) < 0.3 && abs(c.pitch - last.pitch) < 0.1 && abs(c.roll - last.roll) < 0.1
                ) {
                    s.calm += 1
                } else {
                    s.calm = 0
                }
                s.cur = c
                s.hist.add(c)
                if (s.hist.size > 20) s.hist.removeAt(0)
                if (s.calm >= 3) s.done = true
            }
            if (s.t >= s.maxTime) s.done = true
            f += 1
        }
        return s.done
    }

    fun steadyResult(s: WTSteadyJob?): WTSteady? {
        if (s == null || s.failed) return null
        var o = s.cur ?: steadySample(s)
        if (s.calm < 3 && s.hist.isNotEmpty()) {
            // still wobbling: report the mean of the last 10 s
            val n = s.hist.size.toDouble()
            o = WTSample(
                fall = s.hist.sumOf { it.fall } / n, fwd = s.hist.sumOf { it.fwd } / n,
                left = s.hist.sumOf { it.left } / n, yawRate = s.hist.sumOf { it.yawRate } / n,
                pitch = s.hist.sumOf { it.pitch } / n, roll = s.hist.sumOf { it.roll } / n,
            )
        }
        val speed = sqrt(o.fwd * o.fwd + o.left * o.left)
        val out = WTSteady(
            fall = o.fall, fwd = o.fwd, left = o.left, yawRate = o.yawRate, pitch = o.pitch, roll = o.roll,
            speed = speed, glide = if (o.fall > 1e-6) speed / o.fall else 0.0, settled = s.calm >= 3, time = s.t, veq = s.veq,
            radius = if (abs(o.yawRate) > 0.5) speed / (abs(o.yawRate) * D2R) else Double.POSITIVE_INFINITY,
            heldP = !s.freeP, heldR = !s.freeR, mPitch = 0.0, mRoll = 0.0,
        )
        val U = relWind(s.sim, s.env); val sp = len(U)
        if (sp > 1e-6) {
            val m = momentsAt(s.body, s.Q.pitch, s.Q.roll, s.sim.yaw, s.env, U * (50 / sp)); val sc = sp * sp / 2500
            if ((m.mp + m.mr).isFinite()) {
                out.mPitch = m.mp * sc; out.mRoll = m.mr * sc
            }
        }
        return out
    }

    fun steadyState(body: WTBody, P: WTPose, env: WTEnv, hold: WTHold): WTSteady? {
        val s = steadyBegin(body, P, env, hold) ?: return null
        while (!steadyRun(s, 600)) {
            // keep going
        }
        return steadyResult(s)
    }

    class Euler(val pitch: Double, val roll: Double, val yaw: Double)

    /** pitch, roll, yaw (degrees) of a world orientation, the inverse of orientQuat. */
    fun eulerOf(R: WTQuat): Euler {
        val m = qToMat(R)
        val roll = asin(clamp(m[1].x, -1.0, 1.0))
        val yaw = atan2(-m[2].x, m[0].x)
        val a = atan2(-m[1].z, m[1].y)
        var pitch = 90 - a * R2D
        if (pitch > 180) pitch -= 360.0
        if (pitch < -180) pitch += 360.0
        return Euler(pitch, roll * R2D, yaw * R2D)
    }

    // Preset fitting

    /**
     * A preset's pose for one body (the web page's presetFor): an axis that
     * restores itself starts at its own trim, a pose that should stay in
     * place is fitted to the body, and a pose whose pitch does not trim
     * starts at the pitch with no drive.
     */
    fun presetPose(pr: WTPreset, H: Double, M: Double, legs: Double, env: WTEnv): WTPose {
        var p = pr.pose.copy()
        val hold = pr.hold
        val joint = pr.fitJoint
        if (joint != null) {
            p = fitNeutral(p, H, M, env, hold, joint, pr.fitSpan ?: 20.0, legs).pose
        } else {
            val b = buildBody(p, H, M, legs); val tq = findTrim(b, p, env, 40, null, hold)
            if (tq.freeP) {
                p.pitch = tq.pitch
            } else if (pr.neutral && !hold.pitch) {
                val np = neutralPitch(b, p, 50.0, env, 12.0)
                if (np != null) p.pitch = np
            }
            if (tq.freeR) p.roll = tq.roll
        }
        p.pitch = wrap180(p.pitch)
        return p
    }

    /** A preset by id, the first one when the id is unknown. */
    fun preset(id: String): WTPreset = presets.firstOrNull { it.id == id } ?: presets[0]

    // Presets

    /**
     * Joint angles tuned on the 175 cm, 75 kg body. hold names the axes the
     * flier keeps at the preset's angle; fit the joint fitNeutral may move.
     */
    val presets: List<WTPreset> = listOf(
        WTPreset(
            id = "box", name = "弓形", en = "Arch", neutral = true, fitJoint = "knee",
            pose = makePose(
                mapOf("pitch" to 0.0, "neckFlex" to -35.0, "spineFlex" to -15.0),
                mapOf("shAbd" to 82.0, "shFlex" to -19.0, "shRot" to 90.0, "elbow" to 90.0, "pron" to 90.0),
                mapOf("hipFlex" to -7.0, "hipAbd" to 22.0, "knee" to 34.0, "ankle" to 40.0),
            ),
            note = "趴飞的基础中立姿态。髋部前送，四肢微微抬起成弓形，受力中心在重心上方，所以能自己回正。",
            noteEn = "The basic neutral belly-to-earth position. Hips forward, arms and legs slightly raised into an arch: the centre of pressure sits above the centre of mass, so the body rights itself.",
        ),
        WTPreset(
            id = "arch", name = "大弓身", en = "Hard arch", neutral = true, fitJoint = "knee",
            pose = makePose(
                mapOf("pitch" to 0.0, "neckFlex" to -45.0, "spineFlex" to -30.0),
                mapOf("shAbd" to 84.0, "shFlex" to -31.0, "shRot" to 90.0, "elbow" to 95.0, "pron" to 90.0),
                mapOf("hipFlex" to -19.0, "hipAbd" to 25.0, "knee" to 34.0, "ankle" to 45.0),
            ),
            note = "弓得更深，四肢后掠，迎风面积变小，凸面又让气流更容易从四周滑走，下落更快，要更高的风速才能托住。",
            noteEn = "A deeper arch with the limbs swept back: less area to the wind, and the convex shape lets the air slip round the edges. It falls faster and needs more wind to hover.",
        ),
        WTPreset(
            id = "dearch", name = "反弓", en = "De-arch", neutral = true,
            pose = makePose(
                mapOf("pitch" to 0.0, "neckFlex" to -15.0, "spineFlex" to 25.0),
                mapOf("shAbd" to 82.0, "shFlex" to 20.0, "shRot" to 90.0, "elbow" to 90.0, "pron" to 90.0),
                mapOf("hipFlex" to 25.0, "hipAbd" to 22.0, "knee" to 34.0, "ankle" to 40.0),
            ),
            note = "背拱起来，髋和四肢压向来流，整个身体像碗口朝下兜住空气，同样的风速下阻力比弓形大两成多，下落很慢。受力中心落到重心下方，俯仰和侧倾都不回正，要一直主动平衡。",
            noteEn = "Back rounded, hips and limbs pressed into the wind: the body cups the air like an upturned bowl, with over 20% more drag than the arch, so it falls slowly. The centre of pressure drops below the centre of mass: pitch and roll no longer right themselves and must be balanced all the time.",
        ),
        WTPreset(
            id = "slow", name = "平展慢落", en = "Slow fall", neutral = true,
            pose = makePose(
                mapOf("pitch" to 0.0, "neckFlex" to -25.0, "spineFlex" to 10.0),
                mapOf("shAbd" to 100.0, "shFlex" to 6.0, "shRot" to 90.0, "elbow" to 40.0, "pron" to 90.0),
                mapOf("hipFlex" to 9.0, "hipAbd" to 32.0, "knee" to 12.0, "ankle" to 34.0),
            ),
            note = "身体放平，手脚伸开，迎风面积最大，平板又比弓形兜风，下落很慢。代价是失去弓形，俯仰和侧倾都不再自己回正。",
            noteEn = "Body flat, arms and legs spread: the largest area to the wind, and a flat plate holds more air than an arch, so it falls slowly. The price is the arch: pitch and roll no longer right themselves.",
        ),
        WTPreset(
            id = "track", name = "滑翔", en = "Tracking", neutral = false, hold = WTHold(pitch = true),
            pose = makePose(
                mapOf("pitch" to -18.0, "neckFlex" to -15.0, "spineFlex" to 8.0),
                mapOf("shAbd" to 18.0, "shFlex" to 0.0, "shRot" to 0.0, "elbow" to 0.0, "pron" to 0.0),
                mapOf("hipFlex" to 0.0, "hipAbd" to 8.0, "knee" to 0.0, "ankle" to 55.0),
            ),
            note = "双腿伸直并拢，手臂收在体侧，身体低头，把气流向后偏转，换来向前的速度。飞起来以后，上半身先迎到气流并把它压弯，腿在后面的下洗里升力变小，受力中心移到重心前面，身体会想抬头。要靠上半身一直下压才能把头压住，一松就抬头、变慢。",
            noteEn = "Legs straight and together, arms at the sides, head low: the body turns the air backwards and gains forward speed. Once it flies, the upper body meets the air first and bends it, the legs behind lose lift in the downwash, the centre of pressure moves ahead of the centre of mass and the body wants to raise its head. The upper body has to keep pressing down; let go and the head comes up and the track slows.",
        ),
        WTPreset(
            id = "angle", name = "角度飞", en = "Angle", neutral = false, hold = WTHold(pitch = true),
            pose = makePose(
                mapOf("pitch" to -45.0, "neckFlex" to -10.0, "spineFlex" to 0.0),
                mapOf("shAbd" to 30.0, "shFlex" to 0.0, "shRot" to 0.0, "elbow" to 10.0, "pron" to 0.0),
                mapOf("hipFlex" to 0.0, "hipAbd" to 12.0, "knee" to 5.0, "ankle" to 50.0),
            ),
            note = "趴着角度飞：身体伸直，头比脚低约 45°，手臂收在体侧略微张开，双腿伸直稍分开。比滑翔更陡，下落快得多，同时向头的方向前进。俯仰角要一直主动保持，腿和手臂一点点的变化就会改变角度和速度。",
            noteEn = "Belly-down angle flying: body straight, head about 45° below the feet, arms at the sides and slightly open, legs straight and a little apart. Steeper than a track, it falls much faster while moving towards the head. The pitch has to be held actively; small changes of legs and arms change the angle and the speed.",
        ),
        WTPreset(
            id = "turn", name = "弓形左转", en = "Arch turn", neutral = false, fitJoint = "knee",
            pose = makePose(
                mapOf("pitch" to 0.0, "neckFlex" to -35.0, "neckTurn" to 25.0, "spineFlex" to -15.0),
                mapOf("shAbd" to 82.0, "shFlex" to -7.0, "shRot" to 90.0, "elbow" to 90.0, "pron" to 90.0),
                mapOf("hipFlex" to -12.0, "hipAbd" to 22.0, "knee" to 34.0, "ankle" to 40.0),
                mapOf("shFlex" to -31.0), mapOf("hipFlex" to -2.0, "hipRot" to 15.0),
            ),
            note = "左肘下压、右肘上抬，上半身把气流偏向右侧，头这一端被推向左边。右膝下压、左膝抬高：右腿更兜风，把身体带得向左倾，压低的右大腿内侧迎风，下半身被推向右边。右大腿再向外转一点，让右小腿内侧迎风，像舵一样加一把力。上下半身像螺旋桨一样一起把身体向左转，人留在原地。",
            noteEn = "Left elbow down, right elbow up: the upper body turns the air to the right and the head end is pushed left. Right knee down, left knee up: the right leg catches more air and leans the body left, the inside of the lowered right thigh meets the wind and the lower body is pushed right. The right thigh turned out a little puts the inside of the right shin into the wind like a rudder. Upper and lower body work like a propeller and turn the body left on the spot.",
        ),
        WTPreset(
            id = "back", name = "背飞", en = "Back fly", neutral = true, fitJoint = "shFlex", fitSpan = 30.0,
            pose = makePose(
                mapOf("pitch" to 180.0, "neckFlex" to 25.0, "spineFlex" to 18.0),
                mapOf("shAbd" to 82.0, "shFlex" to 42.0, "shRot" to 90.0, "elbow" to 60.0, "pron" to 90.0),
                mapOf("hipFlex" to 31.0, "hipAbd" to 25.0, "knee" to 48.0, "ankle" to 0.0),
            ),
            note = "背对来流，髋微屈，四肢抬向胸前，同样是凸面迎风，所以也能自己回正。迎风面积比趴飞略小，下落略快。",
            noteEn = "Back to the wind, hips slightly bent, arms and legs raised towards the chest: again a convex side faces the wind, so it rights itself too. A little less area than belly flying, so it falls a little faster.",
        ),
        WTPreset(
            id = "sit", name = "坐飞", en = "Sit fly", neutral = true,
            pose = makePose(
                mapOf("pitch" to 90.0, "neckFlex" to 0.0, "spineFlex" to 4.0),
                mapOf("shAbd" to 74.0, "shFlex" to -38.0, "shRot" to 20.0, "elbow" to 30.0, "pron" to 90.0),
                mapOf("hipFlex" to 86.0, "hipAbd" to 18.0, "knee" to 100.0, "ankle" to -2.0),
            ),
            note = "躯干直立，靠大腿、臀部、脚掌和双臂承风。躯干和头在臀部和大腿的尾流里，几乎不受力，所以迎风面积小得多，下落明显更快。俯仰和侧倾都不回正，要一直主动平衡。",
            noteEn = "Torso upright, carried by the thighs, seat, feet and arms. Torso and head sit in the wake of the seat and thighs and carry almost nothing, so the area to the wind is much smaller and it falls clearly faster. Pitch and roll do not right themselves and must be balanced all the time.",
        ),
        WTPreset(
            id = "head", name = "头朝下", en = "Head down", neutral = true,
            pose = makePose(
                mapOf("pitch" to -90.0, "neckFlex" to 0.0, "spineFlex" to 0.0),
                mapOf("shAbd" to 67.0, "shFlex" to -11.0, "shRot" to 0.0, "elbow" to 12.0, "pron" to 90.0),
                mapOf("hipFlex" to 0.0, "hipAbd" to 24.0, "knee" to 14.0, "ankle" to 0.0),
            ),
            note = "身体倒立顺着气流，头盔和肩膀顶在最前面，髋和腿都排在它们的尾流里，迎风面积最小，下落最快。俯仰不能自己回正，要靠主动控制。",
            noteEn = "Upside down along the stream, helmet and shoulders first, hips and legs in their wake: the smallest area to the wind and the fastest fall. Pitch does not right itself and has to be controlled actively.",
        ),
    )
}
