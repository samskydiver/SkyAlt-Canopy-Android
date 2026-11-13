package com.skyalt.canopy.scene

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry builders matching the SceneKit primitives the iOS scene uses. */
object Primitives {
    /** A box centred on the origin. */
    fun box(w: Float, h: Float, l: Float): MeshData {
        val x = w / 2; val y = h / 2; val z = l / 2
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
        )
        val p = FloatList(); val n = FloatList(); val idx = ArrayList<Int>()
        for (f in faces) {
            // Two axes spanning the face.
            val u = if (f[1] != 0f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val v = floatArrayOf(f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0])
            val base = p.size / 3
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                p.add((f[0] + u[0] * a + v[0] * b) * x, (f[1] + u[1] * a + v[1] * b) * y, (f[2] + u[2] * a + v[2] * b) * z)
                n.add(f[0], f[1], f[2])
            }
            idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        return MeshData(p.data.copyOf(p.size), n.data.copyOf(n.size), idx.toIntArray())
    }

    fun sphere(r: Float, rings: Int = 16, segments: Int = 24): MeshData {
        val p = FloatList(); val n = FloatList(); val idx = ArrayList<Int>()
        for (i in 0..rings) {
            val phi = PI * i / rings
            for (j in 0..segments) {
                val theta = 2 * PI * j / segments
                val nx = (sin(phi) * cos(theta)).toFloat()
                val ny = cos(phi).toFloat()
                val nz = (sin(phi) * sin(theta)).toFloat()
                p.add(nx * r, ny * r, nz * r)
                n.add(nx, ny, nz)
            }
        }
        for (i in 0 until rings) for (j in 0 until segments) {
            val a = i * (segments + 1) + j
            val b = a + segments + 1
            idx += listOf(a, b, a + 1, b, b + 1, a + 1)
        }
        return MeshData(p.data.copyOf(p.size), n.data.copyOf(n.size), idx.toIntArray())
    }

    /**
     * A cone frustum along y, centred, bottom radius at −h/2. [caps] adds
     * the end discs (bottom first).
     */
    fun cone(topRadius: Float, bottomRadius: Float, height: Float, segments: Int = 24, capBottom: Boolean = true, capTop: Boolean = true): MeshData {
        val p = FloatList(); val n = FloatList(); val idx = ArrayList<Int>()
        val h2 = height / 2
        val slope = (bottomRadius - topRadius) / height
        val nl = sqrt(1 + slope * slope)
        for (j in 0..segments) {
            val t = 2 * PI * j / segments
            val c = cos(t).toFloat(); val s = sin(t).toFloat()
            p.add(c * bottomRadius, -h2, s * bottomRadius); n.add(c / nl, slope / nl, s / nl)
            p.add(c * topRadius, h2, s * topRadius); n.add(c / nl, slope / nl, s / nl)
        }
        for (j in 0 until segments) {
            val a = j * 2
            idx += listOf(a, a + 1, a + 2, a + 1, a + 3, a + 2)
        }
        fun cap(y: Float, r: Float, ny: Float) {
            if (r <= 0f) return
            val centre = p.size / 3
            p.add(0f, y, 0f); n.add(0f, ny, 0f)
            for (j in 0..segments) {
                val t = 2 * PI * j / segments
                p.add(cos(t).toFloat() * r, y, sin(t).toFloat() * r); n.add(0f, ny, 0f)
            }
            for (j in 0 until segments) idx += listOf(centre, centre + 1 + j, centre + 2 + j)
        }
        if (capBottom) cap(-h2, bottomRadius, -1f)
        if (capTop) cap(h2, topRadius, 1f)
        return MeshData(p.data.copyOf(p.size), n.data.copyOf(n.size), idx.toIntArray())
    }

    /** A torus in the x-z plane. */
    fun torus(ringRadius: Float, pipeRadius: Float, segments: Int = 32, sides: Int = 10): MeshData {
        val p = FloatList(); val n = FloatList(); val idx = ArrayList<Int>()
        for (i in 0..segments) {
            val u = 2 * PI * i / segments
            for (j in 0..sides) {
                val v = 2 * PI * j / sides
                val nx = (cos(v) * cos(u)).toFloat()
                val ny = sin(v).toFloat()
                val nz = (cos(v) * sin(u)).toFloat()
                p.add(cos(u).toFloat() * ringRadius + nx * pipeRadius, ny * pipeRadius, sin(u).toFloat() * ringRadius + nz * pipeRadius)
                n.add(nx, ny, nz)
            }
        }
        for (i in 0 until segments) for (j in 0 until sides) {
            val a = i * (sides + 1) + j
            val b = a + sides + 1
            idx += listOf(a, b, a + 1, b, b + 1, a + 1)
        }
        return MeshData(p.data.copyOf(p.size), n.data.copyOf(n.size), idx.toIntArray())
    }

    /** A flat annulus (or disc when [inner] is 0) on the ground plane. */
    fun flatRing(inner: Float, outer: Float, segments: Int = 64): MeshData {
        val p = FloatList(); val n = FloatList(); val idx = ArrayList<Int>()
        for (j in 0..segments) {
            val t = 2 * PI * j / segments
            val c = cos(t).toFloat(); val s = sin(t).toFloat()
            p.add(c * inner, 0f, s * inner); n.add(0f, 1f, 0f)
            p.add(c * outer, 0f, s * outer); n.add(0f, 1f, 0f)
        }
        for (j in 0 until segments) {
            val a = j * 2
            idx += listOf(a, a + 1, a + 2, a + 1, a + 3, a + 2)
        }
        return MeshData(p.data.copyOf(p.size), n.data.copyOf(n.size), idx.toIntArray())
    }

    /** A rectangle in the x-z plane, facing up, with texture coordinates 0…[uvRepeat]. */
    fun groundPlane(width: Float, height: Float): MeshData {
        val x = width / 2; val z = height / 2
        return MeshData(
            floatArrayOf(-x, 0f, -z, x, 0f, -z, x, 0f, z, -x, 0f, z),
            floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f),
            intArrayOf(0, 2, 1, 0, 3, 2),
            uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
        )
    }

    /**
     * A square in the x-z plane, facing up, cut into [cells] by [cells]
     * squares, with texture coordinates 0…1 across the whole of it. For the
     * ground: as one rectangle its two triangles reach kilometres past the
     * screen and behind the eye, and a GPU, working in single precision,
     * then places them coarsely in depth. On a phone that was enough for
     * what lay centimetres above the ground to flicker; a model of the
     * arithmetic puts the rectangle six to thirty depth steps off in a
     * typical view and hundreds in some, squares of 100 m at most three
     * from 90 m up.
     */
    fun groundGrid(size: Float, cells: Int): MeshData {
        val side = cells + 1
        val positions = FloatArray(side * side * 3)
        val normals = FloatArray(side * side * 3)
        val uvs = FloatArray(side * side * 2)
        for (j in 0 until side) for (i in 0 until side) {
            val k = j * side + i
            val u = i.toFloat() / cells; val v = j.toFloat() / cells
            positions[k * 3] = (u - 0.5f) * size; positions[k * 3 + 2] = (v - 0.5f) * size
            normals[k * 3 + 1] = 1f
            uvs[k * 2] = u; uvs[k * 2 + 1] = v
        }
        val indices = IntArray(cells * cells * 6)
        var next = 0
        for (j in 0 until cells) for (i in 0 until cells) {
            // The square's corners in the order of [groundPlane]'s, and its two triangles wound alike.
            val a = j * side + i; val b = a + 1; val c = b + side; val d = a + side
            for (corner in intArrayOf(a, c, b, a, d, c)) indices[next++] = corner
        }
        return MeshData(positions, normals, indices, uvs = uvs)
    }

    /** A unit quad in x-y (−0.5…0.5), for billboards. */
    fun billboardQuad(aspect: Float): MeshData {
        val x = 0.5f; val y = 0.5f / aspect
        return MeshData(
            floatArrayOf(-x, -y, 0f, x, -y, 0f, x, y, 0f, -x, y, 0f),
            null,
            intArrayOf(0, 1, 2, 0, 2, 3),
            uvs = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
        )
    }

    /**
     * Polylines as thin square tubes in one geometry, so lines have a real
     * thickness. [colors], when given, holds an RGBA per point of each run.
     */
    fun tubes(runs: List<FloatArray>, radius: Float, colors: List<FloatArray>? = null): MeshData? {
        val p = FloatList(); val n = FloatList(); val c = if (colors != null) FloatList() else null
        val idx = ArrayList<Int>()
        val r = max(radius, 0.01f)
        for ((runIndex, run) in runs.withIndex()) {
            val count = run.size / 3
            if (count < 2) continue
            val base = p.size / 3
            val runColors = colors?.getOrNull(runIndex)
            for (i in 0 until count) {
                val a = maxOf(i - 1, 0) * 3; val b = minOf(i + 1, count - 1) * 3
                var dx = run[b] - run[a]; var dy = run[b + 1] - run[a + 1]; var dz = run[b + 2] - run[a + 2]
                val l = sqrt(dx * dx + dy * dy + dz * dz)
                if (l > 1e-6f) { dx /= l; dy /= l; dz /= l } else { dx = 0f; dy = 1f; dz = 0f }
                // side = d × up
                var sx = -dz; var sy = 0f; var sz = dx
                var sl = sqrt(sx * sx + sz * sz)
                if (sl < 1e-4f) { sx = 1f; sz = 0f; sl = 1f }
                sx /= sl; sz /= sl
                // up = side × d
                var ux = sy * dz - sz * dy; var uy = sz * dx - sx * dz; var uz = sx * dy - sy * dx
                val ul = sqrt(ux * ux + uy * uy + uz * uz)
                ux /= ul; uy /= ul; uz /= ul
                val offsets = arrayOf(
                    floatArrayOf(sx, sy, sz), floatArrayOf(ux, uy, uz),
                    floatArrayOf(-sx, -sy, -sz), floatArrayOf(-ux, -uy, -uz),
                )
                for (o in offsets) {
                    p.add(run[i * 3] + o[0] * r, run[i * 3 + 1] + o[1] * r, run[i * 3 + 2] + o[2] * r)
                    n.add(o[0], o[1], o[2])
                    if (c != null) {
                        if (runColors != null && runColors.size >= i * 4 + 4) {
                            c.add(runColors[i * 4]); c.add(runColors[i * 4 + 1]); c.add(runColors[i * 4 + 2]); c.add(runColors[i * 4 + 3])
                        } else { c.add(1f); c.add(1f); c.add(1f); c.add(1f) }
                    }
                }
                if (i > 0) {
                    val ring = base + i * 4
                    val last = ring - 4
                    for (k in 0 until 4) {
                        val k2 = (k + 1) % 4
                        val a1 = last + k; val b1 = last + k2; val c1 = ring + k2; val d1 = ring + k
                        idx += listOf(a1, b1, c1, a1, c1, d1)
                    }
                }
            }
        }
        if (idx.isEmpty()) return null
        return MeshData(p.data.copyOf(p.size), n.data.copyOf(n.size), idx.toIntArray(), c?.let { it.data.copyOf(it.size) })
    }

    /**
     * A straight run from one point to another as points no more than
     * [step] apart, so that [tubes] makes it of short pieces: one piece a
     * kilometre long is placed in depth as coarsely as the ground's two
     * triangles were (see [groundGrid]).
     */
    fun straightRun(from: FloatArray, to: FloatArray, step: Float): FloatArray {
        val dx = to[0] - from[0]; val dy = to[1] - from[1]; val dz = to[2] - from[2]
        val pieces = max(1, kotlin.math.ceil(sqrt(dx * dx + dy * dy + dz * dz) / step).toInt())
        val points = FloatArray((pieces + 1) * 3)
        for (i in 0..pieces) {
            val t = i.toFloat() / pieces
            points[i * 3] = from[0] + dx * t; points[i * 3 + 1] = from[1] + dy * t; points[i * 3 + 2] = from[2] + dz * t
        }
        return points
    }

    /** Breaks a polyline into dashes, each a two-point run. */
    fun dashedRuns(points: FloatArray, dash: Float, gap: Float): List<FloatArray> {
        val runs = ArrayList<FloatArray>()
        val count = points.size / 3
        if (count < 2) return runs
        var phase = 0f
        for (i in 0 until count - 1) {
            val sx = points[i * 3]; val sy = points[i * 3 + 1]; val sz = points[i * 3 + 2]
            val ex = points[i * 3 + 3]; val ey = points[i * 3 + 4]; val ez = points[i * 3 + 5]
            val length = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy) + (ez - sz) * (ez - sz))
            if (length <= 0f) continue
            val dx = (ex - sx) / length; val dy = (ey - sy) / length; val dz = (ez - sz) / length
            var position = 0f
            while (position < length) {
                val remaining = length - position
                if (phase < dash) {
                    val run = minOf(dash - phase, remaining)
                    runs += floatArrayOf(
                        sx + dx * position, sy + dy * position, sz + dz * position,
                        sx + dx * (position + run), sy + dy * (position + run), sz + dz * (position + run),
                    )
                    position += run; phase += run
                } else {
                    val run = minOf(dash + gap - phase, remaining)
                    position += run; phase += run
                }
                if (phase >= dash + gap) phase = 0f
            }
        }
        return runs
    }
}
