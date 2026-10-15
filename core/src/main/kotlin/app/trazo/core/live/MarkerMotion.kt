package app.trazo.core.live

import app.trazo.core.geo.GeoMath
import app.trazo.core.geo.LatLng

/** Where a marker is drawn at a given instant. */
data class MarkerPose(val position: LatLng, val bearingDeg: Double?)

/**
 * Glides markers between real positions instead of letting them jump street to street.
 * The marker only travels between two measured fixes and stops on the newest one; it never
 * extrapolates ahead of the last data. Very long jumps snap, as animating them would invent a path.
 */
class MarkerMotion(
    private val minDurationMs: Long = 350,
    private val maxDurationMs: Long = 1_600,
    private val snapDistanceM: Double = 1_500.0,
) {
    private class Track(
        var from: LatLng, var to: LatLng,
        var fromBearing: Double?, var toBearing: Double?,
        var startMs: Long, var durationMs: Long, var lastTargetMs: Long,
    )

    private val tracks = HashMap<String, Track>()

    val ids: Set<String> get() = tracks.keys

    /** Sets the newest real position of [id]; returns true if the marker will move. */
    fun setTarget(id: String, to: LatLng, bearingDeg: Double?, nowMs: Long): Boolean {
        val t = tracks[id]
        if (t == null) {
            tracks[id] = Track(to, to, bearingDeg, bearingDeg, nowMs, 0, nowMs)
            return true
        }
        if (t.to == to && t.toBearing == bearingDeg) return false
        val current = poseAt(t, nowMs)
        val dist = GeoMath.distanceM(current.position, to)
        // Match the animation to the update rhythm so the marker keeps moving between fixes.
        val interval = (nowMs - t.lastTargetMs).coerceIn(minDurationMs, maxDurationMs)
        t.from = current.position
        t.fromBearing = current.bearingDeg
        t.to = to
        t.toBearing = bearingDeg
        t.startMs = nowMs
        t.durationMs = if (dist > snapDistanceM) 0 else interval
        t.lastTargetMs = nowMs
        return true
    }

    fun remove(id: String) { tracks.remove(id) }

    fun retain(ids: Set<String>) { tracks.keys.retainAll(ids) }

    fun pose(id: String, nowMs: Long): MarkerPose? = tracks[id]?.let { poseAt(it, nowMs) }

    fun isAnimating(nowMs: Long): Boolean = tracks.values.any { it.durationMs > 0 && nowMs < it.startMs + it.durationMs }

    private fun poseAt(t: Track, nowMs: Long): MarkerPose {
        if (t.durationMs <= 0 || nowMs >= t.startMs + t.durationMs) return MarkerPose(t.to, t.toBearing)
        val f = ((nowMs - t.startMs).toDouble() / t.durationMs).coerceIn(0.0, 1.0)
        val e = easeInOut(f)
        val bearing = when {
            t.fromBearing == null -> t.toBearing
            t.toBearing == null -> t.fromBearing
            else -> lerpAngle(t.fromBearing!!, t.toBearing!!, e)
        }
        return MarkerPose(GeoMath.lerp(t.from, t.to, e), bearing)
    }

    companion object {
        fun easeInOut(f: Double) = if (f < 0.5) 2 * f * f else 1 - (-2 * f + 2).let { it * it } / 2

        /** Interpolates along the shortest arc (350° → 10° passes through 0°, not 180°). */
        fun lerpAngle(a: Double, b: Double, f: Double): Double {
            var d = GeoMath.normalizeDeg(b) - GeoMath.normalizeDeg(a)
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return GeoMath.normalizeDeg(a + d * f)
        }
    }
}
