package com.skyalt.canopy.scene

import com.skyalt.canopy.model.DEG
import com.skyalt.canopy.model.smoothStep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 9-cell canopy's shape: span-wise stations along an arc, chord-wise
 * stations along a cambered airfoil, and a trailing edge that curls down
 * behind the brake lines. Rig frame: x across the span, y up, z aft, the
 * jumper hanging [LINE_LENGTH] below the origin. Port of CanopyMeshGeometry.swift.
 */
class CanopyMeshShape {
    class Station {
        var s = 0.0
        var baseX = 0.0
        var baseY = 0.0
        var radialX = 0.0
        var radialY = 0.0
        var brakeAngle = 0.0
        var bulgeTop = 0.0
        var bulgeBottom = 0.0
        var deflate = 0.0
    }

    val stations = Array(SPAN_STATIONS + 1) { Station() }
    var collapse = 0.0
        private set
    var scale = 1.0
        private set

    fun update(dL: Double, dR: Double, stall: Double, collapse: Double, scale: Double) {
        this.collapse = collapse
        this.scale = scale
        val inflation = (1 - 0.9 * stall) * (1 - collapse)
        val onset = stall * (1 - collapse)
        for (i in 0..SPAN_STATIONS) {
            val st = stations[i]
            val s = -1 + 2.0 * i / SPAN_STATIONS
            val angle = s * ARC_HALF_ANGLE
            st.s = s
            val crumple = collapse * (0.3 * sin(s * 13.0) * sin(s * 7.3 + 1.1) + 0.15 * cos(s * 21.0) + 0.2 * sin(s * 4.1 + 0.7))
            val sideways = collapse * 0.2 * sin(s * 17.0 + 0.4)
            val tipDrop = (collapse * 1.0 + onset * 0.35) * abs(s).pow(1.5)
            val drop = collapse * (0.3 + 0.3 * (1 - s * s))
            st.baseX = ARC_RADIUS * sin(angle) * (1 - 0.5 * collapse - 0.08 * onset) + sideways
            st.baseY = ARC_RADIUS * cos(angle) - ARC_RADIUS + crumple - tipDrop - drop
            st.radialX = sin(angle)
            st.radialY = cos(angle)
            val f = (i % SEGMENTS_PER_CELL).toDouble() / SEGMENTS_PER_CELL
            val cellFactor = sin(PI * f)
            val sideBrake = dL + (dR - dL) * smoothStep(-0.15, 0.15, s)
            val scallop = 0.78 + 0.22 * cos(PI * f) * cos(PI * f)
            var brakeAngle = MAXIMUM_BRAKE_ANGLE * sideBrake * brakeProfile(s) * scallop
            val fold = max(0.3 * onset, 0.5 * collapse)
            brakeAngle = min(1.6, brakeAngle + fold * MAXIMUM_BRAKE_ANGLE * (0.6 + 0.4 * sin(s * 9.0)))
            st.brakeAngle = brakeAngle
            st.bulgeTop = 0.06 * CHORD * cellFactor * inflation
            st.bulgeBottom = 0.022 * CHORD * cellFactor * inflation
            st.deflate = min(0.9, 0.5 * stall * (0.5 + 0.5 * cellFactor) + 0.9 * collapse)
        }
    }

    /** A point on station [i] at chord fraction [x], [u] chords above the chord line. */
    fun point(i: Int, x: Double, u: Double, bulge: Double, out: FloatArray, offset: Int) {
        val st = stations[i]
        var aft = x
        var up = u
        if (st.deflate > 0) {
            val foil = airfoil(x)
            val half = max((foil.top - foil.bottom) / 2, 1e-4)
            val keep = min(1.0, max(1 - st.deflate, 0.012 / half))
            up = foil.camber + (u - foil.camber) * keep + collapse * 0.05 * sin(x * 9 + st.s * 5)
        }
        if (x > HINGE && st.brakeAngle > 1e-4) {
            val flapLength = 1 - HINGE
            val along = x - HINGE
            val theta = st.brakeAngle * along / flapLength
            val radius = flapLength / st.brakeAngle
            val bend = radius * sin(theta)
            val dropped = radius * (1 - cos(theta))
            // As in the iOS model, the flap bends the undeflated section.
            aft = HINGE + bend + u * sin(theta)
            up = -dropped + u * cos(theta)
        }
        val z = (aft - 0.30) * CHORD + collapse * 0.5
        val height = up * CHORD + bulge
        out[offset] = ((st.baseX + st.radialX * height) * scale).toFloat()
        out[offset + 1] = ((st.baseY + st.radialY * height) * scale).toFloat()
        out[offset + 2] = (z * scale).toFloat()
    }

    data class Airfoil(val top: Double, val bottom: Double, val camber: Double)

    companion object {
        const val SPAN = 7.6
        const val CHORD = 2.7
        const val REFERENCE_AREA_SQUARE_FEET = 150.0
        const val CELLS = 9
        const val SEGMENTS_PER_CELL = 8
        const val CHORD_STATIONS = 22
        const val ARC_HALF_ANGLE = 0.52
        const val LINE_LENGTH = 4.4
        const val HINGE = 0.62
        const val BOTTOM_START = 0.07
        val MAXIMUM_BRAKE_ANGLE = 78 * DEG
        const val SPAN_STATIONS = CELLS * SEGMENTS_PER_CELL
        const val ARC_RADIUS = (SPAN / 2) / ARC_HALF_ANGLE

        /** A NACA-style section: 17% thick, 3.5% camber at 35% chord. */
        fun airfoil(x: Double): Airfoil {
            val t = 0.17
            val thickness = 5 * t * (0.2969 * sqrt(x) - 0.1260 * x - 0.3516 * x * x + 0.2843 * x * x * x - 0.1036 * x * x * x * x)
            val p = 0.35
            val m = 0.035
            val camber = if (x < p) m / (p * p) * (2 * p * x - x * x)
            else m / ((1 - p) * (1 - p)) * ((1 - 2 * p) + 2 * p * x - x * x)
            return Airfoil(camber + thickness / 2, camber - thickness / 2, camber)
        }

        val topX = DoubleArray(CHORD_STATIONS + 1) { (it.toDouble() / CHORD_STATIONS).pow(1.5) }
        val bottomX = DoubleArray(CHORD_STATIONS + 1) { BOTTOM_START + (1 - BOTTOM_START) * (it.toDouble() / CHORD_STATIONS).pow(1.3) }
        val topFoil = topX.map { airfoil(it) }
        val bottomFoil = bottomX.map { airfoil(it) }

        fun scale(areaSquareFeet: Double) = sqrt(max(1.0, areaSquareFeet) / REFERENCE_AREA_SQUARE_FEET)

        private fun brakeProfile(s: Double) = 0.2 + 0.8 * smoothStep(0.08, 0.6, abs(s))
    }
}

/**
 * Builds the vertex arrays for every piece of the rig from the brake state:
 * the two skins, the inlet, the ribs, the tip caps, the lines, the brake
 * lines, the risers and the hands. Positions are packed xyz floats.
 */
class CanopyMeshBuilder {
    val shape = CanopyMeshShape()

    private val ns = CanopyMeshShape.SPAN_STATIONS
    private val nc = CanopyMeshShape.CHORD_STATIONS
    private val vertexCount = (ns + 1) * (nc + 1)

    val skinIndices: IntArray = gridIndices(ns, nc)
    val inletIndices: IntArray = gridIndices(ns, 2)
    val tipIndices: IntArray
    val topColors = FloatArray(vertexCount * 4)
    val bottomColors = FloatArray(vertexCount * 4)

    val topVertices = FloatArray(vertexCount * 3)
    val topNormals = FloatArray(vertexCount * 3)
    val bottomVertices = FloatArray(vertexCount * 3)
    val bottomNormals = FloatArray(vertexCount * 3)
    val inletVertices = FloatArray((ns + 1) * 3 * 3)
    val inletNormals = FloatArray((ns + 1) * 3 * 3)
    val tipVertices = arrayOf(FloatArray((LOOP_COUNT + 1) * 3), FloatArray((LOOP_COUNT + 1) * 3))
    val tipNormals = arrayOf(FloatArray((LOOP_COUNT + 1) * 3), FloatArray((LOOP_COUNT + 1) * 3))
    val ribSegments = FloatList()
    val lineSegments = FloatList()
    val brakeSegments = FloatList()
    val riserSegments = FloatList()
    var leftHand = floatArrayOf(0f, 0f, 0f)
        private set
    var rightHand = floatArrayOf(0f, 0f, 0f)
        private set

    private val scratch = FloatArray(3)
    private val loopScratch = FloatArray(LOOP_COUNT * 3)

    init {
        val tip = IntArray(LOOP_COUNT * 3)
        for (q in 0 until LOOP_COUNT) {
            tip[q * 3] = LOOP_COUNT
            tip[q * 3 + 1] = q
            tip[q * 3 + 2] = (q + 1) % LOOP_COUNT
        }
        tipIndices = tip
        var k = 0
        for (i in 0..ns) {
            val cell = min(CanopyMeshShape.CELLS - 1, i / CanopyMeshShape.SEGMENTS_PER_CELL)
            val c = CELL_COLORS[cell]
            for (j in 0..nc) {
                topColors[k] = c[0]; topColors[k + 1] = c[1]; topColors[k + 2] = c[2]; topColors[k + 3] = 1f
                bottomColors[k] = c[0] * 0.82f; bottomColors[k + 1] = c[1] * 0.82f; bottomColors[k + 2] = c[2] * 0.82f; bottomColors[k + 3] = 1f
                k += 4
            }
        }
        update(0.0, 0.0, 0.0, 0.0, 1.0)
    }

    fun update(dL: Double, dR: Double, stall: Double, collapse: Double, scale: Double) {
        shape.update(dL, dR, stall, collapse, scale)
        val topX = CanopyMeshShape.topX
        val bottomX = CanopyMeshShape.bottomX
        val topFoil = CanopyMeshShape.topFoil
        val bottomFoil = CanopyMeshShape.bottomFoil
        val inletMid = CanopyMeshShape.airfoil(0.12).camber

        for (i in 0..ns) {
            val st = shape.stations[i]
            for (j in 0..nc) {
                val k = (i * (nc + 1) + j) * 3
                shape.point(i, topX[j], topFoil[j].top, st.bulgeTop * sin(PI * topX[j]).pow(0.7), topVertices, k)
                shape.point(i, bottomX[j], bottomFoil[j].bottom, -st.bulgeBottom * sin(PI * bottomX[j]).pow(0.7), bottomVertices, k)
            }
            val k3 = i * 9
            shape.point(i, 0.0, topFoil[0].top, 0.0, inletVertices, k3)
            shape.point(i, 0.12, inletMid, 0.0, inletVertices, k3 + 3)
            shape.point(i, CanopyMeshShape.BOTTOM_START, bottomFoil[0].bottom, 0.0, inletVertices, k3 + 6)
        }
        computeNormals(topVertices, skinIndices, topNormals)
        computeNormals(bottomVertices, skinIndices, bottomNormals)
        computeNormals(inletVertices, inletIndices, inletNormals)

        // Ribs: a closed loop around the section at every cell wall.
        ribSegments.clear()
        for (i in RIB_STATIONS) {
            sectionLoop(i, loopScratch)
            for (q in 0 until LOOP_COUNT) {
                val n = (q + 1) % LOOP_COUNT
                ribSegments.add(loopScratch[q * 3], loopScratch[q * 3 + 1], loopScratch[q * 3 + 2])
                ribSegments.add(loopScratch[n * 3], loopScratch[n * 3 + 1], loopScratch[n * 3 + 2])
            }
        }

        // Tip caps: a fan from the section's centre.
        for ((index, station) in intArrayOf(0, ns).withIndex()) {
            val tv = tipVertices[index]
            sectionLoop(station, tv)
            var cx = 0f; var cy = 0f; var cz = 0f
            for (q in 0 until LOOP_COUNT) { cx += tv[q * 3]; cy += tv[q * 3 + 1]; cz += tv[q * 3 + 2] }
            tv[LOOP_COUNT * 3] = cx / LOOP_COUNT
            tv[LOOP_COUNT * 3 + 1] = cy / LOOP_COUNT
            tv[LOOP_COUNT * 3 + 2] = cz / LOOP_COUNT
            computeNormals(tv, tipIndices, tipNormals[index])
        }

        // Suspension lines from four chord positions on every cell wall to the risers.
        lineSegments.clear()
        val rows = arrayOf(0.03 to false, 0.3 to false, 0.56 to true, 0.8 to true)
        for (i in RIB_STATIONS) {
            val side = if (shape.stations[i].s < 0) -1f else 1f
            for ((rowIndex, row) in rows.withIndex()) {
                shape.point(i, row.first, CanopyMeshShape.airfoil(row.first).bottom, 0.0, scratch, 0)
                val ax = scratch[0]; val ay = scratch[1]; val az = scratch[2]
                val rx = side * 0.28f; val ry = RISER_HEIGHT; val rz = if (row.second) 0.12f else -0.12f
                if (collapse < 0.02) {
                    lineSegments.add(ax, ay, az)
                    lineSegments.add(rx, ry, rz)
                } else {
                    val c = collapse.toFloat()
                    val wobble = (0.7 + 0.3 * sin(i * 1.7 + rowIndex * 2.3)).toFloat()
                    val bx = side * 0.5f * c * wobble
                    val by = -0.3f * c * wobble
                    val bz = (0.3f + 0.12f * rowIndex) * c * wobble
                    val cx = (ax + rx) / 2 + bx; val cy = (ay + ry) / 2 + by; val cz = (az + rz) / 2 + bz
                    var px = ax; var py = ay; var pz = az
                    for (step in 1..6) {
                        val t = step / 6f
                        val a = (1 - t) * (1 - t); val b = 2 * (1 - t) * t; val d = t * t
                        val nx = ax * a + cx * b + rx * d
                        val ny = ay * a + cy * b + ry * d
                        val nz = az * a + cz * b + rz * d
                        lineSegments.add(px, py, pz)
                        lineSegments.add(nx, ny, nz)
                        px = nx; py = ny; pz = nz
                    }
                }
            }
        }

        // Brake lines: trailing edge → cascade → hand.
        val lp = CanopyMeshShape.LINE_LENGTH.toFloat()
        fun hand(side: Float, d: Double): FloatArray {
            val df = d.toFloat()
            val stroke = sin(PI * d).toFloat()
            return floatArrayOf(side * (0.2f + 0.12f * df + 0.03f * stroke), -lp + 0.8f - 1.188f * df, -0.36f * stroke)
        }
        leftHand = hand(-1f, dL)
        rightHand = hand(1f, dR)
        val leftCascade = floatArrayOf(-0.34f, -lp + 1.9f, 0.4f)
        val rightCascade = floatArrayOf(0.34f, -lp + 1.9f, 0.4f)
        brakeSegments.clear()
        for (i in RIB_STATIONS) {
            shape.point(i, 1.0, 0.0, 0.0, scratch, 0)
            brakeSegments.add(scratch[0], scratch[1], scratch[2])
            val c = if (shape.stations[i].s < 0) leftCascade else rightCascade
            brakeSegments.add(c[0], c[1], c[2])
        }
        brakeSegments.add(leftCascade[0], leftCascade[1], leftCascade[2])
        brakeSegments.add(leftHand[0], leftHand[1], leftHand[2])
        brakeSegments.add(rightCascade[0], rightCascade[1], rightCascade[2])
        brakeSegments.add(rightHand[0], rightHand[1], rightHand[2])

        riserSegments.clear()
        for (side in floatArrayOf(-1f, 1f)) {
            for (z in floatArrayOf(-0.12f, 0.12f)) {
                riserSegments.add(side * 0.17f, -lp + 0.22f, z * 0.6f)
                riserSegments.add(side * 0.28f, RISER_HEIGHT, z)
            }
        }
    }

    /** Top surface nose to tail, then the bottom surface tail to nose. */
    private fun sectionLoop(i: Int, out: FloatArray) {
        var k = 0
        for (j in 0..nc) {
            shape.point(i, CanopyMeshShape.topX[j], CanopyMeshShape.topFoil[j].top, 0.0, out, k); k += 3
        }
        for (j in nc downTo 0) {
            shape.point(i, CanopyMeshShape.bottomX[j], CanopyMeshShape.bottomFoil[j].bottom, 0.0, out, k); k += 3
        }
    }

    companion object {
        const val LOOP_COUNT = 2 * (CanopyMeshShape.CHORD_STATIONS + 1)
        val RISER_HEIGHT = (-CanopyMeshShape.LINE_LENGTH + 0.85).toFloat()
        val RIB_STATIONS = IntArray(CanopyMeshShape.CELLS + 1) { it * CanopyMeshShape.SEGMENTS_PER_CELL }

        val CELL_COLORS: List<FloatArray> = listOf(
            0x1f3b73, 0xf2f2ee, 0xe4582b, 0xf2f2ee, 0x1f3b73, 0xf2f2ee, 0xe4582b, 0xf2f2ee, 0x1f3b73,
        ).map { rgb(it) }

        fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 0xff) / 255f, ((hex shr 8) and 0xff) / 255f, (hex and 0xff) / 255f, 1f)

        fun gridIndices(nx: Int, ny: Int): IntArray {
            val out = IntArray(nx * ny * 6)
            var t = 0
            for (i in 0 until nx) for (j in 0 until ny) {
                val a = i * (ny + 1) + j
                val b = a + ny + 1
                out[t++] = a; out[t++] = b; out[t++] = a + 1
                out[t++] = b; out[t++] = b + 1; out[t++] = a + 1
            }
            return out
        }

        fun computeNormals(v: FloatArray, idx: IntArray, n: FloatArray) {
            n.fill(0f)
            var t = 0
            while (t + 2 < idx.size) {
                val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
                val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
                val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
                val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
                for (p in intArrayOf(a, b, c)) { n[p] += nx; n[p + 1] += ny; n[p + 2] += nz }
                t += 3
            }
            var k = 0
            while (k < n.size) {
                val l = sqrt(n[k] * n[k] + n[k + 1] * n[k + 1] + n[k + 2] * n[k + 2])
                if (l > 1e-9f) { n[k] /= l; n[k + 1] /= l; n[k + 2] /= l } else { n[k] = 0f; n[k + 1] = 1f; n[k + 2] = 0f }
                k += 3
            }
        }
    }
}

/** A growable float list without boxing. */
class FloatList(capacity: Int = 1024) {
    var data = FloatArray(capacity)
        private set
    var size = 0
        private set

    fun clear() { size = 0 }

    fun add(x: Float, y: Float, z: Float) {
        if (size + 3 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 3))
        data[size] = x; data[size + 1] = y; data[size + 2] = z
        size += 3
    }

    fun add(v: Float) {
        if (size + 1 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
}
