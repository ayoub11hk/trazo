package app.trazo.core.playback

import app.trazo.core.geo.GeoMath
import app.trazo.core.geo.LatLng
import app.trazo.core.location.millisTo
import app.trazo.core.trip.TrackPoint

data class PlaybackFrame(
    /** Milliseconds since the first recorded fix. */
    val offsetMs: Long,
    val position: LatLng,
    val bearingDeg: Double?,
    val speedMps: Double?,
    /** Distance travelled along the recorded fixes up to this instant. */
    val distanceM: Double,
    /** Index of the last recorded fix at or before this instant (for drawing the trail). */
    val pointIndex: Int,
    /** True while the playhead is inside a signal gap: the marker holds still, nothing is invented. */
    val inGap: Boolean,
)

/**
 * Replays a trip using its real timestamps. Between two consecutive fixes of the same
 * segment the marker moves linearly (fixes are ~1 s apart while driving, so this is purely
 * visual). Across a signal gap the marker waits at the last fix and then appears at the
 * first fix after the gap, so playback never fabricates a path.
 */
class PlaybackTimeline(points: List<TrackPoint>) {
    private val pts = points.filter { it.usable }.sortedBy { it.seq }
    private val offsets = LongArray(pts.size).also { o ->
        for (i in 1 until pts.size) o[i] = o[i - 1] + pts[i - 1].sample.millisTo(pts[i].sample).coerceAtLeast(0)
    }
    private val cumDist = DoubleArray(pts.size)
    private val bearings = DoubleArray(pts.size)

    init {
        for (i in 1 until pts.size) {
            val a = pts[i - 1].sample
            val b = pts[i].sample
            val d = if (pts[i].segmentStart) 0.0 else GeoMath.distanceM(a.lat, a.lng, b.lat, b.lng)
            cumDist[i] = cumDist[i - 1] + d
        }
        // Heading: prefer the receiver's bearing when reliable, else the direction of travel
        // over the next few metres, carrying the last heading through stops.
        var last = 0.0
        for (i in pts.indices) {
            val s = pts[i].sample
            val reliable = s.reliableBearingDeg()?.toDouble()
            val geometric = run {
                var j = i + 1
                while (j < pts.size && !pts[j].segmentStart &&
                    GeoMath.distanceM(s.lat, s.lng, pts[j].sample.lat, pts[j].sample.lng) < 5.0
                ) j++
                if (j < pts.size && !pts[j].segmentStart) GeoMath.bearingDeg(s.lat, s.lng, pts[j].sample.lat, pts[j].sample.lng) else null
            }
            last = reliable ?: geometric ?: last
            bearings[i] = last
        }
    }

    val isEmpty: Boolean get() = pts.isEmpty()
    val durationMs: Long get() = offsets.lastOrNull() ?: 0L
    val totalDistanceM: Double get() = cumDist.lastOrNull() ?: 0.0
    val pointCount: Int get() = pts.size
    fun pointAt(i: Int): LatLng = pts[i].sample.latLng
    fun offsetOf(i: Int): Long = offsets[i]

    fun frameAt(offsetMs: Long): PlaybackFrame? {
        if (pts.isEmpty()) return null
        val t = offsetMs.coerceIn(0, durationMs)
        // Binary search for the last fix with offset <= t.
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (offsets[mid] <= t) lo = mid else hi = mid - 1
        }
        val i = lo
        val a = pts[i].sample
        if (i == pts.size - 1) {
            return PlaybackFrame(t, a.latLng, bearings[i], a.speedMps?.toDouble(), cumDist[i], i, false)
        }
        val next = pts[i + 1]
        if (next.segmentStart) {
            return PlaybackFrame(t, a.latLng, bearings[i], null, cumDist[i], i, true)
        }
        val span = (offsets[i + 1] - offsets[i]).coerceAtLeast(1)
        val f = (t - offsets[i]).toDouble() / span
        val b = next.sample
        val pos = GeoMath.lerp(a.latLng, b.latLng, f)
        val speed = when {
            a.speedMps != null && b.speedMps != null -> a.speedMps + (b.speedMps - a.speedMps) * f
            else -> GeoMath.distanceM(a.lat, a.lng, b.lat, b.lng) / (span / 1000.0)
        }
        return PlaybackFrame(
            t, pos, GeoMath.lerpHeading(bearings[i], bearings[i + 1], f), speed,
            cumDist[i] + (cumDist[i + 1] - cumDist[i]) * f, i, false,
        )
    }

    /** Seek by fraction of total duration (0..1). */
    fun frameAtFraction(fraction: Double): PlaybackFrame? = frameAt((durationMs * fraction.coerceIn(0.0, 1.0)).toLong())
}
