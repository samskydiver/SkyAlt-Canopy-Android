package com.skyalt.canopy.windtunnel

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Triangles with a normal at each corner, in the frame of the body part they are drawn with. */
class WTMesh(val positions: FloatArray, val normals: FloatArray, val indices: IntArray)

/** What a part of the mannequin is made of: its colour and how sharp its highlight is. */
enum class WTMaterial(val rgb: Int, val shine: Float) {
    SUIT(0xf4aa2e, 11f),
    JOINT(0x3b4a56, 13f),
    HELMET(0xe9edf0, 130f),
    VISOR(0x0d1319, 600f),
}

/**
 * One drawn piece of the mannequin. It moves with a body segment
 * ([segment] is its place in the body's list) or with a joint ([joint]).
 */
class WTFigurePart(val mesh: WTMesh, val material: WTMaterial, val grp: String, val segment: Int, val joint: Int)

/**
 * The mannequin's shapes, as WindTunnelSceneController.swift builds them
 * from SceneKit capsules, spheres and boxes. A stretched shape is made
 * stretched here, with its normals worked out for the stretch, so the
 * picture only ever turns and moves a part.
 */
object WTFigure {
    private const val AROUND = 24
    private const val CAP_RINGS = 8

    fun build(body: WTBody): List<WTFigurePart> {
        val parts = ArrayList<WTFigurePart>()
        for ((j, sg) in body.segs.withIndex()) {
            fun add(mesh: WTMesh, material: WTMaterial) {
                parts += WTFigurePart(mesh, material, sg.grp, j, -1)
            }
            when {
                sg.kind == WTSegment.Kind.CYL -> add(capsule(sg.r, sg.L + 2 * sg.r), WTMaterial.SUIT)
                sg.kind == WTSegment.Kind.BOX && (sg.id == "chest" || sg.id == "pelvis") -> {
                    val w = sg.size.x; val ln = sg.size.y; val d = sg.size.z
                    add(capsule(d / 2, max(ln - d * 0.5, 0.02) + d, scale = WTVec(w / d, 1.0, 1.0)), WTMaterial.SUIT)
                    if (sg.id == "chest") add(box(w * 0.46, ln * 0.1, d * 0.2, WTVec(0.0, ln * 0.14, d * 0.42)), WTMaterial.JOINT)
                }
                sg.kind == WTSegment.Kind.ELL -> {
                    val sm = sg.semi
                    add(ellipsoid(sm, WTVec.ZERO), WTMaterial.HELMET)
                    add(ellipsoid(WTVec(sm.x * 0.8, sm.y * 0.42, sm.z * 0.62), WTVec(0.0, sm.y * 0.06, sm.z * 0.46)), WTMaterial.VISOR)
                    val nl = WTCore.length.getValue("neck") * body.H
                    add(cylinder(sm.x * 0.55, nl, WTVec(0.0, -nl / 2, 0.0)), WTMaterial.JOINT)
                }
                sg.id.startsWith("hand") -> {
                    val hw = sg.size.x; val hl = sg.size.y; val ht = sg.size.z
                    add(capsule(hw / 2, max(hl - hw, 0.01) + hw, scale = WTVec(1.0, 1.0, ht * 1.8 / hw)), WTMaterial.JOINT)
                }
                else -> {
                    // a foot: the capsule lies along the foot, which is the segment's z
                    val fw = sg.size.x; val fh = sg.size.y; val fl = sg.size.z
                    add(capsule(fh * 0.56, max(fl - fh * 1.1, 0.01) + fh * 1.12, scale = WTVec(fw / fh * 0.92, 1.0, 1.0), alongZ = true), WTMaterial.JOINT)
                }
            }
        }
        for ((j, jt) in body.joints.withIndex()) {
            val semi = if (jt.id == "waist") WTVec(jt.r * 1.75, jt.r, jt.r * 1.05) else WTVec(jt.r, jt.r, jt.r)
            parts += WTFigurePart(ellipsoid(semi, WTVec.ZERO, rings = 12, around = 20), WTMaterial.JOINT, jt.grp, -1, j)
        }
        return parts
    }

    /**
     * A capsule along y, [height] from end to end, like SCNCapsule; then
     * stretched by [scale], and with [alongZ] laid over so that its length
     * runs along z.
     */
    fun capsule(capRadius: Double, height: Double, scale: WTVec = WTVec(1.0, 1.0, 1.0), alongZ: Boolean = false): WTMesh {
        val half = max(height / 2 - capRadius, 0.0)
        val ringCount = 2 * (CAP_RINGS + 1)
        val positions = FloatArray(ringCount * AROUND * 3)
        val normals = FloatArray(ringCount * AROUND * 3)
        var k = 0
        for (ring in 0 until ringCount) {
            val top = ring <= CAP_RINGS
            // from the top pole down to the top rim, then from the bottom rim to the bottom pole
            val a = if (top) PI / 2 * ring / CAP_RINGS else PI / 2 + PI / 2 * (ring - CAP_RINGS - 1) / CAP_RINGS
            val ny = cos(a); val nr = sin(a)
            val y = (if (top) half else -half) + capRadius * ny
            for (i in 0 until AROUND) {
                val t = 2 * PI * i / AROUND
                val nx = nr * cos(t); val nz = nr * sin(t)
                put(positions, normals, k++, WTVec(capRadius * nx, y, capRadius * nz), WTVec(nx, ny, nz), scale, alongZ, WTVec.ZERO)
            }
        }
        return WTMesh(positions, normals, bands(ringCount, AROUND))
    }

    /** A ball stretched to [semi] along the three axes, its middle at [offset]. */
    fun ellipsoid(semi: WTVec, offset: WTVec, rings: Int = 16, around: Int = AROUND): WTMesh {
        val positions = FloatArray((rings + 1) * around * 3)
        val normals = FloatArray((rings + 1) * around * 3)
        var k = 0
        for (ring in 0..rings) {
            val a = PI * ring / rings
            val ny = cos(a); val nr = sin(a)
            for (i in 0 until around) {
                val t = 2 * PI * i / around
                val n = WTVec(nr * cos(t), ny, nr * sin(t))
                put(positions, normals, k++, n, n, semi, false, offset)
            }
        }
        return WTMesh(positions, normals, bands(rings + 1, around))
    }

    /** A closed cylinder along y with its middle at [offset]. */
    fun cylinder(radius: Double, height: Double, offset: WTVec, around: Int = AROUND): WTMesh {
        val one = WTVec(1.0, 1.0, 1.0)
        // side: two rings; then each end: a rim and a middle point
        val count = around * 4 + 2
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        val indices = ArrayList<Int>()
        var k = 0
        for (end in 0..1) {
            val y = if (end == 0) height / 2 else -height / 2
            for (i in 0 until around) {
                val t = 2 * PI * i / around
                put(positions, normals, k++, WTVec(radius * cos(t), y, radius * sin(t)), WTVec(cos(t), 0.0, sin(t)), one, false, offset)
            }
        }
        for (i in 0 until around) {
            val a = i; val b = (i + 1) % around; val c = around + i; val d = around + (i + 1) % around
            indices += listOf(a, b, c, b, d, c)
        }
        for (end in 0..1) {
            val y = if (end == 0) height / 2 else -height / 2
            val up = WTVec(0.0, if (end == 0) 1.0 else -1.0, 0.0)
            val first = k
            for (i in 0 until around) {
                val t = 2 * PI * i / around
                put(positions, normals, k++, WTVec(radius * cos(t), y, radius * sin(t)), up, one, false, offset)
            }
            val middle = k
            put(positions, normals, k++, WTVec(0.0, y, 0.0), up, one, false, offset)
            for (i in 0 until around) {
                val a = first + i; val b = first + (i + 1) % around
                indices += if (end == 0) listOf(middle, b, a) else listOf(middle, a, b)
            }
        }
        return WTMesh(positions, normals, indices.toIntArray())
    }

    /** A box [w] by [h] by [l] with its middle at [offset]. */
    fun box(w: Double, h: Double, l: Double, offset: WTVec): WTMesh {
        val one = WTVec(1.0, 1.0, 1.0)
        val positions = FloatArray(24 * 3)
        val normals = FloatArray(24 * 3)
        val indices = IntArray(36)
        val faces = listOf(
            WTVec(1.0, 0.0, 0.0), WTVec(-1.0, 0.0, 0.0), WTVec(0.0, 1.0, 0.0),
            WTVec(0.0, -1.0, 0.0), WTVec(0.0, 0.0, 1.0), WTVec(0.0, 0.0, -1.0),
        )
        var k = 0
        for ((f, n) in faces.withIndex()) {
            // two directions across the face, so that u × v points out of it
            val u = if (n.x != 0.0) WTVec(0.0, 1.0, 0.0) else if (n.y != 0.0) WTVec(0.0, 0.0, 1.0) else WTVec(1.0, 0.0, 0.0)
            val v = WTCore.cross(n, u)
            val base = k
            for ((a, b) in listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)) {
                val c = n + u * a + v * b
                put(positions, normals, k++, WTVec(c.x * w / 2, c.y * h / 2, c.z * l / 2), n, one, false, offset)
            }
            val at = f * 6
            indices[at] = base; indices[at + 1] = base + 1; indices[at + 2] = base + 2
            indices[at + 3] = base; indices[at + 4] = base + 2; indices[at + 5] = base + 3
        }
        return WTMesh(positions, normals, indices)
    }

    /** Point k of a mesh: stretched, laid over if asked, moved; the normal follows the stretch. */
    private fun put(positions: FloatArray, normals: FloatArray, k: Int, p: WTVec, n: WTVec, scale: WTVec, alongZ: Boolean, offset: WTVec) {
        var px = p.x * scale.x; var py = p.y * scale.y; var pz = p.z * scale.z
        var nx = n.x / scale.x; var ny = n.y / scale.y; var nz = n.z / scale.z
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl > 0) { nx /= nl; ny /= nl; nz /= nl }
        if (alongZ) {
            // a quarter turn about x: the length that ran along y now runs along z
            val y = py; py = -pz; pz = y
            val m = ny; ny = -nz; nz = m
        }
        positions[k * 3] = (px + offset.x).toFloat(); positions[k * 3 + 1] = (py + offset.y).toFloat(); positions[k * 3 + 2] = (pz + offset.z).toFloat()
        normals[k * 3] = nx.toFloat(); normals[k * 3 + 1] = ny.toFloat(); normals[k * 3 + 2] = nz.toFloat()
    }

    /** Triangles between each ring of points and the next, seen from outside anticlockwise. */
    private fun bands(ringCount: Int, around: Int): IntArray {
        val indices = IntArray((ringCount - 1) * around * 6)
        var at = 0
        for (ring in 0 until ringCount - 1) {
            for (i in 0 until around) {
                val a = ring * around + i; val b = ring * around + (i + 1) % around
                val c = a + around; val d = b + around
                indices[at++] = a; indices[at++] = b; indices[at++] = c
                indices[at++] = b; indices[at++] = d; indices[at++] = c
            }
        }
        return indices
    }

    /** Half the size of a body part along its own axes, as an egg shape around it. */
    fun halfExtents(sg: WTSegment): WTVec = when {
        sg.kind == WTSegment.Kind.CYL -> WTVec(sg.r, sg.L / 2 + sg.r, sg.r)
        sg.kind == WTSegment.Kind.ELL -> sg.semi
        sg.id == "chest" || sg.id == "pelvis" -> WTVec(sg.size.x / 2, sg.size.y / 2 + sg.size.z / 4, sg.size.z / 2)
        sg.id.startsWith("hand") -> WTVec(sg.size.x / 2, sg.size.y / 2, sg.size.z * 0.9)
        else -> WTVec(sg.size.x * 0.52, sg.size.y * 0.56, sg.size.z / 2)
    }

    /**
     * Where the arrow of a body part starts: where the line of the force
     * leaves the part, so a short arrow is not hidden inside it.
     */
    fun surfacePoint(sg: WTSegment, R: WTQuat, c: WTVec, f: WTVec, k: Double): WTVec {
        val fl = WTCore.len(f)
        if (!(fl > 1e-9)) return c
        val qw = WTCore.qMul(R, sg.q); val d = WTCore.qInvRot(qw, f)
        val h = halfExtents(sg)
        var t = 1 / sqrt(d.x * d.x / (h.x * h.x) + d.y * d.y / (h.y * h.y) + d.z * d.z / (h.z * h.z))
        // head first into the stream: the arrow pushes on the helmet from outside
        if (sg.id == "head" && d.y < -0.6 * fl) t = -(t + k)
        return c + f * t
    }

    /**
     * How far along a ray it first meets an egg shape, or null when it
     * misses. The ray starts at [origin] and runs along [direction]; the
     * shape sits at [centre], turned by [q], [half] wide along its axes.
     */
    fun rayHit(origin: WTVec, direction: WTVec, centre: WTVec, q: WTQuat, half: WTVec): Double? {
        val o = WTCore.qInvRot(q, origin - centre); val d = WTCore.qInvRot(q, direction)
        val ox = o.x / half.x; val oy = o.y / half.y; val oz = o.z / half.z
        val dx = d.x / half.x; val dy = d.y / half.y; val dz = d.z / half.z
        val a = dx * dx + dy * dy + dz * dz
        if (!(a > 0)) return null
        val b = 2 * (ox * dx + oy * dy + oz * dz)
        val c = ox * ox + oy * oy + oz * oz - 1
        val disc = b * b - 4 * a * c
        if (disc < 0) return null
        val root = sqrt(disc)
        val near = (-b - root) / (2 * a)
        if (near > 0) return near
        val far = (-b + root) / (2 * a)
        return if (far > 0) far else null
    }

    /**
     * The group ("armL", "legR", "spine", "head") of the body part a ray
     * meets first. Each part counts as [margin] metres bigger than it is.
     */
    fun group(body: WTBody, R: WTQuat, origin: WTVec, direction: WTVec, margin: Double): String? {
        var best: String? = null
        var bestT = Double.MAX_VALUE
        for (sg in body.segs) {
            val h = halfExtents(sg)
            val t = rayHit(
                origin, direction, WTCore.qRot(R, sg.c - body.com), WTCore.qMul(R, sg.q),
                WTVec(h.x + margin, h.y + margin, h.z + margin),
            ) ?: continue
            if (t < bestT) { bestT = t; best = sg.grp }
        }
        for (jt in body.joints) {
            val wide = if (jt.id == "waist") WTVec(jt.r * 1.75, jt.r, jt.r * 1.05) else WTVec(jt.r, jt.r, jt.r)
            val t = rayHit(
                origin, direction, WTCore.qRot(R, jt.p - body.com), R,
                WTVec(wide.x + margin, wide.y + margin, wide.z + margin),
            ) ?: continue
            if (t < bestT) { bestT = t; best = jt.grp }
        }
        return best
    }
}
