package com.skyalt.canopy.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.Matrix
import com.skyalt.canopy.model.AircraftLiverySettings
import com.skyalt.canopy.model.AircraftLogoPlacement
import com.skyalt.canopy.model.AircraftMarkingsSettings
import com.skyalt.canopy.model.Airfield
import com.skyalt.canopy.model.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// The airfield, the P750, people, the other jumpers and the exit picture,
// built as lists of parts the way the iOS scene builds SceneKit nodes. Port
// of the model builders in CanopySceneController.swift and CanopyExitScene.swift.

/** Pixels uploaded as they are (not premultiplied), for textures with soft alpha. */
class RawTexture(val width: Int, val height: Int, val rgba: ByteArray)

/** A piece of a model on the CPU: geometry, its place in the model, colour, lighting and an optional texture. */
class ModelPart(
    val mesh: MeshData,
    val model: FloatArray,
    val color: FloatArray,
    val lit: Boolean = true,
    val texture: Bitmap? = null,
    val raw: RawTexture? = null,
    val lines: Boolean = false,
)

/** A 4×4 transform built up the way SceneKit nodes nest: each call post-multiplies. */
class Xf {
    val m = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun t(x: Float, y: Float, z: Float) = apply { Matrix.translateM(m, 0, x, y, z) }
    fun t(v: V3) = t(v.x, v.y, v.z)

    /** Radians about an axis, right-handed. */
    fun r(angle: Float, x: Float, y: Float, z: Float) = apply {
        if (angle != 0f) Matrix.rotateM(m, 0, Math.toDegrees(angle.toDouble()).toFloat(), x, y, z)
    }

    fun s(x: Float, y: Float, z: Float) = apply { Matrix.scaleM(m, 0, x, y, z) }

    /** Turns [from] onto [to]. */
    fun align(from: V3, to: V3) = apply {
        val a = from.normalized(); val b = to.normalized()
        val axis = a.cross(b)
        val d = a.dot(b).coerceIn(-1f, 1f)
        if (axis.length < 1e-6f) {
            if (d < 0) {
                // Opposite: any axis at right angles.
                val other = if (abs(a.x) < 0.9f) V3(1f, 0f, 0f) else V3(0f, 1f, 0f)
                val perp = a.cross(other).normalized()
                r(PI.toFloat(), perp.x, perp.y, perp.z)
            }
            return@apply
        }
        val n = axis.normalized()
        r(acos(d), n.x, n.y, n.z)
    }

    /** Columns of a rotation (and the origin), as SceneKit's simdTransform. */
    fun basis(xAxis: V3, yAxis: V3, zAxis: V3, origin: V3) = apply {
        val b = floatArrayOf(
            xAxis.x, xAxis.y, xAxis.z, 0f,
            yAxis.x, yAxis.y, yAxis.z, 0f,
            zAxis.x, zAxis.y, zAxis.z, 0f,
            origin.x, origin.y, origin.z, 1f,
        )
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, m, 0, b, 0)
        out.copyInto(m)
    }

    fun copy() = Xf().also { m.copyInto(it.m) }
}

fun rgba(hex: Int, alpha: Float = 1f) = floatArrayOf(((hex shr 16) and 0xff) / 255f, ((hex shr 8) and 0xff) / 255f, (hex and 0xff) / 255f, alpha)

/** Rotates [v] by [angle] radians about +y, right-handed. */
fun rotY(v: V3, angle: Float): V3 {
    val c = cos(angle); val s = sin(angle)
    return V3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c)
}

/** Rotates [v] by [angle] radians about +x, right-handed. */
fun rotX(v: V3, angle: Float): V3 {
    val c = cos(angle); val s = sin(angle)
    return V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
}

/** More geometry in SceneKit's shapes. */
object Shapes {
    /** SCNPlane: [w] along x, [h] along y, facing +z, the picture's top at +y. */
    fun plane(w: Float, h: Float): MeshData {
        val x = w / 2; val y = h / 2
        return MeshData(
            floatArrayOf(-x, -y, 0f, x, -y, 0f, x, y, 0f, -x, y, 0f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
            intArrayOf(0, 1, 2, 0, 2, 3),
            uvs = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
        )
    }

    fun cylinder(r: Float, h: Float, segments: Int = 20) = Primitives.cone(r, r, h, segments)

    /** SCNCapsule: [height] end to end along y, caps included. */
    fun capsule(r: Float, height: Float, segments: Int = 16, rings: Int = 6): MeshData {
        val half = max(0f, height / 2 - r)
        val p = FloatList(); val n = FloatList(); val idx = ArrayList<Int>()
        // Rings from the top pole to the bottom pole; the upper hemisphere sits half up, the lower half down.
        val rows = ArrayList<Pair<Float, Float>>() // (polar angle, y offset)
        for (i in 0..rings) rows += (PI.toFloat() / 2 * i / rings) to half
        for (i in 0..rings) rows += (PI.toFloat() / 2 + PI.toFloat() / 2 * i / rings) to -half
        for ((phi, offset) in rows) {
            for (j in 0..segments) {
                val theta = 2 * PI.toFloat() * j / segments
                val nx = sin(phi) * cos(theta); val ny = cos(phi); val nz = sin(phi) * sin(theta)
                p.add(nx * r, ny * r + offset, nz * r)
                n.add(nx, ny, nz)
            }
        }
        val rowCount = rows.size
        for (i in 0 until rowCount - 1) for (j in 0 until segments) {
            val a = i * (segments + 1) + j
            val b = a + segments + 1
            idx += listOf(a, b, a + 1, b, b + 1, a + 1)
        }
        return MeshData(p.data.copyOf(p.size), n.data.copyOf(n.size), idx.toIntArray())
    }

    /** A mesh from vertices and triangles, with smoothed normals unless given. */
    fun mesh(vertices: List<V3>, indices: List<Int>, normals: List<V3>? = null): MeshData {
        val pos = FloatArray(vertices.size * 3)
        for ((i, v) in vertices.withIndex()) { pos[i * 3] = v.x; pos[i * 3 + 1] = v.y; pos[i * 3 + 2] = v.z }
        val idx = indices.toIntArray()
        val nrm = FloatArray(vertices.size * 3)
        if (normals != null) {
            for ((i, v) in normals.withIndex()) { nrm[i * 3] = v.x; nrm[i * 3 + 1] = v.y; nrm[i * 3 + 2] = v.z }
        } else {
            CanopyMeshBuilder.computeNormals(pos, idx, nrm)
        }
        return MeshData(pos, nrm, idx)
    }

    /**
     * A surface through rings of equal point counts, each ring closed, every
     * triangle turned to face away from its rings' centre; the ends are
     * capped when [capped].
     */
    fun loft(rings: List<List<V3>>, capped: Boolean): MeshData? {
        if (rings.size < 2) return null
        val n = rings[0].size
        if (n < 3 || rings.any { it.size != n }) return null
        val vertices = ArrayList<V3>(rings.flatten())
        val indices = ArrayList<Int>()
        val centres = rings.map { ring -> ring.fold(V3.ZERO) { a, b -> a + b } / n.toFloat() }
        fun triangle(a: Int, b: Int, c: Int, outward: V3) {
            val normal = (vertices[b] - vertices[a]).cross(vertices[c] - vertices[a])
            if (normal.dot(outward) >= 0) indices += listOf(a, b, c) else indices += listOf(a, c, b)
        }
        for (i in 1 until rings.size) {
            val first = (i - 1) * n
            val second = i * n
            val centre = (centres[i - 1] + centres[i]) / 2f
            for (k in 0 until n) {
                val k2 = (k + 1) % n
                val outward = vertices[first + k] - centre
                triangle(first + k, second + k, second + k2, outward)
                triangle(first + k, second + k2, first + k2, outward)
            }
        }
        if (capped) {
            for ((ring, neighbour) in listOf(0 to 1, rings.size - 1 to rings.size - 2)) {
                val centre = vertices.size
                vertices += centres[ring]
                val outward = centres[ring] - centres[neighbour]
                val base = ring * n
                for (k in 0 until n) triangle(centre, base + k, base + (k + 1) % n, outward)
            }
        }
        return mesh(vertices, indices)
    }

    /** A closed polygon in the x–y plane, extruded [depth] along z about z = 0, as SCNShape. */
    fun extrude(points: List<Pair<Float, Float>>, depth: Float): MeshData {
        val vertices = ArrayList<V3>(); val normals = ArrayList<V3>(); val indices = ArrayList<Int>()
        val tris = earClip(points)
        for ((z, nz) in listOf(depth / 2 to 1f, -depth / 2 to -1f)) {
            val base = vertices.size
            for ((x, y) in points) { vertices += V3(x, y, z); normals += V3(0f, 0f, nz) }
            for (t in tris.chunked(3)) indices += if (nz > 0) listOf(base + t[0], base + t[1], base + t[2]) else listOf(base + t[0], base + t[2], base + t[1])
        }
        val count = points.size
        for (i in 0 until count) {
            val (x0, y0) = points[i]; val (x1, y1) = points[(i + 1) % count]
            val edge = V3(x1 - x0, y1 - y0, 0f)
            val normal = V3(edge.y, -edge.x, 0f).normalized()
            val base = vertices.size
            vertices += listOf(V3(x0, y0, depth / 2), V3(x1, y1, depth / 2), V3(x1, y1, -depth / 2), V3(x0, y0, -depth / 2))
            repeat(4) { normals += normal }
            indices += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        return mesh(vertices, indices, normals)
    }

    /** Triangles of a simple polygon, by ear clipping. */
    private fun earClip(points: List<Pair<Float, Float>>): List<Int> {
        val n = points.size
        var area = 0f
        for (i in 0 until n) {
            val (x0, y0) = points[i]; val (x1, y1) = points[(i + 1) % n]
            area += x0 * y1 - x1 * y0
        }
        val ccw = area > 0
        val remaining = (0 until n).toMutableList()
        val out = ArrayList<Int>()
        fun cross(a: Int, b: Int, c: Int): Float {
            val (ax, ay) = points[a]; val (bx, by) = points[b]; val (cx, cy) = points[c]
            return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        }
        fun inside(p: Int, a: Int, b: Int, c: Int): Boolean {
            val d1 = cross(a, b, p); val d2 = cross(b, c, p); val d3 = cross(c, a, p)
            val neg = d1 < 0 || d2 < 0 || d3 < 0
            val pos = d1 > 0 || d2 > 0 || d3 > 0
            return !(neg && pos)
        }
        var guard = 0
        while (remaining.size > 3 && guard < 1000) {
            guard++
            var clipped = false
            for (i in remaining.indices) {
                val a = remaining[(i + remaining.size - 1) % remaining.size]
                val b = remaining[i]
                val c = remaining[(i + 1) % remaining.size]
                val turn = cross(a, b, c)
                if (if (ccw) turn <= 0 else turn >= 0) continue
                if (remaining.any { it != a && it != b && it != c && inside(it, a, b, c) }) continue
                out += listOf(a, b, c)
                remaining.removeAt(i)
                clipped = true
                break
            }
            if (!clipped) break
        }
        if (remaining.size == 3) out += remaining
        return out
    }

    /**
     * A closed ring around a rounded rectangle in the x–y plane at [z]: half
     * sizes [w] and [h] about a centre [c] up, corners of radius [r].
     */
    fun roundedRing(z: Float, w: Float, h: Float, c: Float, r: Float, count: Int = 24): List<V3> {
        val radius = min(r, min(w, h))
        return (0 until count).map { k ->
            val angle = 2 * PI.toFloat() * k / count
            val s = sin(angle); val t = cos(angle)
            V3((w - radius) * (if (t < 0) -1 else 1) + radius * t, c + (h - radius) * (if (s < 0) -1 else 1) + radius * s, z)
        }
    }

    /**
     * A closed ring around an aerofoil section at spanwise [x]: the chord runs
     * from [leading] towards +z, [thickness] of the chord deep with a flatter
     * underside, after the four-digit NACA thickness law.
     */
    fun aerofoilRing(x: Float, leading: V3, chord: Float, thickness: Float, count: Int = 20): List<V3> {
        val half = count / 2
        return (0 until count).map { k ->
            val top = k < half
            val u = if (top) k.toFloat() / half else (count - k).toFloat() / half
            val t = aerofoilThickness(u, thickness)
            V3(x, leading.y + (if (top) t else -0.55f * t) * chord, leading.z + u * chord)
        }
    }
}

/**
 * The half-depth of an aerofoil section [thickness] of the chord deep, as a
 * fraction of the chord, at [u] of the chord back from the leading edge:
 * the four-digit NACA thickness law.
 */
fun aerofoilThickness(u: Float, thickness: Float) =
    5 * thickness * (0.2969f * sqrt(u) - 0.1260f * u - 0.3516f * u * u + 0.2843f * u * u * u - 0.1036f * u * u * u * u)

/** A station of the fuselage's loft: half width [w] and half height [h] about a centre [c] up, corners of radius [r], at [z] (+ towards the tail). */
class FuselageSection(val z: Float, val w: Float, val h: Float, val c: Float, val r: Float)

/** The models of the scenes. */
object Models {
    /** The P750's fuselage stations: round at the cowling, boxy through the cabin, tapering up to the tail. */
    val FUSELAGE_SECTIONS = listOf(
        FuselageSection(-5.55f, 0.33f, 0.33f, 0.06f, 0.30f),
        FuselageSection(-5.0f, 0.50f, 0.48f, 0.06f, 0.40f),
        FuselageSection(-4.2f, 0.64f, 0.58f, 0.04f, 0.42f),
        FuselageSection(-3.5f, 0.74f, 0.62f, 0.02f, 0.36f),
        FuselageSection(-2.7f, 0.83f, 0.88f, 0f, 0.28f),
        FuselageSection(-1.2f, 0.84f, 0.88f, 0f, 0.26f),
        FuselageSection(1.5f, 0.84f, 0.88f, 0f, 0.26f),
        FuselageSection(2.8f, 0.62f, 0.64f, 0.18f, 0.26f),
        FuselageSection(4.2f, 0.36f, 0.42f, 0.34f, 0.20f),
        FuselageSection(5.5f, 0.14f, 0.22f, 0.46f, 0.10f),
    )

    /** The fin with its dorsal fillet in side view: z along the fuselage and y up, metres. */
    val FIN_OUTLINE = listOf(2.6f to 0.8f, 3.8f to 1.1f, 4.95f to 2.25f, 5.55f to 2.25f, 5.6f to 0.6f, 4.0f to 0.74f)

    /** How far the logo decals stand off the skin. */
    private const val LOGO_STANDOFF = 0.015f

    const val ORANGE = 0xf07f1a

    private fun lineRuns(points: List<V3>) = floatArrayOf(*points.flatMap { listOf(it.x, it.y, it.z) }.toFloatArray())

    /** A flat rectangle on the ground, [width] east–west and [length] north–south. */
    private fun flat(width: Double, length: Double, hex: Int, x: Double, z: Double, height: Float) =
        ModelPart(Primitives.groundPlane(width.toFloat(), length.toFloat()), Xf().t(x.toFloat(), height, z.toFloat()).m, rgba(hex), lit = true)

    /**
     * The airfield's flat parts (runway with its paint, apron, taxiways and
     * grass), drawn pulled forward in depth, and its hangar.
     */
    fun airfield(): Pair<List<ModelPart>, List<ModelPart>> {
        val flats = ArrayList<ModelPart>()
        val solids = ArrayList<ModelPart>()
        val runwayZ = Airfield.RUNWAY_CENTRE_Z
        val runwayLength = Airfield.RUNWAY_EAST_X - Airfield.RUNWAY_WEST_X
        val runwayCentreX = (Airfield.RUNWAY_EAST_X + Airfield.RUNWAY_WEST_X) / 2
        val area = Airfield
        flats += flat(area.LANDING_MAX_X - area.LANDING_MIN_X, area.LANDING_MAX_Z - area.LANDING_MIN_Z, 0x8db46a,
            (area.LANDING_MIN_X + area.LANDING_MAX_X) / 2, (area.LANDING_MIN_Z + area.LANDING_MAX_Z) / 2, 0.03f)
        val hangarCentreX = (Airfield.HANGAR_MIN_X + Airfield.HANGAR_MAX_X) / 2
        val pavement = 0x5a5e66
        flats += flat(Airfield.HANGAR_MAX_X - Airfield.HANGAR_MIN_X, Airfield.APRON_LENGTH, pavement,
            hangarCentreX, Airfield.HANGAR_MIN_Z - Airfield.APRON_LENGTH / 2, 0.05f)
        val taxiwayStart = Airfield.HANGAR_MIN_Z - Airfield.APRON_LENGTH
        val taxiwayLength = taxiwayStart - (Airfield.RUNWAY_CENTRE_Z + Airfield.RUNWAY_WIDTH / 2)
        for (x in Airfield.TAXIWAY_XS) {
            flats += flat(Airfield.TAXIWAY_WIDTH, taxiwayLength, pavement, x, taxiwayStart - taxiwayLength / 2, 0.05f)
        }
        flats += flat(runwayLength, Airfield.RUNWAY_WIDTH, 0x4a4e55, runwayCentreX, runwayZ, 0.06f)

        val paint = rgba(0xf4f4f0, 0.9f)
        val west = Airfield.RUNWAY_WEST_X.toFloat()
        val east = Airfield.RUNWAY_EAST_X.toFloat()
        val rz = runwayZ.toFloat()
        val halfWidth = (Airfield.RUNWAY_WIDTH / 2).toFloat()
        Primitives.tubes(
            Primitives.dashedRuns(floatArrayOf(west + 60, 0.14f, rz, east - 60, 0.14f, rz), 30f, 20f), 0.45f,
        )?.let { flats += ModelPart(it, Xf().m, paint, lit = false) }
        val edges = listOf(-1f, 1f).map { side ->
            floatArrayOf(west, 0.14f, rz + side * (halfWidth - 1), east, 0.14f, rz + side * (halfWidth - 1))
        }
        Primitives.tubes(edges, 0.3f)?.let { flats += ModelPart(it, Xf().m, paint, lit = false) }
        val bars = ArrayList<FloatArray>()
        for (endX in listOf(west + 10, east - 10)) for (k in 0 until 6) {
            val z = rz - halfWidth + 3 + k * 4.5f
            bars += floatArrayOf(endX - 8, 0.14f, z, endX + 8, 0.14f, z)
        }
        Primitives.tubes(bars, 0.8f)?.let { flats += ModelPart(it, Xf().m, paint, lit = false) }

        // The hangar: walls to the eaves, an orange gable roof with its ridge
        // running north to south, the west slope lettered AltiX, and the
        // door in the north wall facing the runway.
        val wallHeight = Airfield.HANGAR_WALL_HEIGHT.toFloat()
        val hw = Airfield.HANGAR_MIN_X.toFloat(); val he = Airfield.HANGAR_MAX_X.toFloat()
        val north = Airfield.HANGAR_MIN_Z.toFloat(); val south = Airfield.HANGAR_MAX_Z.toFloat()
        val hx = hangarCentreX.toFloat()
        solids += ModelPart(Primitives.box(he - hw, wallHeight, south - north), Xf().t(hx, wallHeight / 2, (north + south) / 2).m, rgba(0x9aa3ad))
        val eave = wallHeight
        val ridge = Airfield.HANGAR_HEIGHT.toFloat()
        solids += ModelPart(
            Shapes.mesh(
                listOf(V3(hw, eave, north), V3(he, eave, north), V3(hx, ridge, north), V3(hw, eave, south), V3(he, eave, south), V3(hx, ridge, south)),
                listOf(0, 2, 1, 3, 4, 5),
                listOf(V3(0f, 0f, -1f), V3(0f, 0f, -1f), V3(0f, 0f, -1f), V3(0f, 0f, 1f), V3(0f, 0f, 1f), V3(0f, 0f, 1f)),
            ),
            Xf().m, rgba(ORANGE),
        )
        val run = (he - hw) / 2
        val rise = ridge - eave
        val slopeLength = sqrt(run * run + rise * rise)
        val sign = roofSign(south - north, slopeLength)
        // Each slope is a plane laid along the ridge: its x runs along the
        // building (south on the west slope, so the lettering reads from the
        // landing area), its y up the slope.
        for (side in listOf(-1f, 1f)) {
            val along = V3(0f, 0f, -side)
            val up = V3(-side * run, rise, 0f).normalized()
            val normal = along.cross(up)
            val centre = V3(hx + side * run / 2, (eave + ridge) / 2, (north + south) / 2)
            solids += ModelPart(
                Shapes.plane(south - north, slopeLength), Xf().basis(along, up, normal, centre).m,
                if (side < 0) rgba(0xffffff) else rgba(ORANGE), texture = if (side < 0) sign else null,
            )
        }
        solids += ModelPart(Shapes.plane(40f, 10f), Xf().t(hx, 5.2f, north - 0.15f).r(PI.toFloat(), 0f, 1f, 0f).m, rgba(0x4d5560))
        return flats to solids
    }

    /** The lettering on the hangar's west slope: AltiX across the orange roof, black with a red X. */
    private fun roofSign(width: Float, height: Float): Bitmap {
        val w = 2048
        val h = max(64, (2048 * height / width).roundToInt())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0xf0, 0x7f, 0x1a))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 100f }
        val measured = paint.measureText("AltiX")
        val fm = paint.fontMetrics
        val scale = min(w * 0.9f / measured, h * 0.8f / (fm.descent - fm.ascent))
        paint.textSize = 100f * scale
        val total = paint.measureText("AltiX")
        val alti = paint.measureText("Alti")
        val m2 = paint.fontMetrics
        val baseline = h / 2f - (m2.ascent + m2.descent) / 2
        var x = (w - total) / 2
        paint.color = Color.BLACK
        canvas.drawText("Alti", x, baseline, paint)
        x += alti
        paint.color = Color.rgb(0xe2, 0x23, 0x1a)
        canvas.drawText("X", x, baseline, paint)
        return bitmap
    }

    /** The registration in heavy black letters on a clear ground, for the decals. */
    private fun registrationImage(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1024, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 300f; color = Color.BLACK; textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        val scale = min(1024 * 0.92f / max(paint.measureText(text), 1f), 400 * 0.9f / (fm.descent - fm.ascent))
        paint.textSize = 300f * scale
        val m2 = paint.fontMetrics
        canvas.drawText(text, 512f, 200f - (m2.ascent + m2.descent) / 2, paint)
        return bitmap
    }

    class Aircraft(val body: List<ModelPart>, val propeller: List<ModelPart>, val hub: V3)

    /**
     * The P750 after Pacific Aerospace's figures: a slab-sided cabin behind a
     * long turboprop nose, a low wing with dihedral on the outer panels, a
     * tall swept fin with a dorsal fillet, fixed tricycle gear and a
     * three-bladed propeller. Nose to −z, the fuselage's centre at the origin.
     * The wings and tail wear [livery] (0xRRGGBB); the fuselage stays white.
     */
    fun aircraft(
        livery: Int = AircraftLiverySettings.DEFAULT_COLOR_HEX,
        registration: String = AircraftMarkingsSettings.DEFAULT_REGISTRATION,
        logo: Bitmap? = null,
        logoPlacement: AircraftLogoPlacement = AircraftLogoPlacement(),
    ): Aircraft {
        val parts = ArrayList<ModelPart>()
        val white = rgba(0xf4f4f4)
        val paint = rgba(livery)
        val glass = rgba(0x1a1f26)
        val dark = rgba(0x30343a)
        val rubber = rgba(0x15171a)
        fun add(mesh: MeshData?, xf: Xf = Xf(), color: FloatArray, texture: Bitmap? = null) {
            if (mesh != null) parts += ModelPart(mesh, xf.m, color, texture = texture)
        }

        // The fuselage, lofted through its stations.
        add(Shapes.loft(FUSELAGE_SECTIONS.map { Shapes.roundedRing(it.z, it.w, it.h, it.c, it.r) }, capped = true), color = white)

        // The low wing: 2.2 m chord, 15% thick at the root, the outer panels raised five degrees.
        val dihedral = tan(5 * PI.toFloat() / 180)
        fun wingRing(x: Float): List<V3> {
            val outer = max(0f, abs(x) - 2.6f)
            val tip = abs(x) > 6.35f
            return Shapes.aerofoilRing(x, V3(0f, -0.78f + outer * dihedral, if (tip) -1.75f else -1.85f), if (tip) 2.0f else 2.2f, if (tip) 0.12f else 0.15f)
        }
        add(Shapes.loft(listOf(-6.4f, -2.6f, 2.6f, 6.4f).map(::wingRing), capped = true), color = paint)
        // The tailplane on top of the tail cone, tapered and swept a little.
        fun tailRing(x: Float): List<V3> {
            val u = abs(x) / 2.48f
            return Shapes.aerofoilRing(x, V3(0f, 0.7f, 4.55f + 0.3f * u), 1.25f - 0.35f * u, 0.1f)
        }
        add(Shapes.loft(listOf(-2.48f, 0f, 2.48f).map(::tailRing), capped = true), color = paint)
        // The fin with its dorsal fillet, drawn in side view and turned to stand along the fuselage.
        add(Shapes.extrude(FIN_OUTLINE, 0.14f), Xf().r(-PI.toFloat() / 2, 0f, 1f, 0f), paint)

        // The registration in black: on both sides of the rear fuselage, under the left wing and on top of
        // the right, with the tops of the letters towards the leading edge.
        val lettering = registrationImage(registration)
        val taper = atan2(0.26f, 1.4f)
        for (side in listOf(-1f, 1f)) {
            add(Shapes.plane(1.0f, 0.39f), Xf().t(side * 0.502f, 0.28f, 3.502f).r(side * (PI.toFloat() / 2 - taper), 0f, 1f, 0f), rgba(0xffffff), lettering)
        }
        add(
            Shapes.plane(2.4f, 0.94f),
            Xf().t(-4.45f, -0.78f + 1.85f * dihedral - 0.12f, -0.75f)
                .r(-5 * PI.toFloat() / 180, 0f, 0f, 1f).r(PI.toFloat(), 0f, 1f, 0f).r(PI.toFloat() / 2, 1f, 0f, 0f),
            rgba(0xffffff), lettering,
        )
        // On the upper surface, just clear of the camber and tipped 4° nose-up to follow it down towards the trailing edge.
        add(
            Shapes.plane(2.4f, 0.94f),
            Xf().t(4.45f, -0.78f + 1.85f * dihedral + 0.15f, -0.75f)
                .r(5 * PI.toFloat() / 180, 0f, 0f, 1f).r(-PI.toFloat() / 2 + 4 * PI.toFloat() / 180, 1f, 0f, 0f),
            rgba(0xffffff), lettering,
        )
        // The logo on both sides of the fuselage.
        if (logo != null) {
            for (side in listOf(-1f, 1f)) add(logoDecal(logo, logoPlacement, side), color = rgba(0xffffff), texture = logo)
        }

        // The windshield and the cabin windows (four a side, three on the left ahead of the jump door).
        add(Primitives.box(1.55f, 0.5f, 0.06f), Xf().t(0f, 0.62f, -3.05f).r(0.55f, 1f, 0f, 0f), glass)
        for (side in listOf(-1f, 1f)) {
            val stations = if (side > 0) listOf(-2.05f, -1.2f, -0.35f, 0.5f) else listOf(-2.05f, -1.2f, -0.35f)
            for (z in stations) add(Shapes.plane(0.6f, 0.48f), Xf().t(side * 0.85f, 0.32f, z).r(side * PI.toFloat() / 2, 0f, 1f, 0f), glass)
        }
        add(Shapes.plane(1.3f, 1.15f), Xf().t(-0.85f, -0.12f, 0.8f).r(-PI.toFloat() / 2, 0f, 1f, 0f), rgba(0x3a3f47))

        // The spinner and the exhausts either side of the cowling.
        add(Primitives.cone(0.04f, 0.3f, 0.55f), Xf().t(0f, 0.06f, -5.83f).r(-PI.toFloat() / 2, 1f, 0f, 0f), dark)
        for (side in listOf(-1f, 1f)) {
            add(Shapes.cylinder(0.09f, 0.5f), Xf().t(side * 0.6f, -0.1f, -4.55f).r(1.3f, 1f, 0f, 0f).r(side * 0.35f, 0f, 0f, 1f), dark)
        }

        // Fixed tricycle gear: the nose wheel under the engine and the main wheels on legs under the wings.
        fun wheel(radius: Float, width: Float, at: V3) =
            add(Shapes.cylinder(radius, width), Xf().t(at).r(PI.toFloat() / 2, 0f, 0f, 1f), rubber)
        fun leg(top: V3, bottom: V3, radius: Float) {
            val d = bottom - top
            add(Shapes.cylinder(radius, d.length, 10), Xf().t((top + bottom) / 2f).align(V3(0f, 1f, 0f), d), dark)
        }
        wheel(0.26f, 0.18f, V3(0f, -1.34f, -4.1f))
        leg(V3(0f, -0.5f, -4.1f), V3(0f, -1.34f, -4.1f), 0.06f)
        for (side in listOf(-1f, 1f)) {
            wheel(0.32f, 0.24f, V3(side * 1.59f, -1.28f, -0.9f))
            leg(V3(side * 1.45f, -0.84f, -0.9f), V3(side * 1.59f, -1.28f, -0.9f), 0.07f)
            add(Primitives.box(0.3f, 0.12f, 0.5f), Xf().t(side * 1.45f, -0.86f, -0.9f), dark)
        }

        // The three-bladed propeller, turning about the hub.
        val propeller = ArrayList<ModelPart>()
        val blade = Primitives.box(0.2f, 1.15f, 0.05f)
        for (k in 0 until 3) {
            propeller += ModelPart(
                blade,
                Xf().r(k * 2 * PI.toFloat() / 3, 0f, 0f, 1f).r(0.45f, 0f, 1f, 0f).t(0f, 0.77f, 0f).m,
                dark,
            )
        }
        return Aircraft(parts, propeller, V3(0f, 0.06f, -5.62f))
    }

    /**
     * A patch of the fuselage's skin on one side ([side] −1 left, +1 right),
     * [LOGO_STANDOFF] out from it, carrying the picture: [placement]'s width
     * along the fuselage and as tall as the picture's shape makes it,
     * following the taper and the rounded corners, the picture reading the
     * right way round from that side.
     */
    fun logoDecal(image: Bitmap, placement: AircraftLogoPlacement, side: Float): MeshData? {
        val width = placement.width.toFloat()
        if (image.width <= 0 || image.height <= 0 || width <= 0) return null
        val height = width * image.height / image.width
        val z0 = placement.along.toFloat() - width / 2
        val z1 = placement.along.toFloat() + width / 2
        val y0 = placement.height.toFloat() - height / 2
        val y1 = placement.height.toFloat() + height / 2
        // Columns at the ends, at every station between, and no more than 10 cm apart; rows no more than 5 cm apart.
        val stations = (listOf(z0, z1) + FUSELAGE_SECTIONS.map { it.z }.filter { it > z0 && it < z1 }).sorted()
        val columns = ArrayList<Float>()
        for (i in 0 until stations.size - 1) {
            val a = stations[i]; val b = stations[i + 1]
            val steps = max(1, kotlin.math.ceil((b - a) / 0.1f).toInt())
            for (k in 0 until steps) columns += a + (b - a) * k / steps
        }
        columns += z1
        val rowCount = max(2, kotlin.math.ceil(height / 0.05f).toInt() + 1)
        val rows = (0 until rowCount).map { y0 + (y1 - y0) * it / (rowCount - 1) }
        val positions = FloatList(); val normals = FloatList(); val uvs = FloatList()
        val vertices = ArrayList<V3>(); val vertexNormals = ArrayList<V3>()
        for (z in columns) for (y in rows) {
            val (point, normal) = fuselageSkin(z, y, side)
            val v = point + normal * LOGO_STANDOFF
            vertices += v; vertexNormals += normal
            positions.add(v.x, v.y, v.z)
            normals.add(normal.x, normal.y, normal.z)
            uvs.add(if (side < 0) (z - z0) / width else (z1 - z) / width)
            uvs.add((y1 - y) / height)
        }
        val indices = ArrayList<Int>()
        fun triangle(i: Int, j: Int, k: Int) {
            val n = (vertices[j] - vertices[i]).cross(vertices[k] - vertices[i])
            indices += if (n.dot(vertexNormals[i]) >= 0) listOf(i, j, k) else listOf(i, k, j)
        }
        for (column in 0 until columns.size - 1) for (row in 0 until rowCount - 1) {
            val a = column * rowCount + row
            val b = a + rowCount
            triangle(a, b, b + 1)
            triangle(a, b + 1, a + 1)
        }
        return MeshData(positions.data.copyOf(positions.size), normals.data.copyOf(normals.size), indices.toIntArray(), uvs = uvs.data.copyOf(uvs.size))
    }

    /**
     * The point on the fuselage's skin at [z], [y] on the right (+1) or left
     * (−1) side and the skin's outward normal there: the loft between the
     * stations either side of [z], as [aircraft] builds it, with [y] held
     * within the ring.
     */
    fun fuselageSkin(z: Float, y: Float, side: Float): Pair<V3, V3> {
        val sections = FUSELAGE_SECTIONS
        val zc = z.coerceIn(sections.first().z, sections.last().z)
        var lower = sections.first()
        var upper = sections.last()
        for (i in 0 until sections.size - 1) {
            if (zc >= sections[i].z && zc <= sections[i + 1].z) { lower = sections[i]; upper = sections[i + 1]; break }
        }
        val f = if (upper.z > lower.z) (zc - lower.z) / (upper.z - lower.z) else 0f
        fun mix(a: Float, b: Float) = a + (b - a) * f
        val centre = mix(lower.c, upper.c)
        val half = mix(lower.h, upper.h)
        val yc = y.coerceIn(centre - half * 0.98f, centre + half * 0.98f)
        val ring = Shapes.roundedRing(zc, mix(lower.w, upper.w), half, centre, mix(lower.r, upper.r))
        for (k in ring.indices) {
            val a = ring[k]
            val b = ring[(k + 1) % ring.size]
            if ((a.x + b.x) * side <= 0 || abs(b.y - a.y) <= 1e-4f) continue
            if (yc < min(a.y, b.y) - 1e-5f || yc > max(a.y, b.y) + 1e-5f) continue
            val t = ((yc - a.y) / (b.y - a.y)).coerceIn(0f, 1f)
            val point = a + (b - a) * t
            var normal = V3(b.y - a.y, -(b.x - a.x), 0f)
            if (normal.dot(V3(point.x, point.y - centre, 0f)) < 0) normal = normal * -1f
            return point to (if (normal.length > 1e-6f) normal.normalized() else V3(side, 0f, 0f))
        }
        return V3(side * mix(lower.w, upper.w), yc, zc) to V3(side, 0f, 0f)
    }

    /** Someone standing: legs, a jacket and a head. */
    fun person(jacket: Int): List<ModelPart> = listOf(
        ModelPart(Shapes.cylinder(0.17f, 0.8f, 12), Xf().t(0f, 0.4f, 0f).m, rgba(0x2b3a4f)),
        ModelPart(Shapes.capsule(0.22f, 0.8f), Xf().t(0f, 1.15f, 0f).m, rgba(jacket)),
        ModelPart(Primitives.sphere(0.13f, 8, 12), Xf().t(0f, 1.68f, 0f).m, rgba(0xd9a98a)),
    )

    /** A simpler jumper than the player's: body, helmet, legs and container, no arms; the harness at the origin. */
    fun trafficJumper(suit: Int): List<ModelPart> {
        val leg = Primitives.cone(0.06f, 0.05f, 0.7f, 10)
        return listOf(
            ModelPart(Primitives.box(0.36f, 0.5f, 0.22f), Xf().m, rgba(suit)),
            ModelPart(Primitives.sphere(0.13f, 8, 12), Xf().t(0f, 0.42f, 0f).m, rgba(0xe8e8e8)),
            ModelPart(leg, Xf().t(-0.1f, -0.6f, 0f).m, rgba(suit)),
            ModelPart(leg, Xf().t(0.1f, -0.6f, 0f).m, rgba(suit)),
            ModelPart(Primitives.box(0.34f, 0.48f, 0.2f), Xf().t(0f, -0.02f, 0.21f).m, rgba(0x3a3f47)),
        )
    }

    // MARK: - The exit picture

    /** The centre of the jump door, in the aircraft's frame. */
    val DOOR_CENTRE = V3(-0.85f, -0.12f, 0.8f)
    /** The student, 20 ft left, below and behind the door. */
    val STUDENT_OFFSET = DOOR_CENTRE + V3(-4.6f, -2.8f, 2.8f)
    /** The instructors' hips either side of the student's, in the student's frame. */
    private val INSTRUCTOR_HIPS = V3(1.15f, -0.15f, -0.3f)
    /** The formation turned in the student's belly plane, anticlockwise seen from their back. */
    private const val FORMATION_TURN = (PI / 4).toFloat()
    /** Where the instructors take hold of the student (right side; mirrored for the left). */
    private val UPPER_ARM_GRIP = V3(0.35f, 0.29f, -0.51f)
    private val THIGH_GRIP = V3(0.16f, 0.09f, 0.29f)

    /**
     * The AFF exit out of the door: the student in the arch and an
     * instructor either side lying across them, one hand on the student's
     * upper arm and the other on the thigh, the formation turned 45°.
     */
    fun exitFormation(): List<ModelPart> {
        val group = Xf().t(STUDENT_OFFSET).r(0.6f, 1f, 0f, 0f).r(FORMATION_TURN, 0f, 1f, 0f)
        val parts = ArrayList<ModelPart>()
        fun place(local: List<ModelPart>, xf: Xf) {
            for (part in local) {
                val m = FloatArray(16)
                Matrix.multiplyMM(m, 0, xf.m, 0, part.model, 0)
                parts += ModelPart(part.mesh, m, part.color, part.lit, part.texture, part.raw, part.lines)
            }
        }
        place(archedJumper(0xd8342c), group)
        for (side in listOf(-1f, 1f)) {
            val position = V3(side * INSTRUCTOR_HIPS.x, INSTRUCTOR_HIPS.y, INSTRUCTOR_HIPS.z)
            val angle = side * PI.toFloat() / 2
            val upperArm = V3(side * UPPER_ARM_GRIP.x, UPPER_ARM_GRIP.y, UPPER_ARM_GRIP.z)
            val thigh = V3(side * THIGH_GRIP.x, THIGH_GRIP.y, THIGH_GRIP.z)
            val armHand = rotY(upperArm - position, -angle)
            val thighHand = rotY(thigh - position, -angle)
            val instructor = archedJumper(
                0x2a5db0,
                rightHand = if (side > 0) armHand else thighHand,
                leftHand = if (side > 0) thighHand else armHand,
            )
            place(instructor, group.copy().t(position).r(angle, 0f, 1f, 0f))
        }
        return parts
    }

    /** Where the exit picture's camera looks. */
    val EXIT_TARGET get() = STUDENT_OFFSET + V3(0f, -0.2f, 0f)

    /**
     * A jumper in the arch: hips forward, chest up, arms bent with the hands
     * forward, knees bent with the feet up; head towards −z, back towards
     * +y, the rig on it. A hand given a place reaches there instead.
     */
    fun archedJumper(suitHex: Int, rightHand: V3? = null, leftHand: V3? = null): List<ModelPart> {
        val parts = ArrayList<ModelPart>()
        val suit = rgba(suitHex)
        val gloves = rgba(0x1a1f26)
        fun limb(a: V3, b: V3, radius: Float, color: FloatArray) {
            val d = b - a
            parts += ModelPart(Shapes.capsule(radius, d.length + 2 * radius, 12, 4), Xf().t((a + b) / 2f).align(V3(0f, 1f, 0f), d).m, color)
        }
        fun ball(radius: Float, at: V3, color: FloatArray) {
            parts += ModelPart(Primitives.sphere(radius, 10, 16), Xf().t(at).m, color)
        }
        val hips = V3(0f, 0f, 0f)
        val shoulders = V3(0f, 0.22f, -0.55f)
        limb(hips, shoulders, 0.17f, suit)
        val neck = shoulders + V3(0f, 0.1f, -0.12f)
        ball(0.13f, neck + V3(0f, 0.06f, -0.1f), rgba(0xf2f2f2))
        ball(0.09f, neck + V3(0f, 0f, -0.2f), gloves)
        for (side in listOf(-1f, 1f)) {
            val shoulder = shoulders + V3(side * 0.2f, 0.02f, 0f)
            val grip = if (side > 0) rightHand else leftHand
            val elbow: V3
            val hand: V3
            if (grip != null) {
                hand = grip
                elbow = exitElbow(shoulder, hand, side)
            } else {
                elbow = shoulder + V3(side * 0.3f, 0.1f, 0.08f)
                hand = elbow + V3(side * 0.06f, 0.12f, -0.28f)
            }
            limb(shoulder, elbow, 0.055f, suit)
            limb(elbow, hand, 0.05f, suit)
            ball(0.055f, hand, gloves)
            val hip = hips + V3(side * 0.11f, 0f, 0.08f)
            val knee = hip + V3(side * 0.1f, 0.18f, 0.42f)
            val foot = knee + V3(side * 0.02f, 0.32f, 0.26f)
            limb(hip, knee, 0.075f, suit)
            limb(knee, foot, 0.06f, suit)
            parts += ModelPart(Primitives.box(0.1f, 0.08f, 0.24f), Xf().t(foot + V3(0f, 0.02f, 0.06f)).r(-0.7f, 1f, 0f, 0f).m, gloves)
        }
        // The rig on the back, lying along the torso.
        val torso = (shoulders - hips).normalized()
        parts += ModelPart(
            Primitives.box(0.38f, 0.2f, 0.5f),
            Xf().t((hips + shoulders) / 2f + V3(0f, 0.25f, -0.02f)).align(V3(0f, 0f, -1f), torso).m,
            rgba(0x3a3f47),
        )
        return parts
    }

    /** The elbow for 0.3 m bones between [shoulder] and [hand]: dropped towards the belly (−y); straight out of reach. */
    private fun exitElbow(shoulder: V3, hand: V3, side: Float): V3 {
        val limb = 0.3f
        val span = hand - shoulder
        val distance = span.length
        val middle = (shoulder + hand) / 2f
        if (distance <= 0.01f || distance >= 2 * limb - 0.01f) return middle
        val along = span / distance
        val down = V3(0f, -1f, 0f)
        var drop = down - along * down.dot(along)
        if (drop.length < 0.05f) {
            val out = V3(side, 0f, 0f)
            drop = out - along * out.dot(along)
        }
        drop = drop.normalized()
        val reach = sqrt(limb * limb - distance * distance / 4)
        return middle + drop * reach
    }

    // MARK: - Clouds

    private const val CLOUD_NOISE_SIZE = 256

    /** Tileable value noise, 0…1, computed once. */
    private val cloudNoise: FloatArray by lazy {
        val size = CLOUD_NOISE_SIZE
        var seed = 0x9E3779B97F4A7C15uL
        fun random(): Float {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed shr 7)
            seed = seed xor (seed shl 17)
            return (seed % 10_000uL).toFloat() / 10_000
        }
        val noise = FloatArray(size * size)
        var amplitude = 1f
        for (cells in intArrayOf(4, 8, 16, 32)) {
            val lattice = FloatArray(cells * cells) { random() }
            val step = size / cells
            for (y in 0 until size) {
                val gy = y / step
                val ty = (y % step).toFloat() / step
                val sy = ty * ty * (3 - 2 * ty)
                val row0 = (gy % cells) * cells
                val row1 = ((gy + 1) % cells) * cells
                for (x in 0 until size) {
                    val gx = x / step
                    val tx = (x % step).toFloat() / step
                    val sx = tx * tx * (3 - 2 * tx)
                    val a = lattice[row0 + gx % cells]
                    val b = lattice[row0 + (gx + 1) % cells]
                    val c = lattice[row1 + gx % cells]
                    val d = lattice[row1 + (gx + 1) % cells]
                    val top = a + (b - a) * sx
                    val bottom = c + (d - c) * sx
                    noise[y * size + x] += (top + (bottom - top) * sy) * amplitude
                }
            }
            amplitude *= 0.5f
        }
        var low = Float.MAX_VALUE; var high = -Float.MAX_VALUE
        for (v in noise) { low = min(low, v); high = max(high, v) }
        val span = max(high - low, 1e-6f)
        FloatArray(noise.size) { (noise[it] - low) / span }
    }

    /** The layer's texture: the noise above a threshold that falls with the density, in the tint. */
    fun cloudTexture(density: Double, tint: FloatArray): RawTexture {
        val size = CLOUD_NOISE_SIZE
        val noise = cloudNoise
        val low = (0.85 * (1 - density)).toFloat()
        val high = low + 0.3f
        val bytes = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            val t = ((noise[i] - low) / (high - low)).coerceIn(0f, 1f)
            val alpha = t * t * (3 - 2 * t)
            bytes[i * 4] = (tint[0] * 255).roundToInt().toByte()
            bytes[i * 4 + 1] = (tint[1] * 255).roundToInt().toByte()
            bytes[i * 4 + 2] = (tint[2] * 255).roundToInt().toByte()
            bytes[i * 4 + 3] = (alpha * 255).roundToInt().toByte()
        }
        return RawTexture(size, size, bytes)
    }

    /** The line runs of a mesh builder as a GL_LINES mesh. */
    fun lineMesh(segments: FloatList): MeshData = MeshData(segments.data.copyOf(segments.size), null, null)

    @Suppress("unused")
    private fun unused() = lineRuns(emptyList())
}
