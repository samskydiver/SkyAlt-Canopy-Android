package com.skyalt.canopy.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.Matrix
import com.skyalt.canopy.model.AircraftLogoPlacement
import com.skyalt.canopy.model.Airfield
import com.skyalt.canopy.model.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The airfield, the P750XL's parts, people, the other jumpers and the exit picture,
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
}

/** The models of the scenes. */
object Models {
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

    /** The P750XL's airframe, the wings and tail with their control surfaces in [livery] (0xRRGGBB), the fuselage silver. */
    fun aircraftBody(prepared: AircraftModel.Prepared, livery: Int): List<ModelPart> = prepared.body.map { piece ->
        ModelPart(piece.mesh(), Xf().m, if (piece.paint == AircraftModel.Paint.LIVERY) rgba(livery) else piece.color)
    }

    /** The registration patches on the rear fuselage and the wings, lettered [registration]. */
    fun aircraftRegistration(prepared: AircraftModel.Prepared, registration: String): List<ModelPart> {
        val lettering = registrationImage(registration)
        return prepared.registration.map { ModelPart(it.mesh(), Xf().m, rgba(0xffffff), texture = lettering) }
    }

    /** The logo on both sides of the fuselage at [placement], or nothing without one. */
    fun aircraftLogo(logo: Bitmap?, placement: AircraftLogoPlacement): List<ModelPart> {
        if (logo == null) return emptyList()
        return listOf(-1f, 1f).mapNotNull { side ->
            AircraftModel.logoDecal(logo.width, logo.height, placement, side)?.let { d ->
                ModelPart(MeshData(d.positions, d.normals, d.indices, uvs = d.uvs), Xf().m, rgba(0xffffff), texture = logo)
            }
        }
    }

    /** The blades about the hub ([AircraftModel.PROPELLER_HUB]), to be turned about its z axis. */
    fun aircraftPropeller(prepared: AircraftModel.Prepared): List<ModelPart> =
        prepared.blades.map { ModelPart(it.mesh(), Xf().m, it.color) }

    private fun AircraftModel.Piece.mesh() = MeshData(positions, normals, indices, uvs = uvs)

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
    val DOOR_CENTRE = AircraftModel.DOOR_CENTRE
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
