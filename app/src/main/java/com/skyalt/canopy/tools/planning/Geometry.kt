package com.skyalt.canopy.tools.planning

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** A latitude/longitude in degrees, independent of any map SDK. */
data class Coordinate(val latitude: Double, val longitude: Double) {
    val isValid get() = latitude in -90.0..90.0 && longitude in -180.0..180.0 && latitude.isFinite() && longitude.isFinite()
}

/** A flat east/north offset from a reference coordinate, in metres. */
data class LocalDisplacement(val eastMeters: Double, val northMeters: Double) {
    val magnitude get() = hypot(eastMeters, northMeters)
    fun scaled(factor: Double) = LocalDisplacement(eastMeters * factor, northMeters * factor)
    operator fun plus(o: LocalDisplacement) = LocalDisplacement(eastMeters + o.eastMeters, northMeters + o.northMeters)
    operator fun minus(o: LocalDisplacement) = LocalDisplacement(eastMeters - o.eastMeters, northMeters - o.northMeters)
    operator fun unaryMinus() = LocalDisplacement(-eastMeters, -northMeters)

    companion object {
        val ZERO = LocalDisplacement(0.0, 0.0)
    }
}

enum class MapRelativeSide { LEFT, RIGHT, CENTERED }

/** Where a point sits relative to a line drawn through a centre point. */
data class MapLineOffset(val projectedCoordinate: Coordinate, val distanceMeters: Double, val bearingToPointDegrees: Double, val side: MapRelativeSide)

data class BoundingCircle(val center: Coordinate, val radiusMeters: Double)

/** A map region: centre and spans in degrees. */
data class MapRegion(val center: Coordinate, val latitudeDelta: Double, val longitudeDelta: Double)

/** Small-area planar geometry on the Web Mercator projection, as MapGeometry.swift on MapKit's. */
object MapGeometry {
    private const val EARTH_CIRCUMFERENCE = 40_075_016.68557849

    /** Mercator x/y on a unit square: x east, y south. */
    private fun point(c: Coordinate): Pair<Double, Double> {
        val x = (c.longitude + 180.0) / 360.0
        val lat = c.latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180
        val y = (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0
        return x to y
    }

    private fun coordinate(x: Double, y: Double): Coordinate {
        val lon = x * 360.0 - 180.0
        val n = PI * (1 - 2 * y)
        val lat = atan(0.5 * (exp(n) - exp(-n))) * 180 / PI
        return Coordinate(lat, lon)
    }

    private fun pointsPerMeter(latitude: Double) = 1.0 / (EARTH_CIRCUMFERENCE * cos(latitude * PI / 180))

    fun coordinate(origin: Coordinate, by: LocalDisplacement): Coordinate {
        val ppm = pointsPerMeter(origin.latitude)
        val (x, y) = point(origin)
        return coordinate(x + by.eastMeters * ppm, y - by.northMeters * ppm)
    }

    fun displacement(from: Coordinate, to: Coordinate): LocalDisplacement {
        val ppm = pointsPerMeter(from.latitude)
        val (x0, y0) = point(from)
        val (x1, y1) = point(to)
        return LocalDisplacement((x1 - x0) / ppm, -(y1 - y0) / ppm)
    }

    fun coordinate(origin: Coordinate, distanceMeters: Double, bearingDegrees: Double): Coordinate {
        val r = bearingDegrees * PI / 180
        return coordinate(origin, LocalDisplacement(sin(r) * distanceMeters, cos(r) * distanceMeters))
    }

    fun lineEndpoints(center: Coordinate, bearingDegrees: Double, halfLengthMeters: Double): List<Coordinate> =
        listOf(-halfLengthMeters, halfLengthMeters).map { coordinate(center, it, bearingDegrees) }

    fun perpendicularOffset(point: Coordinate, center: Coordinate, bearingDegrees: Double): MapLineOffset {
        val r = bearingDegrees * PI / 180
        val ue = sin(r)
        val un = cos(r)
        val d = displacement(center, point)
        val along = d.eastMeters * ue + d.northMeters * un
        val projection = LocalDisplacement(ue * along, un * along)
        val perpendicular = d - projection
        val cross = ue * perpendicular.northMeters - un * perpendicular.eastMeters
        val side = if (perpendicular.magnitude < 1) MapRelativeSide.CENTERED else if (cross > 0) MapRelativeSide.LEFT else MapRelativeSide.RIGHT
        return MapLineOffset(
            coordinate(center, projection), perpendicular.magnitude,
            atan2(perpendicular.eastMeters, perpendicular.northMeters) * 180 / PI, side,
        )
    }

    /** Approximates a circle drawn by the user from the bounding box of the stroke. */
    fun fittedCircle(coordinates: List<Coordinate>): BoundingCircle? {
        if (coordinates.size < 8) return null
        val points = coordinates.map { point(it) }
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        val center = coordinate((minX + maxX) / 2, (minY + maxY) / 2)
        val radius = ((maxX - minX) + (maxY - minY)) / 4 / pointsPerMeter(center.latitude)
        if (radius < 10) return null
        return BoundingCircle(center, radius)
    }

    fun region(
        fitting: List<Coordinate>,
        circles: List<BoundingCircle> = emptyList(),
        minimumSpanDegrees: Double = 0.008,
        paddingFactor: Double = 1.5,
    ): MapRegion? {
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        fun include(c: Coordinate) {
            if (!c.isValid) return
            minLat = minOf(minLat, c.latitude); maxLat = maxOf(maxLat, c.latitude)
            minLon = minOf(minLon, c.longitude); maxLon = maxOf(maxLon, c.longitude)
        }
        fitting.forEach(::include)
        for (circle in circles) {
            if (!circle.radiusMeters.isFinite()) continue
            val radius = max(circle.radiusMeters, 0.0)
            val latR = radius / 111_320
            val lonR = radius / (111_320 * max(cos(circle.center.latitude * PI / 180), 0.01))
            include(Coordinate(circle.center.latitude - latR, circle.center.longitude - lonR))
            include(Coordinate(circle.center.latitude + latR, circle.center.longitude + lonR))
        }
        if (!minLat.isFinite() || !maxLat.isFinite()) return null
        val padding = max(paddingFactor, 1.0)
        return MapRegion(
            Coordinate((minLat + maxLat) / 2, (minLon + maxLon) / 2),
            max((maxLat - minLat) * padding, minimumSpanDegrees),
            max((maxLon - minLon) * padding, minimumSpanDegrees),
        )
    }

    fun bearingDegrees(from: Coordinate, to: Coordinate): Double {
        val d = displacement(from, to)
        return atan2(d.eastMeters, d.northMeters) * 180 / PI
    }

    fun midpoint(a: Coordinate, b: Coordinate): Coordinate {
        val (x0, y0) = point(a)
        val (x1, y1) = point(b)
        return coordinate((x0 + x1) / 2, (y0 + y1) / 2)
    }

    /** Wraps a bearing into 0..<360. */
    fun normalizedBearing(degrees: Double): Double {
        val r = degrees % 360
        return if (r >= 0) r else r + 360
    }
}

/** Small spherical-earth helpers, as Geodesy.swift. */
object Geodesy {
    const val EARTH_RADIUS_METERS = 6_371_000.0

    fun normalizedDegrees(degrees: Double): Double = MapGeometry.normalizedBearing(degrees)

    fun bearingDegrees(origin: Coordinate, destination: Coordinate): Double {
        val lat1 = origin.latitude * PI / 180
        val lat2 = destination.latitude * PI / 180
        val dLon = (destination.longitude - origin.longitude) * PI / 180
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizedDegrees(atan2(y, x) * 180 / PI)
    }

    /** Great-circle distance in metres. */
    fun distanceMeters(a: Coordinate, b: Coordinate): Double {
        val lat1 = a.latitude * PI / 180
        val lat2 = b.latitude * PI / 180
        val dLat = lat2 - lat1
        val dLon = (b.longitude - a.longitude) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
    }
}
