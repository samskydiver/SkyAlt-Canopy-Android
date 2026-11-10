package com.skyalt.canopy.scene

import kotlin.math.sqrt

/** Colour and normal helpers the scene's meshes share. */
object CanopyMeshBuilder {
    fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 0xff) / 255f, ((hex shr 8) and 0xff) / 255f, (hex and 0xff) / 255f, 1f)

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
