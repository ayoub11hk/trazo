package app.trazo.core.location

import app.trazo.core.geo.LatLng
import kotlinx.serialization.Serializable

/**
 * One position fix exactly as the device reported it. Nothing in Trazo ever modifies these
 * values; filtered or smoothed geometry is always derived and stored separately.
 *
 * @param timeMs UTC fix time in epoch milliseconds (from the GNSS/fused provider).
 * @param elapsedRealtimeMs monotonic clock at fix time; used for ordering and age because it
 *   cannot jump when the user or network changes the wall clock.
 */
@Serializable
data class LocationSample(
    val timeMs: Long,
    val elapsedRealtimeMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val bearingDeg: Float? = null,
    val bearingAccuracyDeg: Float? = null,
    val altitudeM: Double? = null,
    val provider: String? = null,
    val isMock: Boolean = false,
) {
    val latLng: LatLng get() = LatLng(lat, lng)

    /** Doppler speed is only trusted when the provider says it is reasonably accurate. */
    fun reliableSpeedMps(maxSpeedErrorMps: Float = 3f): Float? {
        val s = speedMps ?: return null
        val acc = speedAccuracyMps
        return if (acc == null || acc <= maxSpeedErrorMps) s else null
    }

    /** Bearing is meaningless when nearly stationary; report it only when it can be trusted. */
    fun reliableBearingDeg(minSpeedMps: Float = 2f, maxBearingErrorDeg: Float = 30f): Float? {
        val b = bearingDeg ?: return null
        val s = speedMps ?: return null
        if (s < minSpeedMps) return null
        val acc = bearingAccuracyDeg
        return if (acc == null || acc <= maxBearingErrorDeg) b else null
    }
}

/** How the filter classified a raw fix. Stored with every point so the raw track is never lost. */
enum class PointFlag(val code: Int) {
    /** Good fix, part of the measured track and distance. */
    ACCEPTED(0),
    /** Accuracy worse than the configured threshold. Kept, not drawn as measured track. */
    LOW_ACCURACY(1),
    /** Implied speed from the previous good fix is physically implausible (GPS jump). */
    JUMP(2),
    /** Same or older timestamp than the previous fix. */
    DUPLICATE(3),
    /** Accepted, but within the noise radius of the previous point while stationary. */
    STATIONARY_JITTER(4);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: ACCEPTED
    }
}

/**
 * Milliseconds from this fix to [later]. Uses the monotonic clock, falling back to wall-clock
 * time when the monotonic clock went backwards (a trip recovered after a device reboot).
 */
fun LocationSample.millisTo(later: LocationSample): Long {
    val mono = later.elapsedRealtimeMs - elapsedRealtimeMs
    return if (mono >= 0 && later.timeMs >= timeMs - 60_000) mono else (later.timeMs - timeMs)
}
