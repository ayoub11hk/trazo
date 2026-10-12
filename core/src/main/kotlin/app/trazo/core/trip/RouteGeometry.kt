package app.trazo.core.trip

import app.trazo.core.geo.KalmanSmoother
import app.trazo.core.geo.LatLng
import app.trazo.core.geo.Simplify

/**
 * Renderable geometry for a trip, keeping the three kinds of geometry apart:
 *  - [measured]: segments of accepted raw fixes, exactly as recorded;
 *  - [smoothed]: the same segments after display smoothing;
 *  - [gaps]: straight connectors across signal losses, to be drawn dashed and labelled,
 *    never presented as a measured path.
 */
data class RouteGeometry(
    val measured: List<List<LatLng>>,
    val smoothed: List<List<LatLng>>,
    val gaps: List<Pair<LatLng, LatLng>>,
    val rejected: List<LatLng>,
) {
    val start: LatLng? get() = measured.firstOrNull()?.firstOrNull()
    val end: LatLng? get() = measured.lastOrNull()?.lastOrNull()
    val allMeasured: List<LatLng> get() = measured.flatten()

    companion object {
        fun build(points: List<TrackPoint>, simplifyToleranceM: Double = 0.0): RouteGeometry {
            val sorted = points.sortedBy { it.seq }
            val segments = ArrayList<MutableList<TrackPoint>>()
            val rejected = ArrayList<LatLng>()
            for (p in sorted) {
                if (!p.usable) { rejected += p.sample.latLng; continue }
                if (p.segmentStart || segments.isEmpty()) segments += mutableListOf(p) else segments.last() += p
            }
            val measured = segments.map { seg -> seg.map { it.sample.latLng } }
            val smoothed = segments.map { seg -> KalmanSmoother.smooth(seg.map { it.sample }) }
            val gaps = (1 until measured.size).map { measured[it - 1].last() to measured[it].first() }
            fun simp(l: List<List<LatLng>>) =
                if (simplifyToleranceM > 0) l.map { Simplify.douglasPeucker(it, simplifyToleranceM) } else l
            return RouteGeometry(simp(measured), simp(smoothed), gaps, rejected)
        }
    }
}
