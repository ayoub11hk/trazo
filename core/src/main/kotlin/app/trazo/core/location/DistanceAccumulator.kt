package app.trazo.core.location

import app.trazo.core.geo.GeoMath

/**
 * Consistent trip distance from filtered fixes.
 *
 * Summing every consecutive pair inflates distance while stationary (GPS jitter adds metres
 * per minute) while summing only far-apart points cuts corners. This accumulator keeps an
 * anchor and only commits a step once the new point is clearly outside the noise radius of
 * the anchor (max of [minStepM] and half the combined accuracy). In motion at 1 Hz the step
 * threshold is reached at nearly every fix, so curves are preserved.
 *
 * Gaps (segment starts) add the straight-line displacement across the gap to [gapM], not to
 * [measuredM], so callers can show both honestly.
 */
class DistanceAccumulator(private val minStepM: Double = 3.0) {
    var measuredM: Double = 0.0
        private set
    var gapM: Double = 0.0
        private set
    val totalM: Double get() = measuredM + gapM

    private var anchor: LocationSample? = null

    fun add(result: FilterResult) {
        if (!result.usable) return
        // Noise around a stationary position is never distance; keep the anchor where it is.
        if (result.flag == PointFlag.STATIONARY_JITTER && anchor != null) return
        val s = result.sample
        val a = anchor
        if (a == null) {
            anchor = s
            return
        }
        val d = GeoMath.distanceM(a.lat, a.lng, s.lat, s.lng)
        if (result.segmentStart) {
            gapM += d
            anchor = s
            return
        }
        val speed = s.reliableSpeedMps()
        // The receiver's Doppler speed is far more reliable than position differences: when it
        // says we are stopped, positions are noise. The anchor stays put, so any creeping is
        // still counted once the vehicle moves off again.
        if (speed != null && speed < STOPPED_MPS) return
        val moving = (speed ?: 0f) >= 1.5f
        val threshold = if (speed == null) {
            maxOf(minStepM, 2.0 * maxOf(a.accuracyM ?: 5f, s.accuracyM ?: 5f))
        } else {
            maxOf(minStepM, ((a.accuracyM ?: 5f) + (s.accuracyM ?: 5f)) / 4.0)
        }
        if (d >= threshold || (moving && d >= 1.0)) {
            measuredM += d
            anchor = s
        }
    }

    fun reset() {
        measuredM = 0.0
        gapM = 0.0
        anchor = null
    }

    private companion object {
        const val STOPPED_MPS = 0.8f
    }
}
