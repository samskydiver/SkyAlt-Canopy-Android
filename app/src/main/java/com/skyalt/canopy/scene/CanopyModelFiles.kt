package com.skyalt.canopy.scene

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.skyalt.canopy.model.CanopyModel

/**
 * The bundled PD models and previews, each read once and shared: the
 * parsed files for the flown rig and the canopy page's 3D view, the
 * decoded pictures for its tiles. Safe to call off the main thread.
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
