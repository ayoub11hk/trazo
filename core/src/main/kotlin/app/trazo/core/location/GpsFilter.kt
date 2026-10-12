package app.trazo.core.location

import app.trazo.core.geo.GeoMath

/**
 * Tunables for [GpsFilter]. Defaults were chosen for road vehicles on ordinary phones.
 *
 * @param maxAccuracyM fixes with a worse (larger) reported accuracy are flagged
 *   [PointFlag.LOW_ACCURACY] and not used for the measured track.
 * @param maxPlausibleSpeedMps above this implied speed between two fixes the newer one is
 *   treated as a GPS jump (default 90 m/s ≈ 324 km/h).
 * @param gapMs time without fixes after which the track is split into a new segment
 *   (tunnel, parking garage, lost signal). The two sides are never joined with an invented
 *   line; the gap is reported as an incident.
 * @param jumpConfirmCount number of consecutive, mutually consistent "jump" fixes after which
 *   the filter concludes the *previous* anchor was wrong and re-anchors (prevents getting
 *   stuck behind a single bad fix).
 */
data class GpsFilterConfig(
    val maxAccuracyM: Float = 35f,
    val maxPlausibleSpeedMps: Double = 90.0,
    val gapMs: Long = 20_000,
    val jumpConfirmCount: Int = 3,
    val stationarySpeedMps: Float = 0.8f,
)

data class FilterResult(
    val sample: LocationSample,
    val flag: PointFlag,
    /** True when this accepted point starts a new track segment (after a gap or re-anchor). */
    val segmentStart: Boolean,
    /** Gap length in ms that preceded this point, when [segmentStart] is due to a gap. */
    val gapBeforeMs: Long = 0,
) {
    val usable: Boolean get() = flag == PointFlag.ACCEPTED || flag == PointFlag.STATIONARY_JITTER
}

/**
 * Stateful, streaming classifier for raw fixes. It never changes coordinates: it only decides
 * whether each fix is part of the measured track. Feed fixes in arrival order.
 */
class GpsFilter(private val config: GpsFilterConfig = GpsFilterConfig()) {
    private var anchor: LocationSample? = null
    private var lastSeen: LocationSample? = null
    private val pendingJumps = ArrayList<LocationSample>()

    fun reset() {
        anchor = null
        lastSeen = null
        pendingJumps.clear()
    }

    /** The last fix accepted into the measured track, if any. */
    val lastAccepted: LocationSample? get() = anchor

    fun process(s: LocationSample): FilterResult {
        val prevSeen = lastSeen
        if (prevSeen != null && s.elapsedRealtimeMs <= prevSeen.elapsedRealtimeMs) {
            return FilterResult(s, PointFlag.DUPLICATE, segmentStart = false)
        }
        lastSeen = s

        val acc = s.accuracyM
        if (acc != null && acc > config.maxAccuracyM) {
            return FilterResult(s, PointFlag.LOW_ACCURACY, segmentStart = false)
        }

        val a = anchor
        if (a == null) {
            anchor = s
            return FilterResult(s, PointFlag.ACCEPTED, segmentStart = true)
        }

        val dtMs = s.elapsedRealtimeMs - a.elapsedRealtimeMs
        val dist = GeoMath.distanceM(a.lat, a.lng, s.lat, s.lng)

        if (dtMs >= config.gapMs) {
            // After a long silence any displacement is plausible as long as the average speed is.
            val implied = dist / (dtMs / 1000.0)
            if (implied <= config.maxPlausibleSpeedMps) {
                anchor = s
                pendingJumps.clear()
                return FilterResult(s, PointFlag.ACCEPTED, segmentStart = true, gapBeforeMs = dtMs)
            }
        }

        if (isJump(a, s, dist, dtMs)) {
            pendingJumps += s
            if (pendingJumps.size >= config.jumpConfirmCount && pendingJumpsConsistent()) {
                // Several fixes agree with each other but not with the anchor: the anchor was the
                // outlier (or we really moved during a short outage). Start a new segment here.
                anchor = s
                pendingJumps.clear()
                return FilterResult(s, PointFlag.ACCEPTED, segmentStart = true)
            }
            return FilterResult(s, PointFlag.JUMP, segmentStart = false)
        }
        pendingJumps.clear()

        val speed = s.reliableSpeedMps() ?: (dist / (dtMs / 1000.0)).toFloat()
        val noiseRadius = maxOf(3.0, (acc ?: 10f).toDouble())
        anchor = s
        return if (speed < config.stationarySpeedMps && dist < noiseRadius) {
            FilterResult(s, PointFlag.STATIONARY_JITTER, segmentStart = false)
        } else {
            FilterResult(s, PointFlag.ACCEPTED, segmentStart = false)
        }
    }

    private fun isJump(a: LocationSample, s: LocationSample, dist: Double, dtMs: Long): Boolean {
        val dt = maxOf(dtMs, 1L) / 1000.0
        // Allow the combined uncertainty of both fixes before judging the displacement.
        val slack = ((a.accuracyM ?: 10f) + (s.accuracyM ?: 10f)).toDouble()
        val effective = maxOf(0.0, dist - slack)
        return effective / dt > config.maxPlausibleSpeedMps
    }

    private fun pendingJumpsConsistent(): Boolean {
        for (i in 1 until pendingJumps.size) {
            val p = pendingJumps[i - 1]
            val q = pendingJumps[i]
            val dt = q.elapsedRealtimeMs - p.elapsedRealtimeMs
            if (isJump(p, q, GeoMath.distanceM(p.lat, p.lng, q.lat, q.lng), dt)) return false
        }
        return true
    }
}
