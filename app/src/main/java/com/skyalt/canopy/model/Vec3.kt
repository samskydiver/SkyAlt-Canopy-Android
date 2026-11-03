package com.skyalt.canopy.model

import kotlin.math.sqrt

/** A double-precision 3-vector: x east, y up, z south (north is −z). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun div(k: Double) = Vec3(x / k, y / k, z / k)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    val length: Double get() = sqrt(x * x + y * y + z * z)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/** A float 3-vector for mesh building. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    operator fun div(k: Float) = V3(x / k, y / k, z / k)
    operator fun unaryMinus() = V3(-x, -y, -z)

    val length: Float get() = sqrt(x * x + y * y + z * z)

    fun dot(o: V3) = x * o.x + y * o.y + z * o.z

    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun normalized(): V3 {
        val l = length
        return if (l > 1e-9f) this / l else V3(0f, 1f, 0f)
    }

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        fun of(v: Vec3) = V3(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
    }
}

fun Double.clamp(lo: Double, hi: Double) = if (this < lo) lo else if (this > hi) hi else this
fun Float.clamp(lo: Float, hi: Float) = if (this < lo) lo else if (this > hi) hi else this
