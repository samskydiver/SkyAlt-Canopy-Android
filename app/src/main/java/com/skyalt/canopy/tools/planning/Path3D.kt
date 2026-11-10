package com.skyalt.canopy.tools.planning

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// What a planning tool shows in its 3D view, and the plain geometry the view
// draws: paths at their heights, marked points and rings, over a picture of
// the map. Port of PlanningPath3DContent in PlanningPath3DView.swift (iOS PR #19).

/** A place and its height above the ground. */
data class Path3DPoint(val coordinate: Coordinate, val altitudeMeters: Double)

data class Path3DPath(val id: String, val points: List<Path3DPoint>, val argb: Int)

enum class Path3DMarkerStyle {
    /** A ball with a line down to the ground and a tag above it. */
    POINT,
    /** Only a small tag, such as a height along a path. */
    TAG,
}

data class Path3DMarker(val id: String, val point: Path3DPoint, val argb: Int, val title: String, val style: Path3DMarkerStyle = Path3DMarkerStyle.POINT)

data class Path3DRing(val id: String, val center: Path3DPoint, val radiusMeters: Double, val argb: Int)

data class PlanningPath3DContent(
    val paths: List<Path3DPath> = emptyList(),
    val markers: List<Path3DMarker> = emptyList(),
    val rings: List<Path3DRing> = emptyList(),
) {
    val isEmpty get() = paths.isEmpty() && markers.isEmpty() && rings.isEmpty()

    /** Every point the view has to frame, rings by their edges. */
    val framingCoordinates: List<Coordinate>
        get() = paths.flatMap { p -> p.points.map { it.coordinate } } + markers.map { it.point.coordinate } +
            rings.flatMap { ring -> (0 until 4).map { MapGeometry.coordinate(ring.center.coordinate, ring.radiusMeters, it * 90.0) } }

    val highestAltitudeMeters: Double
        get() = (paths.flatMap { p -> p.points.map { it.altitudeMeters } } + markers.map { it.point.altitudeMeters } + rings.map { it.center.altitudeMeters })
            .maxOrNull() ?: 0.0

    /** The part of the map that has to be on screen: at least 300 m each way. */
    val neededRect: MercatorRect?
        get() {
            val points = framingCoordinates
            if (points.isEmpty()) return null
            val xs = points.map { WebMercator.x(it.longitude) }
            val ys = points.map { WebMercator.y(it.latitude) }
            val minimum = 300 / WebMercator.metersPerUnit(points.first().latitude)
            val w = max(xs.max() - xs.min(), minimum)
            val h = max(ys.max() - ys.min(), minimum)
            return MercatorRect((xs.min() + xs.max() - w) / 2, (ys.min() + ys.max() - h) / 2, w, h)
        }

    companion object {
        /**
         * Heights in flight order, the ones not worked out yet taken from
         * their neighbours so the route still draws.
         */
        fun filledAltitudes(altitudes: List<Double?>): List<Double> = altitudes.indices.map { index ->
            altitudes[index]?.let { return@map it }
            val before = (index - 1 downTo 0).firstOrNull { altitudes[it] != null }
            val after = (index + 1 until altitudes.size).firstOrNull { altitudes[it] != null }
            when {
                before != null && after != null -> {
                    val f = (index - before).toDouble() / (after - before)
                    altitudes[before]!! + (altitudes[after]!! - altitudes[before]!!) * f
                }
                before != null -> altitudes[before]!!
                after != null -> altitudes[after]!!
                else -> 0.0
            }
        }

        /**
         * Height tags down a drift path every [stepMeters], starting below the
         * top with room under its tag: (point on the path at that height, height).
         */
        fun heightTags(points: List<Path3DPoint>, topMeters: Double, stepMeters: Double): List<Path3DPoint> {
            val clearance = 0.35 * stepMeters
            var height = kotlin.math.floor(topMeters / stepMeters) * stepMeters
            if (height >= topMeters - clearance) height -= stepMeters
            val tags = mutableListOf<Path3DPoint>()
            while (height > 0) {
                points.firstOrNull { it.altitudeMeters <= height }?.let { tags += Path3DPoint(it.coordinate, height) }
                height -= stepMeters
            }
            return tags
        }
    }
}

/** A rectangle in Web Mercator units (0…1 across the world, y growing southward). */
data class MercatorRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val midX get() = x + width / 2
    val midY get() = y + height / 2
    val maxX get() = x + width
    val maxY get() = y + height

    fun contains(other: MercatorRect) = other.x >= x && other.y >= y && other.maxX <= maxX && other.maxY <= maxY
    fun inset(d: Double) = MercatorRect(x + d, y + d, width - 2 * d, height - 2 * d)

    /** South-west and north-east corners. */
    val southWest get() = Coordinate(WebMercator.latitude(maxY), WebMercator.longitude(x))
    val northEast get() = Coordinate(WebMercator.latitude(y), WebMercator.longitude(maxX))

    companion object {
        fun of(southWest: Coordinate, northEast: Coordinate): MercatorRect {
            val x0 = WebMercator.x(southWest.longitude)
            val x1 = WebMercator.x(northEast.longitude)
            val y0 = WebMercator.y(northEast.latitude)
            val y1 = WebMercator.y(southWest.latitude)
            return MercatorRect(x0, y0, x1 - x0, y1 - y0)
        }

        /**
         * A square of map well beyond what is shown, so the paths never run
         * off the ground when the view is tilted.
         */
        fun squareGround(needed: MercatorRect): MercatorRect {
            val side = max(needed.width, needed.height) * 3
            return MercatorRect(needed.midX - side / 2, needed.midY - side / 2, side, side)
        }

        /**
         * The ground still fits: it holds what is shown with some margin and is
         * not so much larger that the map turns blurry.
         */
        fun groundSuits(ground: MercatorRect, needed: MercatorRect): Boolean {
            val side = max(needed.width, needed.height)
            return ground.contains(needed.inset(-side * 0.4)) && min(ground.width, ground.height) < side * 6
        }
    }
}

/** The projection of the map pictures, so they lie flat and true under the paths. */
object WebMercator {
    private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686

    fun x(longitude: Double) = (longitude + 180) / 360

    fun y(latitude: Double): Double {
        val phi = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(phi) + 1 / cos(phi)) / PI) / 2
    }

    fun longitude(x: Double) = x * 360 - 180

    fun latitude(y: Double) = Math.toDegrees(2 * atan(exp(PI * (1 - 2 * y))) - PI / 2)

    /** Ground metres per Mercator unit at a latitude. */
    fun metersPerUnit(latitude: Double) = EARTH_CIRCUMFERENCE_METERS * cos(Math.toRadians(latitude))
}

/**
 * The scene's frame: x east, y up, z south, metres, the origin on the
 * ground at [origin] (Mercator x, y).
 */
class Path3DFrame(val originX: Double, val originY: Double) {
    val metersPerUnit = WebMercator.metersPerUnit(WebMercator.latitude(originY))

    fun x(mercatorX: Double) = ((mercatorX - originX) * metersPerUnit).toFloat()
    fun z(mercatorY: Double) = ((mercatorY - originY) * metersPerUnit).toFloat()

    fun local(point: Path3DPoint) = floatArrayOf(
        x(WebMercator.x(point.coordinate.longitude)),
        point.altitudeMeters.toFloat(),
        z(WebMercator.y(point.coordinate.latitude)),
    )
}

/** Plain triangle meshes: positions (x, y, z) and indices. */
class Path3DMesh(val positions: FloatArray, val indices: IntArray) {
    val isEmpty get() = indices.isEmpty()
}

object Path3DMeshes {
    /** A square tube along the points. */
    fun tube(points: List<FloatArray>, radius: Float): Path3DMesh {
        val v = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val along = sub(b, a)
            if (length(along) < 1e-3f) continue
            val direction = normalize(along)
            var side = cross(direction, floatArrayOf(0f, 1f, 0f))
            if (length(side) < 1e-3f) side = floatArrayOf(1f, 0f, 0f)
            side = scale(normalize(side), radius)
            val up = scale(normalize(cross(side, direction)), radius)
            val corners = listOf(add(side, up), add(scale(side, -1f), up), sub(scale(side, -1f), up), sub(side, up))
            val base = v.size / 3
            for (c in corners) add(v, add(a, c))
            for (c in corners) add(v, add(b, c))
            for (face in 0 until 4) {
                val i0 = base + face
                val i1 = base + (face + 1) % 4
                idx += listOf(i0, i1, i1 + 4, i0, i1 + 4, i0 + 4)
            }
        }
        return Path3DMesh(v.toFloatArray(), idx.toIntArray())
    }

    /** The see-through wall from the path down to the ground, which shows how high each part of it is. */
    fun curtain(points: List<FloatArray>): Path3DMesh {
        val v = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (p in points) {
            add(v, p)
            add(v, floatArrayOf(p[0], 0f, p[2]))
        }
        for (i in 0 until points.size - 1) {
            val k = i * 2
            idx += listOf(k, k + 1, k + 3, k, k + 3, k + 2)
        }
        return Path3DMesh(v.toFloatArray(), idx.toIntArray())
    }

    /** The path's shadow on the ground. */
    fun groundTrack(points: List<FloatArray>, width: Float): Path3DMesh {
        val v = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (i in 0 until points.size - 1) {
            val a = floatArrayOf(points[i][0], 0f, points[i][2])
            val b = floatArrayOf(points[i + 1][0], 0f, points[i + 1][2])
            val along = sub(b, a)
            if (length(along) < 1e-3f) continue
            val side = scale(normalize(floatArrayOf(-along[2], 0f, along[0])), width / 2)
            val base = v.size / 3
            add(v, add(a, side)); add(v, sub(a, side)); add(v, add(b, side)); add(v, sub(b, side))
            idx += listOf(base, base + 1, base + 3, base, base + 3, base + 2)
        }
        return Path3DMesh(v.toFloatArray(), idx.toIntArray())
    }

    /** A flat band round [center] at its height. */
    fun ringOutline(center: FloatArray, radius: Float, width: Float, segments: Int = 96): Path3DMesh {
        val v = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (i in 0..segments) {
            val angle = i.toFloat() / segments * 2 * PI.toFloat()
            val c = cos(angle)
            val s = sin(angle)
            val outer = radius + width / 2
            val inner = max(0f, radius - width / 2)
            add(v, floatArrayOf(center[0] + c * outer, center[1], center[2] + s * outer))
            add(v, floatArrayOf(center[0] + c * inner, center[1], center[2] + s * inner))
        }
        for (i in 0 until segments) {
            val k = i * 2
            idx += listOf(k, k + 1, k + 3, k, k + 3, k + 2)
        }
        return Path3DMesh(v.toFloatArray(), idx.toIntArray())
    }

    /** The flat disc inside a ring. */
    fun disc(center: FloatArray, radius: Float, segments: Int = 96): Path3DMesh {
        val v = ArrayList<Float>()
        val idx = ArrayList<Int>()
        add(v, center)
        for (i in 0..segments) {
            val angle = i.toFloat() / segments * 2 * PI.toFloat()
            add(v, floatArrayOf(center[0] + cos(angle) * radius, center[1], center[2] + sin(angle) * radius))
        }
        for (i in 1..segments) idx += listOf(0, i, i + 1)
        return Path3DMesh(v.toFloatArray(), idx.toIntArray())
    }

    private fun add(out: ArrayList<Float>, p: FloatArray) {
        out += p[0]; out += p[1]; out += p[2]
    }

    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun scale(a: FloatArray, k: Float) = floatArrayOf(a[0] * k, a[1] * k, a[2] * k)
    private fun length(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    private fun normalize(a: FloatArray): FloatArray {
        val l = length(a)
        return if (l < 1e-9f) a else scale(a, 1 / l)
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
}

/** Where the top view puts the camera: straight down on everything shown, north up, all of it on the screen. */
object Path3DTopView {
    const val HORIZONTAL_FIELD_OF_VIEW_DEGREES = 40.0

    /** Camera height above the target for a top view of [needed], in a view [aspect] wide over tall. */
    fun distance(needed: MercatorRect, frame: Path3DFrame, aspect: Double, highestAltitudeMeters: Double): Double {
        val halfWidth = needed.width * frame.metersPerUnit / 2
        val halfHeight = needed.height * frame.metersPerUnit / 2
        val horizontal = Math.toRadians(HORIZONTAL_FIELD_OF_VIEW_DEGREES / 2)
        val vertical = atan(tan(horizontal) / max(aspect, 0.01))
        return max(halfWidth / tan(horizontal), halfHeight / tan(vertical)) * 1.3 + highestAltitudeMeters
    }
}
