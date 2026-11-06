package com.skyalt.canopy.sim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.skyalt.canopy.model.AircraftLiverySettings
import com.skyalt.canopy.model.AircraftLogoPlacement
import com.skyalt.canopy.model.AircraftMarkingsSettings
import com.skyalt.canopy.model.AircraftTouchdownSettings
import com.skyalt.canopy.model.CanopyModel
import com.skyalt.canopy.model.CanopyType
import com.skyalt.canopy.model.CloudSettings
import com.skyalt.canopy.model.RunwayPlacement
import com.skyalt.canopy.model.TrafficSettings
import com.skyalt.canopy.model.DropzoneSettings
import com.skyalt.canopy.model.LineStyle
import com.skyalt.canopy.model.PathStyleSettings
import com.skyalt.canopy.model.PatternHand
import com.skyalt.canopy.model.ReferencePattern
import com.skyalt.canopy.model.SimulatorConfiguration
import com.skyalt.canopy.model.Units
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Settings in SharedPreferences as JSON (missing keys fall back to defaults); the picture in app storage. */
class PreferencesStore(context: Context) : SimulatorStore {
    private val prefs = context.getSharedPreferences("canopy-simulator", Context.MODE_PRIVATE)
    private val imageFile = File(context.filesDir, "CanopySimulator/dropzone.jpg")
    private val logoFile = File(context.filesDir, "CanopySimulator/aircraft-logo")

    override fun loadConfiguration(): SimulatorConfiguration {
        val d = SimulatorConfiguration()
        val j = json("configuration") ?: return d
        val type = CanopyType.fromKey(j.optString("canopyType", d.canopyType.key))
        // A setup saved before the catalogue becomes the series its type stood for; clamped() then moves the size onto one of its.
        val modelKey = j.optString("canopyModelKey", "").takeIf { CanopyModel.forKey(it) != null } ?: CanopyModel.fallback(type).key
        return SimulatorConfiguration(
            startAltitudeMeters = j.optDouble("startAltitudeMeters", d.startAltitudeMeters),
            exitWeightKilograms = j.optDouble("exitWeightKilograms", d.exitWeightKilograms),
            canopyAreaSquareFeet = j.optDouble("canopyAreaSquareFeet", d.canopyAreaSquareFeet),
            canopyType = type,
            canopyModelKey = modelKey,
            stallTolerance = j.optDouble("stallTolerance", d.stallTolerance),
            windSpeed = j.optDouble("windSpeed", d.windSpeed),
            windDirectionDegrees = j.optDouble("windDirectionDegrees", d.windDirectionDegrees),
            referencePattern = ReferencePattern.fromKey(j.optString("referencePattern", d.referencePattern.key)),
            hand = if (j.optString("hand", "left") == "right") PatternHand.RIGHT else PatternHand.LEFT,
            runInMeters = j.optDouble("runInMeters", d.runInMeters),
            downwindAltitudeMeters = j.optDouble("downwindAltitudeMeters", d.downwindAltitudeMeters),
            baseAltitudeMeters = j.optDouble("baseAltitudeMeters", d.baseAltitudeMeters),
            finalAltitudeMeters = j.optDouble("finalAltitudeMeters", d.finalAltitudeMeters),
            startEastMeters = if (j.has("startEastMeters")) j.optDouble("startEastMeters") else null,
            startSouthMeters = if (j.has("startSouthMeters")) j.optDouble("startSouthMeters") else null,
            windsockEastMeters = j.optDouble("windsockEastMeters", d.windsockEastMeters),
            windsockSouthMeters = j.optDouble("windsockSouthMeters", d.windsockSouthMeters),
            finalHeadingDegrees = if (j.has("finalHeadingDegrees")) j.optDouble("finalHeadingDegrees") else null,
        ).clamped()
    }

    override fun saveConfiguration(c: SimulatorConfiguration) {
        val j = JSONObject()
            .put("startAltitudeMeters", c.startAltitudeMeters)
            .put("exitWeightKilograms", c.exitWeightKilograms)
            .put("canopyAreaSquareFeet", c.canopyAreaSquareFeet)
            .put("canopyType", c.canopyType.key)
            .put("canopyModelKey", c.canopyModelKey)
            .put("stallTolerance", c.stallTolerance)
            .put("windSpeed", c.windSpeed)
            .put("windDirectionDegrees", c.windDirectionDegrees)
            .put("referencePattern", c.referencePattern.key)
            .put("hand", if (c.hand == PatternHand.RIGHT) "right" else "left")
            .put("runInMeters", c.runInMeters)
            .put("downwindAltitudeMeters", c.downwindAltitudeMeters)
            .put("baseAltitudeMeters", c.baseAltitudeMeters)
            .put("finalAltitudeMeters", c.finalAltitudeMeters)
            .put("windsockEastMeters", c.windsockEastMeters)
            .put("windsockSouthMeters", c.windsockSouthMeters)
        c.startEastMeters?.let { j.put("startEastMeters", it) }
        c.startSouthMeters?.let { j.put("startSouthMeters", it) }
        c.finalHeadingDegrees?.let { j.put("finalHeadingDegrees", it) }
        prefs.edit().putString("configuration", j.toString()).apply()
    }

    override fun loadUnits() = Units.fromKey(prefs.getString("units", null))
    override fun saveUnits(u: Units) = prefs.edit().putString("units", u.key).apply()

    override fun loadPathStyle(): PathStyleSettings {
        val d = PathStyleSettings()
        val j = json("path-styles") ?: return d
        fun line(key: String, fallback: LineStyle): LineStyle {
            val o = j.optJSONObject(key) ?: return fallback
            return LineStyle(o.optInt("colorHex", fallback.colorHex), o.optDouble("opacity", fallback.opacity), o.optDouble("widthMeters", fallback.widthMeters))
        }
        return PathStyleSettings(
            pattern = line("pattern", d.pattern),
            patternGround = line("patternGround", d.patternGround),
            trail = line("trail", d.trail),
            trailColoredBySink = j.optBoolean("trailColoredBySink", d.trailColoredBySink),
            history = line("history", d.history),
        ).clamped()
    }

    override fun savePathStyle(s: PathStyleSettings) {
        fun line(l: LineStyle) = JSONObject().put("colorHex", l.colorHex).put("opacity", l.opacity).put("widthMeters", l.widthMeters)
        val j = JSONObject()
            .put("pattern", line(s.pattern))
            .put("patternGround", line(s.patternGround))
            .put("trail", line(s.trail))
            .put("trailColoredBySink", s.trailColoredBySink)
            .put("history", line(s.history))
        prefs.edit().putString("path-styles", j.toString()).apply()
    }

    override fun loadDropzone(): DropzoneSettings {
        val d = DropzoneSettings()
        val j = json("dropzone") ?: return d
        return DropzoneSettings(
            j.optDouble("widthMeters", d.widthMeters),
            j.optDouble("eastOffsetMeters", d.eastOffsetMeters),
            j.optDouble("southOffsetMeters", d.southOffsetMeters),
            j.optDouble("rotationDegrees", d.rotationDegrees),
            j.optJSONObject("runway")?.let { r ->
                RunwayPlacement(
                    r.optDouble("centreX", 0.0), r.optDouble("centreY", 0.0),
                    r.optDouble("length", 0.5), r.optDouble("width", 0.06), r.optDouble("angleDegrees", 0.0),
                )
            },
        ).clamped()
    }

    override fun saveDropzone(s: DropzoneSettings) {
        val j = JSONObject()
            .put("widthMeters", s.widthMeters)
            .put("eastOffsetMeters", s.eastOffsetMeters)
            .put("southOffsetMeters", s.southOffsetMeters)
            .put("rotationDegrees", s.rotationDegrees)
        s.runway?.let { r ->
            j.put(
                "runway",
                JSONObject().put("centreX", r.centreX).put("centreY", r.centreY).put("length", r.length)
                    .put("width", r.width).put("angleDegrees", r.angleDegrees),
            )
        }
        prefs.edit().putString("dropzone", j.toString()).apply()
    }

    override fun loadDropzoneImage(): Bitmap? =
        if (imageFile.exists()) BitmapFactory.decodeFile(imageFile.path) else null

    /** Scaled to at most 2048 px on its longest side and kept as a JPEG. */
    override fun saveDropzoneImage(b: Bitmap): Bitmap? = try {
        val factor = min(1.0, MAXIMUM_PIXEL_SIZE / max(b.width, b.height).toDouble())
        val scaled = if (factor < 1) {
            Bitmap.createScaledBitmap(b, max(1, (b.width * factor).roundToInt()), max(1, (b.height * factor).roundToInt()), true)
        } else b
        imageFile.parentFile?.mkdirs()
        imageFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        BitmapFactory.decodeFile(imageFile.path) ?: scaled
    } catch (e: Exception) {
        null
    }

    override fun removeDropzoneImage() {
        imageFile.delete()
    }

    override fun loadClouds(): CloudSettings {
        val d = CloudSettings()
        val j = json("clouds") ?: return d
        return CloudSettings(
            j.optDouble("baseAltitudeMeters", d.baseAltitudeMeters),
            j.optDouble("thicknessMeters", d.thicknessMeters),
            j.optDouble("density", d.density),
        ).clamped()
    }

    override fun saveClouds(c: CloudSettings) {
        val j = JSONObject().put("baseAltitudeMeters", c.baseAltitudeMeters).put("thicknessMeters", c.thicknessMeters).put("density", c.density)
        prefs.edit().putString("clouds", j.toString()).apply()
    }

    override fun loadTraffic(): TrafficSettings {
        val d = TrafficSettings()
        val j = json("traffic") ?: return d
        return TrafficSettings(
            j.optInt("count", d.count),
            j.optBoolean("showsWakes", d.showsWakes),
            j.optBoolean("aircraftLanding", d.aircraftLanding),
        ).clamped()
    }

    override fun saveTraffic(t: TrafficSettings) {
        val j = JSONObject().put("count", t.count).put("showsWakes", t.showsWakes).put("aircraftLanding", t.aircraftLanding)
        prefs.edit().putString("traffic", j.toString()).apply()
    }

    override fun loadAircraftLivery() =
        AircraftLiverySettings(prefs.getInt("aircraft-livery", AircraftLiverySettings.DEFAULT_COLOR_HEX))

    override fun saveAircraftLivery(l: AircraftLiverySettings) = prefs.edit().putInt("aircraft-livery", l.colorHex).apply()

    override fun loadAircraftTouchdown() =
        AircraftTouchdownSettings(prefs.getFloat("aircraft-touchdown", AircraftTouchdownSettings().metersFromThreshold.toFloat()).toDouble()).clamped()

    override fun saveAircraftTouchdown(t: AircraftTouchdownSettings) =
        prefs.edit().putFloat("aircraft-touchdown", t.metersFromThreshold.toFloat()).apply()

    override fun loadAircraftMarkings(): AircraftMarkingsSettings {
        val d = AircraftMarkingsSettings()
        val j = json("aircraft-markings") ?: return d
        val logo = j.optJSONObject("logo")
        return AircraftMarkingsSettings(
            j.optString("registration", d.registration),
            AircraftLogoPlacement(
                logo?.optDouble("along", d.logo.along) ?: d.logo.along,
                logo?.optDouble("height", d.logo.height) ?: d.logo.height,
                logo?.optDouble("width", d.logo.width) ?: d.logo.width,
            ),
        ).clamped()
    }

    override fun saveAircraftMarkings(m: AircraftMarkingsSettings) {
        val j = JSONObject()
            .put("registration", m.registration)
            .put("logo", JSONObject().put("along", m.logo.along).put("height", m.logo.height).put("width", m.logo.width))
        prefs.edit().putString("aircraft-markings", j.toString()).apply()
    }

    override fun loadAircraftLogo(): Bitmap? =
        if (logoFile.exists()) BitmapFactory.decodeFile(logoFile.path) else null

    override fun saveAircraftLogo(b: Bitmap): Bitmap? = try {
        val data = AircraftLogoCompression.compressed(b)
        if (data == null) null else {
            logoFile.parentFile?.mkdirs()
            val temporary = File(logoFile.path + ".tmp")
            temporary.writeBytes(data)
            if (!temporary.renameTo(logoFile)) { logoFile.delete(); temporary.renameTo(logoFile) }
            BitmapFactory.decodeByteArray(data, 0, data.size)
        }
    } catch (e: Exception) {
        null
    }

    override fun removeAircraftLogo() {
        logoFile.delete()
    }

    override fun loadAutoFirstPerson() = prefs.getBoolean("auto-first-person", true)
    override fun saveAutoFirstPerson(on: Boolean) = prefs.edit().putBoolean("auto-first-person", on).apply()

    override fun loadFirstPersonAltitude(): Double? =
        if (prefs.contains("first-person-altitude")) prefs.getFloat("first-person-altitude", 30f).toDouble() else null

    override fun saveFirstPersonAltitude(m: Double) = prefs.edit().putFloat("first-person-altitude", m.toFloat()).apply()

    private fun json(key: String): JSONObject? = prefs.getString(key, null)?.let {
        try { JSONObject(it) } catch (e: Exception) { null }
    }

    companion object {
        const val MAXIMUM_PIXEL_SIZE = 2048.0
    }
}

/**
 * The picture on the P750's sides, encoded under a megabyte: with a clear
 * background it stays a PNG, drawn smaller until it fits; otherwise it is a
 * JPEG, its quality and then its size brought down until it fits.
 */
object AircraftLogoCompression {
    const val MAXIMUM_PIXEL_SIZE = 1_024
    const val MAXIMUM_BYTES = 1_000_000

    fun compressed(image: Bitmap): ByteArray? {
        var longest = min(MAXIMUM_PIXEL_SIZE, max(image.width, image.height))
        if (longest <= 0) return null
        var opaque: Boolean? = null
        repeat(8) {
            val factor = min(1.0, longest / max(1, max(image.width, image.height)).toDouble())
            val drawn = if (factor < 1) {
                Bitmap.createScaledBitmap(image, max(1, (image.width * factor).roundToInt()), max(1, (image.height * factor).roundToInt()), true)
            } else image
            val isOpaque = opaque ?: isOpaque(drawn).also { opaque = it }
            if (isOpaque) {
                for (quality in listOf(85, 70, 55)) {
                    val data = encode(drawn, Bitmap.CompressFormat.JPEG, quality)
                    if (data.size <= MAXIMUM_BYTES) return data
                }
            } else {
                val data = encode(drawn, Bitmap.CompressFormat.PNG, 100)
                if (data.size <= MAXIMUM_BYTES) return data
            }
            longest = (longest * 0.75).toInt()
            if (longest < 64) return null
        }
        return null
    }

    private fun encode(b: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        b.compress(format, quality, out)
        return out.toByteArray()
    }

    /** Whether the picture has no clear parts to keep. */
    private fun isOpaque(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return true
        val row = IntArray(b.width)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (pixel in row) if ((pixel ushr 24) < 0xff) return false
        }
        return true
    }
}
