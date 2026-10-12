package app.trazo.core.trip

import app.trazo.core.geo.GeoMath
import app.trazo.core.location.DistanceAccumulator
import app.trazo.core.location.FilterResult
import app.trazo.core.location.PointFlag
import app.trazo.core.location.millisTo
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class TrackIncident(
    val type: Type,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val detail: String = "",
) {
    enum class Type { SIGNAL_GAP, LOW_ACCURACY_STRETCH, GPS_JUMPS_REJECTED, SIMULATED_LOCATION }
}

@Serializable
data class TripSummary(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val movingMs: Long,
    /** Distance measured between good fixes. */
    val measuredDistanceM: Double,
    /** Straight-line displacement across signal gaps (tunnels etc.), shown separately. */
    val gapDistanceM: Double,
    val avgSpeedMps: Double,
    val avgMovingSpeedMps: Double,
    val maxSpeedMps: Double,
    val pointCount: Int,
    val usablePointCount: Int,
    val medianAccuracyM: Double?,
    /** 0–100: share of good fixes, sampling density and absence of gaps. */
    val qualityScore: Int,
    val incidents: List<TrackIncident>,
    val simulated: Boolean,
) {
    val distanceM: Double get() = measuredDistanceM + gapDistanceM
}

/** Computes trip statistics from stored points; deterministic so phone and server agree. */
object TripAnalyzer {
    private const val MOVING_SPEED_MPS = 1.5
    /** Max speed is taken over windows of at least this long, which suppresses single-fix spikes. */
    private const val MAX_SPEED_WINDOW_MS = 3_000L

    fun analyze(points: List<TrackPoint>): TripSummary? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.seq }
        val usable = sorted.filter { it.usable }
        val first = sorted.first().sample
        val last = sorted.last().sample

        val acc = DistanceAccumulator()
        usable.forEach { acc.add(FilterResult(it.sample, it.flag, it.segmentStart)) }

        var movingMs = 0L
        for (i in 1 until usable.size) {
            val p = usable[i - 1].sample
            val q = usable[i].sample
            if (usable[i].segmentStart) continue
            val dt = p.millisTo(q)
            val v = q.reliableSpeedMps()?.toDouble()
                ?: (GeoMath.distanceM(p.lat, p.lng, q.lat, q.lng) / (dt / 1000.0).coerceAtLeast(0.001))
            if (v >= MOVING_SPEED_MPS) movingMs += dt
        }

        val durationMs = (last.timeMs - first.timeMs).coerceAtLeast(0)
        val dist = acc.totalM
        val maxSpeed = maxSpeed(usable)
        val accuracies = usable.mapNotNull { it.sample.accuracyM?.toDouble() }.sorted()
        val medianAcc = if (accuracies.isEmpty()) null else accuracies[accuracies.size / 2]
        val incidents = incidents(sorted)

        return TripSummary(
            startTimeMs = first.timeMs,
            endTimeMs = last.timeMs,
            durationMs = durationMs,
            movingMs = movingMs,
            measuredDistanceM = acc.measuredM,
            gapDistanceM = acc.gapM,
            avgSpeedMps = if (durationMs > 0) dist / (durationMs / 1000.0) else 0.0,
            avgMovingSpeedMps = if (movingMs > 0) acc.measuredM / (movingMs / 1000.0) else 0.0,
            maxSpeedMps = maxSpeed,
            pointCount = sorted.size,
            usablePointCount = usable.size,
            medianAccuracyM = medianAcc,
            qualityScore = quality(sorted, usable, medianAcc, incidents, durationMs),
            incidents = incidents,
            simulated = sorted.any { it.sample.isMock },
        )
    }

    private fun maxSpeed(usable: List<TrackPoint>): Double {
        var best = 0.0
        // Doppler speeds from the receiver are far more accurate than position differences.
        // Require two consecutive readings to agree within 20 % to discard isolated spikes.
        for (i in 1 until usable.size) {
            val a = usable[i - 1].sample.reliableSpeedMps(2f)?.toDouble() ?: continue
            val b = usable[i].sample.reliableSpeedMps(2f)?.toDouble() ?: continue
            val lo = minOf(a, b)
            if (lo > 0 && maxOf(a, b) / lo <= 1.2) best = maxOf(best, lo)
        }
        if (best > 0) return best
        // Fallback: displacement over windows of at least MAX_SPEED_WINDOW_MS.
        var j = 0
        for (i in usable.indices) {
            if (usable[i].segmentStart) j = i
            while (j < i && usable[j + 1].sample.millisTo(usable[i].sample) >= MAX_SPEED_WINDOW_MS) j++
            val dt = usable[j].sample.millisTo(usable[i].sample)
            if (dt >= MAX_SPEED_WINDOW_MS) {
                var d = 0.0
                for (k in j + 1..i) d += GeoMath.distanceM(
                    usable[k - 1].sample.lat, usable[k - 1].sample.lng, usable[k].sample.lat, usable[k].sample.lng,
                )
                best = maxOf(best, d / (dt / 1000.0))
            }
        }
        return best
    }

    private fun incidents(sorted: List<TrackPoint>): List<TrackIncident> {
        val out = ArrayList<TrackIncident>()
        var prevUsable: TrackPoint? = null
        var lowStart: TrackPoint? = null
        var lowEnd: TrackPoint? = null
        var lowCount = 0
        var jumps = 0
        var firstJump: Long? = null
        var lastJump: Long? = null
        for (p in sorted) {
            if (p.usable) {
                val prev = prevUsable
                if (p.segmentStart && prev != null) {
                    out += TrackIncident(
                        TrackIncident.Type.SIGNAL_GAP, prev.sample.timeMs, p.sample.timeMs,
                        "${prev.sample.millisTo(p.sample) / 1000} s",
                    )
                }
                prevUsable = p
                if (lowCount >= 5) out += TrackIncident(
                    TrackIncident.Type.LOW_ACCURACY_STRETCH, lowStart!!.sample.timeMs, lowEnd!!.sample.timeMs, "$lowCount puntos",
                )
                lowCount = 0; lowStart = null
            } else if (p.flag == PointFlag.LOW_ACCURACY) {
                if (lowStart == null) lowStart = p
                lowEnd = p
                lowCount++
            } else if (p.flag == PointFlag.JUMP) {
                jumps++
                if (firstJump == null) firstJump = p.sample.timeMs
                lastJump = p.sample.timeMs
            }
        }
        if (lowCount >= 5) out += TrackIncident(
            TrackIncident.Type.LOW_ACCURACY_STRETCH, lowStart!!.sample.timeMs, lowEnd!!.sample.timeMs, "$lowCount puntos",
        )
        if (jumps > 0) out += TrackIncident(TrackIncident.Type.GPS_JUMPS_REJECTED, firstJump!!, lastJump!!, "$jumps puntos")
        if (sorted.any { it.sample.isMock }) out += TrackIncident(
            TrackIncident.Type.SIMULATED_LOCATION, sorted.first().sample.timeMs, sorted.last().sample.timeMs,
        )
        return out
    }

    private fun quality(
        all: List<TrackPoint>, usable: List<TrackPoint>, medianAcc: Double?,
        incidents: List<TrackIncident>, durationMs: Long,
    ): Int {
        if (all.isEmpty()) return 0
        val goodShare = usable.size.toDouble() / all.size
        val accScore = when {
            medianAcc == null -> 0.5
            medianAcc <= 5 -> 1.0
            medianAcc <= 10 -> 0.85
            medianAcc <= 20 -> 0.6
            else -> 0.35
        }
        val minutes = (durationMs / 60_000.0).coerceAtLeast(1.0 / 60)
        val density = (usable.size / minutes / 30.0).coerceAtMost(1.0) // ≥ 30 fixes/min is full marks
        val gapMs = incidents.filter { it.type == TrackIncident.Type.SIGNAL_GAP }.sumOf { it.endTimeMs - it.startTimeMs }
        val gapPenalty = if (durationMs > 0) (gapMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        val score = 100 * (0.35 * goodShare + 0.35 * accScore + 0.3 * density) * (1 - 0.6 * gapPenalty)
        return score.roundToInt().coerceIn(0, 100)
    }
}
