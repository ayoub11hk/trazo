package app.trazo.core

import app.trazo.core.geo.LatLng
import app.trazo.core.location.LocationSample
import app.trazo.core.trip.DetectorState
import app.trazo.core.trip.EndReason
import app.trazo.core.trip.TrackPoint
import app.trazo.core.trip.TripDetectorConfig
import app.trazo.core.trip.TripRecorder
import app.trazo.core.trip.TripSink

val MADRID = LatLng(40.4168, -3.7038)

class RecordedTrip(val startTimeMs: Long, val manual: Boolean) {
    val points = ArrayList<TrackPoint>()
    var lastMovingSeq: Int? = null
    var reason: EndReason? = null
    val trimmed: List<TrackPoint> get() = lastMovingSeq?.let { s -> points.filter { it.seq <= s } } ?: points
}

class CollectingSink : TripSink {
    val trips = ArrayList<RecordedTrip>()
    var discarded = 0
    val states = ArrayList<DetectorState>()
    private var current: RecordedTrip? = null
    override fun tripStarted(startTimeMs: Long, manual: Boolean) { current = RecordedTrip(startTimeMs, manual) }
    override fun point(p: TrackPoint) { current!!.points += p }
    override fun tripEnded(lastMovingSeq: Int?, reason: EndReason) {
        current!!.lastMovingSeq = lastMovingSeq; current!!.reason = reason; trips += current!!; current = null
    }
    override fun tripDiscarded() { discarded++; current = null }
    override fun stateChanged(state: DetectorState) { states += state }
    val open: RecordedTrip? get() = current
}

/** Feeds samples, ticking the clock every second, and keeps ticking [tailMs] after the last fix. */
fun runRecorder(samples: List<LocationSample>, tailMs: Long = 0, cfg: TripDetectorConfig = TripDetectorConfig()): CollectingSink {
    val sink = CollectingSink()
    val rec = TripRecorder(detectorConfig = cfg, sink = sink)
    samples.forEach { rec.onFix(it) }
    val last = samples.last().elapsedRealtimeMs
    var t = last
    while (t < last + tailMs) { t += 1000; rec.onTick(t) }
    return sink
}
