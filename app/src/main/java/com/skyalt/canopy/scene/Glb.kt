package com.skyalt.canopy.scene

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * The parts of a binary glTF (.glb) the canopy and aircraft models use: the
 * meshes with their positions, normals, texture coordinates and triangles,
 * each primitive's material name and base colour, and the morph targets
 * (the brake shapes). Node
 * transforms are applied, so every mesh comes out in the file's root
 * frame. Plain Kotlin, so the unit tests read the bundled files too.
 */
object Glb {
    class Primitive(
        val material: String,
        /** sRGB, alpha last. */
        val color: FloatArray,
        val positions: FloatArray,
        val normals: FloatArray?,
        val indices: IntArray,
        /** Per target, the offset of every vertex (x, y, z). */
        val targets: List<FloatArray>,
        /** TEXCOORD_0 (u, v per vertex, v = 0 at the image's top), when the file has it. */
        val uvs: FloatArray? = null,
    )

    class Mesh(val name: String, val primitives: List<Primitive>, val targetNames: List<String>)

    class Model(val meshes: List<Mesh>)

    fun parse(bytes: ByteArray): Model {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getInt(0) == 0x46546C67) { "not a glb" }
        var offset = 12
        var json: Map<*, *>? = null
        var bin: ByteBuffer? = null
        while (offset + 8 <= bytes.size) {
            val length = buffer.getInt(offset)
            val type = buffer.getInt(offset + 4)
            val start = offset + 8
            when (type) {
                0x4E4F534A -> json = Json.parse(String(bytes, start, length, Charsets.UTF_8)) as Map<*, *>
                0x004E4942 -> bin = ByteBuffer.wrap(bytes, start, length).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            offset = start + length
        }
        val root = json ?: error("no json chunk")
        val data = bin ?: ByteBuffer.allocate(0)
        return Reader(root, data).model()
    }

    private class Reader(val root: Map<*, *>, val bin: ByteBuffer) {
        val accessors = root.list("accessors")
        val views = root.list("bufferViews")
        val materials = root.list("materials")
        val meshes = root.list("meshes")
        val nodes = root.list("nodes")

        fun model(): Model {
            val out = ArrayList<Mesh>()
            val scene = (root["scene"] as? Number)?.toInt() ?: 0
            val roots = root.list("scenes").getOrNull(scene)?.let { (it as Map<*, *>).list("nodes") }
                ?: nodes.indices.toList()
            for (n in roots) visit((n as Number).toInt(), identity(), null, out)
            return Model(out)
        }

        private fun visit(index: Int, parent: FloatArray, parentName: String?, out: MutableList<Mesh>) {
            val node = nodes[index] as Map<*, *>
            val world = multiply(parent, local(node))
            val name = node["name"] as? String ?: parentName ?: ""
            (node["mesh"] as? Number)?.let { out += mesh(it.toInt(), name, world) }
            for (child in node.list("children")) visit((child as Number).toInt(), world, name, out)
        }

        private fun mesh(index: Int, nodeName: String, m: FloatArray): Mesh {
            val mesh = meshes[index] as Map<*, *>
            val names = ((mesh["extras"] as? Map<*, *>)?.list("targetNames") ?: emptyList<Any>()).map { it.toString() }
            val primitives = mesh.list("primitives").mapNotNull { p ->
                p as Map<*, *>
                val mode = (p["mode"] as? Number)?.toInt() ?: 4
                if (mode != 4) return@mapNotNull null
                val attributes = p["attributes"] as Map<*, *>
                val local = floats((attributes["POSITION"] as Number).toInt())
                val count = local.size / 3
                val positions = transform(local, m, 1f)
                val normals = (attributes["NORMAL"] as? Number)?.let { normalize(transform(floats(it.toInt()), m, 0f)) }
                val indices = (p["indices"] as? Number)?.let { ints(it.toInt()) } ?: IntArray(count) { it }
                val uvs = (attributes["TEXCOORD_0"] as? Number)?.toInt()
                    ?.takeIf { ((accessors[it] as Map<*, *>)["componentType"] as Number).toInt() == 5126 }
                    ?.let { floats(it) }
                val targets = p.list("targets").map { t ->
                    val position = ((t as Map<*, *>)["POSITION"] as? Number)?.toInt()
                    if (position == null) FloatArray(count * 3) else transform(floats(position), m, 0f)
                }
                val material = (p["material"] as? Number)?.let { materials.getOrNull(it.toInt()) as? Map<*, *> }
                val factor = ((material?.get("pbrMetallicRoughness") as? Map<*, *>)?.list("baseColorFactor"))
                    ?.map { (it as Number).toFloat() }
                val color = if (factor != null && factor.size >= 4) {
                    floatArrayOf(srgb(factor[0]), srgb(factor[1]), srgb(factor[2]), factor[3])
                } else floatArrayOf(0.8f, 0.8f, 0.8f, 1f)
                Primitive(material?.get("name") as? String ?: "", color, positions, normals, indices, targets, uvs)
            }
            return Mesh(nodeName.ifEmpty { mesh["name"] as? String ?: "" }, primitives, names)
        }

        private fun view(accessor: Map<*, *>): Triple<Int, Int, Int> {
            val view = views[(accessor["bufferView"] as Number).toInt()] as Map<*, *>
            val offset = ((view["byteOffset"] as? Number)?.toInt() ?: 0) + ((accessor["byteOffset"] as? Number)?.toInt() ?: 0)
            val stride = (view["byteStride"] as? Number)?.toInt() ?: 0
            return Triple(offset, stride, (accessor["count"] as Number).toInt())
        }

        private fun components(type: String) = when (type) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 1
        }

        fun floats(index: Int): FloatArray {
            val a = accessors[index] as Map<*, *>
            val n = components(a["type"] as String)
            require((a["componentType"] as Number).toInt() == 5126) { "float accessor expected" }
            val count = (a["count"] as Number).toInt()
            val out = FloatArray(count * n)
            if (a["bufferView"] != null) {
                val (offset, strideIn, _) = view(a)
                val stride = if (strideIn == 0) n * 4 else strideIn
                for (i in 0 until count) for (c in 0 until n) out[i * n + c] = bin.getFloat(offset + i * stride + c * 4)
            }
            // Sparse: zeros (or the base view) with a few elements replaced, as Blender writes morph targets.
            (a["sparse"] as? Map<*, *>)?.let { sparse ->
                val k = (sparse["count"] as Number).toInt()
                val idx = sparse["indices"] as Map<*, *>
                val values = sparse["values"] as Map<*, *>
                val at = indexList(idx, k)
                val vOffset = offsetOf(values)
                for (e in 0 until k) for (c in 0 until n) out[at[e] * n + c] = bin.getFloat(vOffset + (e * n + c) * 4)
            }
            return out
        }

        private fun offsetOf(ref: Map<*, *>): Int {
            val view = views[(ref["bufferView"] as Number).toInt()] as Map<*, *>
            return ((view["byteOffset"] as? Number)?.toInt() ?: 0) + ((ref["byteOffset"] as? Number)?.toInt() ?: 0)
        }

        private fun indexList(ref: Map<*, *>, count: Int): IntArray {
            val offset = offsetOf(ref)
            val type = (ref["componentType"] as Number).toInt()
            return IntArray(count) { i ->
                when (type) {
                    5121 -> bin.get(offset + i).toInt() and 0xff
                    5123 -> bin.getShort(offset + i * 2).toInt() and 0xffff
                    else -> bin.getInt(offset + i * 4)
                }
            }
        }

        fun ints(index: Int): IntArray {
            val a = accessors[index] as Map<*, *>
            val (offset, strideIn, count) = view(a)
            val type = (a["componentType"] as Number).toInt()
            val size = when (type) { 5121 -> 1; 5123 -> 2; else -> 4 }
            val stride = if (strideIn == 0) size else strideIn
            return IntArray(count) { i ->
                val at = offset + i * stride
                when (type) {
                    5121 -> bin.get(at).toInt() and 0xff
                    5123 -> bin.getShort(at).toInt() and 0xffff
                    else -> bin.getInt(at)
                }
            }
        }
    }

    /** Linear to sRGB, as the models' colours are shown on iOS. */
    private fun srgb(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) 12.92f * v else (1.055 * v.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }

    private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: emptyList<Any>()

    private fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** Column-major, as glTF writes matrices. */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0..3) for (r in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
        return out
    }

    private fun local(node: Map<*, *>): FloatArray {
        node.list("matrix").takeIf { it.size == 16 }?.let { m -> return FloatArray(16) { (m[it] as Number).toFloat() } }
        val t = node.list("translation").map { (it as Number).toFloat() }.takeIf { it.size == 3 } ?: listOf(0f, 0f, 0f)
        val q = node.list("rotation").map { (it as Number).toFloat() }.takeIf { it.size == 4 } ?: listOf(0f, 0f, 0f, 1f)
        val s = node.list("scale").map { (it as Number).toFloat() }.takeIf { it.size == 3 } ?: listOf(1f, 1f, 1f)
        val (x, y, z, w) = q
        val r = floatArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w),
            2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w),
            2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y),
        )
        val m = FloatArray(16)
        for (c in 0..2) for (row in 0..2) m[c * 4 + row] = r[c * 3 + row] * s[c]
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1f
        return m
    }

    private fun transform(v: FloatArray, m: FloatArray, w: Float): FloatArray {
        val out = FloatArray(v.size)
        var i = 0
        while (i + 2 < v.size) {
            val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
            out[i] = m[0] * x + m[4] * y + m[8] * z + m[12] * w
            out[i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13] * w
            out[i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14] * w
            i += 3
        }
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        var i = 0
        while (i + 2 < v.size) {
            val l = kotlin.math.sqrt(v[i] * v[i] + v[i + 1] * v[i + 1] + v[i + 2] * v[i + 2])
            if (l > 1e-12f) { v[i] /= l; v[i + 1] /= l; v[i + 2] /= l }
            i += 3
        }
        return v
    }
}

/** A small JSON reader: objects to maps, arrays to lists, numbers to doubles. */
object Json {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); v }

    /** Maps, lists, strings, numbers, booleans and null as JSON; a number that is not finite becomes null. */
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Number -> {
                val d = value.toDouble()
                if (!d.isFinite()) out.append("null")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) out.append(d.toLong())
                else out.append(d)
            }
            is String -> {
                out.append('"')
                for (c in value) when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c < ' ' -> out.append(String.format("\\u%04x", c.code))
                    else -> out.append(c)
                }
                out.append('"')
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    write(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    write(v, out)
                }
                out.append(']')
            }
            else -> write(value.toString(), out)
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val key = str()
                ws(); i++ // ':'
                out[key] = value()
                ws()
                if (s[i++] == '}') return out
            }
        }

        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                ws()
                if (s[i++] == ']') return out
            }
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (s[i] != '"') {
                val c = s[i++]
                if (c == '\\') {
                    when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                } else sb.append(c)
            }
            i++
            return sb.toString()
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
