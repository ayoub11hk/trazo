package app.trazo.core.trip

import app.trazo.core.geo.GeoMath
import app.trazo.core.location.FilterResult
import app.trazo.core.location.LocationSample
import kotlinx.serialization.Serializable

enum class Sensitivity { LOW, NORMAL, HIGH }

/** Coarse activity reported by Android's Activity Recognition, used only as a hint. */
enum class ActivityHint { IN_VEHICLE, ON_BICYCLE, ON_FOOT, STILL, UNKNOWN }

/**
 * Thresholds for automatic trip detection. All times are on the monotonic clock.
 *
 * Hysteresis: a trip needs sustained speed *and* displacement to start, and sustained
 * stillness to end, so traffic lights, jams and short stops never split a trip.
 */
@Serializable
data class TripDetectorConfig(
    /** Speed that arms the detector (≈ 18 km/h at NORMAL; above jogging pace). */
    val startSpeedMps: Float = 5.0f,
    /** Armed for at least this long with movement before a trip is confirmed. */
    val startDurationMs: Long = 25_000,
    /** Straight-line displacement from the arming point needed to confirm. */
    val startDistanceM: Double = 200.0,
    /** Median speed while armed must exceed this, which rules out walking and running. */
    val minMedianSpeedMps: Float = 3.2f,
    /** Give up arming after this long without reaching the start conditions. */
    val armTimeoutMs: Long = 90_000,
    /** Below this speed (and within [stopRadiusM]) the vehicle counts as stopped. */
    val movingSpeedMps: Float = 1.5f,
    val stopRadiusM: Double = 35.0,
    /** Stillness needed to end a trip. Long enough for lights, level crossings and jams. */
    val endStillMs: Long = 5 * 60_000,
    /** Shorter end timeout when Android also reports the user left the vehicle. */
    val endStillAfterExitMs: Long = 2 * 60_000,
    /** Without any fix for this long the trip is closed at its last known point. */
    val maxSilenceMs: Long = 20 * 60_000,
    /** Automatically detected trips shorter than this are discarded as false positives. */
    val minTripDistanceM: Double = 400.0,
    val minTripDurationMs: Long = 90_000,
) {
    companion object {
        fun forSensitivity(s: Sensitivity): TripDetectorConfig = when (s) {
            Sensitivity.LOW -> TripDetectorConfig(
                startSpeedMps = 6.5f, startDurationMs = 40_000, startDistanceM = 350.0,
                endStillMs = 8 * 60_000, endStillAfterExitMs = 3 * 60_000,
                minTripDistanceM = 800.0, minTripDurationMs = 150_000,
            )
            Sensitivity.NORMAL -> TripDetectorConfig()
            Sensitivity.HIGH -> TripDetectorConfig(
                startSpeedMps = 4.0f, startDurationMs = 15_000, startDistanceM = 120.0,
                minMedianSpeedMps = 2.8f, endStillMs = 3 * 60_000, endStillAfterExitMs = 90_000,
                minTripDistanceM = 250.0, minTripDurationMs = 60_000,
            )
        }
    }
}

enum class DetectorState { IDLE, ARMED, DRIVING, STOPPED }

enum class EndReason { STILL, LEFT_VEHICLE, SIGNAL_LOST, MANUAL }

sealed interface TripEvent {
    /** A trip began at [startSample]; [preRoll] holds the fixes collected while arming. */
    data class Started(val startSample: LocationSample, val preRoll: List<LocationSample>, val manual: Boolean) : TripEvent
    data class Stopped(val atElapsedMs: Long) : TripEvent
    data class Resumed(val atElapsedMs: Long) : TripEvent
    /** The trip ended; its last moving fix is [lastMovingSample]. Later fixes are trailing idle. */
    data class Ended(val lastMovingSample: LocationSample?, val endElapsedMs: Long, val reason: EndReason) : TripEvent
    /** An automatically started trip turned out too short to be a drive. */
    data class Discarded(val distanceM: Double, val durationMs: Long) : TripEvent
}

@Serializable
data class DetectorSnapshot(
    val state: DetectorState,
    val manual: Boolean,
    val startSample: LocationSample?,
    val lastMoving: LocationSample?,
    val lastSample: LocationSample?,
    val stopAnchor: LocationSample?,
    val stillSinceMs: Long?,
    val distanceM: Double,
)

/**
 * Streaming trip start/stop state machine. Feed it filtered fixes ([onFix]), activity
 * transitions ([onActivity]) and periodic clock ticks ([onTick], needed because location
 * updates slow down while parked). It returns events; it does not do I/O.
 */
class TripDetector(config: TripDetectorConfig = TripDetectorConfig()) {
    var config: TripDetectorConfig = config
        private set

    var state: DetectorState = DetectorState.IDLE
        private set

    private var manual = false
    private var vehicleHint = false
    private var exitedVehicleAtMs: Long? = null
    private val armBuffer = ArrayList<LocationSample>()
    private var armStart: LocationSample? = null
    private var armLastMovingMs: Long = 0
    private var startSample: LocationSample? = null
    private var lastMoving: LocationSample? = null
    private var lastSample: LocationSample? = null
    private var stopAnchor: LocationSample? = null
    private var stillSinceMs: Long? = null
    private var distanceM = 0.0

    val isInTrip: Boolean get() = state == DetectorState.DRIVING || state == DetectorState.STOPPED

    fun updateConfig(c: TripDetectorConfig) { config = c }

    fun onActivity(hint: ActivityHint, nowElapsedMs: Long): List<TripEvent> {
        when (hint) {
            ActivityHint.IN_VEHICLE -> { vehicleHint = true; exitedVehicleAtMs = null }
            ActivityHint.ON_FOOT, ActivityHint.ON_BICYCLE -> {
                if (vehicleHint || isInTrip) exitedVehicleAtMs = nowElapsedMs
                vehicleHint = false
            }
            ActivityHint.STILL, ActivityHint.UNKNOWN -> Unit
        }
        return onTick(nowElapsedMs)
    }

    fun onFix(r: FilterResult): List<TripEvent> {
        if (!r.usable) return emptyList()
        val s = r.sample
        val prev = lastSample
        val speed = s.reliableSpeedMps()?.toDouble() ?: prev?.let { p ->
            val dt = (s.elapsedRealtimeMs - p.elapsedRealtimeMs) / 1000.0
            if (dt > 0) GeoMath.distanceM(p.lat, p.lng, s.lat, s.lng) / dt else 0.0
        } ?: 0.0
        if (isInTrip && prev != null) {
            distanceM += GeoMath.distanceM(prev.lat, prev.lng, s.lat, s.lng)
        }
        lastSample = s
        val events = ArrayList<TripEvent>()
        when (state) {
            DetectorState.IDLE -> {
                val threshold = if (vehicleHint) config.startSpeedMps * 0.7f else config.startSpeedMps
                if (speed >= threshold) {
                    state = DetectorState.ARMED
                    armStart = s
                    armBuffer.clear()
                    armBuffer += s
                    armLastMovingMs = s.elapsedRealtimeMs
                }
            }
            DetectorState.ARMED -> {
                if (armBuffer.size < MAX_ARM_BUFFER) armBuffer += s
                if (speed >= config.movingSpeedMps) armLastMovingMs = s.elapsedRealtimeMs
                val a = armStart!!
                val dur = s.elapsedRealtimeMs - a.elapsedRealtimeMs
                val disp = GeoMath.distanceM(a.lat, a.lng, s.lat, s.lng)
                val needDist = if (vehicleHint) config.startDistanceM * 0.5 else config.startDistanceM
                val needDur = if (vehicleHint) config.startDurationMs / 2 else config.startDurationMs
                if (dur >= needDur && disp >= needDist && medianArmSpeed() >= config.minMedianSpeedMps) {
                    events += begin(a, armBuffer.toList(), manual = false)
                    // Distance already covered while arming belongs to the trip.
                    distanceM = pathLength(armBuffer)
                    lastMoving = s
                } else if (s.elapsedRealtimeMs - armLastMovingMs > config.armTimeoutMs ||
                    dur > config.armTimeoutMs * 3
                ) {
                    resetToIdle()
                }
            }
            DetectorState.DRIVING -> {
                val anchor = stopAnchor ?: s
                if (speed < config.movingSpeedMps &&
                    GeoMath.distanceM(anchor.lat, anchor.lng, s.lat, s.lng) < config.stopRadiusM
                ) {
                    if (stopAnchor == null) stopAnchor = s
                    if (stillSinceMs == null) stillSinceMs = s.elapsedRealtimeMs
                    // Only declare "stopped" after a short dwell so rolling slowdowns do not flap.
                    if (s.elapsedRealtimeMs - stillSinceMs!! >= STOP_DWELL_MS) {
                        state = DetectorState.STOPPED
                        events += TripEvent.Stopped(stillSinceMs!!)
                    }
                } else {
                    lastMoving = s
                    stopAnchor = null
                    stillSinceMs = null
                }
            }
            DetectorState.STOPPED -> {
                val anchor = stopAnchor ?: s
                val moved = GeoMath.distanceM(anchor.lat, anchor.lng, s.lat, s.lng) >= config.stopRadiusM
                if (speed >= config.movingSpeedMps * 2 || moved) {
                    state = DetectorState.DRIVING
                    stopAnchor = null
                    stillSinceMs = null
                    lastMoving = s
                    exitedVehicleAtMs = null
                    events += TripEvent.Resumed(s.elapsedRealtimeMs)
                }
            }
        }
        events += onTick(s.elapsedRealtimeMs)
        return events
    }

    fun onTick(nowElapsedMs: Long): List<TripEvent> {
        when (state) {
            DetectorState.ARMED -> {
                if (nowElapsedMs - armLastMovingMs > config.armTimeoutMs) resetToIdle()
            }
            DetectorState.DRIVING, DetectorState.STOPPED -> {
                if (manual) return emptyList()
                val last = lastSample
                if (last != null && nowElapsedMs - last.elapsedRealtimeMs >= config.maxSilenceMs) {
                    return listOf(finish(EndReason.SIGNAL_LOST, nowElapsedMs))
                }
                val still = stillSinceMs
                if (still != null) {
                    val exited = exitedVehicleAtMs
                    val timeout = if (exited != null && exited >= still - 60_000) config.endStillAfterExitMs else config.endStillMs
                    if (nowElapsedMs - still >= timeout) {
                        return listOf(finish(if (timeout == config.endStillMs) EndReason.STILL else EndReason.LEFT_VEHICLE, nowElapsedMs))
                    }
                }
            }
            DetectorState.IDLE -> Unit
        }
        return emptyList()
    }

    /** Manual start; manual trips are never auto-discarded and only end manually or by silence. */
    fun forceStart(current: LocationSample?): List<TripEvent> {
        if (isInTrip) return emptyList()
        val s = current ?: lastSample
        val pre = if (state == DetectorState.ARMED) armBuffer.toList() else emptyList()
        if (s == null) {
            state = DetectorState.DRIVING
            manual = true
            distanceM = 0.0
            return emptyList() // Caller starts the trip record itself; the first fix anchors it.
        }
        distanceM = 0.0
        val ev = begin(s, pre, manual = true)
        lastMoving = s
        return listOf(ev)
    }

    fun forceEnd(nowElapsedMs: Long): List<TripEvent> =
        if (isInTrip) listOf(finish(EndReason.MANUAL, nowElapsedMs)) else emptyList()

    fun snapshot() = DetectorSnapshot(state, manual, startSample, lastMoving, lastSample, stopAnchor, stillSinceMs, distanceM)

    /**
     * Restores after process death. An ARMED state is not restored (it is cheap to re-arm).
     * After a device reboot the monotonic clock restarted, so time-based anchors are dropped
     * and the next fix simply continues the trip as a new segment.
     */
    fun restore(snap: DetectorSnapshot, rebooted: Boolean = false) {
        state = if (snap.state == DetectorState.ARMED) DetectorState.IDLE else snap.state
        manual = snap.manual
        startSample = snap.startSample
        lastMoving = snap.lastMoving
        lastSample = snap.lastSample
        stopAnchor = snap.stopAnchor
        stillSinceMs = snap.stillSinceMs
        distanceM = snap.distanceM
        if (rebooted) {
            lastSample = null
            stopAnchor = null
            stillSinceMs = null
        }
    }

    private fun begin(start: LocationSample, preRoll: List<LocationSample>, manual: Boolean): TripEvent {
        state = DetectorState.DRIVING
        this.manual = manual
        startSample = start
        stopAnchor = null
        stillSinceMs = null
        armBuffer.clear()
        armStart = null
        return TripEvent.Started(start, preRoll, manual)
    }

    private fun finish(reason: EndReason, nowElapsedMs: Long): TripEvent {
        val start = startSample
        val end = lastMoving ?: lastSample
        val wasManual = manual
        val dist = distanceM
        resetToIdle()
        val durationMs = if (start != null && end != null) end.elapsedRealtimeMs - start.elapsedRealtimeMs else 0L
        if (!wasManual && reason != EndReason.MANUAL &&
            (dist < config.minTripDistanceM || durationMs < config.minTripDurationMs)
        ) {
            return TripEvent.Discarded(dist, durationMs)
        }
        return TripEvent.Ended(end, nowElapsedMs, reason)
    }

    private fun resetToIdle() {
        state = DetectorState.IDLE
        manual = false
        armBuffer.clear()
        armStart = null
        startSample = null
        lastMoving = null
        stopAnchor = null
        stillSinceMs = null
        distanceM = 0.0
    }

    private fun medianArmSpeed(): Double {
        if (armBuffer.size < 2) return 0.0
        val speeds = ArrayList<Double>()
        for (i in 1 until armBuffer.size) {
            val p = armBuffer[i - 1]
            val q = armBuffer[i]
            val v = q.reliableSpeedMps()?.toDouble() ?: run {
                val dt = (q.elapsedRealtimeMs - p.elapsedRealtimeMs) / 1000.0
                if (dt > 0) GeoMath.distanceM(p.lat, p.lng, q.lat, q.lng) / dt else 0.0
            }
            speeds += v
        }
        speeds.sort()
        return speeds[speeds.size / 2]
    }

    private fun pathLength(points: List<LocationSample>): Double {
        var d = 0.0
        for (i in 1 until points.size) d += GeoMath.distanceM(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
        return d
    }

    companion object {
        const val STOP_DWELL_MS = 8_000L
        const val MAX_ARM_BUFFER = 600
    }
}
