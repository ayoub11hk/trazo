package app.trazo.core

import app.trazo.core.location.DistanceAccumulator
import app.trazo.core.location.GpsFilter
import app.trazo.core.location.LocationSample
import app.trazo.core.location.PointFlag
import app.trazo.core.sim.DriveSimulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpsFilterTest {
    private val sim = DriveSimulator()

    @Test fun rejectsIsolatedJumpAndKeepsTrack() {
        val samples = sim.simulate(
            listOf(DriveSimulator.Leg(DriveSimulator.straight(MADRID, 2000.0, 45.0), 14.0)),
            DriveSimulator.Options(jumpsAt = setOf(30_000L, 80_000L)),
        )
        val f = GpsFilter()
        val results = samples.map { f.process(it) }
        assertEquals(2, results.count { it.flag == PointFlag.JUMP })
        assertEquals(1, results.count { it.segmentStart })
    }

    @Test fun lowAccuracyFixesAreFlaggedNotDropped() {
        val samples = sim.simulate(
            listOf(DriveSimulator.Leg(DriveSimulator.straight(MADRID, 1500.0, 0.0), 12.0)),
            DriveSimulator.Options(poorAccuracy = listOf(20_000L..40_000L)),
        )
        val results = GpsFilter().process(samples)
        assertEquals(samples.size, results.size)
        assertTrue(results.count { it.flag == PointFlag.LOW_ACCURACY } in 18..22)
    }

    @Test fun tunnelGapStartsNewSegmentAndCountsGapDistanceSeparately() {
        val samples = sim.simulate(
            listOf(DriveSimulator.Leg(DriveSimulator.straight(MADRID, 3000.0, 90.0), 20.0)),
            DriveSimulator.Options(gaps = listOf(50_000L..85_000L), noiseM = 0.0),
        )
        val f = GpsFilter()
        val acc = DistanceAccumulator()
        val results = samples.map { f.process(it).also(acc::add) }
        assertEquals(2, results.count { it.segmentStart })
        assertTrue(acc.gapM in 650.0..760.0, "gap distance ${acc.gapM}")
        assertTrue(acc.totalM in 2900.0..3050.0, "total ${acc.totalM}")
    }

    @Test fun stationaryJitterAddsNoDistance() {
        val samples = sim.simulate(
            listOf(DriveSimulator.Leg(listOf(MADRID, MADRID), 0.0, stopAfterMs = 10 * 60_000L)),
            DriveSimulator.Options(noiseM = 6.0, accuracyM = 10f),
        )
        val f = GpsFilter()
        val acc = DistanceAccumulator()
        samples.forEach { acc.add(f.process(it)) }
        assertTrue(acc.totalM < 60.0, "jitter distance ${acc.totalM} over 10 min")
    }

    @Test fun duplicateAndOutOfOrderAreFlagged() {
        val a = LocationSample(1000, 1000, 40.0, -3.0, 5f)
        val f = GpsFilter()
        f.process(a)
        assertEquals(PointFlag.DUPLICATE, f.process(a).flag)
        assertEquals(PointFlag.DUPLICATE, f.process(a.copy(elapsedRealtimeMs = 500)).flag)
    }

    @Test fun reanchorsAfterConsistentRunOfJumps() {
        // A single wrong first fix far away must not block the whole track.
        val good = sim.simulate(listOf(DriveSimulator.Leg(DriveSimulator.straight(MADRID, 1000.0, 0.0), 10.0)))
        val bad = good.first().copy(lat = good.first().lat + 0.05, elapsedRealtimeMs = good.first().elapsedRealtimeMs - 1000)
        val results = GpsFilter().process(listOf(bad) + good)
        assertTrue(results.drop(5).all { it.usable }, "track recovered")
    }

    private fun GpsFilter.process(l: List<LocationSample>) = l.map { process(it) }
}
