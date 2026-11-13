package com.skyalt.canopy.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class PrimitivesTest {
    /** Twice a triangle's area as seen from above, signed by the way it winds. */
    private fun winding(p: FloatArray, a: Int, b: Int, c: Int): Double {
        val abx = (p[b * 3] - p[a * 3]).toDouble(); val abz = (p[b * 3 + 2] - p[a * 3 + 2]).toDouble()
        val acx = (p[c * 3] - p[a * 3]).toDouble(); val acz = (p[c * 3 + 2] - p[a * 3 + 2]).toDouble()
        return abx * acz - abz * acx
    }

    @Test
    fun theGroundGridIsTheGroundPlaneInSmallSquares() {
        val size = 10_000f
        val cells = 100
        val grid = Primitives.groundGrid(size, cells)
        val p = grid.positions
        val uv = grid.uvs!!
        val triangles = grid.indices!!
        assertEquals((cells + 1) * (cells + 1) * 3, p.size)
        assertEquals(cells * cells * 6, triangles.size)

        // Level, facing up, from edge to edge, with the plane's texture coordinates.
        var lowX = 0f; var highX = 0f; var lowZ = 0f; var highZ = 0f
        for (k in 0 until p.size / 3) {
            assertEquals(0f, p[k * 3 + 1], 0f)
            assertEquals(0f, grid.normals!![k * 3], 0f)
            assertEquals(1f, grid.normals!![k * 3 + 1], 0f)
            assertEquals(0f, grid.normals!![k * 3 + 2], 0f)
            assertEquals(p[k * 3] / size + 0.5f, uv[k * 2], 1e-6f)
            assertEquals(p[k * 3 + 2] / size + 0.5f, uv[k * 2 + 1], 1e-6f)
            lowX = minOf(lowX, p[k * 3]); highX = maxOf(highX, p[k * 3])
            lowZ = minOf(lowZ, p[k * 3 + 2]); highZ = maxOf(highZ, p[k * 3 + 2])
        }
        assertEquals(-size / 2, lowX, 0f); assertEquals(size / 2, highX, 0f)
        assertEquals(-size / 2, lowZ, 0f); assertEquals(size / 2, highZ, 0f)

        // Every triangle half a square, wound as the plane's two, and together the whole ground.
        val plane = Primitives.groundPlane(size, size)
        val planeWinding = winding(plane.positions, plane.indices!![0], plane.indices!![1], plane.indices!![2])
        val cell = (size / cells).toDouble()
        var area = 0.0
        for (t in 0 until triangles.size / 3) {
            val a = triangles[t * 3]; val b = triangles[t * 3 + 1]; val c = triangles[t * 3 + 2]
            val doubled = winding(p, a, b, c)
            assertTrue(doubled * planeWinding > 0)
            assertEquals(cell * cell, abs(doubled), cell * cell * 1e-3)
            for ((m, n) in listOf(a to b, b to c, c to a)) {
                val length = hypot((p[m * 3] - p[n * 3]).toDouble(), (p[m * 3 + 2] - p[n * 3 + 2]).toDouble())
                assertTrue(length <= cell * 1.4143)
            }
            area += abs(doubled) / 2
        }
        assertEquals(size.toDouble() * size, area, size.toDouble() * size * 1e-6)
    }

    @Test
    fun aStraightRunIsCutIntoEvenPiecesNoLongerThanTheStep() {
        val from = floatArrayOf(-250f, 0.14f, -151f)
        val to = floatArrayOf(750f, 0.14f, -151f)
        val run = Primitives.straightRun(from, to, 100f)
        assertEquals(11 * 3, run.size)
        for (i in 0..10) {
            assertEquals(-250f + 100f * i, run[i * 3], 1e-3f)
            assertEquals(0.14f, run[i * 3 + 1], 0f)
            assertEquals(-151f, run[i * 3 + 2], 0f)
        }
        // A length that the step does not divide: one piece more, all of them shorter.
        val uneven = Primitives.straightRun(floatArrayOf(0f, 0f, 0f), floatArrayOf(30f, 0f, 40f), 20f)
        assertEquals(4 * 3, uneven.size)
        assertEquals(30f, uneven[9], 0f); assertEquals(40f, uneven[11], 0f)
        assertEquals(10f, uneven[3], 1e-4f); assertEquals(13.3333f, uneven[5], 1e-3f)
        // Shorter than the step: its two ends.
        val short = Primitives.straightRun(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 2f, 3f), 100f)
        assertTrue(short.contentEquals(floatArrayOf(1f, 2f, 3f, 4f, 2f, 3f)))
        // No length at all: still a run of two points, as tubes needs.
        assertEquals(6, Primitives.straightRun(from, from, 100f).size)
    }

    @Test
    fun tubesOfAStraightRunAreOnePieceBetweenEachPairOfPoints() {
        val run = Primitives.straightRun(floatArrayOf(-250f, 0.14f, -151f), floatArrayOf(750f, 0.14f, -151f), 100f)
        val tube = Primitives.tubes(listOf(run), 0.3f)!!
        // Four corners at each point, four sides of two triangles between neighbours.
        assertEquals(11 * 4 * 3, tube.positions.size)
        assertEquals(10 * 4 * 6, tube.indices!!.size)
        val p = tube.positions
        for (t in 0 until tube.indices!!.size / 3) {
            val xs = (0..2).map { p[tube.indices!![t * 3 + it] * 3] }
            assertTrue(xs.max() - xs.min() <= 100.001f)
        }
        // The same outline as the single piece it replaces: 0.3 m to either side, above and below.
        var low = Float.MAX_VALUE; var high = -Float.MAX_VALUE; var near = Float.MAX_VALUE; var far = -Float.MAX_VALUE
        for (k in 0 until p.size / 3) {
            low = minOf(low, p[k * 3 + 1]); high = maxOf(high, p[k * 3 + 1])
            near = minOf(near, p[k * 3 + 2]); far = maxOf(far, p[k * 3 + 2])
        }
        assertEquals(0.14f - 0.3f, low, 1e-5f); assertEquals(0.14f + 0.3f, high, 1e-5f)
        assertEquals(-151.3f, near, 1e-4f); assertEquals(-150.7f, far, 1e-4f)
    }
}
