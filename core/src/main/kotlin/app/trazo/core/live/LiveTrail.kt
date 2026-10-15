package app.trazo.core.live

import app.trazo.core.geo.GeoMath
import app.trazo.core.geo.LatLng

/** One position as it reached the map: from my own GPS, a friend's realtime update or the server backlog. */
data class TrailPoint(val timeMs: Long, val lat: Double, val lng: Double, val accuracyM: Float? = null)

data class LiveTrailConfig(
    /** Longer than this without any position and the line is broken instead of bridged. */
    val maxGapMs: Long = 120_000,
    /** Faster than this between two fixes is a GPS jump, not a car. */
    val maxSpeedMps: Double = 85.0,
    /** Fixes less precise than this are not drawn. */
    val maxAccuracyM: Float = 60f,
    /** Moves shorter than this are jitter while standing still; they do not add vertices. */
    val minStepM: Double = 4.0,
    /** After this many consecutive rejected jumps the new place is accepted as a fresh segment. */
    val jumpsBeforeRelocate: Int = 3,
    /** Points older than this are trimmed (0 = keep everything). */
    val maxAgeMs: Long = 0,
    val maxPoints: Int = 6_000,
)

/**
 * The line drawn behind someone while they move. It only ever contains measured positions:
 * nothing is interpolated, invalid or imprecise fixes are dropped, GPS jumps are rejected,
 * and a long silence starts a new segment so two distant places are never joined by a
 * straight line. History uses the stored trip; this is the live view of the same data.
 */
class LiveTrail(private val cfg: LiveTrailConfig = LiveTrailConfig()) {
    private val segments = ArrayList<ArrayList<TrailPoint>>()
    private var lastSeenMs = Long.MIN_VALUE
    private var rejectedJumps = 0

    val pointCount: Int get() = segments.sumOf { it.size }
    val lastPoint: TrailPoint? get() = segments.lastOrNull()?.lastOrNull()
    val isEmpty: Boolean get() = pointCount == 0

    /** Segments as map coordinates; single-point segments are kept (they become a line once the next fix arrives). */
    fun segments(): List<List<LatLng>> = segments.map { seg -> seg.map { LatLng(it.lat, it.lng) } }

    fun clear() {
        segments.clear()
        lastSeenMs = Long.MIN_VALUE
        rejectedJumps = 0
    }

    /** Returns true if the point changed the drawn line. */
    fun add(p: TrailPoint): Boolean {
        if (!valid(p)) return false
        if (p.timeMs <= lastSeenMs) return false // duplicate or out of order
        val acc = p.accuracyM
        if (acc != null && acc > cfg.maxAccuracyM) return false
        val last = lastPoint
        val sinceSeen = if (lastSeenMs == Long.MIN_VALUE) Long.MAX_VALUE else p.timeMs - lastSeenMs
        if (last == null || sinceSeen > cfg.maxGapMs) {
            startSegment(p)
            return true
        }
        val d = GeoMath.distanceM(last.lat, last.lng, p.lat, p.lng)
        val dt = (p.timeMs - last.timeMs).coerceAtLeast(1) / 1000.0
        // Allow for the uncertainty of both fixes before calling it a jump.
        val slack = ((last.accuracyM ?: 10f) + (acc ?: 10f)).toDouble()
        if (d - slack > cfg.maxSpeedMps * dt) {
            rejectedJumps++
            if (rejectedJumps >= cfg.jumpsBeforeRelocate) {
                startSegment(p)
                return true
            }
            return false
        }
        rejectedJumps = 0
        lastSeenMs = p.timeMs
        if (d < cfg.minStepM) return false
        segments.last().add(p)
        trim(p.timeMs)
        return true
    }

    fun addAll(points: Iterable<TrailPoint>): Boolean {
        var changed = false
        points.sortedBy { it.timeMs }.forEach { if (add(it)) changed = true }
        return changed
    }

    private fun startSegment(p: TrailPoint) {
        rejectedJumps = 0
        lastSeenMs = p.timeMs
        segments.add(arrayListOf(p))
        trim(p.timeMs)
    }

    private fun trim(nowMs: Long) {
        if (cfg.maxAgeMs > 0) {
            val cutoff = nowMs - cfg.maxAgeMs
            segments.forEach { seg -> seg.removeAll { it.timeMs < cutoff } }
        }
        while (pointCount > cfg.maxPoints && segments.isNotEmpty()) {
            val first = segments.first()
            first.removeAt(0)
            if (first.isEmpty()) segments.removeAt(0)
        }
        segments.removeAll { it.isEmpty() }
    }

    private fun valid(p: TrailPoint): Boolean =
        !p.lat.isNaN() && !p.lng.isNaN() && p.lat in -90.0..90.0 && p.lng in -180.0..180.0 &&
            !(p.lat == 0.0 && p.lng == 0.0)
}
