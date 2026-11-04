package com.skyalt.canopy.tools.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.MapType
import com.skyalt.canopy.tools.planning.Coordinate
import com.skyalt.canopy.tools.planning.ManualWindLayer
import com.skyalt.canopy.tools.planning.MapGeometry
import com.skyalt.canopy.tools.planning.WindMath
import com.skyalt.canopy.tools.planning.WindProfile
import com.skyalt.canopy.tools.planning.WindProfileClient
import com.skyalt.canopy.tools.planning.WindServiceException
import com.skyalt.canopy.ui.LocalStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant

fun Coordinate.latLng() = LatLng(latitude, longitude)
fun LatLng.coordinate() = Coordinate(latitude, longitude)

/** Auto (forecast) or manually entered winds. */
enum class WindSourceMode(val titleKey: String, val statusKey: String, val icon: ImageVector) {
    FORECAST("Auto", "Auto wind", Icons.Filled.Air),
    MANUAL("Manual", "Manual wind", Icons.Filled.Tune),
}

/** Base map imagery the planning tools cycle through with one button. */
enum class PlanningMapStyle(val titleKey: String, val mapType: MapType, val icon: ImageVector) {
    SATELLITE("Satellite", MapType.SATELLITE, Icons.Filled.Public),
    HYBRID("Hybrid", MapType.HYBRID, Icons.Filled.Layers),
    STANDARD("Standard", MapType.NORMAL, Icons.Filled.Map);

    val next get() = entries[(ordinal + 1) % entries.size]
}

/** The default wind column for manual mode. */
val DEFAULT_MANUAL_LAYERS: List<ManualWindLayer>
    get() = listOf(
        ManualWindLayer(altitudeAGLFeet = 0.0, speedKnots = 5.0, directionFromDegrees = 270.0),
        ManualWindLayer(altitudeAGLFeet = 3_000.0, speedKnots = 10.0, directionFromDegrees = 260.0),
        ManualWindLayer(altitudeAGLFeet = 6_000.0, speedKnots = 15.0, directionFromDegrees = 250.0),
        ManualWindLayer(altitudeAGLFeet = 10_000.0, speedKnots = 20.0, directionFromDegrees = 240.0),
        ManualWindLayer(altitudeAGLFeet = 14_000.0, speedKnots = 25.0, directionFromDegrees = 230.0),
    )

/** A camera move a session asks its map for. */
sealed interface CameraRequest {
    data class Center(val coordinate: Coordinate) : CameraRequest
    data class Fit(val coordinates: List<Coordinate>) : CameraRequest
}

/** Default view: Bangkok, as on iOS, about 1.3 km across. */
val DEFAULT_MAP_CENTER = Coordinate(13.736_717, 100.523_186)
const val DEFAULT_MAP_ZOOM = 15.5f

/** Applies a session's pending camera request to the map, animated. */
@Composable
fun ApplyCameraRequests(camera: CameraPositionState, request: CameraRequest?, onDone: () -> Unit) {
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        try {
            when (r) {
                is CameraRequest.Center -> camera.animate(CameraUpdateFactory.newLatLngZoom(r.coordinate.latLng(), DEFAULT_MAP_ZOOM), 350)
                is CameraRequest.Fit -> {
                    val region = MapGeometry.region(r.coordinates, minimumSpanDegrees = 0.003, paddingFactor = 1.75)
                    if (region != null) {
                        val c = region.center
                        val bounds = LatLngBounds(
                            LatLng(c.latitude - region.latitudeDelta / 2, c.longitude - region.longitudeDelta / 2),
                            LatLng(c.latitude + region.latitudeDelta / 2, c.longitude + region.longitudeDelta / 2),
                        )
                        camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 0), 350)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The map was not laid out yet; the next request will move it.
        }
        onDone()
    }
}

/**
 * The wind source of a planning tool: the forecast for the tool's anchor
 * point and hour, or the manually entered layers. [onChange] runs whenever
 * the profile is replaced or cleared, so the owner can recalculate.
 */
class WindController(
    private val scope: CoroutineScope,
    private val client: WindProfileClient,
    private val anchor: () -> Coordinate?,
    initialLayers: List<ManualWindLayer> = DEFAULT_MANUAL_LAYERS,
    private val onChange: () -> Unit,
) {
    var forecastDate by mutableStateOf(WindProfile.forecastHour(Instant.now()))
        private set
    var mode by mutableStateOf(WindSourceMode.FORECAST)
        private set
    var profile by mutableStateOf<WindProfile?>(null)
        private set
    /** The strings key of the last fetch error. */
    var errorKey by mutableStateOf<String?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var manualTerrainFeet by mutableDoubleStateOf(0.0)
        private set
    var manualLayers by mutableStateOf(initialLayers)
        private set

    private var job: Job? = null
    private var revision = 0

    fun selectMode(newMode: WindSourceMode) {
        if (newMode == mode) return
        mode = newMode
        refresh()
    }

    fun selectForecastDate(date: Instant) {
        val normalized = WindProfile.forecastHour(date)
        if (normalized == forecastDate) return
        forecastDate = normalized
        refresh()
    }

    fun updateManualTerrain(feet: Double) {
        if (feet == manualTerrainFeet) return
        manualTerrainFeet = feet
        if (mode == WindSourceMode.MANUAL) applyManual()
    }

    fun updateManualLayers(layers: List<ManualWindLayer>) {
        if (layers == manualLayers) return
        manualLayers = layers
        if (mode == WindSourceMode.MANUAL) applyManual()
    }

    /** Drops the current profile and loads winds for the anchor again. */
    fun reload() {
        profile = null
        errorKey = null
        onChange()
        refresh()
    }

    /** Stops any fetch and forgets the profile. */
    fun clear() {
        cancel()
        profile = null
        errorKey = null
        isLoading = false
    }

    private fun refresh() = if (mode == WindSourceMode.FORECAST) requestForecast() else applyManual()

    private fun cancel() {
        revision += 1
        job?.cancel()
        job = null
    }

    private fun requestForecast() {
        val coordinate = anchor() ?: return
        cancel()
        val myRevision = revision
        val validAt = forecastDate
        isLoading = true
        errorKey = null
        job = scope.launch {
            try {
                val fetched = client.fetch(coordinate, validAt)
                if (myRevision != revision || mode != WindSourceMode.FORECAST) return@launch
                profile = fetched
                errorKey = null
                isLoading = false
                onChange()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (myRevision != revision) return@launch
                profile = null
                errorKey = (e as? WindServiceException)?.key ?: "The weather service could not be reached."
                isLoading = false
                onChange()
            }
        }
    }

    private fun applyManual() {
        val coordinate = anchor() ?: return
        cancel()
        profile = WindProfile.manual(
            coordinate, manualTerrainFeet * WindMath.METERS_PER_FOOT, forecastDate, manualLayers,
            source = "Manual", model = "User-entered wind layers",
        )
        errorKey = null
        isLoading = false
        onChange()
    }
}

/** Follows the device location while a planning map is on screen. */
class MapLocationTracker(private val context: Context) {
    var location by mutableStateOf<Location?>(null)
        private set
    var hasPermission by mutableStateOf(granted(context))
        internal set
    internal var requestPermission: () -> Unit = {}

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location = it }
        }
    }
    private var running = false

    /** Starts updates, asking for the location permission first if needed. */
    fun start() {
        if (!hasPermission) {
            requestPermission()
            return
        }
        startUpdates()
    }

    @SuppressLint("MissingPermission")
    internal fun startUpdates() {
        if (running || !granted(context)) return
        running = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000).build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { last -> if (last != null && location == null) location = last }
    }

    fun stop() {
        if (!running) return
        running = false
        client.removeLocationUpdates(callback)
    }

    companion object {
        fun granted(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

/** A location tracker that runs while the calling screen is shown. */
@Composable
fun rememberMapLocationTracker(): MapLocationTracker {
    val context = LocalContext.current
    val tracker = remember { MapLocationTracker(context.applicationContext) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tracker.hasPermission = MapLocationTracker.granted(context)
        if (tracker.hasPermission) tracker.startUpdates()
    }
    tracker.requestPermission = {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    DisposableEffect(tracker) {
        tracker.start()
        onDispose { tracker.stop() }
    }
    return tracker
}

/** The dark round button stacked on the right edge of a planning map. */
@Composable
fun MapButton(
    icon: ImageVector,
    contentDescription: String,
    showsProgress: Boolean = false,
    isActive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(52.dp)
            .shadow(4.dp, CircleShape)
            .background(if (isActive) Color(0xFF32ADE6) else Color.Black.copy(alpha = 0.72f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (showsProgress) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
        }
    }
}

/** The dark "Loading data…" pill shown over a planning map. */
@Composable
fun MapLoadingPill(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier
            .shadow(10.dp, RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
        Text(s("Loading data…"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** A coloured instruction banner over the map. */
@Composable
fun MapInstructionBanner(text: String, tint: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(5.dp, RoundedCornerShape(13.dp))
            .background(tint.copy(alpha = 0.92f), RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
