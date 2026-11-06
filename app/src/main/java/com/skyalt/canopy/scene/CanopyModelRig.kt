package com.skyalt.canopy.scene

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The chosen PD series flown under the jumper: the bundled model with its
 * fabric curled by the brakes from the file's own brake shapes, the cells
 * let down and the tips folded by a stall or a collapse, and the toggles
 * pulled down with the hands. The file is already in the rig's frame (y
 * up, flying toward −z, the harness at the origin). The moving parts are
 * reshaped on the CPU each time the inputs change; the scene uploads a
 * part again when its [RigPart.version] moves. Plain Kotlin (no GL), so
 * the unit tests fly it. Port of CanopyModelRig.swift.
 */
class CanopyModelRig private constructor(
    val key: String,
    val parts: List<RigPart>,
    private val fabric: List<FabricPart>,
    private val toggles: List<TogglePart>,
    private val field: SpanField,
) {
    private val lastInputs = floatArrayOf(-1f, -1f, -1f, -1f)

    /** Shapes the wing for the frame's inputs; nothing is rebuilt while they hold still. */
    fun update(leftBrake: Float, rightBrake: Float, stall: Float, collapse: Float) {
        val inputs = floatArrayOf(leftBrake, rightBrake, stall, collapse)
        var change = 0f
        for (i in 0..3) change = max(change, abs(inputs[i] - lastInputs[i]))
        if (change <= 0.0005f) return
        inputs.copyInto(lastInputs)
        val weights = BrakeWeights(leftBrake.coerceIn(0f, 1f), rightBrake.coerceIn(0f, 1f))
        for (part in fabric) part.update(weights, stall, collapse, field)
        for (toggle in toggles) {
            val brake = if (toggle.isLeft) leftBrake else rightBrake
            toggle.part.offsetY = -TOGGLE_TRAVEL * brake.coerceIn(0f, 1f)
        }
    }

    /** Where the hand holds the toggle, in the model's own metres from the harness. */
    fun togglePosition(left: Boolean, brake: Float): FloatArray {
        val rest = toggles.firstOrNull { it.isLeft == left }?.centroid
            ?: floatArrayOf(if (left) -LINK_X else LINK_X, RISER_LENGTH - TOGGLE_DROP, LINK_Z)
        return floatArrayOf(rest[0], rest[1] - TOGGLE_TRAVEL * brake.coerceIn(0f, 1f), rest[2])
    }

    companion object {
        /**
         * The generator's rigging in metres at the model's own size: the
         * links above the harness (also the centre of the span arc), their
         * spacing, and the toggles' rest and travel below the rear links.
         */
        const val RISER_LENGTH = 0.56f
        const val LINK_X = 0.19f
        const val LINK_Z = 0.05f
        const val TOGGLE_DROP = 0.10f
        const val TOGGLE_TRAVEL = 0.55f
        /** Where the harness sits on the jumper: at the shoulders. */
        const val HARNESS_HEIGHT = 0.2f

        /** Null when the file holds no skins to hang the deformation on. */
        fun build(model: Glb.Model, key: String): CanopyModelRig? {
            val meshes = model.meshes.mapNotNull { BakedMesh.of(it) }
            val bottom = meshes.firstOrNull { it.role == MeshRole.BOTTOM_SKIN } ?: return null
            val top = meshes.firstOrNull { it.role == MeshRole.TOP_SKIN } ?: return null
            val field = SpanField(bottom.positions, top.positions)
            val parts = ArrayList<RigPart>()
            val fabric = ArrayList<FabricPart>()
            val toggles = ArrayList<TogglePart>()
            for (mesh in meshes) {
                when (mesh.role) {
                    MeshRole.RISERS -> {
                        val (fixed, sides) = splitToggles(mesh)
                        parts += fixed
                        toggles += sides
                        parts += sides.map { it.part }
                    }
                    MeshRole.SLIDER, MeshRole.OTHER -> parts += RigPart(
                        mesh.positions.copyOf(), mesh.normals ?: normals(mesh.positions, mesh.triangles), mesh.colors, mesh.triangles,
                    )
                    else -> FabricPart(mesh, field).also { fabric += it; parts += it.part }
                }
            }
            return CanopyModelRig(key, parts, fabric, toggles, field)
        }

        /** Angle round the links (0 straight above them, positive to the right) and the distance from them, in the x–y plane. */
        fun polarAngle(x: Float, y: Float) = atan2(x, y - RISER_LENGTH)
        fun polarRadius(x: Float, y: Float): Float {
            val dy = y - RISER_LENGTH
            return sqrt(x * x + dy * dy)
        }

        /** Area-weighted vertex normals of the triangles. */
        fun normals(positions: FloatArray, triangles: IntArray, into: FloatArray = FloatArray(positions.size)): FloatArray {
            into.fill(0f)
            var t = 0
            while (t + 2 < triangles.size) {
                val a = triangles[t] * 3; val b = triangles[t + 1] * 3; val c = triangles[t + 2] * 3
                t += 3
                val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
                val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (i in intArrayOf(a, b, c)) { into[i] += nx; into[i + 1] += ny; into[i + 2] += nz }
            }
            var i = 0
            while (i + 2 < into.size) {
                val l = sqrt(into[i] * into[i] + into[i + 1] * into[i + 1] + into[i + 2] * into[i + 2])
                if (l > 1e-12f) { into[i] /= l; into[i + 1] /= l; into[i + 2] /= l } else { into[i] = 0f; into[i + 1] = 1f; into[i + 2] = 0f }
                i += 3
            }
            return into
        }

        /**
         * The risers mesh with its toggles taken out into parts of their
         * own, one a side, so they can slide down the brake lines; the dive
         * loops, which share their material, stay with the risers.
         */
        private fun splitToggles(mesh: BakedMesh): Pair<RigPart, List<TogglePart>> {
            val fixed = ArrayList<Int>()
            val sides = mapOf(true to ArrayList<Int>(), false to ArrayList())
            for (range in mesh.ranges) {
                val isToggle = range.material.lowercase().contains("toggle")
                var t = range.start
                while (t + 2 < range.start + range.count) {
                    val a = mesh.triangles[t]; val b = mesh.triangles[t + 1]; val c = mesh.triangles[t + 2]
                    t += 3
                    if (!isToggle) { fixed += a; fixed += b; fixed += c; continue }
                    val p = mesh.positions
                    val cx = (p[a * 3] + p[b * 3] + p[c * 3]) / 3
                    val cz = (p[a * 3 + 2] + p[b * 3 + 2] + p[c * 3 + 2]) / 3
                    // The dive loops on the front risers share the toggles' material; they stay where they are.
                    val list = if (cz < 0) fixed else sides.getValue(cx < 0)
                    list += a; list += b; list += c
                }
            }
            val normals = mesh.normals ?: normals(mesh.positions, mesh.triangles)
            val toggles = sides.filterValues { it.isNotEmpty() }.map { (isLeft, list) ->
                var sx = 0f; var sy = 0f; var sz = 0f
                for (i in list) { sx += mesh.positions[i * 3]; sy += mesh.positions[i * 3 + 1]; sz += mesh.positions[i * 3 + 2] }
                val n = max(1, list.size)
                TogglePart(compact(mesh, normals, list.toIntArray()), isLeft, floatArrayOf(sx / n, sy / n, sz / n))
            }
            return compact(mesh, normals, fixed.toIntArray()) to toggles
        }

        /** The vertices the triangles use, renumbered. */
        private fun compact(mesh: BakedMesh, normals: FloatArray, triangles: IntArray): RigPart {
            val map = HashMap<Int, Int>()
            val order = ArrayList<Int>()
            val indices = IntArray(triangles.size) { i -> map.getOrPut(triangles[i]) { order += triangles[i]; order.size - 1 } }
            val positions = FloatArray(order.size * 3)
            val outNormals = FloatArray(order.size * 3)
            val colors = FloatArray(order.size * 4)
            for ((j, i) in order.withIndex()) {
                for (c in 0..2) { positions[j * 3 + c] = mesh.positions[i * 3 + c]; outNormals[j * 3 + c] = normals[i * 3 + c] }
                for (c in 0..3) colors[j * 4 + c] = mesh.colors[i * 4 + c]
            }
            return RigPart(positions, outNormals, colors, indices)
        }

        internal fun smoothStep(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}

/**
 * One drawable piece of the rig: vertex positions and normals in the
 * model's metres, an RGBA colour per vertex, triangles. [version] moves
 * whenever the positions change; a toggle slides by [offsetY].
 */
class RigPart(val positions: FloatArray, var normals: FloatArray, val colors: FloatArray, val indices: IntArray) {
    @Volatile var version = 0
    var offsetY = 0f
}

/** Which mesh of the file a node holds, by the name the generator gave it. */
internal enum class MeshRole {
    TOP_SKIN, BOTTOM_SKIN, RIBS, INTERNALS, STABILIZERS, STEERING_LINES, LINES, RISERS, SLIDER, PILOT_CHUTE, OTHER;

    companion object {
        fun of(name: String): MeshRole {
            val n = name.lowercase()
            return when {
                "topskin" in n -> TOP_SKIN
                "bottomskin" in n -> BOTTOM_SKIN
                "rib" in n -> RIBS
                "internal" in n -> INTERNALS
                "stabil" in n -> STABILIZERS
                "steering" in n -> STEERING_LINES
                "line" in n -> LINES
                "riser" in n -> RISERS
                "slider" in n -> SLIDER
                "pilot" in n -> PILOT_CHUTE
                else -> OTHER
            }
        }
    }
}

internal enum class BrakeSide { LEFT, RIGHT, BOTH }

/** The file's three brake shapes blended exactly at the corners: what both sides share comes from the both-brakes shape, the rest from a side's own. */
internal class BrakeWeights(left: Float, right: Float) {
    val both = min(left, right)
    val left = left - both
    val right = right - both

    operator fun get(side: BrakeSide) = when (side) {
        BrakeSide.LEFT -> left
        BrakeSide.RIGHT -> right
        BrakeSide.BOTH -> both
    }
}

/** One of the file's meshes, its primitives joined: one colour per vertex, the triangles kept with their material's name. */
internal class BakedMesh(
    val role: MeshRole,
    val positions: FloatArray,
    val normals: FloatArray?,
    val colors: FloatArray,
    val triangles: IntArray,
    val ranges: List<Range>,
    /** How far each vertex moves at full brake on a side or both. */
    val brakeShapes: Map<BrakeSide, FloatArray>,
) {
    class Range(val material: String, val start: Int, val count: Int)

    companion object {
        fun of(mesh: Glb.Mesh): BakedMesh? {
            if (mesh.primitives.isEmpty()) return null
            val vertexCount = mesh.primitives.sumOf { it.positions.size / 3 }
            val positions = FloatArray(vertexCount * 3)
            val hasNormals = mesh.primitives.all { it.normals != null }
            val normals = if (hasNormals) FloatArray(vertexCount * 3) else null
            val colors = FloatArray(vertexCount * 4)
            val triangles = IntArray(mesh.primitives.sumOf { it.indices.size })
            val ranges = ArrayList<Range>()
            val targetCount = mesh.primitives.maxOf { it.targets.size }
            val targets = List(targetCount) { FloatArray(vertexCount * 3) }
            var vertex = 0
            var index = 0
            for (p in mesh.primitives) {
                val n = p.positions.size / 3
                p.positions.copyInto(positions, vertex * 3)
                if (normals != null) p.normals!!.copyInto(normals, vertex * 3)
                for (i in 0 until n) p.color.copyInto(colors, (vertex + i) * 4)
                for ((t, target) in p.targets.withIndex()) target.copyInto(targets[t], vertex * 3, 0, min(target.size, n * 3))
                ranges += Range(p.material, index, p.indices.size)
                for (i in p.indices) triangles[index++] = i + vertex
                vertex += n
            }
            // Each shape is told apart by the side of the wing it moves.
            val shapes = HashMap<BrakeSide, FloatArray>()
            for (target in targets) {
                var sx = 0f; var moved = 0
                for (i in 0 until vertexCount) {
                    val dx = target[i * 3]; val dy = target[i * 3 + 1]; val dz = target[i * 3 + 2]
                    if (dx * dx + dy * dy + dz * dz > 1e-8f) { sx += positions[i * 3]; moved++ }
                }
                if (moved == 0) continue
                val centre = sx / moved
                val side = if (centre < -0.25f) BrakeSide.LEFT else if (centre > 0.25f) BrakeSide.RIGHT else BrakeSide.BOTH
                shapes[side] = target
            }
            return BakedMesh(MeshRole.of(mesh.name), positions, normals, colors, triangles, ranges, shapes)
        }
    }
}

/** The span arc the generator hung the wing on: its centre is the links, and a vertex's angle round it is its place along the span. */
internal class SpanField(bottom: FloatArray, top: FloatArray) {
    /** The bottom skin's mean distance from the links. */
    val radius: Float
    /** The top skin's outermost angle. */
    val tipAngle: Float
    /** The bottom skin unrolled: along the arc, along the chord, and the distance from the links (3 per vertex). */
    private val unrolled: FloatArray
    /** The top skin's nose and tail (least and greatest z) along the span, in bins of angle from one tip to the other. */
    private val chordNose = FloatArray(BINS)
    private val chordTail = FloatArray(BINS)

    init {
        val n = bottom.size / 3
        var sum = 0f
        for (i in 0 until n) sum += CanopyModelRig.polarRadius(bottom[i * 3], bottom[i * 3 + 1])
        radius = max(0.5f, sum / max(1, n))
        var tip = 0f
        for (i in 0 until top.size / 3) tip = max(tip, abs(CanopyModelRig.polarAngle(top[i * 3], top[i * 3 + 1])))
        tipAngle = max(0.1f, tip)
        unrolled = FloatArray(n * 3)
        for (i in 0 until n) {
            val x = bottom[i * 3]; val y = bottom[i * 3 + 1]
            unrolled[i * 3] = CanopyModelRig.polarAngle(x, y) * radius
            unrolled[i * 3 + 1] = bottom[i * 3 + 2]
            unrolled[i * 3 + 2] = CanopyModelRig.polarRadius(x, y)
        }
        chordNose.fill(Float.MAX_VALUE)
        chordTail.fill(-Float.MAX_VALUE)
        for (i in 0 until top.size / 3) {
            val b = bin(CanopyModelRig.polarAngle(top[i * 3], top[i * 3 + 1]))
            chordNose[b] = min(chordNose[b], top[i * 3 + 2])
            chordTail[b] = max(chordTail[b], top[i * 3 + 2])
        }
        // An empty bin takes its nearest filled neighbour's chord.
        val filled = (0 until BINS).filter { chordNose[it] <= chordTail[it] }
        if (filled.isEmpty()) {
            chordNose.fill(0f); chordTail.fill(1f)
        } else {
            for (b in 0 until BINS) if (chordNose[b] > chordTail[b]) {
                val nearest = filled.minByOrNull { abs(it - b) }!!
                chordNose[b] = chordNose[nearest]; chordTail[b] = chordTail[nearest]
            }
        }
    }

    private fun bin(angle: Float): Int {
        val t = (angle / tipAngle + 1) / 2
        return min(BINS - 1, max(0, (t * BINS).toInt()))
    }

    fun nose(angle: Float) = chordNose[bin(angle)]
    fun tail(angle: Float) = chordTail[bin(angle)]

    /** How far above the bottom skin a point is along the radial, from the nearest bottom-skin vertex on the unrolled wing. */
    fun height(x: Float, y: Float, z: Float): Float {
        val r = CanopyModelRig.polarRadius(x, y)
        val s = CanopyModelRig.polarAngle(x, y) * radius
        var best = Float.MAX_VALUE
        var below = r
        var i = 0
        while (i < unrolled.size) {
            val ds = unrolled[i] - s
            val dz = unrolled[i + 1] - z
            val d = ds * ds + dz * dz
            if (d < best) { best = d; below = unrolled[i + 2] }
            i += 3
        }
        return max(0f, r - below)
    }

    companion object {
        const val BINS = 32
    }
}

/** A mesh reshaped as its inputs change: the skins, ribs, internal fabric, stabilizers, steering lines and lines, and the pilot chute. */
internal class FabricPart(private val mesh: BakedMesh, field: SpanField) {
    private val count = mesh.positions.size / 3
    /** Metres above the bottom skin, let down by a stall or a collapse. */
    private val height = FloatArray(count)
    /** Where along the span each vertex sits, −1 at the left tip to 1 at the right, from the rest shape. */
    private val span = FloatArray(count)
    /** How much of the tail's tuck each vertex takes: 0 ahead of the hinge at 55% chord, 1 from 78% chord back. */
    private val tuckWeight = FloatArray(count)
    /** The tail hinge's z at the vertex's station. */
    private val hingeZ = FloatArray(count)
    /** A collapse's crumple, metres along the radial. */
    private val ripple = FloatArray(count)
    /** Only the wing and the upper lines swing with the span; what hangs near the links stays with the risers. */
    private val moves = BooleanArray(count)
    private val restNormals: FloatArray
    val part: RigPart
    private var atRest = true

    init {
        val hasHeight = mesh.role == MeshRole.TOP_SKIN || mesh.role == MeshRole.RIBS || mesh.role == MeshRole.INTERNALS
        val hasRipple = mesh.role == MeshRole.TOP_SKIN || mesh.role == MeshRole.BOTTOM_SKIN
        val p = mesh.positions
        for (i in 0 until count) {
            val x = p[i * 3]; val y = p[i * 3 + 1]; val z = p[i * 3 + 2]
            val angle = CanopyModelRig.polarAngle(x, y)
            span[i] = (angle / field.tipAngle).coerceIn(-1f, 1f)
            moves[i] = CanopyModelRig.polarRadius(x, y) > 0.5f * field.radius
            val nose = field.nose(angle)
            val tail = field.tail(angle)
            val fraction = (z - nose) / max(0.1f, tail - nose)
            tuckWeight[i] = CanopyModelRig.smoothStep(0.55f, 0.78f, fraction)
            hingeZ[i] = nose + 0.55f * (tail - nose)
            if (hasHeight) height[i] = field.height(x, y, z)
            if (hasRipple) {
                val s = angle * field.radius
                ripple[i] = 0.07f * sin(13 * s) * sin(7.3f * z + 1.1f) + 0.04f * cos(21 * s) * sin(4.5f * z)
            }
        }
        restNormals = mesh.normals ?: CanopyModelRig.normals(mesh.positions, mesh.triangles)
        part = RigPart(mesh.positions.copyOf(), restNormals.copyOf(), mesh.colors, mesh.triangles)
    }

    fun update(weights: BrakeWeights, stall: Float, collapse: Float, field: SpanField) {
        val braking = mesh.brakeShapes.keys.any { weights[it] > 0.0005f }
        val distressed = stall > 0.001f || collapse > 0.001f
        val positions = part.positions
        if (!braking && !distressed) {
            if (!atRest) {
                mesh.positions.copyInto(positions)
                part.normals = restNormals.copyOf()
                atRest = true
                part.version++
            }
            return
        }
        atRest = false
        mesh.positions.copyInto(positions)
        for ((side, shape) in mesh.brakeShapes) {
            val w = weights[side]
            if (w <= 0.0005f) continue
            for (i in positions.indices) positions[i] += shape[i] * w
        }
        if (distressed) {
            // A toggle stall as filmed: at its onset the tips and
            // stabilizers curl down and in and the cells soften; stalled,
            // the outer cells fold down and under the centre into a
            // horseshoe, the tail tucking under, the centre cells holding
            // their air; at its deepest a wrinkled lump, one side lower.
            // A collapse in wake looks the same.
            val onset = stall * (1 - collapse)
            val deflate = 0.35f * onset + 0.6f * collapse
            val fold = 0.45f * onset + 1.6f * collapse
            val foldLeft = fold * (1 + 0.2f * collapse)
            val foldRight = fold * (1 - 0.2f * collapse)
            val tuck = 1.05f * collapse
            val hingeSpan = 0.45f
            val hingeAngle = hingeSpan * field.tipAngle
            val hingeR = field.radius
            val hingeX = hingeR * sin(hingeAngle)
            val hingeY = CanopyModelRig.RISER_LENGTH + hingeR * cos(hingeAngle)
            for (i in 0 until count) {
                if (!moves[i]) continue
                val px = positions[i * 3]; val py = positions[i * 3 + 1]
                val angle = CanopyModelRig.polarAngle(px, py)
                val u = span[i]
                var r = CanopyModelRig.polarRadius(px, py) - deflate * (0.3f + 0.7f * abs(u)) * height[i] + collapse * ripple[i]
                var z = positions[i * 3 + 2]
                // The tail curls down and forward under the wing, from the hinge back.
                val tuckAngle = tuck * tuckWeight[i]
                if (tuckAngle > 0.001f) {
                    val dz = z - hingeZ[i]
                    val dr = r - hingeR
                    val c = cos(tuckAngle); val sn = sin(tuckAngle)
                    z = hingeZ[i] + dz * c + dr * sn
                    r = hingeR - dz * sn + dr * c
                }
                var x = r * sin(angle)
                var y = CanopyModelRig.RISER_LENGTH + r * cos(angle)
                // The outer wing curls down and under about a hinge at 45% of the half span, more the further out.
                if (abs(u) > hingeSpan) {
                    val w = CanopyModelRig.smoothStep(hingeSpan, 1f, abs(u))
                    val a = (if (u < 0) foldLeft else foldRight) * w
                    val hx = if (u < 0) -hingeX else hingeX
                    val dx = x - hx
                    val dy = y - hingeY
                    val c = cos(a); val sn = sin(a)
                    if (u < 0) {
                        x = hx + dx * c - dy * sn
                        y = hingeY + dx * sn + dy * c
                    } else {
                        x = hx + dx * c + dy * sn
                        y = hingeY - dx * sn + dy * c
                    }
                }
                positions[i * 3] = x; positions[i * 3 + 1] = y; positions[i * 3 + 2] = z
            }
        }
        CanopyModelRig.normals(positions, mesh.triangles, part.normals)
        part.version++
    }
}

/** One toggle, moved down the brake line as a whole. */
internal class TogglePart(val part: RigPart, val isLeft: Boolean, val centroid: FloatArray)
