package app.trazo.core

import app.trazo.core.geo.GeoMath
import app.trazo.core.geo.LatLng
import app.trazo.core.live.CameraPolicy
import app.trazo.core.live.CameraTarget
import app.trazo.core.live.LiveTrail
import app.trazo.core.live.LiveTrailConfig
import app.trazo.core.live.MarkerMotion
import app.trazo.core.live.TrailPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveTrailTest {
    /** ~14 m per step east at Madrid's latitude, one step per second (≈ 50 km/h). */
    private fun drive(n: Int, t0: Long = 0, start: LatLng = MADRID, stepDeg: Double = 0.000165) =
        (0 until n).map { TrailPoint(t0 + it * 1000L, start.lat, start.lng + it * stepDeg, 5f) }

    @Test fun drawsContinuousLineWhileDriving() {
        val t = LiveTrail()
        t.addAll(drive(30))
        assertEquals(1, t.segments().size)
        assertEquals(30, t.pointCount)
    }

    @Test fun rejectsInvalidAndImpreciseFixes() {
        val t = LiveTrail()
        assertFalse(t.add(TrailPoint(0, 0.0, 0.0)))
        assertFalse(t.add(TrailPoint(1, Double.NaN, 3.0)))
        assertFalse(t.add(TrailPoint(2, 95.0, 3.0)))
        assertFalse(t.add(TrailPoint(3, MADRID.lat, MADRID.lng, accuracyM = 250f)))
        assertTrue(t.isEmpty)
    }

    @Test fun ignoresDuplicatesAndOutOfOrder() {
        val t = LiveTrail()
        val pts = drive(5)
        t.addAll(pts)
        assertFalse(t.add(pts[2]))
        assertFalse(t.add(pts[4]))
        assertEquals(5, t.pointCount)
    }

    @Test fun standingStillDoesNotAddJitterVertices() {
        val t = LiveTrail()
        repeat(60) { t.add(TrailPoint(it * 1000L, MADRID.lat + (it % 2) * 0.00001, MADRID.lng, 5f)) }
        assertEquals(1, t.pointCount)
    }

    @Test fun singleGpsJumpIsDropped() {
        val t = LiveTrail()
        val pts = drive(10).toMutableList()
        // 3 km away one second later: impossible for a car.
        pts.add(5, TrailPoint(5_500, MADRID.lat + 0.027, MADRID.lng, 8f))
        t.addAll(pts)
        assertEquals(1, t.segments().size)
        assertEquals(10, t.pointCount)
    }

    @Test fun longSilenceStartsNewSegmentInsteadOfStraightLine() {
        val t = LiveTrail(LiveTrailConfig(maxGapMs = 120_000))
        t.addAll(drive(10))
        // Ten minutes without signal, then 8 km further on.
        val later = LatLng(MADRID.lat + 0.07, MADRID.lng)
        t.addAll(drive(10, t0 = 10_000 + 600_000, start = later))
        val segs = t.segments()
        assertEquals(2, segs.size)
        val gap = GeoMath.distanceM(segs[0].last(), segs[1].first())
        assertTrue(gap > 7_000, "segments must not be joined")
    }

    @Test fun realRelocationIsAcceptedAfterRepeatedJumps() {
        val t = LiveTrail(LiveTrailConfig(jumpsBeforeRelocate = 3))
        t.addAll(drive(5))
        val far = LatLng(MADRID.lat + 0.05, MADRID.lng)
        t.addAll(drive(6, t0 = 5_000, start = far))
        assertEquals(2, t.segments().size)
    }

    @Test fun trimsOldPoints() {
        val t = LiveTrail(LiveTrailConfig(maxAgeMs = 10_000))
        t.addAll(drive(30))
        assertTrue(t.pointCount <= 11)
    }
}

class MarkerMotionTest {
    private val a = MADRID
    private val b = LatLng(MADRID.lat, MADRID.lng + 0.001)

    @Test fun firstPositionShowsImmediately() {
        val m = MarkerMotion()
        m.setTarget("x", a, null, 0)
        assertEquals(a, m.pose("x", 0)!!.position)
        assertFalse(m.isAnimating(0))
    }

    @Test fun glidesBetweenFixesAndStopsOnTheNewest() {
        val m = MarkerMotion()
        m.setTarget("x", a, 90.0, 0)
        m.setTarget("x", b, 90.0, 1_000)
        assertTrue(m.isAnimating(1_200))
        val mid = m.pose("x", 1_500)!!.position
        assertTrue(mid.lng > a.lng && mid.lng < b.lng)
        assertEquals(b, m.pose("x", 10_000)!!.position)
        assertFalse(m.isAnimating(10_000))
    }

    @Test fun neverOvershootsLastFix() {
        val m = MarkerMotion()
        m.setTarget("x", a, null, 0)
        m.setTarget("x", b, null, 1_000)
        for (t in 1_000L..4_000L step 50) assertTrue(m.pose("x", t)!!.position.lng <= b.lng + 1e-12)
    }

    @Test fun longJumpsSnap() {
        val m = MarkerMotion()
        m.setTarget("x", a, null, 0)
        val far = LatLng(MADRID.lat + 0.5, MADRID.lng)
        m.setTarget("x", far, null, 1_000)
        assertEquals(far, m.pose("x", 1_001)!!.position)
    }

    @Test fun bearingTurnsTheShortWay() {
        assertEquals(0.0, MarkerMotion.lerpAngle(350.0, 10.0, 0.5), 1e-9)
        assertEquals(355.0, MarkerMotion.lerpAngle(10.0, 340.0, 0.5), 1e-9)
    }
}

class CameraPolicyTest {
    @Test fun opensAtStreetLevelOnAPreciseFix() {
        val c = CameraPolicy.initial(MADRID, 8f, null)
        assertNotNull(c)
        assertEquals(17.0, c.zoom)
    }

    @Test fun impreciseFixZoomsLess() {
        assertEquals(15.0, CameraPolicy.zoomFor(400f))
        assertTrue(CameraPolicy.zoomFor(2_000f) < 15.0)
    }

    @Test fun fallsBackToSavedCameraThenNothing() {
        val saved = CameraTarget(MADRID, 12.0)
        assertEquals(saved, CameraPolicy.initial(null, null, saved))
        assertEquals(null, CameraPolicy.initial(null, null, null))
    }

    @Test fun doesNotFightTheUser() {
        assertTrue(CameraPolicy.shouldRecenter(following = false, centeredOnFreshFix = false, userMovedMap = false))
        assertFalse(CameraPolicy.shouldRecenter(following = false, centeredOnFreshFix = false, userMovedMap = true))
        assertFalse(CameraPolicy.shouldRecenter(following = false, centeredOnFreshFix = true, userMovedMap = false))
        assertTrue(CameraPolicy.shouldRecenter(following = true, centeredOnFreshFix = true, userMovedMap = true))
    }
}
