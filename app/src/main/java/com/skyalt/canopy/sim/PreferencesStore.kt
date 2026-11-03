package com.skyalt.canopy.sim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.skyalt.canopy.model.CanopyType
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

    override fun loadConfiguration(): SimulatorConfiguration {
        val d = SimulatorConfiguration()
        val j = json("configuration") ?: return d
        return SimulatorConfiguration(
            startAltitudeMeters = j.optDouble("startAltitudeMeters", d.startAltitudeMeters),
            exitWeightKilograms = j.optDouble("exitWeightKilograms", d.exitWeightKilograms),
            canopyAreaSquareFeet = j.optDouble("canopyAreaSquareFeet", d.canopyAreaSquareFeet),
            canopyType = CanopyType.fromKey(j.optString("canopyType", d.canopyType.key)),
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
        ).clamped()
    }

    override fun saveConfiguration(c: SimulatorConfiguration) {
        val j = JSONObject()
            .put("startAltitudeMeters", c.startAltitudeMeters)
            .put("exitWeightKilograms", c.exitWeightKilograms)
            .put("canopyAreaSquareFeet", c.canopyAreaSquareFeet)
            .put("canopyType", c.canopyType.key)
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
        ).clamped()
    }

    override fun saveDropzone(s: DropzoneSettings) {
        val j = JSONObject()
            .put("widthMeters", s.widthMeters)
            .put("eastOffsetMeters", s.eastOffsetMeters)
            .put("southOffsetMeters", s.southOffsetMeters)
            .put("rotationDegrees", s.rotationDegrees)
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
