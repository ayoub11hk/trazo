package app.trazo.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import app.trazo.core.location.LocationSample
import app.trazo.core.policy.LocationRequestSpec
import app.trazo.core.policy.Priority
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

/** A source of raw fixes whose request parameters can change while running. */
interface LocationSource {
    fun start(spec: LocationRequestSpec, onFix: (LocationSample) -> Unit)
    fun update(spec: LocationRequestSpec)
    fun stop()
    val name: String

    companion object {
        /** The position the phone already knows (may be minutes old); null without permission or history. */
        @SuppressLint("MissingPermission")
        suspend fun lastKnown(ctx: Context): LocationSample? = runCatching {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS) {
                LocationServices.getFusedLocationProviderClient(ctx).lastLocation.await()?.toSample()
            } else {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.toSample()
            }
        }.getOrNull()

        fun best(ctx: Context): LocationSource =
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS) {
                FusedSource(ctx)
            } else {
                PlatformSource(ctx)
            }
    }
}

fun Location.toSample(): LocationSample = LocationSample(
    timeMs = time,
    elapsedRealtimeMs = elapsedRealtimeNanos / 1_000_000,
    lat = latitude,
    lng = longitude,
    accuracyM = if (hasAccuracy()) accuracy else null,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    bearingDeg = if (hasBearing()) bearing else null,
    bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
    altitudeM = if (hasAltitude()) altitude else null,
    provider = provider,
    isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider,
)

/**
 * Google's fused provider: combines GNSS with Wi-Fi/cell and motion sensors, and at
 * PRIORITY_HIGH_ACCURACY keeps the GNSS receiver on for the best possible fixes.
 */
@SuppressLint("MissingPermission")
class FusedSource(ctx: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(ctx)
    private var callback: LocationCallback? = null
    private var onFix: ((LocationSample) -> Unit)? = null
    override val name = "fused"

    override fun start(spec: LocationRequestSpec, onFix: (LocationSample) -> Unit) {
        this.onFix = onFix
        request(spec)
    }

    override fun update(spec: LocationRequestSpec) {
        if (onFix != null) request(spec)
    }

    private fun request(spec: LocationRequestSpec) {
        callback?.let { client.removeLocationUpdates(it) }
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Batched results arrive oldest first; keep chronological order.
                result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach { onFix?.invoke(it.toSample()) }
            }
        }
        callback = cb
        val priority = when (spec.priority) {
            Priority.HIGH_ACCURACY -> com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
            Priority.BALANCED -> com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
            Priority.LOW_POWER -> com.google.android.gms.location.Priority.PRIORITY_LOW_POWER
            Priority.PASSIVE -> com.google.android.gms.location.Priority.PRIORITY_PASSIVE
        }
        val req = LocationRequest.Builder(priority, spec.intervalMs)
            .setMinUpdateIntervalMillis(spec.minIntervalMs)
            .setMinUpdateDistanceMeters(spec.minDistanceM)
            .setMaxUpdateDelayMillis(spec.maxDelayMs)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        onFix = null
    }
}

/** Fallback for phones without Google Play services: raw GNSS from the platform. */
@SuppressLint("MissingPermission")
class PlatformSource(ctx: Context) : LocationSource {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null
    private var onFix: ((LocationSample) -> Unit)? = null
    override val name = "gps"

    override fun start(spec: LocationRequestSpec, onFix: (LocationSample) -> Unit) {
        this.onFix = onFix
        request(spec)
    }

    override fun update(spec: LocationRequestSpec) {
        if (onFix != null) request(spec)
    }

    private fun request(spec: LocationRequestSpec) {
        listener?.let { lm.removeUpdates(it) }
        val l = LocationListener { loc -> onFix?.invoke(loc.toSample()) }
        listener = l
        val provider = when (spec.priority) {
            Priority.HIGH_ACCURACY -> LocationManager.GPS_PROVIDER
            Priority.BALANCED, Priority.LOW_POWER ->
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
            Priority.PASSIVE -> LocationManager.PASSIVE_PROVIDER
        }
        lm.requestLocationUpdates(provider, spec.intervalMs, spec.minDistanceM, l, Looper.getMainLooper())
    }

    override fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
        onFix = null
    }
}
