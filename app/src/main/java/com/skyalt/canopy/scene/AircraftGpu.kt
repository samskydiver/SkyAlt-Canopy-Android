package com.skyalt.canopy.scene

import android.graphics.Bitmap
import com.skyalt.canopy.model.AircraftLogoPlacement
import com.skyalt.canopy.model.V3

/**
 * The P750XL on the GPU of one GL context: the airframe, the registration
 * patches and logo decals to draw over it, and the blades to turn about
 * [hub]. Each layer is built again only when its markings change, and a new
 * livery only repaints the wings and tail. Make a new one for a new context;
 * call everything on its GL thread.
 */
class AircraftGpu {
    var body: List<GpuPart> = emptyList(); private set
    /** The registration and the logo, drawn after [body] and pulled towards the eye. */
    var decals: List<GpuPart> = emptyList(); private set
    var propeller: List<GpuPart> = emptyList(); private set
    val hub: V3 get() = AircraftModel.PROPELLER_HUB

    private var prepared: AircraftModel.Prepared? = null
    private var liveryColors: List<FloatArray> = emptyList()
    private var registrationParts: List<GpuPart> = emptyList()
    private var logoParts: List<GpuPart> = emptyList()
    private var livery: Int? = null
    private var registration: String? = null
    private var logo: Bitmap? = null
    private var placement: AircraftLogoPlacement? = null

    /**
     * Brings the parts up to these markings. False while the model is still
     * loading: it is then read in the background, or here when [wait].
     */
    fun sync(livery: Int, registration: String, logo: Bitmap?, placement: AircraftLogoPlacement, wait: Boolean = false): Boolean {
        val model = prepared ?: when {
            wait -> CanopyModelFiles.aircraft()
            else -> CanopyModelFiles.loadedAircraft().also { if (it == null) CanopyModelFiles.preloadAircraft() }
        } ?: return false
        if (prepared == null) {
            prepared = model
            val parts = Models.aircraftBody(model, livery)
            body = GpuPart.of(parts)
            liveryColors = model.body.indices.filter { model.body[it].paint == AircraftModel.Paint.LIVERY }.map { parts[it].color }
            propeller = GpuPart.of(Models.aircraftPropeller(model))
            this.livery = livery
        }
        if (this.livery != livery) {
            val paint = rgba(livery)
            for (color in liveryColors) paint.copyInto(color)
            this.livery = livery
        }
        var changed = false
        if (this.registration != registration) {
            registrationParts.forEach { it.release() }
            registrationParts = GpuPart.of(Models.aircraftRegistration(model, registration))
            this.registration = registration
            changed = true
        }
        if (this.logo !== logo || this.placement != placement) {
            logoParts.forEach { it.release() }
            logoParts = GpuPart.of(Models.aircraftLogo(logo, placement))
            this.logo = logo
            this.placement = placement
            changed = true
        }
        if (changed) decals = registrationParts + logoParts
        return true
    }

    fun release() {
        for (part in body + registrationParts + logoParts + propeller) part.release()
        body = emptyList(); decals = emptyList(); propeller = emptyList()
        registrationParts = emptyList(); logoParts = emptyList(); liveryColors = emptyList()
        prepared = null; livery = null; registration = null; logo = null; placement = null
    }
}
