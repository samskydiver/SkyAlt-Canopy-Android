package com.skyalt.canopy.windtunnel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow

/**
 * The Kotlin core against the web page's own numbers: the same lines the
 * iPhone app's parity check prints (Reference/WindTunnel/parity in the
 * iOS repository: parity.js makes expected.txt from core.js, main.swift
 * prints them from the Swift port). Every preset, as a raw pose and as
 * the app applies it, down to the steady flight it settles into.
 */
class WindTunnelCoreParityTest {
    /** A number as C's printf("%.df") prints it, with −0 shown as 0. */
    private fun f(x: Double, d: Int = 3): String {
        if (!x.isFinite()) return "inf"
        val v = if (abs(x) < 0.5 * 10.0.pow(-d)) 0.0 else x
        return BigDecimal(v).setScale(d, RoundingMode.HALF_EVEN).toPlainString()
    }

    private fun lines(): List<String> {
        val env = WTEnv(rho = WTCore.airDensity(0.0), k = 1.0, suit = WTCore.suits.getValue("std"), V = 50.0)
        val out = ArrayList<String>()
        for (pr in WTCore.presets) {
            // raw pose, no fitting: checks the force model on its own
            val b0 = WTCore.buildBody(pr.pose, 1.75, 75.0, 1.0)
            val a0 = WTCore.analyse(b0, pr.pose, 50.0, env)
            out += "raw ${pr.id} F ${f(a0.F.x)} ${f(a0.F.y)} ${f(a0.F.z)} T ${f(a0.T.x)} ${f(a0.T.y)} ${f(a0.T.z)}" +
                " wake ${f(a0.wake, 4)} shape ${a0.shape?.let { f(it.gain, 4) } ?: "-"}"
            // the preset as the app applies it
            val p = WTCore.presetPose(pr, H = 1.75, M = 75.0, legs = 1.0, env = env)
            val b = WTCore.buildBody(p, 1.75, 75.0, 1.0)
            val a = WTCore.analyse(b, p, 50.0, env)
            val e2 = env.copy(assist = 0.06 * WTCore.clamp((0.07 - a.ratio) / 0.03, 0.0, 1.0))
            val s = WTCore.steadyState(b, p, e2, hold = pr.hold)
            var line = "preset ${pr.id} pitch ${f(p.pitch, 2)} roll ${f(p.roll, 2)} hover ${f(a.veq * 3.6, 1)} km/h"
            line += if (s != null) {
                " steady fall ${f(s.fall * 3.6, 1)} fwd ${f(s.fwd * 3.6, 1)} left ${f(s.left * 3.6, 1)}" +
                    " glide ${f(s.glide, 2)} yaw ${f(s.yawRate, 1)} settled ${s.settled}"
            } else {
                " steady -"
            }
            out += line
        }
        return out
    }

    @Test
    fun everyPresetMatchesTheWebCore() {
        val expected = EXPECTED.trim().lines()
        val actual = lines()
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("line ${i + 1}", expected[i], actual[i])
    }

    /** A preset must not be changed by fitting it: the next body starts from the same pose. */
    @Test
    fun fittingLeavesThePresetAlone() {
        val env = WTEnv(rho = WTCore.airDensity(0.0), V = 50.0)
        for (pr in WTCore.presets) {
            val before = pr.pose.copy()
            WTCore.presetPose(pr, H = 1.62, M = 58.0, legs = 1.04, env = env)
            assertEquals(pr.id, before, pr.pose)
        }
    }

    private companion object {
        const val EXPECTED = """
raw box F 0.000 843.586 -0.537 T 1.201 0.000 0.000 wake 0.0003 shape 1.0018
preset box pitch -0.03 roll 0.00 hover 168.4 km/h steady fall 168.4 fwd 0.0 left 0.0 glide 0.00 yaw 0.0 settled true
raw arch F 0.000 665.517 -4.148 T 9.272 0.000 0.000 wake 0.0308 shape 0.9054
preset arch pitch -1.24 roll 0.00 hover 190.9 km/h steady fall 190.9 fwd 0.0 left 0.0 glide 0.00 yaw 0.0 settled true
raw dearch F 0.000 1051.198 22.381 T 15.012 0.000 0.000 wake 0.0217 shape 1.3228
preset dearch pitch 2.94 roll 0.00 hover 150.4 km/h steady fall 150.4 fwd 0.0 left 0.0 glide 0.00 yaw 0.0 settled true
raw slow F 0.000 1087.854 6.926 T -3.200 0.000 0.000 wake 0.0085 shape 1.2511
preset slow pitch 0.82 roll 0.00 hover 148.0 km/h steady fall 148.0 fwd 0.0 left 0.0 glide 0.00 yaw 0.0 settled true
raw track F 0.000 985.851 210.865 T 59.646 0.000 0.000 wake 0.0093 shape 1.2147
preset track pitch -18.00 roll 0.00 hover 155.5 km/h steady fall 172.2 fwd 71.4 left 0.0 glide 0.41 yaw 0.0 settled true
raw angle F 0.000 620.648 245.914 T -40.407 0.000 0.000 wake 0.0756 shape 1.1363
preset angle pitch -45.00 roll 0.00 hover 195.9 km/h steady fall 240.7 fwd 97.4 left 0.0 glide 0.40 yaw 0.0 settled true
raw turn F 6.533 843.901 -1.372 T 4.369 10.188 -4.308 wake 0.0009 shape 1.0083
preset turn pitch 0.20 roll -2.49 hover 170.0 km/h steady fall 170.0 fwd -0.1 left 0.3 glide 0.00 yaw 76.8 settled true
raw back F 0.000 735.359 4.451 T -11.719 0.000 0.000 wake 0.0025 shape 1.0094
preset back pitch -177.92 roll 0.00 hover 176.5 km/h steady fall 176.5 fwd 0.0 left 0.0 glide 0.00 yaw 0.0 settled true
raw sit F 0.000 437.133 1.440 T -0.423 0.000 0.000 wake 0.3196 shape 1.0000
preset sit pitch 90.56 roll 0.00 hover 232.9 km/h steady fall 232.9 fwd 0.0 left 0.0 glide 0.00 yaw 0.0 settled true
raw head F 0.000 308.120 -1.886 T -1.702 0.000 0.000 wake 0.3243 shape 1.0000
preset head pitch -88.70 roll 0.00 hover 277.8 km/h steady fall 277.8 fwd 0.0 left 0.0 glide 0.00 yaw 0.0 settled true
"""
    }
}
