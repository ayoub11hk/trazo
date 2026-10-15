package app.trazo.map

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.trazo.core.geo.LatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.geometry.LatLng as MLatLng

fun LatLng.toML() = MLatLng(lat, lng)

/** Holder that screens use to drive the map imperatively once it is ready. */
class MapHandle internal constructor(val map: MapLibreMap, val style: Style, private val context: Context) {
    val density: Float get() = context.resources.displayMetrics.density

    fun moveTo(p: LatLng, zoom: Double? = null, tilt: Double? = null, bearing: Double? = null, animateMs: Int = 600) {
        val b = CameraPosition.Builder(map.cameraPosition).target(p.toML())
        zoom?.let { b.zoom(it) }
        tilt?.let { b.tilt(it) }
        bearing?.let { b.bearing(it) }
        val upd = CameraUpdateFactory.newCameraPosition(b.build())
        if (animateMs > 0) map.animateCamera(upd, animateMs) else map.moveCamera(upd)
    }

    fun fit(points: List<LatLng>, paddingPx: Int = (64 * density).toInt(), animateMs: Int = 700) {
        if (points.isEmpty()) return
        if (points.size == 1 || points.all { it == points[0] }) { moveTo(points[0], zoom = 15.0); return }
        val bounds = LatLngBounds.Builder().apply { points.forEach { include(it.toML()) } }.build()
        val upd = CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
        if (animateMs > 0) map.animateCamera(upd, animateMs) else map.moveCamera(upd)
    }

    fun setTilt(tilt: Double) {
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition).tilt(tilt).build()), 500)
    }

    /** Moves the camera without animation (used every frame while following a moving marker). */
    fun jumpTo(p: LatLng) {
        map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition).target(p.toML()).build()))
    }

    val zoom: Double get() = map.cameraPosition.zoom
    val target: LatLng? get() = map.cameraPosition.target?.let { LatLng(it.latitude, it.longitude) }
}

/**
 * MapLibre MapView hosted in Compose with its lifecycle forwarded. [onReady] runs every time
 * a style finishes loading (sources and layers belong to a style and must be re-added).
 */
@Composable
fun TrazoMapView(
    styleUrl: String,
    modifier: Modifier = Modifier,
    initialCamera: CameraPosition? = null,
    onCameraMovedByUser: () -> Unit = {},
    onLoadFailed: (String) -> Unit = {},
    attributionTopDp: Int = 0,
    onReady: (MapHandle) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnMoved by rememberUpdatedState(onCameraMovedByUser)
    val currentOnFailed by rememberUpdatedState(onLoadFailed)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
            addOnDidFailLoadingMapListener { msg -> currentOnFailed(msg ?: "") }
            getMapAsync { m ->
                m.uiSettings.isAttributionEnabled = true
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isCompassEnabled = true
                val d = context.resources.displayMetrics.density
                if (attributionTopDp > 0) {
                    // Keep the OpenStreetMap credit visible but out of the way of floating panels.
                    m.uiSettings.attributionGravity = android.view.Gravity.TOP or android.view.Gravity.START
                    m.uiSettings.setAttributionMargins((8 * d).toInt(), (attributionTopDp * d).toInt(), 0, 0)
                    m.uiSettings.compassGravity = android.view.Gravity.TOP or android.view.Gravity.END
                    m.uiSettings.setCompassMargins(0, (attributionTopDp * d).toInt(), (12 * d).toInt(), 0)
                }
                m.setMinZoomPreference(2.0)
                m.setMaxPitchPreference(70.0)
                initialCamera?.let { m.cameraPosition = it }
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) currentOnMoved()
                }
                map = m
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        // Catch up if the screen is already started.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, styleUrl) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(styleBuilder(context, styleUrl)) { style ->
            currentOnReady(MapHandle(m, style, context))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Bundled styles (asset://…) are read from the APK; everything else is fetched by MapLibre. */
private fun styleBuilder(context: Context, url: String): Style.Builder {
    if (url.startsWith("asset://")) {
        val json = runCatching { context.assets.open(url.removePrefix("asset://")).bufferedReader().use { it.readText() } }.getOrNull()
        if (json != null) return Style.Builder().fromJson(json)
    }
    return Style.Builder().fromUri(url)
}
