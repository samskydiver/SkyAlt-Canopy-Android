package com.skyalt.canopy.scene

import com.skyalt.canopy.model.AircraftFlight
import com.skyalt.canopy.model.AircraftLogoPlacement
import com.skyalt.canopy.model.V3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The scenes' jump plane, a PAC 750XL: the bundled model aircraft/P750XL.glb,
 * rebuilt in Blender from a photograph of one and the type certificate's
 * figures (12.80 m span, 11.84 m long, 4.04 m tall), exported without that
 * aircraft's lettering and with four blank patches where the registration
 * goes. [prepare] sorts the file's parts into what the app paints; it is
 * plain Kotlin, so the unit tests read the bundled file too. Port of
 * CanopyAircraftModel.swift.
 *
 * The aircraft's frame: nose to −z, right wing +x, y up, the origin
 * [AircraftFlight.GROUND_HEIGHT] above the ground, so the wheels stand on it.
 */
object AircraftModel {
    const val ASSET = "aircraft/P750XL.glb"

    /** Where the file's origin, on the propeller's axis at mid-length 1.717 m above the ground, sits in the aircraft's frame. */
    val MODEL_OFFSET = V3(0f, (1.717 - AircraftFlight.GROUND_HEIGHT).toFloat(), 0f)
    /** The propeller's hub; the blades turn about the z axis through it. */
    val PROPELLER_HUB = MODEL_OFFSET + V3(0f, 0f, -5.567f)
    /** The middle of the jump door's opening on the left side, 1.27 m wide just behind the wing. */
    val DOOR_CENTRE = V3(-0.668f, 0.105f, 0.918f)

    /** The parts painted in the livery colour: the wings and the tail with their control surfaces, but not their white tips. */
    private val LIVERY_PARTS = setOf(
        "Wing", "FlapL", "FlapR", "AileronL", "AileronR",
        "Tailplane", "ElevatorL", "ElevatorR", "Fin", "Rudder",
    )
    /** The file's material for the skin the livery replaces, and for the registration patches. */
    private const val SKIN_MATERIAL = "Polished"
    private const val REGISTRATION_MATERIAL = "Registration"
    /** The node holding the blades, at the hub. */
    private const val PROPELLER_NODE = "Propeller"

    /** The model's colours for the scene's shading, in sRGB, as iOS shows them: the file's own are linear and physically based. */
    private val COLOURS = mapOf(
        "Polished" to 0xe5e6e8, "CowlWhite" to 0xececeb, "TipWhite" to 0xececeb, "White" to 0xececec,
        "Interior" to 0x42577e, "Floor" to 0x595b5f, "DoorFrame" to 0xa9adb2,
        "Glass" to 0x1d272f, "Seal" to 0x191919, "AntiGlare" to 0x1d1d1f,
        "Spinner" to 0xc9ccd0, "Blade" to 0x9a9da1, "Exhaust" to 0x7d766e, "ExhaustIn" to 0x191919, "Intake" to 0x161616,
        "Tyre" to 0x272729, "Hub" to 0xe5e6e7, "Strut" to 0xe5e6e7, "Chrome" to 0xc8c8c8,
        "Red" to 0xc8201e, "RedText" to 0xd8605a,
    )

    /** What a piece is painted with. */
    enum class Paint { COLOUR, LIVERY, REGISTRATION }

    /**
     * One primitive of the file: its triangles in the aircraft's frame (a
     * blade's about the hub), its colour (sRGB, alpha last) unless it takes
     * the livery or the registration image, and its texture coordinates.
     */
    class Piece(
        val part: String,
        val material: String,
        val paint: Paint,
        val color: FloatArray,
        val positions: FloatArray,
        val normals: FloatArray?,
        val uvs: FloatArray?,
        val indices: IntArray,
    )

    /** The model sorted for the app: the airframe, the registration patches and the blades. */
    class Prepared(val body: List<Piece>, val registration: List<Piece>, val blades: List<Piece>)

    /** Sorts [model] (the parsed P750XL.glb) into the pieces the app paints and turns. */
    fun prepare(model: Glb.Model): Prepared {
        val body = ArrayList<Piece>()
        val registration = ArrayList<Piece>()
        val blades = ArrayList<Piece>()
        for (mesh in model.meshes) {
            val isBlade = mesh.name == PROPELLER_NODE
            // The blades about the hub, the rest raised into the aircraft's frame.
            val shift = if (isBlade) -(PROPELLER_HUB - MODEL_OFFSET) else MODEL_OFFSET
            for (p in mesh.primitives) {
                val paint = when {
                    p.material == REGISTRATION_MATERIAL -> Paint.REGISTRATION
                    p.material == SKIN_MATERIAL && mesh.name in LIVERY_PARTS -> Paint.LIVERY
                    else -> Paint.COLOUR
                }
                val color = COLOURS[p.material]?.let { rgba(it) } ?: p.color.copyOf()
                val positions = p.positions.copyOf()
                var i = 0
                while (i + 2 < positions.size) {
                    positions[i] += shift.x; positions[i + 1] += shift.y; positions[i + 2] += shift.z
                    i += 3
                }
                val piece = Piece(mesh.name, p.material, paint, color, positions, p.normals, p.uvs, p.indices)
                when {
                    isBlade -> blades += piece
                    paint == Paint.REGISTRATION -> registration += piece
                    else -> body += piece
                }
            }
        }
        return Prepared(body, registration, blades)
    }

    private fun rgba(hex: Int) = floatArrayOf(((hex shr 16) and 0xff) / 255f, ((hex shr 8) and 0xff) / 255f, (hex and 0xff) / 255f, 1f)

    // MARK: - The fuselage's skin

    /**
     * A station of the fuselage after the model's script: two super-elliptic
     * halves, above and below the widest point. In the script's frame: z aft
     * of the wing's leading edge, y up from the ground, metres.
     */
    private class Station(
        val z: Float, val halfWidth: Float, val crown: Float, val keel: Float, val widest: Float, val upper: Float, val lower: Float,
    )

    private val STATIONS = listOf(
        Station(-3.62f, 0.235f, 1.945f, 1.070f, 1.680f, 2.0f, 2.3f),
        Station(-3.30f, 0.300f, 1.950f, 1.020f, 1.650f, 2.1f, 2.4f),
        Station(-2.80f, 0.400f, 1.960f, 0.975f, 1.610f, 2.3f, 2.6f),
        Station(-2.30f, 0.500f, 1.990f, 0.950f, 1.570f, 2.5f, 2.8f),
        Station(-2.02f, 0.560f, 2.040f, 0.935f, 1.540f, 2.6f, 2.9f),
        Station(-1.94f, 0.578f, 2.075f, 0.930f, 1.530f, 2.7f, 3.0f),
        Station(-1.60f, 0.640f, 2.260f, 0.905f, 1.510f, 3.0f, 3.1f),
        Station(-1.25f, 0.695f, 2.435f, 0.880f, 1.490f, 3.3f, 3.2f),
        Station(-1.05f, 0.712f, 2.490f, 0.868f, 1.485f, 3.5f, 3.2f),
        Station(-0.80f, 0.720f, 2.520f, 0.858f, 1.480f, 3.6f, 3.2f),
        Station(-0.45f, 0.720f, 2.525f, 0.848f, 1.480f, 3.6f, 3.2f),
        Station(0.10f, 0.720f, 2.490f, 0.840f, 1.480f, 3.6f, 3.2f),
        Station(0.80f, 0.720f, 2.440f, 0.840f, 1.485f, 3.6f, 3.2f),
        Station(1.40f, 0.720f, 2.410f, 0.845f, 1.490f, 3.6f, 3.2f),
        Station(2.00f, 0.718f, 2.380f, 0.890f, 1.500f, 3.5f, 3.1f),
        Station(2.60f, 0.675f, 2.345f, 0.925f, 1.540f, 3.4f, 3.0f),
        Station(3.30f, 0.600f, 2.300f, 0.975f, 1.590f, 3.2f, 2.9f),
        Station(3.90f, 0.515f, 2.270f, 1.070f, 1.640f, 3.0f, 2.7f),
        Station(4.60f, 0.415f, 2.215f, 1.160f, 1.700f, 2.7f, 2.6f),
        Station(5.20f, 0.330f, 2.150f, 1.205f, 1.720f, 2.5f, 2.6f),
        Station(6.00f, 0.230f, 2.050f, 1.215f, 1.720f, 2.3f, 2.6f),
        Station(6.70f, 0.150f, 1.985f, 1.250f, 1.720f, 2.2f, 2.5f),
        Station(7.10f, 0.105f, 1.955f, 1.430f, 1.760f, 2.1f, 2.2f),
        Station(7.30f, 0.075f, 1.935f, 1.640f, 1.815f, 2.0f, 2.0f),
        Station(7.42f, 0.030f, 1.895f, 1.820f, 1.860f, 2.0f, 2.0f),
    )
    /** The script's frame less the aircraft's: its z is from the wing's leading edge and its y from the ground. */
    private val SCRIPT_ORIGIN = V3(0f, AircraftFlight.GROUND_HEIGHT.toFloat(), 1.747f)
    private val STATION_Z = STATIONS.map { it.z }.toFloatArray()

    /** The stations' z in the aircraft's frame, nose to tail. */
    val stationPositions: List<Float> get() = STATIONS.map { it.z - SCRIPT_ORIGIN.z }

    /**
     * The point on the fuselage's skin at [z], [y] (aircraft frame) on the
     * right (+1) or left (−1) side, and the skin's outward normal there, [y]
     * held within 98% of the section's height. The jump door's opening is
     * not left out.
     */
    fun fuselageSkin(z: Float, y: Float, side: Float): Pair<V3, V3> {
        val sz = (z + SCRIPT_ORIGIN.z).coerceIn(STATION_Z.first(), STATION_Z.last())
        fun value(key: (Station) -> Float) = catmull(STATION_Z, STATIONS.map(key), sz)
        val w = value { it.halfWidth }
        val crown = value { it.crown }
        val keel = value { it.keel }
        val widest = value { it.widest }
        val sy = (y + SCRIPT_ORIGIN.y).coerceIn(widest - 0.98f * (widest - keel), widest + 0.98f * (crown - widest))
        val above = sy >= widest
        val h = if (above) crown - widest else widest - keel
        val e = if (above) value { it.upper } else value { it.lower }
        val t = min(abs(sy - widest) / h, 0.999f)
        val x = w * (1 - t.pow(e)).pow(1 / e)
        val gx = e * (x / w).pow(e - 1) / w
        val gy = e * t.pow(e - 1) / h * (if (above) 1 else -1)
        val l = sqrt(gx * gx + gy * gy)
        val normal = V3(side * gx / l, gy / l, 0f)
        return V3(side * x, sy - SCRIPT_ORIGIN.y, sz - SCRIPT_ORIGIN.z) to normal
    }

    /** Catmull–Rom interpolation of [ys] over the sorted, unevenly spaced [xs] at [x], held at the ends, as the script lofts the stations. */
    private fun catmull(xs: FloatArray, ys: List<Float>, x: Float): Float {
        val last = xs.size - 1
        if (x <= xs[0]) return ys[0]
        if (x >= xs[last]) return ys[last]
        var i = 0
        while (i + 1 < last && xs[i + 1] < x) i++
        val x0 = xs[i]; val x1 = xs[i + 1]
        val t = (x - x0) / (x1 - x0)
        val p1 = ys[i]; val p2 = ys[i + 1]
        val h = x1 - x0
        val m1 = if (i > 0) (p2 - ys[i - 1]) / (x1 - xs[i - 1]) * h else p2 - p1
        val m2 = if (i + 2 <= last) (ys[i + 2] - p1) / (xs[i + 2] - x0) * h else p2 - p1
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * p1 + (t3 - 2 * t2 + t) * m1 + (-2 * t3 + 3 * t2) * p2 + (t3 - t2) * m2
    }

    // MARK: - The logo

    /** How far the logo decals stand off the skin. */
    const val LOGO_STANDOFF = 0.015f

    /** A decal's triangles: positions, normals, texture coordinates (v = 0 at the picture's top) and indices. */
    class Decal(val positions: FloatArray, val normals: FloatArray, val uvs: FloatArray, val indices: IntArray)

    /**
     * A patch of the fuselage's skin on one side ([side] −1 left, +1 right),
     * [LOGO_STANDOFF] out from it, carrying a picture [imageWidth] by
     * [imageHeight]: [placement]'s width along the fuselage and as tall as
     * the picture's shape makes it, following the skin's curves, the picture
     * reading the right way round from that side.
     */
    fun logoDecal(imageWidth: Int, imageHeight: Int, placement: AircraftLogoPlacement, side: Float): Decal? {
        val width = placement.width.toFloat()
        if (imageWidth <= 0 || imageHeight <= 0 || width <= 0) return null
        val height = width * imageHeight / imageWidth
        val z0 = placement.along.toFloat() - width / 2
        val z1 = placement.along.toFloat() + width / 2
        val y0 = placement.height.toFloat() - height / 2
        val y1 = placement.height.toFloat() + height / 2
        // Columns at the ends, at every station between, and no more than 6 cm apart; rows no more than 5 cm apart.
        val stations = (listOf(z0, z1) + stationPositions.filter { it > z0 && it < z1 }).sorted()
        val columns = ArrayList<Float>()
        for (i in 0 until stations.size - 1) {
            val a = stations[i]; val b = stations[i + 1]
            val steps = max(1, ceil((b - a) / 0.06f).toInt())
            for (k in 0 until steps) columns += a + (b - a) * k / steps
        }
        columns += z1
        val rowCount = max(2, ceil(height / 0.05f).toInt() + 1)
        val rows = (0 until rowCount).map { y0 + (y1 - y0) * it / (rowCount - 1) }
        val count = columns.size * rowCount
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        val uvs = FloatArray(count * 2)
        val vertices = ArrayList<V3>(count)
        val vertexNormals = ArrayList<V3>(count)
        var n = 0
        for (z in columns) for (y in rows) {
            val (point, normal) = fuselageSkin(z, y, side)
            val v = point + normal * LOGO_STANDOFF
            vertices += v; vertexNormals += normal
            positions[n * 3] = v.x; positions[n * 3 + 1] = v.y; positions[n * 3 + 2] = v.z
            normals[n * 3] = normal.x; normals[n * 3 + 1] = normal.y; normals[n * 3 + 2] = normal.z
            uvs[n * 2] = if (side < 0) (z - z0) / width else (z1 - z) / width
            uvs[n * 2 + 1] = (y1 - y) / height
            n++
        }
        val indices = ArrayList<Int>()
        fun triangle(i: Int, j: Int, k: Int) {
            val face = (vertices[j] - vertices[i]).cross(vertices[k] - vertices[i])
            if (face.dot(vertexNormals[i]) >= 0) { indices += i; indices += j; indices += k } else { indices += i; indices += k; indices += j }
        }
        for (column in 0 until columns.size - 1) for (row in 0 until rowCount - 1) {
            val a = column * rowCount + row
            val b = a + rowCount
            triangle(a, b, b + 1)
            triangle(a, b + 1, a + 1)
        }
        return Decal(positions, normals, uvs, indices.toIntArray())
    }
}
