package com.skyalt.canopy.tools.map

import android.graphics.Bitmap
import android.graphics.Point
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import com.skyalt.canopy.ui.LocalStrings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.skyalt.canopy.tools.planning.MercatorRect
import com.skyalt.canopy.tools.planning.PlanningPath3DContent
import com.skyalt.canopy.tools.planning.SavedMapView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A planning tool's 3D view: whether it is shown, the request for the top
 * view, and the picture of the map under it. The map is edited on the map;
 * 3D only shows the plan.
 */
class Planning3DState {
    var shows by mutableStateOf(false)
    var topViewRequest by mutableIntStateOf(0)
        private set
    var ground by mutableStateOf<Planning3DGround?>(null)
        internal set
    /** While the map is photographed for the ground, it shows no plan, marker or location dot. */
    var capturing by mutableStateOf(false)
        internal set
    internal var map: GoogleMap? = null
    /** The panels over the 3D view, in window pixels, by name. */
    internal val obstructions = mutableStateMapOf<String, android.graphics.RectF>()

    fun toggle() {
        shows = !shows
    }

    fun requestTopView() {
        topViewRequest += 1
    }
}

@Composable
fun rememberPlanning3DState() = remember { Planning3DState() }

/**
 * Marks a panel that lies over the 3D view, so the paths are framed clear
 * of it, as planningPath3DObstruction() on the iPhone. [key] names it
 * among the tool's panels.
 */
fun Modifier.planning3DObstruction(state: Planning3DState, key: String): Modifier = composed {
    DisposableEffect(state, key) { onDispose { state.obstructions.remove(key) } }
    onGloballyPositioned { coordinates ->
        val b = coordinates.boundsInWindow()
        val rect = android.graphics.RectF(b.left, b.top, b.right, b.bottom)
        if (state.obstructions[key] != rect) state.obstructions[key] = rect
    }
}

/** Lets the 3D view borrow the map to photograph the ground; call it inside the GoogleMap content. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun Planning3DMapHook(state: Planning3DState) {
    MapEffect(Unit) { map -> state.map = map }
}

/**
 * Keeps a picture of the map under the 3D view: a square well beyond what is
 * shown, in the map's style, taken again when the plan outgrows it or the
 * style changes. The map is moved to take it and put back; [holdMapView]
 * gets the view to save meanwhile.
 */
@Composable
fun Planning3DGroundLoader(state: Planning3DState, content: PlanningPath3DContent, style: PlanningMapStyle, holdMapView: (SavedMapView?) -> Unit = {}) {
    val needed = content.neededRect
    LaunchedEffect(state.shows, needed, style) {
        if (!state.shows || needed == null) return@LaunchedEffect
        val current = state.ground
        if (current != null && current.style == style && MercatorRect.groundSuits(current.rect, needed)) return@LaunchedEffect
        // A short pause, so a slider drag photographs once, where it stops.
        delay(if (current == null) 0L else 400L)
        val map = state.map ?: return@LaunchedEffect
        captureGround(state, map, MercatorRect.squareGround(needed), style, holdMapView)?.let { state.ground = it }
    }
}

private suspend fun captureGround(
    state: Planning3DState,
    map: GoogleMap,
    rect: MercatorRect,
    style: PlanningMapStyle,
    holdMapView: (SavedMapView?) -> Unit,
): Planning3DGround? {
    val held = map.cameraPosition
    holdMapView(held.savedMapView)
    state.capturing = true
    try {
        // Let the map drop the plan drawn on it.
        delay(120)
        val bounds = LatLngBounds(rect.southWest.latLng(), rect.northEast.latLng())
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0))
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { continuation ->
                map.setOnMapLoadedCallback { if (continuation.isActive) continuation.resume(Unit) }
                // Not here: a timeout cancels on another thread, and the map may only be touched on the main one.
            }
        }
        map.setOnMapLoadedCallback(null)
        delay(150)
        val bitmap: Bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
            map.snapshot { if (continuation.isActive) continuation.resume(it) }
        } ?: return null
        val projection = map.projection
        val northWest = projection.fromScreenLocation(Point(0, 0)).coordinate()
        val southEast = projection.fromScreenLocation(Point(bitmap.width, bitmap.height)).coordinate()
        return Planning3DGround(
            bitmap,
            MercatorRect.of(
                com.skyalt.canopy.tools.planning.Coordinate(southEast.latitude, northWest.longitude),
                com.skyalt.canopy.tools.planning.Coordinate(northWest.latitude, southEast.longitude),
            ),
            style,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    } finally {
        // On the main thread: the coroutine runs on it.
        map.setOnMapLoadedCallback(null)
        map.moveCamera(CameraUpdateFactory.newCameraPosition(held))
        state.capturing = false
        holdMapView(null)
    }
}

/**
 * The 3D button and, in 3D, the way back to the top view, in the map's
 * button column. [onToggle] runs before the switch.
 */
@Composable
fun Planning3DButtons(state: Planning3DState, enabled: Boolean = true, onToggle: () -> Unit = {}) {
    val s = LocalStrings.current
    MapButton(
        if (state.shows) Icons.Filled.Map else Icons.Filled.ViewInAr,
        s(if (state.shows) "Show Map" else "Show in 3D"),
        isActive = state.shows,
        enabled = enabled || state.shows,
    ) {
        onToggle()
        state.toggle()
    }
    if (state.shows) {
        MapButton(Icons.Filled.Navigation, s("Top View")) { state.requestTopView() }
    }
}

/** The 3D view over the map while [state] shows it. */
@Composable
fun Planning3DLayer(state: Planning3DState, content: PlanningPath3DContent, modifier: Modifier = Modifier) {
    if (!state.shows) return
    val appliedRequest = remember { intArrayOf(state.topViewRequest) }
    AndroidView(
        factory = { PlanningPath3DView(it) },
        modifier = modifier,
        update = { view ->
            view.update(content, state.ground)
            view.setObstructions(state.obstructions.values.sortedBy { it.top * 10_000 + it.left })
            if (appliedRequest[0] != state.topViewRequest) {
                appliedRequest[0] = state.topViewRequest
                view.showTopView(animated = true)
            }
        },
    )
}
