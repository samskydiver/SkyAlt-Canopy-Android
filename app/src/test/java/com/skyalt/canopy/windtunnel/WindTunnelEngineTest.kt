package com.skyalt.canopy.windtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.math.abs

class WindTunnelEngineTest {
    private class FakeScreen : WTScreen {
        override var settings = WTSettings()
        override var shownPose = WTPose()
        override var panel = WTPanel()
        override var hud = WTHud()
        override var note: String? = null
    }

    private var now = 100.0
    private val inline = Executor { it.run() }
    private val screen = FakeScreen()

    private fun engine(zh: Boolean = true) = WindTunnelEngine(screen, { now }, inline, inline).also { it.zh = zh }

    private fun run(e: WindTunnelEngine, seconds: Double) {
        val steps = (seconds * 60).toInt()
        repeat(steps) {
            now += 1.0 / 60
            e.frame()
        }
    }

    @Test
    fun startsOnTheArchAndFallsInFreeFall() {
        val e = engine()
        run(e, 3.0)
        assertEquals("box", e.settings.preset)
        assertFalse(e.settings.modified)
        assertTrue("falls: ${e.sim.vel.y}", e.sim.vel.y < -10)
        assertTrue(e.hud.vy < -10)
        assertTrue(e.panel.insight.isNotEmpty())
        assertTrue(e.panel.zoneBars.size == 8)
        assertEquals(e.settings, screen.settings)
        assertEquals(e.panel, screen.panel)
    }

    @Test
    fun tunnelHoversNearTheMiddle() {
        val e = engine()
        e.setEnv(WTEnvKind.TUNNEL)
        assertTrue("hover wind ${e.settings.V}", e.settings.V in 40.0..70.0)
        run(e, 6.0)
        assertTrue("height ${e.sim.pos.y}", e.sim.pos.y in 0.3..4.5)
        assertTrue("speed ${e.sim.vel.y}", abs(e.sim.vel.y) < 3)
        e.setEnv(WTEnvKind.FREE)
        assertEquals(0.0, e.settings.V, 0.0)
    }

    @Test
    fun linkedJointsMoveTogether() {
        val e = engine()
        e.toggleLink("knee")
        e.setJoint("kneeL", 50.0)
        assertEquals(50.0, e.target["kneeR"], 1e-9)
        assertTrue(e.settings.modified)
        e.toggleLink("knee")
        e.setJoint("kneeL", 10.0)
        assertEquals(50.0, e.target["kneeR"], 1e-9)
        assertEquals(10.0, screen.shownPose["kneeL"], 1e-9)
    }

    @Test
    fun turningPitchByHandHoldsItAndShowsANote() {
        val e = engine(zh = false)
        e.setJoint("pitch", 20.0)
        assertTrue(e.settings.holdP)
        assertNotNull(screen.note)
        assertTrue(screen.note!!.startsWith("Pitch is now held"))
        run(e, 6.0)
        assertNull("the note goes after a while", screen.note)
    }

    @Test
    fun everyPresetAndModeStaysFinite() {
        val e = engine()
        for (mode in WTMode.entries) {
            e.setMode(mode)
            for (env in WTEnvKind.entries) {
                e.setEnv(env)
                for (p in WTCore.presets) {
                    e.applyPreset(p.id)
                    run(e, 1.0)
                    assertEquals(p.id, e.settings.preset)
                    val v = e.sim.vel
                    assertTrue("${p.id} $mode $env", (v.x + v.y + v.z + e.sim.pos.y).isFinite())
                    assertTrue(e.panel.trimText.isNotEmpty() || e.curves == null)
                }
            }
        }
    }

    @Test
    fun trackingIsCalledGlidingInChinese() {
        assertEquals("滑翔", WTCore.preset("track").name)
        assertEquals("Tracking", WTCore.preset("track").en)
    }

    @Test
    fun panelTextFollowsTheLanguage() {
        val zh = engine(zh = true)
        run(zh, 1.0)
        assertTrue(zh.panel.zoneBars.first().name == "头")
        val en = engine(zh = false)
        run(en, 1.0)
        assertTrue(en.panel.zoneBars.first().name == "Head")
    }

    @Test
    fun formatRoundsHalvesAwayFromZero() {
        assertEquals("3", WTFormat.whole(2.5))
        assertEquals("-3", WTFormat.whole(-2.5))
        assertEquals("1.5", WTFormat.fixed(1.5, 1))
        assertEquals("–", WTFormat.whole(Double.NaN))
    }
}
