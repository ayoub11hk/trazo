package app.trazo.core.trip

/**
 * History corrections. Raw fixes are never altered: merging concatenates the point lists in
 * time order (the junction becomes a new segment if there was a pause), splitting partitions
 * them at a timestamp.
 */
object TripEditor {
    fun merge(a: List<TrackPoint>, b: List<TrackPoint>): List<TrackPoint> {
        val sa = a.sortedBy { it.seq }
        val sb = b.sortedBy { it.seq }
        val (first, second) = if ((sa.firstOrNull()?.sample?.timeMs ?: Long.MAX_VALUE) <=
            (sb.firstOrNull()?.sample?.timeMs ?: Long.MAX_VALUE)
        ) sa to sb else sb to sa
        val joined = first + second.mapIndexed { i, p -> if (i == 0 && p.usable) p.copy(segmentStart = true) else p }
        return renumber(joined)
    }

    /** Split at [timeMs] (UTC). Returns null when either side would be empty. */
    fun split(points: List<TrackPoint>, timeMs: Long): Pair<List<TrackPoint>, List<TrackPoint>>? {
        val sorted = points.sortedBy { it.seq }
        val left = sorted.filter { it.sample.timeMs < timeMs }
        val right = sorted.filter { it.sample.timeMs >= timeMs }
        if (left.isEmpty() || right.isEmpty()) return null
        val r = right.mapIndexed { i, p -> if (i == 0) p.copy(segmentStart = true) else p }
        return renumber(left) to renumber(r)
    }

    /** Trims trailing idle fixes recorded after the last moving fix. */
    fun trimAfter(points: List<TrackPoint>, lastSeq: Int): List<TrackPoint> =
        points.filter { it.seq <= lastSeq }

    private fun renumber(list: List<TrackPoint>) = list.mapIndexed { i, p -> p.copy(seq = i) }
}
