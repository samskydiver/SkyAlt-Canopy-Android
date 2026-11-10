package com.skyalt.canopy.scene

import com.skyalt.canopy.model.CanopyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanopyModelRigTest {
    private fun asset(name: String): File =
        listOf(File("src/main/assets", name), File("app/src/main/assets", name)).first { it.exists() }

    private fun rig(model: CanopyModel) =
        CanopyModelRig.build(Glb.parse(asset(model.modelAsset).readBytes()), model.key)

    @Test
    fun everySeriesLoadsWithItsPreview() {
        for (m in CanopyModel.ALL) {
            assertTrue(m.previewAsset, asset(m.previewAsset).length() > 1000)
            val rig = rig(m)
            assertNotNull(m.key, rig)
            // Both toggles found, hands either side.
            assertTrue(m.key, rig!!.togglePosition(true, 0f)[0] < 0)
            assertTrue(m.key, rig.togglePosition(false, 0f)[0] > 0)
        }
    }

    @Test
    fun leftBrakeCurlsTheLeftTailAndStallFoldsTheTips() {
        val rig = rig(CanopyModel.forKey("sabre3")!!)!!
        val rest = rig.parts.map { it.positions.copyOf() }
        rig.update(1f, 0f, 0f, 0f)
        // The left half moves more than the right.
        var left = 0f; var right = 0f
        for ((k, part) in rig.parts.withIndex()) {
            val p = part.positions
            for (i in 0 until p.size / 3) {
                val d = Math.abs(p[i * 3 + 1] - rest[k][i * 3 + 1])
                if (rest[k][i * 3] < -1f) left += d else if (rest[k][i * 3] > 1f) right += d
            }
        }
        assertTrue("left $left right $right", left > 5 * right)
        assertEquals(-CanopyModelRig.TOGGLE_TRAVEL, rig.togglePosition(true, 1f)[1] - rig.togglePosition(true, 0f)[1], 1e-5f)

        rig.update(0f, 0f, 1f, 1f)
        // Collapsed: the span shrinks to little over half.
        fun span(): Float {
            var lo = 0f; var hi = 0f
            for (part in rig.parts) for (i in 0 until part.positions.size / 3) {
                lo = minOf(lo, part.positions[i * 3]); hi = maxOf(hi, part.positions[i * 3])
            }
            return hi - lo
        }
        val collapsed = span()
        rig.update(0f, 0f, 0f, 0f)
        val open = span()
        assertTrue("collapsed $collapsed open $open", collapsed < 0.8f * open)
        for ((k, part) in rig.parts.withIndex()) assertTrue(part.positions.contentEquals(rest[k]))
    }
}
