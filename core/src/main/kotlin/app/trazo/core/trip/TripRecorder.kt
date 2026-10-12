package app.trazo.core.trip

import app.trazo.core.location.FilterResult
import app.trazo.core.location.GpsFilter
import app.trazo.core.location.GpsFilterConfig
import app.trazo.core.location.LocationSample
import kotlinx.serialization.Serializable

/** Receives the recorder's output; the Android service persists it to Room. */
interface TripSink {
    fun tripStarted(startTimeMs: Long, manual: Boolean)
    fun point(p: TrackPoint)
    fun tripEnded(lastMovingSeq: Int?, reason: EndReason)
    fun tripDiscarded()
    fun stateChanged(state: DetectorState) {}
}

@Serializable
data class RecorderSnapshot(val detector: DetectorSnapshot, val nextSeq: Int, val lastMovingSeq: Int?)

/**
 * Glue between [GpsFilter] and [TripDetector]: classifies every fix, keeps a bounded
 * look-back buffer so the start of a trip is not lost while the detector is still confirming
 * it, and streams numbered [TrackPoint]s to a [TripSink]. Holds no unbounded state.
 */
class TripRecorder(
    filterConfig: GpsFilterConfig = GpsFilterConfig(),
    detectorConfig: TripDetectorConfig = TripDetectorConfig(),
    private val sink: TripSink,
) {
    private val filter = GpsFilter(filterConfig)
    val detector = TripDetector(detectorConfig)
    private val lookBack = ArrayDeque<FilterResult>()
    private val seqByElapsed = LinkedHashMap<Long, Int>()
    private var nextSeq = 0
    private var firstUsableEmitted = false
    private var lastState = DetectorState.IDLE

    val state: DetectorState get() = detector.state
    val lastAccepted: LocationSample? get() = filter.lastAccepted

    fun onFix(sample: LocationSample): FilterResult {
        val r = filter.process(sample)
        val wasInTrip = detector.isInTrip
        if (!wasInTrip) {
            lookBack.addLast(r)
            while (lookBack.size > LOOK_BACK) lookBack.removeFirst()
        }
        val events = detector.onFix(r)
        if (wasInTrip && detector.isInTrip) emit(r)
        handle(events, r)
        notifyState()
        return r
    }

    fun onActivity(hint: ActivityHint, nowElapsedMs: Long) {
        handle(detector.onActivity(hint, nowElapsedMs), null)
        notifyState()
    }

    fun onTick(nowElapsedMs: Long) {
        handle(detector.onTick(nowElapsedMs), null)
        notifyState()
    }

    fun startManual(nowMs: Long) {
        val events = detector.forceStart(filter.lastAccepted)
        if (events.isEmpty() && detector.isInTrip) {
            // No fix yet: open the trip now, the first fix will anchor it.
            beginTrip(nowMs, manual = true)
        }
        handle(events, null)
        notifyState()
    }

    fun stopManual(nowElapsedMs: Long) {
        handle(detector.forceEnd(nowElapsedMs), null)
        notifyState()
    }

    fun updateDetectorConfig(c: TripDetectorConfig) = detector.updateConfig(c)

    fun snapshot() = RecorderSnapshot(detector.snapshot(), nextSeq, lastMovingSeq())

    fun restore(s: RecorderSnapshot, rebooted: Boolean) {
        detector.restore(s.detector, rebooted)
        nextSeq = s.nextSeq
        firstUsableEmitted = s.nextSeq > 0
        if (rebooted) filter.reset()
        lastState = detector.state
    }

    private fun handle(events: List<TripEvent>, current: FilterResult?) {
        for (e in events) when (e) {
            is TripEvent.Started -> {
                beginTrip(e.startSample.timeMs, e.manual)
                val startElapsed = e.startSample.elapsedRealtimeMs
                lookBack.filter { it.sample.elapsedRealtimeMs >= startElapsed }.forEach { emit(it) }
                lookBack.clear()
                if (current != null && seqByElapsed[current.sample.elapsedRealtimeMs] == null &&
                    current.sample.elapsedRealtimeMs >= startElapsed
                ) emit(current)
            }
            is TripEvent.Ended -> {
                val seq = e.lastMovingSample?.let { seqByElapsed[it.elapsedRealtimeMs] }
                sink.tripEnded(seq, e.reason)
                resetTripState()
            }
            is TripEvent.Discarded -> {
                sink.tripDiscarded()
                resetTripState()
            }
            is TripEvent.Stopped, is TripEvent.Resumed -> Unit
        }
    }

    private fun beginTrip(startTimeMs: Long, manual: Boolean) {
        nextSeq = 0
        firstUsableEmitted = false
        seqByElapsed.clear()
        sink.tripStarted(startTimeMs, manual)
    }

    private fun emit(r: FilterResult) {
        val seq = nextSeq++
        val segStart = if (!firstUsableEmitted && r.usable) { firstUsableEmitted = true; true } else r.segmentStart && r.usable
        seqByElapsed[r.sample.elapsedRealtimeMs] = seq
        if (seqByElapsed.size > SEQ_INDEX) seqByElapsed.remove(seqByElapsed.keys.first())
        sink.point(TrackPoint(seq, r.sample, r.flag, segStart))
    }

    private fun lastMovingSeq(): Int? =
        detector.snapshot().lastMoving?.let { seqByElapsed[it.elapsedRealtimeMs] }

    private fun resetTripState() {
        seqByElapsed.clear()
        lookBack.clear()
        firstUsableEmitted = false
        nextSeq = 0
    }

    private fun notifyState() {
        if (detector.state != lastState) {
            lastState = detector.state
            sink.stateChanged(lastState)
        }
    }

    companion object {
        const val LOOK_BACK = 900
        const val SEQ_INDEX = 4_000
    }
}
