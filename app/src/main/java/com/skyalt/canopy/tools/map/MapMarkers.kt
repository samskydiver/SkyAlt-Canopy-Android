package com.skyalt.canopy.tools.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.skyalt.canopy.tools.planning.Coordinate
import kotlin.math.atan2

/**
 * A marker that can be dragged (after a long press, as Google Maps does).
 * [onDrag] follows the finger, [onDragEnd] gets the drop position.
 */
@Composable
@GoogleMapComposable
fun DraggableMapMarker(
    id: String,
    coordinate: Coordinate,
    vararg contentKeys: Any,
    anchor: Offset = Offset(0.5f, 0.5f),
    zIndex: Float = 2f,
    onClick: () -> Unit = {},
    onDrag: (Coordinate) -> Unit = {},
    onDragEnd: (Coordinate) -> Unit,
    content: @Composable () -> Unit,
) {
    val state = remember(id) { MarkerState(coordinate.latLng()) }
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    LaunchedEffect(state, coordinate) {
        if (!state.isDragging) state.position = coordinate.latLng()
    }
    LaunchedEffect(state) {
        var dragging = false
        snapshotFlow { state.isDragging to state.position }.collect { (isDragging, position) ->
            if (isDragging) {
                dragging = true
                drag(position.coordinate())
            } else if (dragging) {
                dragging = false
                dragEnd(position.coordinate())
            }
        }
    }
    MarkerComposable(
        *contentKeys,
        state = state,
        anchor = anchor,
        draggable = true,
        zIndex = zIndex,
        onClick = { onClick(); true },
        content = content,
    )
}

/** A fixed marker that swallows taps so the map does not recentre on it. */
@Composable
@GoogleMapComposable
fun StaticMapMarker(
    coordinate: Coordinate,
    vararg contentKeys: Any,
    anchor: Offset = Offset(0.5f, 0.5f),
    rotation: Float = 0f,
    flat: Boolean = false,
    zIndex: Float = 1f,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state = remember { MarkerState(coordinate.latLng()) }
    state.position = coordinate.latLng()
    MarkerComposable(
        *contentKeys,
        state = state,
        anchor = anchor,
        rotation = rotation,
        flat = flat,
        zIndex = zIndex,
        onClick = { onClick(); true },
        content = content,
    )
}

/** A round marker with a white symbol, for a plan's anchor point. */
@Composable
fun BasePointBadge(icon: ImageVector, color: Color, size: Int = 38) {
    Box(
        Modifier.size(size.dp).background(color, CircleShape).border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.55).dp))
    }
}

/** The small arrow at the middle of a leg; the marker is rotated to the leg's bearing. */
@Composable
fun LegArrowBadge(color: Color) {
    Box(
        Modifier.size(20.dp).background(color, CircleShape).border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** A dark capsule label, as the altitude above a checkpoint. */
@Composable
fun MapLabel(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** A label above a coloured dot; anchor it at [LABELED_DOT_ANCHOR]. */
@Composable
fun LabeledDot(label: String, color: Color, dotSize: Int = 20) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MapLabel(label)
        Box(Modifier.size(dotSize.dp).background(color, CircleShape).border(2.dp, Color.White, CircleShape))
    }
}

/** Anchor for [LabeledDot]: centre of the dot, near the bottom. */
fun labeledDotAnchor(dotSize: Int = 20, labelHeight: Int = 21, spacing: Int = 4): Offset {
    val height = labelHeight + spacing + dotSize
    return Offset(0.5f, (height - dotSize / 2f) / height)
}

/** Bearing in degrees of [to] from [from] on screen (y down), clockwise from up. */
fun screenBearing(from: Offset, to: Offset): Double = Math.toDegrees(atan2((to.x - from.x).toDouble(), -(to.y - from.y).toDouble()))

/** Signed smallest angle from [from] to [to], −180…180. */
fun signedDelta(from: Double, to: Double): Double {
    var d = (to - from) % 360
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lengthSquared = ab.x * ab.x + ab.y * ab.y
    if (lengthSquared == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lengthSquared).coerceIn(0f, 1f)
    return (p - Offset(a.x + ab.x * t, a.y + ab.y * t)).getDistance()
}

/**
 * Dragging along a drawn pattern rotates it about [pivot]: the touch must
 * start within 22 dp of a path (and away from the pivot marker itself).
 * The map keeps every other gesture. [onRotate] gets the running delta in
 * degrees, [onEnd] the final one.
 */
fun Modifier.patternRotationGesture(
    camera: CameraPositionState,
    enabled: () -> Boolean,
    pivot: () -> Coordinate?,
    paths: () -> List<List<Coordinate>>,
    onBegin: () -> Unit,
    onRotate: (Double) -> Unit,
    onEnd: (Double) -> Unit,
): Modifier = pointerInput(Unit) {
    val hitRadius = 22.dp.toPx()
    val pivotRadius = 30.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        val projection = camera.projection ?: return@awaitEachGesture
        val center = pivot() ?: return@awaitEachGesture
        fun screen(c: Coordinate) = projection.toScreenLocation(c.latLng()).let { Offset(it.x.toFloat(), it.y.toFloat()) }
        val pivotPoint = screen(center)
        if ((down.position - pivotPoint).getDistance() < pivotRadius) return@awaitEachGesture
        val onPath = paths().any { path ->
            path.map(::screen).zipWithNext().any { (a, b) -> distanceToSegment(down.position, a, b) <= hitRadius }
        }
        if (!onPath) return@awaitEachGesture

        val slop = viewConfiguration.touchSlop
        val startBearing = screenBearing(pivotPoint, down.position)
        var rotating = false
        var delta = 0.0
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!rotating && event.changes.size > 1) return@awaitEachGesture // a pinch belongs to the map
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (rotating) change.consume()
                break
            }
            if (!rotating) {
                if ((change.position - down.position).getDistance() < slop) continue
                rotating = true
                onBegin()
            }
            event.changes.forEach { it.consume() }
            delta = signedDelta(startBearing, screenBearing(pivotPoint, change.position))
            onRotate(delta)
        }
        if (rotating) onEnd(delta)
    }
}
