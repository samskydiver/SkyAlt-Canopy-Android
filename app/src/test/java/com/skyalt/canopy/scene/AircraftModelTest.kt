package com.skyalt.canopy.scene

import com.skyalt.canopy.model.AircraftLogoPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class AircraftModelTest {
    private val prepared by lazy {
        val file = listOf(File("src/main/assets", AircraftModel.ASSET), File("app/src/main/assets", AircraftModel.ASSET)).first { it.exists() }
        AircraftModel.prepare(Glb.parse(file.readBytes()))
    }

    private fun range(pieces: List<AircraftModel.Piece>, axis: Int): Pair<Float, Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (piece in pieces) {
            var i = axis
            while (i < piece.positions.size) {
                lo = minOf(lo, piece.positions[i]); hi = maxOf(hi, piece.positions[i])
                i += 3
            }
        }
        return lo to hi
    }

    @Test
    fun theWheelsStandOnTheGroundAndTheNoseIsForward() {
        // The origin is 1.6 m above the ground: the tyres reach down to −1.6.
        val (bottom, top) = range(prepared.body, 1)
        assertEquals(-1.6f, bottom, 0.03f)
        assertEquals(4.04f - 1.6f, top, 0.05f)
        val (nose, tail) = range(prepared.body, 2)
        assertTrue(nose < -5.5f && tail > 5.5f)
        assertEquals(11.84f, tail - nose, 0.1f)
        val (left, right) = range(prepared.body, 0)
        assertEquals(12.80f, right - left, 0.05f)
    }

    @Test
    fun theWingsAndTailTakeTheLiveryAndThePatchesTheRegistration() {
        val painted = prepared.body.filter { it.paint == AircraftModel.Paint.LIVERY }.map { it.part }.toSet()
        assertEquals(setOf("Wing", "FlapL", "FlapR", "AileronL", "AileronR", "Tailplane", "ElevatorL", "ElevatorR", "Fin", "Rudder"), painted)
        // The tips and the fuselage keep their own colours.
        assertTrue(prepared.body.none { it.paint == AircraftModel.Paint.LIVERY && it.material != "Polished" })
        assertTrue(prepared.body.any { it.part == "Fuselage" && it.material == "Polished" && it.paint == AircraftModel.Paint.COLOUR })
        assertEquals(setOf("RegistrationFuselageL", "RegistrationFuselageR", "RegistrationWingL", "RegistrationWingR"), prepared.registration.map { it.part }.toSet())
        for (patch in prepared.registration) {
            assertNotNull(patch.uvs)
            val uvs = patch.uvs!!
            assertEquals(patch.positions.size / 3 * 2, uvs.size)
            assertTrue(uvs.all { it in -0.001f..1.001f })
        }
        // On the left of the fuselage the lettering reads nose to tail, its top (v = 0) up.
        val left = prepared.registration.first { it.part == "RegistrationFuselageL" }
        val uvs = left.uvs!!
        val first = (0 until left.positions.size / 3).minByOrNull { uvs[it * 2] + uvs[it * 2 + 1] }!!
        val last = (0 until left.positions.size / 3).maxByOrNull { uvs[it * 2] + uvs[it * 2 + 1] }!!
        assertTrue(left.positions[last * 3 + 2] > left.positions[first * 3 + 2])
        assertTrue(left.positions[last * 3 + 1] < left.positions[first * 3 + 1])
    }

    @Test
    fun theBladesTurnAboutTheHub() {
        assertTrue(prepared.blades.isNotEmpty())
        val (x0, x1) = range(prepared.blades, 0)
        val (y0, y1) = range(prepared.blades, 1)
        val (z0, z1) = range(prepared.blades, 2)
        // Centred on the hub, a disc across x and y, thin along z: the tips all as far out.
        var reach = 0f
        for (blade in prepared.blades) {
            var i = 0
            while (i + 1 < blade.positions.size) {
                reach = maxOf(reach, kotlin.math.hypot(blade.positions[i], blade.positions[i + 1]))
                i += 3
            }
        }
        assertEquals(0f, (x0 + x1) / 2, 0.15f)
        assertTrue(reach > 1.2f && reach < 1.5f)
        assertTrue(y1 <= reach + 1e-3f && y0 >= -reach - 1e-3f)
        assertTrue(abs(z0) < 0.4f && abs(z1) < 0.4f)
        assertEquals(-5.567f, AircraftModel.PROPELLER_HUB.z, 1e-4f)
        assertEquals(0.117f, AircraftModel.PROPELLER_HUB.y, 1e-4f)
    }

    @Test
    fun theSkinFollowsTheFuselage() {
        // The cabin side under the windows, where the logo sits by default.
        val (point, normal) = AircraftModel.fuselageSkin(-1.0f, 0.08f, -1f)
        assertEquals(-0.72f, point.x, 0.02f)
        assertTrue(normal.x < -0.9f)
        val (right, rightNormal) = AircraftModel.fuselageSkin(-1.0f, 0.08f, 1f)
        assertEquals(-point.x, right.x, 1e-5f)
        assertTrue(rightNormal.x > 0.9f)
        // Above the cabin's crown the point is held on the skin.
        val (held, _) = AircraftModel.fuselageSkin(0f, 5f, -1f)
        assertTrue(held.y < 2.525f - 1.6f + 0.01f && held.x < 0f)
        // The stations run nose to tail in the aircraft's frame.
        val stations = AircraftModel.stationPositions
        assertEquals(-3.62f - 1.747f, stations.first(), 1e-4f)
        assertEquals(stations.sorted(), stations)
    }

    @Test
    fun theLogoIsAPatchOfTheSkinReadingRightFromEachSide() {
        val placement = AircraftLogoPlacement()
        assertEquals(-1.0, placement.along, 0.0)
        assertEquals(0.08, placement.height, 0.0)
        val decal = AircraftModel.logoDecal(400, 200, placement, -1f)!!
        val count = decal.positions.size / 3
        assertEquals(count * 2, decal.uvs.size)
        assertTrue(decal.indices.all { it in 0 until count })
        // 0.8 m wide and 0.4 m tall: columns at most 6 cm apart and rows 5 cm.
        assertTrue(count >= 14 * 9)
        // On the left, u runs nose to tail; v from the top down.
        assertTrue(decal.positions[2] < decal.positions[(count - 1) * 3 + 2])
        assertEquals(0f, decal.uvs[0], 1e-5f)
        assertEquals(1f, decal.uvs[1], 1e-5f)
        val right = AircraftModel.logoDecal(400, 200, placement, 1f)!!
        assertEquals(1f, right.uvs[0], 1e-5f)
        // Standing off the skin.
        assertTrue(decal.positions[0] < -0.72f)
        assertTrue(AircraftModel.logoDecal(0, 200, placement, -1f) == null)
    }
}
