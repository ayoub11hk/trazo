package app.trazo.core.policy

import app.trazo.core.geo.GeoMath
import app.trazo.core.location.LocationSample
import app.trazo.core.trip.DetectorState

enum class Priority { HIGH_ACCURACY, BALANCED, LOW_POWER, PASSIVE }

data class LocationRequestSpec(
    val priority: Priority,
    val intervalMs: Long,
    val minIntervalMs: Long,
    val minDistanceM: Float,
    /** Allow the OS to batch fixes for this long (0 = deliver immediately). */
    val maxDelayMs: Long = 0,
)

data class DeviceContext(
    val detectorState: DetectorState,
    val appVisible: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean,
    val sharingLive: Boolean,
    /** User-chosen live update interval while driving, in seconds (1–30). */
    val userDrivingIntervalSec: Int = 1,
)

/**
 * Adaptive request selection. While driving accuracy wins: 1 Hz high-accuracy GNSS so curves
 * and turns are captured. Stopped inside a trip the rate drops, parked it drops much further
 * and Activity Recognition (near-zero cost) is what wakes tracking up again.
 */
object AdaptiveLocationPolicy {
    fun requestFor(ctx: DeviceContext): LocationRequestSpec {
        val lowBattery = !ctx.charging && (ctx.batteryPercent ?: 100) <= 15
        return when (ctx.detectorState) {
            DetectorState.DRIVING -> {
                val base = ctx.userDrivingIntervalSec.coerceIn(1, 30) * 1000L
                val interval = if (lowBattery) maxOf(base, 2_000L) else base
                LocationRequestSpec(Priority.HIGH_ACCURACY, interval, minOf(interval, 1_000L), 0f)
            }
            DetectorState.ARMED -> LocationRequestSpec(Priority.HIGH_ACCURACY, 2_000, 1_000, 0f)
            DetectorState.STOPPED -> LocationRequestSpec(Priority.HIGH_ACCURACY, 5_000, 2_000, 0f)
            DetectorState.IDLE -> when {
                ctx.appVisible -> LocationRequestSpec(Priority.HIGH_ACCURACY, 3_000, 1_000, 2f)
                ctx.sharingLive && !lowBattery -> LocationRequestSpec(Priority.BALANCED, 60_000, 30_000, 25f, maxDelayMs = 120_000)
                else -> LocationRequestSpec(Priority.PASSIVE, 300_000, 60_000, 50f)
            }
        }
    }
}

/**
 * Decides whether a new fix is worth sending to friends. Skips redundant updates (parked,
 * tiny moves) but never throttles below the user's chosen rate while driving, and sends a
 * heartbeat so friends can tell "stationary" from "disconnected".
 */
class LivePublishPolicy(
    var minIntervalMs: Long = 2_000,
    var heartbeatMs: Long = 60_000,
    private val minMoveM: Double = 8.0,
    private val minHeadingChangeDeg: Double = 20.0,
) {
    private var last: LocationSample? = null
    private var lastSentAtMs = 0L

    fun shouldPublish(s: LocationSample, nowMs: Long, stateChanged: Boolean = false): Boolean {
        val prev = last
        val since = nowMs - lastSentAtMs
        val decision = when {
            prev == null || stateChanged -> true
            since < minIntervalMs -> false
            since >= heartbeatMs -> true
            else -> {
                val moved = GeoMath.distanceM(prev.lat, prev.lng, s.lat, s.lng)
                val turned = run {
                    val a = prev.reliableBearingDeg()?.toDouble()
                    val b = s.reliableBearingDeg()?.toDouble()
                    a != null && b != null && GeoMath.headingDelta(a, b) >= minHeadingChangeDeg
                }
                moved >= minMoveM || turned
            }
        }
        if (decision) { last = s; lastSentAtMs = nowMs }
        return decision
    }

    fun reset() { last = null; lastSentAtMs = 0 }
}
