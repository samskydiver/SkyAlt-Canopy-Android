package com.skyalt.canopy.scene

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.skyalt.canopy.model.CanopyModel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bundled PD models and previews and the P750XL, each read once and
 * shared: the parsed files for the flown rig and the canopy page's 3D view,
 * the decoded pictures for its tiles, the aircraft for the scenes. Safe to
 * call off the main thread.
 */
object CanopyModelFiles {
    @Volatile private var assets: AssetManager? = null
    private val models = HashMap<String, Glb.Model>()
    private val previews = HashMap<String, Bitmap>()

    fun init(context: Context) {
        if (assets == null) assets = context.applicationContext.assets
    }

    fun model(model: CanopyModel): Glb.Model? {
        synchronized(models) { models[model.key] }?.let { return it }
        val manager = assets ?: return null
        return try {
            val parsed = manager.open(model.modelAsset).use { Glb.parse(it.readBytes()) }
            synchronized(models) { models[model.key] = parsed }
            parsed
        } catch (e: Exception) {
            Log.e("CanopyModel", "could not read ${model.modelAsset}", e)
            null
        }
    }

    /** A new rig of the series, to fly or to show; each one keeps its own shape. */
    fun rig(model: CanopyModel): CanopyModelRig? = model(model)?.let { CanopyModelRig.build(it, model.key) }

    @Volatile private var aircraftModel: AircraftModel.Prepared? = null
    private var aircraftFailed = false
    private val aircraftLock = Any()
    private val aircraftLoading = AtomicBoolean(false)

    /** The P750XL, once it has been read; null while it is still loading. */
    fun loadedAircraft(): AircraftModel.Prepared? = aircraftModel

    /** Starts reading the P750XL off the calling thread, unless it is read or being read. */
    fun preloadAircraft() {
        if (aircraftModel != null || !aircraftLoading.compareAndSet(false, true)) return
        Thread({
            try { aircraft() } finally { aircraftLoading.set(false) }
        }, "aircraft-model").start()
    }

    /** The P750XL, reading it the first time (a second caller waits for the first); null when the file cannot be read. */
    fun aircraft(): AircraftModel.Prepared? {
        aircraftModel?.let { return it }
        synchronized(aircraftLock) {
            aircraftModel?.let { return it }
            if (aircraftFailed) return null
            val manager = assets ?: return null
            return try {
                manager.open(AircraftModel.ASSET).use { AircraftModel.prepare(Glb.parse(it.readBytes())) }.also { aircraftModel = it }
            } catch (e: Exception) {
                Log.e("AircraftModel", "could not read ${AircraftModel.ASSET}", e)
                aircraftFailed = true
                null
            }
        }
    }

    fun preview(model: CanopyModel): Bitmap? {
        synchronized(previews) { previews[model.key] }?.let { return it }
        val manager = assets ?: return null
        return try {
            val bitmap = manager.open(model.previewAsset).use { BitmapFactory.decodeStream(it) } ?: return null
            synchronized(previews) { previews[model.key] = bitmap }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}

/** Rig parts on the GPU, each uploaded again when its shape has changed. */
open class GpuParts(val parts: List<RigPart>) {
    private val meshes = parts.map { GpuMesh() }
    private val uploaded = IntArray(parts.size) { -1 }

    /** Brings the GPU copies up to date; call on the GL thread before drawing. */
    fun sync() {
        for ((i, part) in parts.withIndex()) {
            val version = part.version
            if (uploaded[i] == version) continue
            meshes[i].set(part.positions, normals = part.normals, colors = part.colors, indices = part.indices)
            uploaded[i] = version
        }
    }

    /** Each part with its own mesh. */
    inline fun forEachPart(action: (GpuMesh, RigPart) -> Unit) {
        for (i in parts.indices) action(meshAt(i), parts[i])
    }

    fun meshAt(i: Int) = meshes[i]

    fun release() {
        for (m in meshes) m.release()
    }
}

/** A flown rig's parts on the GPU, following its shape. */
class GpuRig(val rig: CanopyModelRig) : GpuParts(rig.parts)
