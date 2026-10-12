package app.trazo.core

import app.trazo.core.sim.DriveSimulator
import app.trazo.core.sim.DriveSimulator.Leg
import app.trazo.core.trip.EndReason
import app.trazo.core.trip.Sensitivity
import app.trazo.core.trip.TripAnalyzer
import app.trazo.core.trip.TripDetectorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripDetectionTest {
    private val sim = DriveSimulator()

    @Test fun cityDriveWithTrafficLightsIsOneTrip() {
        val p1 = DriveSimulator.cityL(MADRID, 600.0)
        val p2 = DriveSimulator.straight(p1.last(), 800.0, 0.0)
        val p3 = DriveSimulator.straight(p2.last(), 900.0, 270.0)
        val samples = sim.simulate(
            listOf(
                Leg(p1, 11.0, stopAfterMs = 90_000),   // red light
                Leg(p2, 9.0, stopAfterMs = 150_000),   // long level crossing
                Leg(p3, 12.0, stopAfterMs = 7 * 60_000L), // parked
            ),
            DriveSimulator.Options(stationaryLeadMs = 60_000),
        )
        val sink = runRecorder(samples)
        assertEquals(1, sink.trips.size, "trips: ${sink.trips.size}")
        val trip = sink.trips.single()
        assertEquals(EndReason.STILL, trip.reason)
        val s = TripAnalyzer.analyze(trip.trimmed)!!
        // True path is 1200 + 800 + 900 = 2900 m.
        assertTrue(s.distanceM in 2750.0..3000.0, "distance ${s.distanceM}")
        // Parked tail must be trimmed: trip ends at the last moving fix, not 7 minutes later.
        val lastT = trip.trimmed.last().sample.timeMs
        assertTrue(samples.last().timeMs - lastT > 6 * 60_000L)
    }

    @Test fun trafficJamCrawlDoesNotEndTrip() {
        val a = DriveSimulator.straight(MADRID, 1500.0, 0.0)
        val jam = DriveSimulator.straight(a.last(), 900.0, 0.0) // 900 m at 1.5 m/s = 10 min
        val b = DriveSimulator.straight(jam.last(), 1500.0, 0.0)
        val samples = sim.simulate(listOf(Leg(a, 15.0), Leg(jam, 1.6), Leg(b, 15.0, stopAfterMs = 6 * 60_000L)))
        val sink = runRecorder(samples)
        assertEquals(1, sink.trips.size)
    }

    @Test fun walkingIsNotATrip() {
        val samples = sim.simulate(listOf(Leg(DriveSimulator.straight(MADRID, 1200.0, 30.0), 1.4, stopAfterMs = 6 * 60_000L)))
        val sink = runRecorder(samples)
        assertEquals(0, sink.trips.size)
        assertNull(sink.open)
    }

    @Test fun shortHopIsDiscarded() {
        val samples = sim.simulate(listOf(Leg(DriveSimulator.straight(MADRID, 300.0, 30.0), 8.0, stopAfterMs = 6 * 60_000L)))
        val sink = runRecorder(samples)
        assertEquals(0, sink.trips.size)
    }

    @Test fun tunnelDoesNotSplitTrip() {
        val path = DriveSimulator.straight(MADRID, 6000.0, 120.0)
        val samples = sim.simulate(
            listOf(Leg(path, 22.0, stopAfterMs = 6 * 60_000L)),
            DriveSimulator.Options(gaps = listOf(60_000L..150_000L)),
        )
        val sink = runRecorder(samples)
        assertEquals(1, sink.trips.size)
        val s = TripAnalyzer.analyze(sink.trips.single().trimmed)!!
        assertEquals(1, s.incidents.count { it.type.name == "SIGNAL_GAP" })
        assertTrue(s.gapDistanceM > 1800, "gap distance ${s.gapDistanceM}")
        assertTrue(s.distanceM in 5800.0..6150.0, "distance ${s.distanceM}")
    }

    @Test fun signalLostForeverClosesTrip() {
        val samples = sim.simulate(listOf(Leg(DriveSimulator.straight(MADRID, 3000.0, 0.0), 15.0)))
        val sink = runRecorder(samples, tailMs = 25 * 60_000L)
        assertEquals(1, sink.trips.size)
        assertEquals(EndReason.SIGNAL_LOST, sink.trips.single().reason)
    }

    @Test fun startIsNotLostWhileConfirming() {
        val path = DriveSimulator.straight(MADRID, 4000.0, 0.0)
        val samples = sim.simulate(listOf(Leg(path, 14.0, stopAfterMs = 6 * 60_000L)), DriveSimulator.Options(noiseM = 0.0))
        val trip = runRecorder(samples).trips.single()
        val first = trip.points.first().sample
        assertTrue(app.trazo.core.geo.GeoMath.distanceM(first.latLng, MADRID) < 30, "trip starts where driving started")
    }

    @Test fun twoDrivesSeparatedByLongStopAreTwoTrips() {
        val a = DriveSimulator.straight(MADRID, 3000.0, 0.0)
        val b = DriveSimulator.straight(a.last(), 3000.0, 90.0)
        val samples = sim.simulate(listOf(Leg(a, 15.0, stopAfterMs = 20 * 60_000L), Leg(b, 15.0, stopAfterMs = 6 * 60_000L)))
        assertEquals(2, runRecorder(samples).trips.size)
    }

    @Test fun sensitivityChangesEndTimeout() {
        val a = DriveSimulator.straight(MADRID, 3000.0, 0.0)
        val b = DriveSimulator.straight(a.last(), 3000.0, 90.0)
        val samples = sim.simulate(listOf(Leg(a, 15.0, stopAfterMs = 4 * 60_000L), Leg(b, 15.0, stopAfterMs = 9 * 60_000L)))
        assertEquals(1, runRecorder(samples, cfg = TripDetectorConfig.forSensitivity(Sensitivity.NORMAL)).trips.size)
        assertEquals(2, runRecorder(samples, cfg = TripDetectorConfig.forSensitivity(Sensitivity.HIGH)).trips.size)
    }
}
