package com.skyalt.canopy.windtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.random.Random

class WindTunnelSceneTest {
    private class FakeScreen : WTScreen {
        override var settings = WTSettings()
        override var shownPose = WTPose()
        override var panel = WTPanel()
        override var hud = WTHud()
        override var note: String? = null
    }

    private var now = 10.0
    private val inline = Executor { it.run() }

    private fun ready(): Pair<WindTunnelEngine, WindTunnelSceneModel> {
        val e = WindTunnelEngine(FakeScreen(), { now }, inline, inline)
        e.setMode(WTMode.PIN)
        val m = WindTunnelSceneModel(Random(1))
        m.resize(500.0, 350.0)
        repeat(30) {
            now += 1.0 / 60
            m.update(e, e.frame())
        }
        return e to m
    }

    @Test
    fun meshesAreWhole() {
        val body = WTCore.buildBody(WTCore.presets[0].pose, 1.75, 75.0, 1.0)
        val parts = WTFigure.build(body)
        assertEquals(body.segs.size + 1 + 2 + body.joints.size, parts.size)
        for (p in parts) {
            val n = p.mesh.positions.size / 3
            assertEquals(p.mesh.positions.size, p.mesh.normals.size)
            assertTrue(p.mesh.indices.all { it in 0 until n })
            for (i in 0 until n) {
                val l = p.mesh.normals[i * 3] * p.mesh.normals[i * 3] + p.mesh.normals[i * 3 + 1] * p.mesh.normals[i * 3 + 1] + p.mesh.normals[i * 3 + 2] * p.mesh.normals[i * 3 + 2]
                assertEquals(1.0, l.toDouble(), 1e-3)
            }
        }
    }

    @Test
    fun capsuleSpansItsHeight() {
        val m = WTFigure.capsule(0.05, 0.4)
        val ys = (0 until m.positions.size / 3).map { m.positions[it * 3 + 1] }
        assertEquals(0.2, ys.max().toDouble(), 1e-6)
        assertEquals(-0.2, ys.min().toDouble(), 1e-6)
        val z = WTFigure.capsule(0.05, 0.4, alongZ = true)
        val zs = (0 until z.positions.size / 3).map { z.positions[it * 3 + 2] }
        assertEquals(0.2, zs.max().toDouble(), 1e-6)
    }

    @Test
    fun theBodyIsInTheMiddleOfThePicture() {
        val (_, m) = ready()
        val p = WTMat.project(m.viewProjection, WTVec.ZERO)
        assertTrue("x ${p[0]} y ${p[1]}", abs(p[0]) < 0.1 && abs(p[1]) < 0.2)
        assertTrue(p[3] > 0)
    }

    @Test
    fun aTapOnTheMiddleFindsTheTorsoAndTheEdgeFindsNothing() {
        val (e, m) = ready()
        // from above the spread arms leave the back clear
        m.setView(WTView.TOP, 0.0)
        // the chest's own middle, on the screen
        val chest = e.body.segs[e.body.iChest]
        val c = WTCore.qRot(e.live!!.a.R, chest.c - e.body.com)
        val p = WTMat.project(m.viewProjection, c)
        val x = (p[0] + 1) / 2 * m.viewWidth; val y = (1 - p[1]) / 2 * m.viewHeight
        assertEquals("spine", m.group(x, y))
        assertNull(m.group(2.0, 2.0))
    }

    @Test
    fun namedViewsAndZoom() {
        val (_, m) = ready()
        m.setView(WTView.TOP, 0.0)
        assertTrue(m.eye.y > m.target.y + 5)
        m.zoom(100.0)
        assertEquals(3.5, m.radius, 1e-9)
        m.setView(WTView.SIDE, 0.0)
        assertTrue(abs(m.eye.x - m.target.x) > 5)
        m.pan(1000.0, 0.0)
        assertTrue(WTCore.len(m.target - WTVec(0.0, 0.1, 0.0)) <= 8.0001)
    }

    @Test
    fun arrowsAndStreaksAreDrawnInTheStream() {
        val (e, m) = ready()
        assertTrue(m.netArrow.visible)
        assertTrue(m.weightArrow.visible)
        assertTrue(m.streaksVisible)
        e.setEnv(WTEnvKind.TUNNEL)
        now += 0.02
        m.update(e, e.frame())
        assertTrue(m.tunnelOn)
        assertNotNull(m.world)
        assertTrue(m.levels.all { it.fineOpacity == 0.0 })
    }
}
