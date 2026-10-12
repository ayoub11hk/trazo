package app.trazo.core

import app.trazo.core.geo.GeoMath
import app.trazo.core.geo.LatLng
import app.trazo.core.geo.Polyline
import app.trazo.core.geo.Simplify
import app.trazo.core.location.GpsFilter
import app.trazo.core.playback.PlaybackTimeline
import app.trazo.core.policy.DeviceContext
import app.trazo.core.policy.AdaptiveLocationPolicy
import app.trazo.core.policy.LivePublishPolicy
import app.trazo.core.policy.Priority
import app.trazo.core.sim.DriveSimulator
import app.trazo.core.sim.DriveSimulator.Leg
import app.trazo.core.social.Freshness
import app.trazo.core.social.InviteCode
import app.trazo.core.social.LocationFreshness
import app.trazo.core.stats.Leaderboard
import app.trazo.core.stats.LeaderboardMetric
import app.trazo.core.stats.Period
import app.trazo.core.stats.PeriodRange
import app.trazo.core.stats.StatsCalculator
import app.trazo.core.stats.Totals
import app.trazo.core.stats.TripFacts
import app.trazo.core.trip.DetectorState
import app.trazo.core.trip.RouteGeometry
import app.trazo.core.trip.TrackPoint
import app.trazo.core.trip.TripAnalyzer
import app.trazo.core.trip.TripEditor
import app.trazo.core.trip.TripRecorder
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiscCoreTest {
    private fun track(legs: List<Leg>, opt: DriveSimulator.Options = DriveSimulator.Options()): List<TrackPoint> {
        val f = GpsFilter()
        return DriveSimulator().simulate(legs, opt).mapIndexed { i, s ->
            val r = f.process(s); TrackPoint(i, s, r.flag, r.segmentStart || i == 0)
        }
    }

    @Test fun haversineKnownDistance() {
        // Madrid (Sol) → Barcelona (Pl. Catalunya) ≈ 505 km great-circle.
        val d = GeoMath.distanceM(40.4168, -3.7038, 41.3870, 2.1701)
        assertTrue(d in 500_000.0..510_000.0, "$d")
    }

    @Test fun polylineRoundTrip() {
        val pts = DriveSimulator.cityL(MADRID, 500.0)
        val back = Polyline.decode(Polyline.encode(pts))
        assertEquals(pts.size, back.size)
        pts.zip(back).forEach { (a, b) -> assertTrue(GeoMath.distanceM(a, b) < 0.2) }
    }

    @Test fun simplificationKeepsCornerAndShape() {
        val pts = DriveSimulator.cityL(MADRID, 1000.0)
        val s = Simplify.douglasPeucker(pts, 2.0)
        assertTrue(s.size in 3..5, "kept ${s.size}")
        val corner = pts[pts.size / 2]
        assertTrue(s.any { GeoMath.distanceM(it, corner) < 1 })
    }

    @Test fun playbackUsesTimestampsAndHoldsInGaps() {
        val pts = track(
            listOf(Leg(DriveSimulator.straight(MADRID, 3000.0, 0.0), 20.0)),
            DriveSimulator.Options(gaps = listOf(40_000L..70_000L), noiseM = 0.0),
        )
        val tl = PlaybackTimeline(pts)
        assertTrue(tl.durationMs in 148_000L..151_000L, "${tl.durationMs}")
        val f20 = tl.frameAt(20_500)!!
        assertTrue(f20.distanceM in 395.0..425.0, "d at 20.5s ${f20.distanceM}")
        val inGap = tl.frameAt(55_000)!!
        assertTrue(inGap.inGap)
        val before = tl.frameAt(39_000)!!
        assertTrue(GeoMath.distanceM(inGap.position, before.position) < 30)
        var prev = -1.0
        for (t in 0L..tl.durationMs step 500) { val fr = tl.frameAt(t)!!; assertTrue(fr.distanceM >= prev); prev = fr.distanceM }
    }

    @Test fun routeGeometrySeparatesMeasuredAndGaps() {
        val pts = track(
            listOf(Leg(DriveSimulator.straight(MADRID, 3000.0, 0.0), 20.0)),
            DriveSimulator.Options(gaps = listOf(40_000L..70_000L), jumpsAt = setOf(100_000L)),
        )
        val g = RouteGeometry.build(pts)
        assertEquals(2, g.measured.size)
        assertEquals(1, g.gaps.size)
        assertEquals(1, g.rejected.size)
        assertEquals(g.measured.sumOf { it.size }, g.smoothed.sumOf { it.size })
    }

    @Test fun analyzerMaxSpeedIgnoresSpikes() {
        val pts = track(listOf(Leg(DriveSimulator.straight(MADRID, 2000.0, 0.0), 25.0)), DriveSimulator.Options(jumpsAt = setOf(30_000L)))
        val s = TripAnalyzer.analyze(pts)!!
        assertTrue(s.maxSpeedMps in 24.0..26.5, "max ${s.maxSpeedMps}")
        assertTrue(s.simulated)
        assertTrue(s.qualityScore > 70, "quality ${s.qualityScore}")
    }

    @Test fun mergeAndSplitPreserveAllPoints() {
        val a = track(listOf(Leg(DriveSimulator.straight(MADRID, 1000.0, 0.0), 10.0)))
        val b = track(listOf(Leg(DriveSimulator.straight(MADRID, 1000.0, 90.0), 10.0)), DriveSimulator.Options(startTimeMs = 1_760_000_500_000))
        val merged = TripEditor.merge(b, a)
        assertEquals(a.size + b.size, merged.size)
        assertEquals((0 until merged.size).toList(), merged.map { it.seq })
        assertEquals(a.first().sample, merged.first().sample)
        assertTrue(merged[a.size].segmentStart)
        val (l, r) = TripEditor.split(merged, b.first().sample.timeMs)!!
        assertEquals(a.size, l.size); assertEquals(b.size, r.size)
        assertNull(TripEditor.split(merged, 0))
    }

    @Test fun inviteCodeNormalisation() {
        assertTrue(InviteCode.isValid("abcd-efgh"))
        assertEquals("ABCD0EF1", InviteCode.normalize("abcd oef i"))
        assertEquals("K7Q2-M9XZ", InviteCode.format("k7q2m9xz"))
        assertEquals("K7Q2M9XZ", InviteCode.fromLinkOrText("Únete a Trazo: trazo://invite/K7Q2M9XZ"))
        assertNull(InviteCode.fromLinkOrText("hola"))
    }

    @Test fun freshnessNeverCallsOldPositionsLive() {
        val now = 1_000_000_000L
        assertEquals(Freshness.LIVE, LocationFreshness.classify(now - 10_000, now, false))
        assertEquals(Freshness.RECENT, LocationFreshness.classify(now - 120_000, now, false))
        assertEquals(Freshness.STALE, LocationFreshness.classify(now - 3_600_000, now, false))
        assertEquals(Freshness.PAUSED, LocationFreshness.classify(now, now, true))
        assertEquals("hace 2 min", LocationFreshness.ageLabel(now - 125_000, now))
    }

    @Test fun adaptivePolicy() {
        val drive = AdaptiveLocationPolicy.requestFor(DeviceContext(DetectorState.DRIVING, false, 80, false, true))
        assertEquals(Priority.HIGH_ACCURACY, drive.priority); assertEquals(1000, drive.intervalMs)
        val low = AdaptiveLocationPolicy.requestFor(DeviceContext(DetectorState.DRIVING, false, 10, false, true))
        assertEquals(2000, low.intervalMs)
        val parked = AdaptiveLocationPolicy.requestFor(DeviceContext(DetectorState.IDLE, false, 80, false, false))
        assertEquals(Priority.PASSIVE, parked.priority)
    }

    @Test fun livePublishSkipsRedundantUpdates() {
        val p = LivePublishPolicy(minIntervalMs = 2000, heartbeatMs = 60_000)
        val parked = DriveSimulator().simulate(listOf(Leg(listOf(MADRID, MADRID), 0.0, stopAfterMs = 120_000)), DriveSimulator.Options(noiseM = 1.0))
        var sent = 0
        parked.forEach { if (p.shouldPublish(it, it.elapsedRealtimeMs)) sent++ }
        assertTrue(sent in 2..4, "parked sent $sent")
        p.reset(); sent = 0
        val driving = DriveSimulator().simulate(listOf(Leg(DriveSimulator.straight(MADRID, 2000.0, 0.0), 15.0)))
        driving.forEach { if (p.shouldPublish(it, it.elapsedRealtimeMs)) sent++ }
        assertTrue(sent >= driving.size / 2 - 2, "driving sent $sent of ${driving.size}")
    }

    @Test fun statsPeriodsComparisonAndRecords() {
        val zone = ZoneId.of("Europe/Madrid")
        fun t(day: String, km: Double, v: String? = "car") = TripFacts(
            day + km, LocalDate.parse(day).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), 0,
            km * 1000, 3_600_000, 3_700_000, 30.0, v,
        )
        val trips = listOf(t("2026-10-05", 10.0), t("2026-10-06", 20.0), t("2026-10-07", 5.0, "moto"), t("2026-09-29", 7.0))
        val calc = StatsCalculator(zone)
        val week = PeriodRange.containing(Period.WEEK, LocalDate.parse("2026-10-08"))
        val cmp = calc.compare(trips, week)
        assertEquals(3, cmp.current.trips)
        assertEquals(35_000.0, cmp.current.distanceM)
        assertEquals(7_000.0, cmp.previous.distanceM)
        assertEquals(4.0, cmp.distanceChange!!, 1e-9)
        assertEquals(5_000.0, calc.perVehicle(trips)["moto"]!!.distanceM)
        val r = calc.records(trips)
        assertEquals(20_000.0, r.longestTrip!!.distanceM)
        assertEquals(3, r.longestStreakDays)
        val series = calc.series(trips, Period.DAY, LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-08"))
        assertEquals(5, series.size)
        assertEquals(0, series.first().totals.trips)
    }

    @Test fun leaderboardTiesShareRank() {
        val r = Leaderboard.rank(
            mapOf("a" to Totals(trips = 3, distanceM = 10.0), "b" to Totals(trips = 3, distanceM = 30.0), "c" to Totals(trips = 1, distanceM = 30.0)),
            LeaderboardMetric.DISTANCE,
        )
        assertEquals(listOf(1, 1, 3), r.map { it.rank })
    }

    @Test fun recorderSnapshotRestoreContinuesTrip() {
        val samples = DriveSimulator().simulate(listOf(Leg(DriveSimulator.straight(MADRID, 6000.0, 0.0), 15.0, stopAfterMs = 6 * 60_000L)))
        val sink1 = CollectingSink()
        val r1 = TripRecorder(sink = sink1)
        val half = samples.size / 3
        samples.take(half).forEach { r1.onFix(it) }
        assertNotNull(sink1.open)
        val snap = r1.snapshot()
        val pointsBefore = sink1.open!!.points.size
        // Process death: a new recorder restores and keeps writing into the same trip.
        val sink2 = CollectingSink().also { it.tripStarted(0, false) }
        val r2 = TripRecorder(sink = sink2)
        r2.restore(snap, rebooted = false)
        samples.drop(half).forEach { r2.onFix(it) }
        assertEquals(1, sink2.trips.size)
        assertEquals(pointsBefore, sink2.trips.single().points.first().seq)
    }
}
