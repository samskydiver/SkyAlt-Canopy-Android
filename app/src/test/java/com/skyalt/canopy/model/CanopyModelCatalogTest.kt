package com.skyalt.canopy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanopyModelCatalogTest {
    @Test
    fun eighteenSeriesWithTheirSizes() {
        assertEquals(18, CanopyModel.ALL.size)
        assertEquals(18, CanopyModel.ALL.map { it.key }.toSet().size)
        for (m in CanopyModel.ALL) {
            assertTrue(m.key, m.modelSize in m.sizes)
            assertEquals(m.key, m.sizes.sorted(), m.sizes)
        }
        assertEquals(CanopyType.STUDENT, CanopyModel.forKey("navigator")!!.type)
        assertEquals(1, CanopyModel.ALL.count { it.type == CanopyType.STUDENT })
    }

    @Test
    fun nearestSizeTakesTheLargerOnATie() {
        val sabre = CanopyModel.forKey("sabre3")!!
        assertEquals(150.0, sabre.nearestSize(155.0), 0.0)
        assertEquals(170.0, sabre.nearestSize(160.0), 0.0)
        assertEquals(89.0, sabre.nearestSize(10.0), 0.0)
    }

    @Test
    fun configurationFliesTheSeriesAtOneOfItsSizes() {
        val d = SimulatorConfiguration()
        assertEquals("navigator", d.canopyModel.key)
        assertEquals(220.0, d.canopyAreaSquareFeet, 0.0)
        val c = d.copy(canopyModelKey = "katana", canopyType = CanopyType.PERFORMANCE, canopyAreaSquareFeet = 100.0).clamped()
        assertEquals(97.0, c.canopyAreaSquareFeet, 0.0)
        assertEquals(CanopyModel.forKey("katana")!!.coefficients, c.performance.coefficients)
        assertTrue(c.affectsLiveFlight(c.copy(canopyModelKey = "stiletto")))
        // An unknown key flies the type's series.
        assertEquals("valkyrie", d.copy(canopyModelKey = "x", canopyType = CanopyType.CROSS_BRACED).canopyModel.key)
        assertFalse(c.studentMode)
    }

    @Test
    fun scenesFlyTheStudentSeries() {
        for (s in Scenario.entries) assertEquals("navigator", s.configuration().canopyModelKey)
    }
}
