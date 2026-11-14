package com.skyalt.canopy.windtunnel

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Chinese or English for the simulator's generated sentences. The web
 * prototype builds these sentences from numbers on the fly, so they are
 * written here in both languages rather than as catalogue keys, as in
 * WindTunnelText.swift.
 */
class WTText(val zh: Boolean) {
    fun s(zh: String, en: String): String = if (this.zh) zh else en

    fun modeNote(m: WTMode): String = when (m) {
        WTMode.PIN -> s(
            "固定：身体定在气流里不动，朝向和角度完全按你设定，只看这个姿态的受力。飞起来以后会怎样，看「这个姿态会怎么飞」里的推算。",
            "Pinned: the body stays put in the stream at exactly the angles you set, to show the forces on this pose. How it would fly is predicted under How this pose flies.",
        )
        WTMode.FLY -> s(
            "飞行：俯仰能自己回正的会转到平衡角度，不能回正的按设定的角度保持。侧倾没按住时，身体会被两侧不一样的受力带着倾斜，压膝的转向主要就是这样来的。升降、平移和转向按受力运动。",
            "Flying: a pitch that rights itself turns to its balance angle, one that does not is kept at the set angle. A roll that is not held leans with the loads on the two sides, which is where most of a knee-press turn comes from. Rise, fall, drift and turn follow the forces.",
        )
        WTMode.FREE -> s("放手：没有任何主动控制，身体会自己翻到能稳住的姿态。", "Hands off: no active control at all; the body tumbles into whatever attitude it can hold.")
    }

    fun envNote(e: WTEnvKind): String =
        if (e == WTEnvKind.FREE) {
            s("自由落体：空气静止，没有风速可调。身体从静止开始下落，加速到这个姿态的终速后稳定。", "Free fall: still air, no wind to set. The body falls from rest and speeds up to this pose's terminal speed.")
        } else {
            s("风洞：底下是网，四周是直径 8 m 的玻璃墙，玻璃段高 5 m，再往上风速变小。风速低于悬停时身体会落到网上。", "Tunnel: a net below and a glass wall 8 m across, 5 m high, with the stream weakening above it. Below hover speed the body sinks onto the net.")
        }

    fun zoneName(z: String): String = zoneNames[z]?.let { s(it.first, it.second) } ?: z

    fun measureName(m: WTMeasure): String = when (m) {
        WTMeasure.DRAG -> s("阻力", "Drag")
        WTMeasure.LIFT -> s("升力", "Lift")
        WTMeasure.PITCH -> s("俯仰", "Pitch")
        WTMeasure.ROLL -> s("侧倾", "Roll")
        WTMeasure.YAW -> s("转向", "Yaw")
    }

    fun measurePos(m: WTMeasure): String = when (m) {
        WTMeasure.PITCH -> s("抬头", "head up")
        WTMeasure.ROLL -> s("右倾", "right")
        WTMeasure.YAW -> s("左转", "left")
        else -> ""
    }

    fun measureNeg(m: WTMeasure): String = when (m) {
        WTMeasure.PITCH -> s("低头", "head down")
        WTMeasure.ROLL -> s("左倾", "left")
        WTMeasure.YAW -> s("右转", "right")
        else -> ""
    }

    fun measureNote(m: WTMeasure): String = when (m) {
        WTMeasure.DRAG -> s("顺着相对气流方向的力，托住体重的就是它。手算在手臂里，脚算在小腿里。", "The force along the relative wind; it is what carries the weight. Hands count with the arms, feet with the shins.")
        WTMeasure.LIFT -> s("垂直于相对气流的力，推着身体平移。左右两侧方向相反的部分会互相抵消。", "The force across the relative wind; it moves the body sideways. Opposite parts on the two sides cancel out.")
        WTMeasure.PITCH -> s("各部位让身体抬头还是低头。两边加起来为零，俯仰就平衡。", "Whether each part raises or lowers the head. When the two add up to zero, pitch is balanced.")
        WTMeasure.ROLL -> s("各部位让身体向左倾还是向右倾。", "Whether each part leans the body left or right.")
        WTMeasure.YAW -> s("各部位让身体向左转还是向右转。", "Whether each part turns the body left or right.")
    }

    companion object {
        val zoneNames: Map<String, Pair<String, String>> = mapOf(
            "head" to ("头" to "Head"), "torso" to ("躯干" to "Torso"), "armL" to ("左臂" to "Left arm"), "armR" to ("右臂" to "Right arm"),
            "thighL" to ("左大腿" to "Left thigh"), "thighR" to ("右大腿" to "Right thigh"),
            "shankL" to ("左小腿" to "Left shin"), "shankR" to ("右小腿" to "Right shin"),
        )
    }
}

enum class WTTagIcon { UP, DOWN, HOLD, OK, WARN, DOT, SIDE, NET, PIN }

enum class WTTagTone { ACCENT, GOOD, WARN }

data class WTTag(val icon: WTTagIcon, val tone: WTTagTone, val text: String)

data class WTRow(val id: String, val name: String, val value: String, val detail: String, val tag: WTTag?, val wide: Boolean = false)

data class WTZoneBar(
    val id: String,
    val name: String,
    /** Width of the bar as a share of the track (unsigned) or of half of it (signed). */
    val fraction: Double,
    /** Clean-air drag the wake took away, as a share of the track. */
    val ghost: Double,
    /** Signed bars grow to the right from the middle when true. */
    val right: Boolean,
    val word: String,
    val value: String,
)

data class WTStat(val id: String, val name: String, val value: String, val unit: String)

/** Everything the analysis column shows, rebuilt a few times a second. */
data class WTPanel(
    val presetNote: String = "",
    val zoneBars: List<WTZoneBar> = emptyList(),
    val zoneSigned: Boolean = false,
    val zoneAxisLeft: String = "",
    val zoneAxisRight: String = "",
    val zoneNote: String = "",
    val headLine: String = "",
    val curveX: List<Double> = emptyList(),
    val curvePitch: List<Double> = emptyList(),
    val curveRoll: List<Double> = emptyList(),
    val curveMax: Double = 10.0,
    val trimText: String = "",
    val insight: String = "",
    val prediction: String = "",
    val stale: Boolean = false,
    val stats: List<WTStat> = emptyList(),
    val flowLine: String = "",
    val motionRows: List<WTRow> = emptyList(),
    val forceRows: List<WTRow> = emptyList(),
    val holdNote: String = "",
)

/** Numbers as the panels print them: a point for the decimals in every language, as on the iPhone. */
object WTFormat {
    fun fixed(x: Double, decimals: Int): String = String.format(Locale.US, "%." + decimals + "f", x)

    /** To the nearest whole number, halves away from zero (Swift's rounded()). */
    fun round(x: Double): Double = if (x < 0) -floor(-x + 0.5) else floor(x + 0.5)

    fun whole(x: Double): String = if (x.isFinite()) round(x).toLong().toString() else "–"
}

/**
 * Builds the panel from the engine's state: a port of WTPanelBuilder in
 * WindTunnelText.swift (the web page's insight, prediction, rows, zone
 * bars and chart texts). Call it with the engine's lock held.
 */
class WTPanelBuilder(private val engine: WindTunnelEngine, private val live: WTLive, private val t: WTText) {
    private val st: WTSettings get() = engine.settings
    private val rest: WTRest? get() = engine.rest
    private val curves: WTCurves? get() = engine.curves
    private val sim: WTSim get() = engine.sim
    private val cur: WTPose get() = engine.cur

    private class BodyAxes(val lat: WTVec, val head: WTVec, val chest: WTVec)

    // Formatting

    fun vNum(v: Double): String = if (!v.isFinite()) "–" else WTFormat.fixed(v * st.unit.factor, st.unit.decimals)
    fun vStr(v: Double): String = vNum(v) + " " + st.unit.label
    fun fmtTorque(x0: Double): String {
        val x = if (abs(x0) < 0.05) 0.0 else x0
        return (if (abs(x) < 10) WTFormat.fixed(x, 1) else WTFormat.whole(x)) + " N·m"
    }

    fun fmtAbs(x0: Double): String {
        val x = abs(x0)
        return if (x < 0.05) "0" else if (x < 10) WTFormat.fixed(x, 1) else WTFormat.whole(x)
    }

    fun signed1(x: Double): String = if (abs(x) < 0.05) "0.0" else (if (x > 0) "+" else "−") + WTFormat.fixed(abs(x), 1)
    fun r0(x: Double): String = WTFormat.whole(x)
    private fun f1(x: Double): String = WTFormat.fixed(x, 1)
    private fun f2(x: Double): String = WTFormat.fixed(x, 2)

    /** Name of the body direction a horizontal vector points along most closely. */
    private fun sideName(v: WTVec, pose: WTPose, axes: BodyAxes): String {
        val n = sqrt(v.x * v.x + v.z * v.z)
        if (!(n > 1e-9)) return ""
        val belly = abs(pose.pitch) <= 45 && abs(pose.roll) <= 45
        val cands: List<Pair<WTVec, String>> = listOf(
            axes.head to (if (belly) t.s("向前", "forward") else t.s("向头顶方向", "towards the head")),
            -axes.head to (if (belly) t.s("向后", "backward") else t.s("向脚的方向", "towards the feet")),
            axes.chest to t.s("向胸口一侧", "towards the chest"), -axes.chest to t.s("向后背一侧", "towards the back"),
            axes.lat to t.s("向左", "left"), -axes.lat to t.s("向右", "right"),
        )
        var best = ""; var bd = -2.0
        for (c in cands) {
            val h = sqrt(c.first.x * c.first.x + c.first.z * c.first.z)
            if (h < 0.5) continue
            val d = (c.first.x * v.x + c.first.z * v.z) / (h * n)
            if (d > bd) {
                bd = d
                best = c.second
            }
        }
        return best
    }

    private fun axesFor(pose: WTPose): BodyAxes {
        val R = WTCore.orientQuat(pose.pitch, pose.roll, pose.yaw)
        return BodyAxes(WTCore.qRot(R, WTVec(1.0, 0.0, 0.0)), WTCore.qRot(R, WTVec(0.0, 1.0, 0.0)), WTCore.qRot(R, WTVec(0.0, 0.0, 1.0)))
    }

    private val liveAxes: BodyAxes get() = BodyAxes(live.a.axes.lat, live.a.axes.head, live.a.axes.chest)

    // Build

    private class Zones(
        val bars: List<WTZoneBar>, val signed: Boolean, val axisLeft: String, val axisRight: String, val note: String, val headLine: String,
    )

    private class Chart(val x: List<Double>, val pitch: List<Double>, val roll: List<Double>, val max: Double, val trimText: String)

    fun build(): WTPanel {
        val pr = WTCore.preset(st.preset)
        val presetNote = if (st.modified) {
            t.s("自定义姿态，从「${pr.name}」改来。再点一次预设可以还原。", "Custom pose, changed from ${pr.en}. Tap the preset again to restore it.")
        } else {
            t.s(pr.note, pr.noteEn)
        }
        val zones = buildZones()
        val chart = buildChart()
        val stale = engine.ssStale
        val r = if (st.mode == WTMode.FREE) null else engine.steady
        val flowLine = t.s("相对气流 ", "Relative wind ") + vStr(live.speed) +
            (if (live.tilt > 2) t.s("，偏离竖直 ${r0(live.tilt)}°", ", ${r0(live.tilt)}° off vertical") else "")
        val rows = buildRows()
        return WTPanel(
            presetNote = presetNote,
            zoneBars = zones.bars, zoneSigned = zones.signed, zoneAxisLeft = zones.axisLeft, zoneAxisRight = zones.axisRight,
            zoneNote = zones.note, headLine = zones.headLine,
            curveX = chart?.x ?: emptyList(), curvePitch = chart?.pitch ?: emptyList(), curveRoll = chart?.roll ?: emptyList(),
            curveMax = chart?.max ?: 10.0, trimText = chart?.trimText ?: "",
            insight = insight(), prediction = prediction(), stale = stale && r != null,
            stats = buildStats(r), flowLine = flowLine,
            motionRows = rows.first, forceRows = rows.second, holdNote = holdNote(),
        )
    }

    private fun buildStats(r: WTSteady?): List<WTStat> {
        val u = st.unit.label
        val stats = ArrayList<WTStat>()
        stats += WTStat("veq", if (st.env == WTEnvKind.TUNNEL) t.s("悬停风速", "Hover wind") else t.s("下落终速", "Terminal speed"), vNum(rest?.veq ?: Double.NaN), u)
        stats += WTStat("vh", t.s("稳定后平移", "Steady drift"), r?.let { vNum(it.speed) } ?: "–", u)
        stats += WTStat("glide", t.s("滑翔比", "Glide ratio"), r?.let { f2(it.glide) } ?: "–", "")
        stats += WTStat("fall", t.s("稳定后落速", "Steady fall"), r?.let { vNum(it.fall) } ?: "–", u)
        var turnUnit = "°/s"
        if (r != null && abs(r.yawRate) >= 0.5) {
            turnUnit = if (engine.steadySym) {
                if (abs(r.yawRate) >= 3) t.s("°/s 方向不定", "°/s either way") else "°/s"
            } else {
                "°/s " + (if (r.yawRate > 0) t.s("向左", "left") else t.s("向右", "right"))
            }
        }
        stats += WTStat("turn", t.s("稳定后转动", "Steady turn"), r?.let { r0(abs(it.yawRate)) } ?: "–", turnUnit)
        stats += WTStat("cda", "CdA", rest?.let { if (it.cda.isFinite()) f2(it.cda) else "–" } ?: "–", "m²")
        val shOn = shapeCounts()
        var shapeUnit = t.s("躯干没有正对气流，不计", "torso not facing the stream, not counted")
        var shapeValue = "–"
        val sh = rest?.shape
        if (shOn && sh != null) {
            shapeValue = shapeName(sh)
            shapeUnit = t.s("阻力 ×", "drag ×") + f2(sh.gain) + (if (shapePartial()) t.s("，躯干斜对气流，只计一部分", ", torso at a slant, partly counted") else "")
        }
        stats += WTStat("shape", t.s("整体形状", "Overall shape"), shapeValue, shapeUnit)
        return stats
    }

    // Words

    private fun shapeCounts(): Boolean {
        val sh = rest?.shape ?: return false
        return sh.flat > 0.5 || abs(sh.gain - 1) >= 0.005
    }

    private fun shapePartial(): Boolean = shapeCounts() && (rest?.shape?.flat ?: 1.0) < 0.98

    private fun shapeName(sh: WTShape): String =
        if (sh.angle > 3) t.s("反弓 ", "De-arch ") + r0(sh.angle) + "°"
        else if (sh.angle < -3) t.s("弓 ", "Arch ") + r0(-sh.angle) + "°"
        else t.s("平", "Flat")

    private fun shapeWords(): String {
        val sh = rest?.shape
        if (!shapeCounts() || sh == null) return ""
        val pc = WTFormat.round(abs(sh.gain - 1) * 100).toInt()
        if (pc < 4) return ""
        val tail = t.s(
            "和标准弓形的弓度相比，这个形状让阻力" + (if (sh.gain > 1) "多了 " else "少了 ") + "$pc%。",
            "Compared with the standard arch this shape has $pc% " + (if (sh.gain > 1) "more" else "less") + " drag.",
        )
        if (sh.gain < 1) {
            return t.s("身体弓得比标准弓形深，弓度 ${r0(-sh.angle)}°，气流更容易从四周滑走。", "The body arches deeper than the standard arch, ${r0(-sh.angle)}°, so the air slips round the edges more easily. ") + tail
        }
        if (sh.angle > 3) {
            return t.s("身体整体反弓 ${r0(sh.angle)}°，像碗口朝着来流兜住空气。", "The whole body is de-arched ${r0(sh.angle)}° and cups the air like a bowl. ") + tail
        }
        if (sh.angle >= -3) {
            return t.s("身体整体接近平板，比弓形兜风。", "The body is close to a flat plate and holds more air than an arch. ") + tail
        }
        return t.s("身体弓得比标准弓形浅，弓度只有 ${r0(-sh.angle)}°。", "The body arches less than the standard arch, only ${r0(-sh.angle)}°. ") + tail
    }

    private fun wakeWords(): String {
        val a = rest?.a ?: return ""
        if (!(a.wake >= 0.03)) return ""
        val list = listOf(
            Triple("head", "头", "the head"), Triple("torso", "躯干", "the torso"), Triple("arm", "手臂", "the arms"),
            Triple("thigh", "大腿", "the thighs"), Triple("shank", "小腿", "the shins"),
        )
        val names = ArrayList<String>()
        for (w in list) {
            val v = a.detail[w.first]?.wake ?: max(a.detail[w.first + "L"]?.wake ?: 0.0, a.detail[w.first + "R"]?.wake ?: 0.0)
            if (v >= 0.25) names += t.s(w.second, w.third)
        }
        val pc = r0(a.wake * 100)
        if (t.zh) {
            return (if (names.isEmpty()) "有的部位排在别的部位后面，在尾流里受力变小。" else names.joinToString("、") + "排在别的部位后面，在它们的尾流里受力小了很多。") +
                "尾流让全身的阻力少了 $pc%。"
        }
        val joined = names.joinToString(", ")
        val first = if (names.isEmpty()) {
            "Some parts sit behind others and carry less in their wake. "
        } else {
            joined.take(1).uppercase(Locale.US) + joined.drop(1) + " sit behind other parts and carry much less in their wake. "
        }
        return first + "Wakes take $pc% off the whole drag. "
    }

    private fun leadWords(): String {
        val ld = live.a.lead ?: return ""
        if (!(ld.shift >= 0.02)) return ""
        val h = WTCore.dot(ld.dir, live.a.axes.head); val l = WTCore.dot(ld.dir, live.a.axes.lat)
        val end = if (abs(h) >= abs(l)) (if (h > 0) t.s("头", "head") else t.s("脚", "feet")) else (if (l > 0) t.s("左侧", "left side") else t.s("右侧", "right side"))
        val cm = f1(ld.shift * 100)
        return t.s(
            "气流斜着吹到身体上，先迎到气流的${end}这一端受力更大，后面的部位在被它压弯的气流里受力变小，受力中心因此向${end}这一端移了 $cm cm。",
            "The stream meets the body at a slant: the $end end meets it first and carries more, the parts behind sit in the air it has bent and carry less, so the centre of pressure moves $cm cm towards the $end end. ",
        )
    }

    private fun stabilityWords(flying: Boolean): String {
        val curves = curves ?: return ""
        val sp = curves.stablePitch; val sr = curves.stableRoll; val hp = flying && st.holdP; val hr = flying && st.holdR
        val weak = flying && !hr && sr && engine.rollLeans()
        if (!hp && !hr && !weak) {
            if (sp && sr) {
                return if (flying) t.s("俯仰和侧倾都能自己回正，身体停在自己的平衡角度。", "Pitch and roll both right themselves; the body sits at its own balance angles. ")
                else t.s("俯仰和侧倾受到扰动后都能自己回正。", "Pitch and roll both right themselves after a disturbance. ")
            }
            if (!sp && !sr) {
                return t.s("俯仰和侧倾都不能自己回正，要靠飞行者一直修正", "Neither pitch nor roll rights itself; the flier has to correct both all the time") +
                    (if (flying) t.s("。这里俯仰按设定的角度保持，侧倾平衡在水平附近，两侧受力不一样时会被带着倾斜。", ". Here pitch is kept at the set angle and roll is balanced near level, leaning when the two sides carry different loads. ") else t.s("。", ". "))
            }
        }

        fun one(name: String, hold: Boolean, stable: Boolean, lean: Boolean): String {
            val tail = if (hold) t.s("按住在设定的角度", " held at the set angle")
            else if (lean && weak) t.s("自己回正的力很弱，靠主动平衡在水平附近", " rights itself only weakly and is balanced near level")
            else if (stable) t.s("能自己回正", " rights itself")
            else if (!flying) t.s("不能自己回正", " does not right itself")
            else if (lean) t.s("不能自己回正，靠主动平衡在水平附近", " does not right itself and is balanced near level")
            else t.s("不能自己回正，按设定的角度保持", " does not right itself and is kept at the set angle")
            return name + tail
        }
        return one(t.s("俯仰", "Pitch"), hp, sp, false) + t.s("；", "; ") + one(t.s("侧倾", "roll"), hr, sr, true) + t.s("。", ". ")
    }

    private fun insight(): String {
        val rest = rest
        if (rest == null || !rest.veq.isFinite()) return t.s("这个姿态几乎不产生阻力，托不住体重。", "This pose makes almost no drag and cannot carry the weight.")
        val out = ArrayList<String>()
        out += if (st.env == WTEnvKind.TUNNEL) t.s("这个姿态原地悬停要 ${vStr(rest.veq)} 的风。", "This pose needs ${vStr(rest.veq)} of wind to hover. ")
        else t.s("自由落体，空气静止。这个姿态的下落终速大约 ${vStr(rest.veq)}。", "Free fall in still air. This pose's terminal speed is about ${vStr(rest.veq)}. ")
        if (st.env != WTEnvKind.TUNNEL) {
            val vf = if (st.mode == WTMode.PIN) 0.0 else -sim.vel.y
            if (st.mode == WTMode.PIN) {
                out += t.s("现在身体被固定住，箭头按下落终速时的气流画。", "The body is pinned; the arrows show the air at terminal speed. ")
            } else if (vf < rest.veq - 1) {
                out += t.s("身体还在加速，现在每秒下落 ${f1(vf)} m。", "The body is still speeding up, falling ${f1(vf)} m/s now. ")
            } else {
                out += t.s("已经到了终速，阻力托住了体重。", "It has reached terminal speed; the drag carries the weight. ")
            }
        } else if (st.V < 0.5) {
            out += t.s("现在没有风，身体落在网上。", "There is no wind now; the body lies on the net. ")
        } else if (st.mode == WTMode.PIN) {
            val bal = live.Fy / live.W
            if (abs(bal - 1) < 0.02) {
                out += t.s("现在的风速正好托住它。", "The wind now carries it exactly. ")
            } else {
                out += t.s(
                    "现在的风速" + (if (bal > 1) "偏大，向上的气动力是体重的 " else "偏小，向上的气动力只有体重的 ") + "${r0(bal * 100)}%。",
                    "The wind is " + (if (bal > 1) "too strong: the upward force is " else "too weak: the upward force is only ") + "${r0(bal * 100)}% of the weight. ",
                )
            }
        } else {
            val vHere = st.V * (1 - WTCore.TUNNEL.gradient * WTCore.clamp(sim.pos.y, 0.0, WTCore.TUNNEL.glassTop))
            val r = if (st.mode == WTMode.FLY && !engine.ssStale) engine.steady else null
            val dv = vHere - (r?.fall ?: rest.veq)
            if (abs(dv) < 0.4) {
                out += t.s("现在的风速正好托住它。", "The wind now carries it exactly. ")
            } else {
                val steadyWord = r?.let { abs(it.fall - it.veq) > 0.5 } ?: false
                out += t.s(
                    "现在的风速" + (if (dv > 0) "偏大，身体会上浮" else "偏小，身体会下沉") + "，" + (if (steadyWord) "稳定后" else "") + "大约每秒 ${f1(abs(dv))} m。",
                    "The wind is " + (if (dv > 0) "too strong and the body rises" else "too weak and the body sinks") + ", about ${f1(abs(dv))} m/s" + (if (steadyWord) " once steady" else "") + ". ",
                )
            }
        }
        val sw = shapeWords(); if (sw.isNotEmpty()) out += sw
        val ww = wakeWords(); if (ww.isNotEmpty()) out += ww
        val lw = leadWords(); if (lw.isNotEmpty()) out += lw
        val curves = curves
        if (curves != null) {
            val flying = st.mode == WTMode.FLY
            out += stabilityWords(flying)
            val need = ArrayList<String>()
            val k = live.s
            if ((!flying || st.holdP || !curves.stablePitch) && abs(live.mPitch) > 5 && k > 0) {
                need += fmtAbs(live.mPitch) + t.s(" N·m 的" + (if (live.mPitch > 0) "抬头" else "低头") + "力矩", " N·m " + (if (live.mPitch > 0) "head-up" else "head-down") + " moment")
            }
            if ((!flying || st.holdR || !curves.stableRoll) && abs(live.mRoll) > 5 && k > 0) {
                need += fmtAbs(live.mRoll) + t.s(" N·m 的" + (if (live.mRoll > 0) "右倾" else "左倾") + "力矩", " N·m " + (if (live.mRoll > 0) "right-roll" else "left-roll") + " moment")
            }
            if (need.isNotEmpty() && st.mode != WTMode.FREE) {
                out += t.s(
                    (if (st.mode == WTMode.PIN) "固定在正下方吹来的气流里，要保持这个角度得顶住 " else "要保持这个角度，得一直顶住 ") + need.joinToString("和 ") + "。",
                    (if (st.mode == WTMode.PIN) "Pinned in a stream from straight below, keeping this angle takes pushing against a " else "Keeping this angle takes pushing all the time against a ") + need.joinToString(" and a ") + ". ",
                )
            }
        }
        return out.joinToString("").trim(' ')
    }

    private fun prediction(): String {
        if (st.mode == WTMode.FREE) return t.s("放手模式下身体会自己翻转，这里不推算稳定后的运动。", "Hands off, the body tumbles on its own; no steady flight is predicted.")
        val r = engine.steady ?: return if (engine.ssStale) t.s("正在推算稳定后的运动…", "Working out the steady flight…") else ""
        val held = ArrayList<String>()
        if (st.holdP) held += t.s("俯仰", "pitch")
        if (st.holdR) held += t.s("侧倾", "roll")
        val pre = if (t.zh) {
            (if (st.mode == WTMode.PIN) (if (held.isEmpty()) "放开去飞的话，" else "按住" + held.joinToString("和") + "去飞的话，") else "") +
                (if (r.settled) "稳定后" else "运动不会完全稳定下来，平均来看")
        } else {
            (if (st.mode == WTMode.PIN) (if (held.isEmpty()) "Flown free, " else "Flown with " + held.joinToString(" and ") + " held, ") else "") +
                (if (r.settled) "once steady it " else "the motion never fully settles; on average it ")
        }
        val mov = r.speed >= 0.5; val turn = abs(r.yawRate) >= 3; val wander = turn && engine.steadySym
        val out = ArrayList<String>()
        if (!mov && !turn) {
            out += pre + t.s("基本停在原地，不平移也不转。", "stays in place, with no drift and no turn. ")
        } else {
            val bits = ArrayList<String>()
            if (mov) {
                val ps = WTPose(); ps.pitch = r.pitch; ps.roll = r.roll
                val dir = sideName(if (wander) WTVec(0.0, 0.0, r.fwd) else WTVec(r.left, 0.0, r.fwd), ps, axesFor(ps))
                bits += t.s(
                    "${dir}平移，速度 ${vStr(r.speed)}，滑翔比 ${f2(r.glide)}，也就是每下落 100 m 平移 ${r0(r.glide * 100)} m",
                    "moves $dir at ${vStr(r.speed)}, glide ratio ${f2(r.glide)}, so ${r0(r.glide * 100)} m across for every 100 m down",
                )
            }
            if (turn && !wander) {
                val per = f1(360 / abs(r.yawRate))
                bits += t.s(
                    (if (mov) "同时" else "原地") + (if (r.yawRate > 0) "向左" else "向右") + "转，每秒 ${r0(abs(r.yawRate))}°，一圈大约 $per 秒",
                    (if (mov) "while turning " else "turns on the spot ") + (if (r.yawRate > 0) "left" else "right") + " at ${r0(abs(r.yawRate))}°/s, about $per s a turn",
                )
            }
            out += pre + bits.joinToString(t.s("，", ", ")) + t.s("。", ". ")
            if (wander) {
                out += t.s(
                    "这样飞朝向不稳定：姿态左右对称，身体却会自己转开，往哪边转由最初的一点扰动决定，要一直修正朝向。",
                    "The heading is unstable this way: the pose is symmetric but the body turns away on its own, to whichever side the first small disturbance sends it, so the heading needs constant correction. ",
                )
            }
        }
        if (!st.holdR && !wander && abs(r.roll) >= 2) {
            val same = turn && (r.roll > 0) == (r.yawRate < 0)
            out += t.s(
                "这时身体向" + (if (r.roll > 0) "右" else "左") + "倾 ${r0(abs(r.roll))}°" + (if (same) "，倾斜的身体会往这一侧转。" else "。"),
                "The body then leans ${r0(abs(r.roll))}° to the " + (if (r.roll > 0) "right" else "left") + (if (same) ", and a leaning body turns to that side. " else ". "),
            )
        }
        if (r.heldP && !wander && abs(r.mPitch) >= 8) {
            val belly = abs(r.pitch) <= 45 && abs(r.roll) <= 45
            val up = r.mPitch > 0
            out += t.s(
                "身体一直想" + (if (up) "抬头" else "低头") + "，保持这个俯仰角要顶住 ${fmtAbs(r.mPitch)} N·m 的" + (if (up) "抬头" else "低头") + "力矩" + (if (belly && up) "，也就是上半身要一直往下压。" else "。"),
                "The body keeps wanting to put its head " + (if (up) "up" else "down") + "; holding this pitch takes ${fmtAbs(r.mPitch)} N·m against it" + (if (belly && up) ", so the upper body has to keep pressing down. " else ". "),
            )
        }
        val df = r.fall - r.veq
        if (abs(df) > 0.5) {
            out += t.s(
                "这时的下落速度是 ${vStr(r.fall)}，比悬停风速" + (if (df > 0) "快 " else "慢 ") + vStr(abs(df)) + "。",
                "It then falls at ${vStr(r.fall)}, " + vStr(abs(df)) + (if (df > 0) " faster" else " slower") + " than its hover speed. ",
            )
        }
        return out.joinToString("").trim(' ')
    }

    private fun holdNote(): String {
        if (st.mode == WTMode.PIN) return t.s("固定：角度完全按设定。「保持」在这里只决定推算飞行时哪个角度按住不放。", "Pinned: the angles are exactly as set. Hold here only decides which angles the prediction keeps.")
        if (st.mode == WTMode.FREE) return t.s("放手：角度由身体自己决定，拖滑块只是给它一个新的起始角度。", "Hands off: the body decides its angles; a knob only gives it a new starting angle.")
        val sp = engine.flyInfo?.rest?.passiveP ?: curves?.stablePitch ?: false
        val pitch = if (st.holdP) t.s("按住在设定的角度", " is held at the set angle")
        else if (sp) t.s("会自己停在平衡角度", " settles at its balance angle")
        else t.s("不能自己回正，按设定的角度保持", " does not right itself and is kept at the set angle")
        val roll = if (st.holdR) t.s("按住在设定的角度", " is held at the set angle")
        else if (engine.rollLeans()) t.s("靠主动平衡在水平附近，两侧受力不一样时会被带着倾斜", " is balanced near level and leans when the two sides carry different loads")
        else t.s("会自己停在平衡角度", " settles at its balance angle")
        return t.s("俯仰", "Pitch") + pitch + t.s("；侧倾", "; roll") + roll + t.s("。", ".")
    }

    // Rows

    private fun buildRows(): Pair<List<WTRow>, List<WTRow>> {
        val a = live; val pin = st.mode == WTMode.PIN; val flying = st.mode == WTMode.FLY
        val vy = if (pin) 0.0 else sim.vel.y; val vx = if (pin) 0.0 else sim.vel.x; val vz = if (pin) 0.0 else sim.vel.z
        val vh = sqrt(vx * vx + vz * vz)
        val yr = if (pin) 0.0 else if (flying) sim.yawRate * WTCore.R2D else sim.omega.y * WTCore.R2D
        val motion = ArrayList<WTRow>()
        val tag = if (pin) WTTag(WTTagIcon.PIN, WTTagTone.ACCENT, t.s("已固定", "pinned"))
        else if (abs(vy) < 0.15) WTTag(WTTagIcon.HOLD, WTTagTone.ACCENT, t.s("悬停", "hovering"))
        else if (vy > 0) WTTag(WTTagIcon.UP, WTTagTone.ACCENT, t.s("上浮", "rising"))
        else WTTag(WTTagIcon.DOWN, WTTagTone.ACCENT, t.s("下沉", "sinking"))
        motion += WTRow("vy", t.s("升降", "Vertical"), signed1(vy) + " m/s", if (abs(vy) < 0.15) "" else t.s("相当于 ", "= ") + vStr(abs(vy)), tag)
        motion += WTRow(
            "vh", t.s("平移", "Drift"), vStr(vh), if (vh > 0.3) sideName(WTVec(vx, 0.0, vz), cur, liveAxes) else "",
            if (pin) null else if (vh < 0.3) WTTag(WTTagIcon.HOLD, WTTagTone.ACCENT, t.s("不平移", "none")) else WTTag(WTTagIcon.SIDE, WTTagTone.ACCENT, t.s("在平移", "drifting")),
        )
        motion += WTRow(
            "turn", t.s("转动", "Turn"), r0(abs(yr)) + " °/s", if (abs(yr) < 1) "" else if (yr > 0) t.s("向左转", "left") else t.s("向右转", "right"),
            if (pin) null else if (abs(yr) < 1) WTTag(WTTagIcon.HOLD, WTTagTone.ACCENT, t.s("不转", "none")) else WTTag(WTTagIcon.DOT, WTTagTone.ACCENT, t.s("在转", "turning")),
        )
        val hd = sqrt(sim.pos.x * sim.pos.x + sim.pos.z * sim.pos.z)
        val pos = if (st.env == WTEnvKind.TUNNEL) {
            t.s("离网 ${f1(max(0.0, sim.pos.y))} m，离风洞中心 ${f1(hd)} m", "${f1(max(0.0, sim.pos.y))} m above the net, ${f1(hd)} m from the centre")
        } else {
            val hs = if (hd < 100) f1(hd) else r0(hd)
            val y = sim.pos.y
            val ys = if (abs(y) < 0.05) "0.0" else (if (y > 0) "+" else "−") + (if (abs(y) < 100) f1(abs(y)) else r0(abs(y)))
            t.s("离起点水平 $hs m，高度 $ys m", "$hs m across from the start, height $ys m")
        }
        motion += WTRow("pos", t.s("位置", "Position"), "", pos, null, wide = true)

        val forces = ArrayList<WTRow>()
        val bal = (a.Fy - a.W) / a.W
        val dragTag = if (abs(bal) < 0.02) WTTag(WTTagIcon.HOLD, WTTagTone.ACCENT, t.s("托住体重", "carries the weight"))
        else if (bal > 0) WTTag(WTTagIcon.UP, WTTagTone.ACCENT, t.s("合力向上", "net force up"))
        else WTTag(WTTagIcon.DOWN, WTTagTone.ACCENT, t.s("合力向下", "net force down"))
        forces += WTRow("drag", t.s("阻力", "Drag"), r0(a.D) + " N", t.s("体重的 ", "") + r0(a.D / a.W * 100) + t.s("%", "% of weight"), dragTag)
        forces += WTRow(
            "lift", t.s("升力", "Lift"), r0(a.Lmag) + " N", if (a.ratio > 0.01) t.s("推", "pushes ") + sideName(a.L, cur, liveAxes) else "",
            if (a.ratio > 0.02) WTTag(WTTagIcon.SIDE, WTTagTone.ACCENT, t.s("升阻比 ", "L/D ") + f2(a.ratio)) else WTTag(WTTagIcon.HOLD, WTTagTone.ACCENT, t.s("很小", "small")),
        )

        fun stab(hold: Boolean, stable: Boolean, lean: Boolean): WTTag? {
            if (flying && hold) return WTTag(WTTagIcon.PIN, WTTagTone.ACCENT, t.s("按住", "held"))
            if (curves == null) return null
            if (flying && lean) return WTTag(WTTagIcon.HOLD, WTTagTone.ACCENT, t.s("主动平衡", "balanced"))
            return if (stable) WTTag(WTTagIcon.OK, WTTagTone.GOOD, t.s("能回正", "rights itself")) else WTTag(WTTagIcon.WARN, WTTagTone.WARN, t.s("不回正", "unstable"))
        }
        forces += WTRow(
            "pitch", t.s("俯仰", "Pitch"), fmtTorque(a.mPitch), if (abs(a.mPitch) < 0.5) "" else if (a.mPitch > 0) t.s("抬头", "head up") else t.s("低头", "head down"),
            stab(st.holdP, curves?.stablePitch ?: false, false),
        )
        forces += WTRow(
            "roll", t.s("侧倾", "Roll"), fmtTorque(a.mRoll), if (abs(a.mRoll) < 0.5) "" else if (a.mRoll > 0) t.s("向右倾", "right") else t.s("向左倾", "left"),
            stab(st.holdR, curves?.stableRoll ?: false, engine.rollLeans()),
        )
        var yawTag: WTTag? = null
        if (pin) {
            val pq = if (engine.ssStale) null else engine.steady
            if (pq != null) {
                yawTag = if (abs(pq.yawRate) < 3) WTTag(WTTagIcon.HOLD, WTTagTone.ACCENT, t.s("飞起来不转", "no turn in flight"))
                else if (engine.steadySym) WTTag(WTTagIcon.DOT, WTTagTone.ACCENT, t.s("飞起来方向不定", "turns either way"))
                else WTTag(WTTagIcon.DOT, WTTagTone.ACCENT, t.s("飞起来", "turns ") + (if (pq.yawRate > 0) t.s("向左转", "left") else t.s("向右转", "right")))
            } else if (engine.ssStale) {
                yawTag = WTTag(WTTagIcon.PIN, WTTagTone.ACCENT, t.s("推算中", "working…"))
            }
        } else {
            yawTag = if (abs(a.mYaw) < 0.5) WTTag(WTTagIcon.HOLD, WTTagTone.ACCENT, t.s("平衡", "balanced")) else WTTag(WTTagIcon.DOT, WTTagTone.ACCENT, t.s("在加速转", "spinning up"))
        }
        forces += WTRow(
            "yaw", t.s("转向", "Yaw"), fmtTorque(a.mYaw), if (abs(a.mYaw) < 0.5) "" else if (a.mYaw > 0) t.s("向左转", "left") else t.s("向右转", "right"), yawTag,
        )
        return motion to forces
    }

    // Zone bars

    private fun zoneVal(z: String, m: WTMeasure): Double {
        val d = live.a.detail[z] ?: return 0.0
        val s = live.s
        return when (m) {
            WTMeasure.DRAG -> d.drag * s
            WTMeasure.LIFT -> d.liftMag * s
            WTMeasure.PITCH -> d.mPitch * s
            WTMeasure.ROLL -> d.mRoll * s
            WTMeasure.YAW -> d.mYaw * s
        }
    }

    private fun zoneWord(m: WTMeasure, v: Double): String = if (abs(v) < 0.05) "" else if (v > 0) t.measurePos(m) else t.measureNeg(m)

    private fun buildZones(): Zones {
        val m = st.measure
        val signed = m == WTMeasure.PITCH || m == WTMeasure.ROLL || m == WTMeasure.YAW; val flip = m == WTMeasure.YAW
        var mx = 0.0; var shaded = false
        val vals = HashMap<String, Double>()
        for (z in WTCore.zones) {
            val v = zoneVal(z, m)
            vals[z] = v
            mx = max(mx, abs(v))
        }
        if (m == WTMeasure.DRAG) {
            for (z in WTCore.zones) {
                if (!((live.a.detail[z]?.wake ?: 0.0) >= 0.03)) continue
                shaded = true
                mx = max(mx, (live.a.detail[z]?.clean ?: 0.0) * live.s)
            }
        }
        val bars = ArrayList<WTZoneBar>()
        for (z in WTCore.zones) {
            val v = vals.getValue(z); val frac = if (mx > 1e-9) abs(v) / mx else 0.0
            if (!signed) {
                val d = live.a.detail[z]
                val ghost = if (m == WTMeasure.DRAG && (d?.wake ?: 0.0) >= 0.03 && mx > 1e-9) (d?.clean ?: 0.0) * live.s / mx else 0.0
                val word = if (m == WTMeasure.DRAG && live.D > 1) r0(v / live.D * 100) + "%" else ""
                bars += WTZoneBar(z, t.zoneName(z), frac, ghost, true, word, r0(v) + " N")
            } else {
                bars += WTZoneBar(z, t.zoneName(z), frac, 0.0, (v > 0) != flip, zoneWord(m, v), fmtAbs(v))
            }
        }
        var axisLeft = ""; var axisRight = ""
        if (signed) {
            axisLeft = "← " + (if (flip) t.measurePos(m) else t.measureNeg(m))
            axisRight = (if (flip) t.measureNeg(m) else t.measurePos(m)) + " →  N·m"
        }
        val note = t.measureNote(m) + (if (shaded) t.s("浅色的一段是被前面部位的尾流挡掉的阻力。", " The light stretch is the drag taken away by the wake of the parts in front.") else "")
        // the head, spelled out
        var headLine = ""
        val d = live.a.detail["head"]
        if (d != null) {
            val s = live.s; val drag = d.drag * s; val lift = d.liftMag * s
            var line = t.s("头部：阻力 ", "Head: drag ") + r0(drag) + " N"
            if (live.D > 1) line += t.s("，占全身的 ", ", ") + f1(drag / live.D * 100) + t.s("%", "% of the body")
            line += t.s("；升力 ", "; lift ") + r0(lift) + " N"
            if (lift >= 1) line += t.s("，把身体推", ", pushing the body ") + sideName(d.lift, cur, liveAxes)
            val mom = ArrayList<String>()
            for (k in listOf(WTMeasure.PITCH, WTMeasure.ROLL, WTMeasure.YAW)) {
                val v = zoneVal("head", k)
                if (abs(v) >= 0.5) mom += zoneWord(k, v) + " " + fmtAbs(v) + " N·m"
            }
            line += if (mom.isEmpty()) t.s("。", ".") else t.s("。它让身体", ". It turns the body ") + mom.joinToString(t.s("、", ", ")) + t.s("。", ".")
            headLine = line
        }
        return Zones(bars, signed, axisLeft, axisRight, note, headLine)
    }

    // Moment chart

    private fun buildChart(): Chart? {
        val curves = curves ?: return null
        val s = live.s
        var mx = 0.0
        for (i in curves.x.indices) mx = max(mx, max(abs(curves.pitch[i] * s), abs(curves.roll[i] * s)))
        val out = ArrayList<String>()
        fun say(name: String, stable: Boolean, slope: Double, trim: Double?, up: String, down: String) {
            if (stable) {
                if (trim != null && abs(trim) >= 1.5) {
                    out += t.s(
                        "$name：会回正，但平衡点比现在" + (if (trim > 0) up else down) + " ${r0(abs(trim))}°。",
                        "$name: rights itself, but its balance point is ${r0(abs(trim))}° more " + (if (trim > 0) up else down) + " than now. ",
                    )
                } else {
                    out += t.s("$name：偏开后会被推回来。", "$name: pushed back after a disturbance. ")
                }
            } else if (slope < 0) {
                out += t.s("$name：回正的力很弱，实际上稳不住。", "$name: the restoring force is too weak to hold it. ")
            } else {
                out += t.s("$name：偏开后会越偏越多。", "$name: a disturbance keeps growing. ")
            }
        }
        say(t.s("俯仰", "Pitch"), curves.stablePitch, curves.slopePitch, curves.trimPitch, t.s("抬头", "head-up"), t.s("低头", "head-down"))
        say(t.s("侧倾", "Roll"), curves.stableRoll, curves.slopeRoll, curves.trimRoll, t.s("右倾", "to the right"), t.s("左倾", "to the left"))
        return Chart(
            x = curves.x.toList(), pitch = curves.pitch.map { it * s }, roll = curves.roll.map { it * s },
            max = niceMax(mx * 1.05), trimText = out.joinToString("").trim(' '),
        )
    }

    companion object {
        fun niceMax(v: Double): Double {
            for (s in doubleArrayOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)) if (v <= s) return s
            return ceil(v / 5000) * 5000
        }
    }
}
