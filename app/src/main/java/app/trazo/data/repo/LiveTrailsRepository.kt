package app.trazo.data.repo

import app.trazo.core.geo.LatLng
import app.trazo.core.live.LiveTrail
import app.trazo.core.live.LiveTrailConfig
import app.trazo.core.live.TrailPoint
import app.trazo.core.location.LocationSample
import app.trazo.data.remote.Backend
import app.trazo.data.remote.LiveLocationDto
import app.trazo.data.remote.LiveTrailPointDto
import app.trazo.data.remote.isoToMillis
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lines drawn behind people on the main map while they move. Built only from real positions:
 * friends' realtime updates plus, for a trip in progress, the points already uploaded
 * (server RPC live_trail, permission-checked). Independent from trip history, which reads
 * stored trips; both use the same filtering rules from :core.
 */
class LiveTrailsRepository(private val backend: Backend, private val scope: CoroutineScope) {
    private class Entry(val key: String, var trail: LiveTrail, val cfg: LiveTrailConfig) {
        /** Everything received, so a late backlog can be merged in time order. */
        val raw = ArrayList<TrailPoint>()
        var backfillRequested = false
        var clearedBeforeMs = Long.MIN_VALUE
    }

    private val entries = HashMap<String, Entry>()
    private val _version = MutableStateFlow(0L)
    /** Bumps whenever any line changes. */
    val version: StateFlow<Long> = _version

    /** My own line when no trip is being recorded (while recording, the stored trip is drawn instead). */
    private var mine = Entry("me", LiveTrail(FREE_CFG), FREE_CFG)

    @Synchronized
    fun ingest(dto: LiveLocationDto, me: String?) {
        if (dto.userId == me) return
        val lat = dto.lat
        val lng = dto.lng
        if (!dto.sharing || lat == null || lng == null) {
            if (entries.remove(dto.userId) != null) bump()
            return
        }
        val t = isoToMillis(dto.recordedAt) ?: isoToMillis(dto.updatedAt) ?: return
        val key = dto.tripId ?: "free"
        var e = entries[dto.userId]
        if (e == null || e.key != key) {
            // A new trip (or the end of one) starts a fresh line: the finished one lives in history.
            val cfg = if (dto.tripId != null) TRIP_CFG else FREE_CFG
            e = Entry(key, LiveTrail(cfg), cfg)
            entries[dto.userId] = e
        }
        val p = TrailPoint(t, lat, lng, dto.accuracyM)
        e.raw += p
        if (e.raw.size > MAX_RAW) e.raw.subList(0, e.raw.size - MAX_RAW).clear()
        if (t > e.clearedBeforeMs && e.trail.add(p)) bump()
        if (dto.tripId != null && !e.backfillRequested) {
            e.backfillRequested = true
            backfill(dto.userId, e)
        }
    }

    private fun backfill(userId: String, e: Entry) = scope.launch {
        val pts = runCatching {
            backend.client().postgrest.rpc("live_trail", buildJsonObject { put("p_user", userId) })
                .decodeList<LiveTrailPointDto>()
        }.getOrNull() ?: return@launch // older database or offline: the line simply starts now
        synchronized(this@LiveTrailsRepository) {
            if (entries[userId] !== e) return@launch
            val merged = (pts.mapNotNull { d -> isoToMillis(d.t)?.let { TrailPoint(it, d.lat, d.lng, d.accuracyM) } } + e.raw)
                .filter { it.timeMs > e.clearedBeforeMs }
                .distinctBy { it.timeMs }
            e.trail = LiveTrail(e.cfg).also { it.addAll(merged) }
            bump()
        }
    }

    @Synchronized
    fun ingestMine(s: LocationSample) {
        if (s.isMock) return
        if (s.timeMs > mine.clearedBeforeMs && mine.trail.add(TrailPoint(s.timeMs, s.lat, s.lng, s.accuracyM))) bump()
    }

    @Synchronized
    fun segments(userId: String): List<List<LatLng>> = entries[userId]?.trail?.segments().orEmpty()

    @Synchronized
    fun mySegments(): List<List<LatLng>> = mine.trail.segments()

    /** Points recorded before this instant are hidden for [userId] (null = me). */
    @Synchronized
    fun clearedBefore(userId: String?): Long = (if (userId == null) mine else entries[userId])?.clearedBeforeMs ?: Long.MIN_VALUE

    /** Starts the line again from now; history is not touched. */
    @Synchronized
    fun clear(userId: String?, nowMs: Long = System.currentTimeMillis()) {
        val e = if (userId == null) mine else entries[userId] ?: return
        e.clearedBeforeMs = nowMs
        e.trail = LiveTrail(e.cfg)
        bump()
    }

    @Synchronized
    fun reset() {
        entries.clear()
        mine = Entry("me", LiveTrail(FREE_CFG), FREE_CFG)
        bump()
    }

    private fun bump() { _version.value = _version.value + 1 }

    private companion object {
        val TRIP_CFG = LiveTrailConfig()
        /** Without a trip, keep only the last hour: enough to see where someone came from. */
        val FREE_CFG = LiveTrailConfig(maxAgeMs = 60 * 60_000L, maxPoints = 3_600)
        const val MAX_RAW = 4_000
    }
}
