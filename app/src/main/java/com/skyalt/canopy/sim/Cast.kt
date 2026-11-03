package com.skyalt.canopy.sim

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Casting: the 3D view goes to a second display (a TV over wireless
 * projection / Miracast, or a USB-C/HDMI cable) while the phone keeps the
 * controls. Android reports such a display as a presentation display; the
 * activity puts a [android.app.Presentation] on it.
 */
class CastController(context: Context) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    /** The display the 3D view goes to, or null while none is connected. */
    var display by mutableStateOf<Display?>(null)
        private set

    /** The phone is in its casting layout (Cast tapped), with or without a display. */
    var isCasting by mutableStateOf(false)

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun activate() {
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun deactivate() {
        displayManager.unregisterDisplayListener(listener)
    }

    private fun refresh() {
        val found = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (found?.displayId != display?.displayId) display = found
    }
}
