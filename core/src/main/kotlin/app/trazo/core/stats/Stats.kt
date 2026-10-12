package app.trazo.core.stats

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Minimal per-trip facts needed for aggregation (mirrors the persisted trip row). */
@Serializable
data class TripFacts(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceM: Double,
    val movingMs: Long,
    val durationMs: Long,
    val maxSpeedMps: Double,
    val vehicleId: String?,
)

enum class Period { DAY, WEEK, MONTH, YEAR, ALL }

data class PeriodRange(val period: Period, val start: LocalDate, val endExclusive: LocalDate) {
    fun contains(d: LocalDate) = !d.isBefore(start) && d.isBefore(endExclusive)
    fun previous(): PeriodRange = when (period) {
        Period.DAY -> PeriodRange(period, start.minusDays(1), start)
        Period.WEEK -> PeriodRange(period, start.minusWeeks(1), start)
        Period.MONTH -> PeriodRange(period, start.minusMonths(1), start)
        Period.YEAR -> PeriodRange(period, start.minusYears(1), start)
        Period.ALL -> this
    }

    companion object {
        fun containing(period: Period, day: LocalDate): PeriodRange = when (period) {
            Period.DAY -> PeriodRange(period, day, day.plusDays(1))
            Period.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { PeriodRange(period, it, it.plusWeeks(1)) }
            Period.MONTH -> day.withDayOfMonth(1).let { PeriodRange(period, it, it.plusMonths(1)) }
            Period.YEAR -> day.withDayOfYear(1).let { PeriodRange(period, it, it.plusYears(1)) }
            Period.ALL -> PeriodRange(period, LocalDate.of(1970, 1, 1), LocalDate.of(9999, 1, 1))
        }
    }
}

data class Totals(
    val trips: Int = 0,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val durationMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
) {
    val avgMovingSpeedMps: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0
    operator fun plus(t: TripFacts) = Totals(
        trips + 1, distanceM + t.distanceM, movingMs + t.movingMs, durationMs + t.durationMs,
        maxOf(maxSpeedMps, t.maxSpeedMps),
    )
}

data class Comparison(val current: Totals, val previous: Totals) {
    /** Relative change of distance, null when there is nothing to compare against. */
    val distanceChange: Double? get() = if (previous.distanceM > 0) (current.distanceM - previous.distanceM) / previous.distanceM else null
}

data class Bucket(val start: LocalDate, val totals: Totals)

data class Records(
    val longestTrip: TripFacts?,
    val longestDurationTrip: TripFacts?,
    val bestDay: Pair<LocalDate, Double>?,
    val longestStreakDays: Int,
)

/** Aggregations used by the statistics screen; everything is derived from stored trips. */
class StatsCalculator(private val zone: ZoneId) {
    fun dayOf(t: TripFacts): LocalDate = Instant.ofEpochMilli(t.startTimeMs).atZone(zone).toLocalDate()

    fun totals(trips: List<TripFacts>, range: PeriodRange? = null, vehicleId: String? = null): Totals =
        trips.filter { (range == null || range.contains(dayOf(it))) && (vehicleId == null || it.vehicleId == vehicleId) }
            .fold(Totals()) { acc, t -> acc + t }

    fun compare(trips: List<TripFacts>, range: PeriodRange): Comparison =
        Comparison(totals(trips, range), totals(trips, range.previous()))

    /** Evolution chart: one bucket per day/week/month/year covering [from]..[to]. */
    fun series(trips: List<TripFacts>, granularity: Period, from: LocalDate, to: LocalDate): List<Bucket> {
        require(granularity != Period.ALL)
        val out = ArrayList<Bucket>()
        var r = PeriodRange.containing(granularity, from)
        val byDay = trips.groupBy { dayOf(it) }
        while (!r.start.isAfter(to)) {
            var tot = Totals()
            var d = r.start
            while (d.isBefore(r.endExclusive)) {
                byDay[d]?.forEach { tot += it }
                d = d.plusDays(1)
            }
            out += Bucket(r.start, tot)
            r = PeriodRange.containing(granularity, r.endExclusive)
        }
        return out
    }

    fun perVehicle(trips: List<TripFacts>): Map<String?, Totals> =
        trips.groupBy { it.vehicleId }.mapValues { (_, l) -> l.fold(Totals()) { a, t -> a + t } }

    fun records(trips: List<TripFacts>): Records {
        val byDay = trips.groupBy { dayOf(it) }.mapValues { (_, l) -> l.sumOf { it.distanceM } }
        var longest = 0
        var cur = 0
        var prev: LocalDate? = null
        for (d in byDay.keys.sorted()) {
            cur = if (prev != null && prev.plusDays(1) == d) cur + 1 else 1
            longest = maxOf(longest, cur)
            prev = d
        }
        return Records(
            longestTrip = trips.maxByOrNull { it.distanceM },
            longestDurationTrip = trips.maxByOrNull { it.durationMs },
            bestDay = byDay.maxByOrNull { it.value }?.toPair(),
            longestStreakDays = longest,
        )
    }
}

enum class LeaderboardMetric { DISTANCE, TRIPS, DRIVING_TIME, AVG_TRIP_DISTANCE }

data class LeaderboardEntry(val userId: String, val value: Double, val rank: Int)

object Leaderboard {
    /**
     * Ranks users by a metric. Speed is deliberately not a ranking metric, so the social
     * features never reward driving fast.
     */
    fun rank(perUser: Map<String, Totals>, metric: LeaderboardMetric): List<LeaderboardEntry> {
        val values = perUser.map { (u, t) ->
            u to when (metric) {
                LeaderboardMetric.DISTANCE -> t.distanceM
                LeaderboardMetric.TRIPS -> t.trips.toDouble()
                LeaderboardMetric.DRIVING_TIME -> t.movingMs.toDouble()
                LeaderboardMetric.AVG_TRIP_DISTANCE -> if (t.trips > 0) t.distanceM / t.trips else 0.0
            }
        }.sortedByDescending { it.second }
        val out = ArrayList<LeaderboardEntry>()
        var rank = 0
        var prevValue: Double? = null
        values.forEachIndexed { i, (u, v) ->
            if (v != prevValue) rank = i + 1
            prevValue = v
            out += LeaderboardEntry(u, v, rank)
        }
        return out
    }
}
