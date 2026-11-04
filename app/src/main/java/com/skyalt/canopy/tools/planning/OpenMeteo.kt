package com.skyalt.canopy.tools.planning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Wind service failures; the message is the strings key, as on iOS. */
class WindServiceException(val key: String) : IOException(key)

/** Source of wind profiles; injectable so tests never touch the network. */
fun interface WindProfileClient {
    suspend fun fetch(coordinate: Coordinate, validAt: Instant): WindProfile

    companion object {
        val live = WindProfileClient { c, t -> OpenMeteoWindService.fetchProfile(c, t) }
    }
}

/**
 * GFS pressure-level winds and terrain elevation from Open-Meteo, folded
 * into a WindProfile and cached per place and hour. Port of OpenMeteoWindService.swift.
 */
object OpenMeteoWindService {
    private data class CacheKey(val lat: Int, val lon: Int, val hour: Long)

    private val cache = ConcurrentHashMap<CacheKey, WindProfile>()
    private val pressureLevels = (1000 downTo 400 step 25).toList()
    private val hourFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US)

    suspend fun fetchProfile(coordinate: Coordinate, validAt: Instant): WindProfile {
        val key = CacheKey((coordinate.latitude * 1000).roundToInt(), (coordinate.longitude * 1000).roundToInt(), validAt.epochSecond / 3600)
        cache[key]?.let { return it }
        val profile = coroutineScope {
            val forecast = async { load(forecastUrl(coordinate, validAt)) }
            val elevation = async { load(elevationUrl(coordinate)) }
            parseForecast(forecast.await(), coordinate, parseElevation(elevation.await()), validAt)
        }
        cache[key] = profile
        return profile
    }

    private fun forecastUrl(c: Coordinate, validAt: Instant): String {
        val hour = WindProfile.forecastHour(validAt)
        val historical = hour.isBefore(WindProfile.forecastHour(Instant.now()))
        val variables = mutableListOf("wind_speed_10m", "wind_direction_10m")
        for (level in pressureLevels) {
            variables += "wind_speed_${level}hPa"; variables += "wind_direction_${level}hPa"; variables += "geopotential_height_${level}hPa"
        }
        val hourString = LocalDateTime.ofInstant(hour, ZoneOffset.UTC).format(hourFormat)
        val base = if (historical) "https://historical-forecast-api.open-meteo.com/v1/forecast" else "https://api.open-meteo.com/v1/gfs"
        val query = mutableListOf(
            "latitude" to String.format(Locale.US, "%.6f", c.latitude),
            "longitude" to String.format(Locale.US, "%.6f", c.longitude),
            "hourly" to variables.joinToString(","),
            "wind_speed_unit" to "ms",
            "timezone" to "UTC",
            "start_hour" to hourString,
            "end_hour" to hourString,
        )
        if (historical) query += "models" to "gfs_seamless"
        return base + "?" + query.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
    }

    private fun elevationUrl(c: Coordinate) =
        "https://api.open-meteo.com/v1/elevation?latitude=" + String.format(Locale.US, "%.6f", c.latitude) +
            "&longitude=" + String.format(Locale.US, "%.6f", c.longitude)

    private suspend fun load(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            if (connection.responseCode !in 200..299) throw WindServiceException("The weather service returned an invalid response.")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseElevation(text: String): Double = try {
        JSONObject(text).getJSONArray("elevation").getDouble(0)
    } catch (e: Exception) {
        throw WindServiceException("The weather service returned an invalid response.")
    }

    internal fun parseForecast(text: String, coordinate: Coordinate, terrain: Double, requested: Instant): WindProfile {
        val hourly: JSONObject
        val time: String
        try {
            hourly = JSONObject(text).getJSONObject("hourly")
            time = hourly.getJSONArray("time").getString(0)
        } catch (e: Exception) {
            throw WindServiceException("The weather service returned an invalid response.")
        }
        fun number(key: String): Double? {
            val array = hourly.optJSONArray(key) ?: return null
            if (array.length() == 0 || array.isNull(0)) return null
            return array.optDouble(0).takeIf { it.isFinite() }
        }
        val samples = mutableListOf<WindProfileSample>()
        val s10 = number("wind_speed_10m")
        val d10 = number("wind_direction_10m")
        if (s10 != null && d10 != null) samples += WindProfileSample(null, terrain + 10, WindVector.meteorological(s10, d10))
        // Pressure levels below the terrain are underground in the model and are skipped.
        for (level in pressureLevels) {
            val speed = number("wind_speed_${level}hPa") ?: continue
            val direction = number("wind_direction_${level}hPa") ?: continue
            val height = number("geopotential_height_${level}hPa") ?: continue
            if (height < terrain) continue
            samples += WindProfileSample(level, height, WindVector.meteorological(speed, direction))
        }
        if (samples.size < 2) throw WindServiceException("No pressure-level wind data is available for that place and time.")
        val valid = try {
            LocalDateTime.parse(time, hourFormat).toInstant(ZoneOffset.UTC)
        } catch (e: Exception) {
            requested
        }
        return WindProfile(coordinate, terrain, valid, Instant.now(), "Open-Meteo", "GFS 0.25° · 25 hPa", samples.sortedBy { it.altitudeMSLMeters })
    }
}
