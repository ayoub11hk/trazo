package app.trazo.core.sim

import app.trazo.core.geo.GeoMath
import app.trazo.core.geo.LatLng
import app.trazo.core.geo.LocalProjection
import app.trazo.core.location.LocationSample
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Generates synthetic drives for tests and for the in-app simulation mode. Every sample is
 * marked `isMock = true` so simulated data can never be confused with real measurements.
 */
class DriveSimulator(private val seed: Int = 42) {

    /** One leg: drive along [path] at [speedMps], then optionally stand still for [stopAfterMs]. */
    data class Leg(val path: List<LatLng>, val speedMps: Double, val stopAfterMs: Long = 0)

    data class Options(
        val startTimeMs: Long = 1_760_000_000_000,
        val startElapsedMs: Long = 10_000_000,
        val intervalMs: Long = 1_000,
        val accuracyM: Float = 5f,
        val noiseM: Double = 3.0,
        /** Time windows (offset ms from start) with no fixes at all, e.g. a tunnel. */
        val gaps: List<LongRange> = emptyList(),
        /** Offsets (ms) at which a wild fix [jumpM] metres away is injected. */
        val jumpsAt: Set<Long> = emptySet(),
        val jumpM: Double = 800.0,
        /** Time windows with degraded accuracy (urban canyon). */
        val poorAccuracy: List<LongRange> = emptyList(),
        val poorAccuracyM: Float = 60f,
        val stationaryLeadMs: Long = 0,
    )

    fun simulate(legs: List<Leg>, opt: Options = Options()): List<LocationSample> {
        val rnd = Random(seed)
        val out = ArrayList<LocationSample>()
        var t = 0L
        fun emit(p: LatLng, speed: Double, bearing: Double?) {
            if (opt.gaps.any { t in it }) { t += opt.intervalMs; return }
            val poor = opt.poorAccuracy.any { t in it }
            val acc = if (poor) opt.poorAccuracyM else opt.accuracyM
            val noise = if (poor) opt.poorAccuracyM.toDouble() * 0.6 else opt.noiseM
            var q = jitter(p, noise, rnd)
            if (t in opt.jumpsAt) q = offset(p, opt.jumpM, rnd.nextDouble() * 360)
            out += LocationSample(
                timeMs = opt.startTimeMs + t,
                elapsedRealtimeMs = opt.startElapsedMs + t,
                lat = q.lat, lng = q.lng,
                accuracyM = acc,
                speedMps = (speed + rnd.nextGaussian() * 0.2).coerceAtLeast(0.0).toFloat(),
                speedAccuracyMps = 0.5f,
                bearingDeg = bearing?.toFloat(),
                bearingAccuracyDeg = if (bearing != null && speed > 2) 8f else 180f,
                provider = "sim",
                isMock = true,
            )
            t += opt.intervalMs
        }
        val first = legs.first().path.first()
        while (t < opt.stationaryLeadMs) emit(first, 0.0, null)
        for (leg in legs) {
            var carry = 0.0
            for (i in 1 until leg.path.size) {
                val a = leg.path[i - 1]
                val b = leg.path[i]
                val len = GeoMath.distanceM(a, b)
                val brg = GeoMath.bearingDeg(a.lat, a.lng, b.lat, b.lng)
                val step = leg.speedMps * opt.intervalMs / 1000.0
                var d = carry
                while (d < len) {
                    emit(GeoMath.lerp(a, b, d / len), leg.speedMps, brg)
                    d += step
                }
                carry = d - len
            }
            val end = leg.path.last()
            var stopped = 0L
            while (stopped < leg.stopAfterMs) { emit(end, 0.0, null); stopped += opt.intervalMs }
        }
        return out
    }

    companion object {
        /** A straight path of [lengthM] metres heading [bearingDeg] from [from], with points every 50 m. */
        fun straight(from: LatLng, lengthM: Double, bearingDeg: Double): List<LatLng> {
            val n = (lengthM / 50).toInt().coerceAtLeast(1)
            return (0..n).map { offset(from, lengthM * it / n, bearingDeg) }
        }

        /** An L-shaped city block route with a right-angle turn halfway. */
        fun cityL(from: LatLng, legM: Double): List<LatLng> {
            val a = straight(from, legM, 90.0)
            return a + straight(a.last(), legM, 0.0).drop(1)
        }

        fun offset(p: LatLng, distM: Double, bearingDeg: Double): LatLng {
            val proj = LocalProjection(p.lat, p.lng)
            val r = GeoMath.toRad(bearingDeg)
            return LatLng(proj.lat(distM * cos(r)), proj.lng(distM * sin(r)))
        }

        private fun jitter(p: LatLng, sigmaM: Double, rnd: Random): LatLng {
            if (sigmaM <= 0) return p
            return offset(p, kotlin.math.abs(rnd.nextGaussian()) * sigmaM, rnd.nextDouble() * 360)
        }

        private fun Random.nextGaussian(): Double {
            // Box–Muller.
            val u1 = nextDouble().coerceAtLeast(1e-12)
            val u2 = nextDouble()
            return sqrt(-2 * ln(u1)) * cos(2 * Math.PI * u2)
        }
    }
}
