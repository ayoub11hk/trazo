package app.trazo.data.repo

import app.trazo.core.geo.Polyline
import app.trazo.core.geo.Simplify
import app.trazo.core.trip.RouteGeometry
import app.trazo.core.trip.TrackPoint
import app.trazo.core.trip.TripAnalyzer
import app.trazo.core.trip.TripEditor
import app.trazo.data.local.PointDao
import app.trazo.data.local.PointEntity
import app.trazo.data.local.TripDao
import app.trazo.data.local.TripEntity
import app.trazo.data.local.TripStatus
import app.trazo.data.remote.Backend
import app.trazo.data.remote.TripDto
import app.trazo.data.remote.TripPointDto
import app.trazo.data.remote.isoToMillis
import app.trazo.data.remote.millisToIso
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class TripRepository(
    private val trips: TripDao,
    private val points: PointDao,
    private val backend: Backend,
    private val json: Json,
) {
    fun observeTrips(): Flow<List<TripEntity>> = trips.observeCompleted()
    fun observeTrip(id: String): Flow<TripEntity?> = trips.observe(id)
    fun observeRecording(): Flow<TripEntity?> = trips.observeRecording()
    fun observePoints(tripId: String): Flow<List<PointEntity>> = points.observeForTrip(tripId)
    fun observePendingUploads(): Flow<Int> = points.observePendingCount()
    suspend fun completed(): List<TripEntity> = trips.completed()
    suspend fun get(id: String) = trips.get(id)
    suspend fun recording() = trips.recording()

    // ------------------------------------------------------------------ recording

    suspend fun startTrip(startMs: Long, vehicleId: String?, convoyId: String?, manual: Boolean, visibility: String): String {
        val id = UUID.randomUUID().toString()
        trips.upsert(
            TripEntity(
                id = id, ownerId = backend.userIdOrNull(), vehicleId = vehicleId, convoyId = convoyId,
                status = TripStatus.RECORDING, visibility = visibility, startedAt = startMs, manual = manual,
            ),
        )
        return id
    }

    suspend fun addPoint(tripId: String, p: TrackPoint) = points.insert(PointEntity.from(tripId, p))

    /** Closes a trip: trims idle tail, computes the summary from the stored fixes. */
    suspend fun finishTrip(tripId: String, lastMovingSeq: Int?, reason: String) {
        if (lastMovingSeq != null) points.deleteAfter(tripId, lastMovingSeq)
        val t = trips.get(tripId) ?: return
        val pts = points.forTrip(tripId).map { it.toTrackPoint() }
        trips.upsert(summarize(t, pts).copy(status = TripStatus.COMPLETED, endReason = reason))
    }

    suspend fun discardTrip(tripId: String) {
        points.deleteForTrip(tripId)
        trips.purge(tripId)
    }

    /** Recomputes the live summary of the trip being recorded (cheap enough every ~30 s). */
    suspend fun refreshRecordingSummary(tripId: String) {
        val t = trips.get(tripId) ?: return
        trips.upsert(summarize(t, points.forTrip(tripId).map { it.toTrackPoint() }).copy(status = TripStatus.RECORDING))
    }

    private fun summarize(t: TripEntity, pts: List<TrackPoint>): TripEntity {
        val s = TripAnalyzer.analyze(pts) ?: return t.copy(updatedAt = System.currentTimeMillis(), dirty = true)
        val geo = RouteGeometry.build(pts)
        val preview = Simplify.douglasPeucker(geo.allMeasured, 15.0)
        return t.copy(
            startedAt = s.startTimeMs, endedAt = s.endTimeMs, distanceM = s.distanceM,
            measuredDistanceM = s.measuredDistanceM, gapDistanceM = s.gapDistanceM, durationMs = s.durationMs,
            movingMs = s.movingMs, avgSpeedMps = s.avgSpeedMps, maxSpeedMps = s.maxSpeedMps,
            pointCount = s.pointCount, quality = s.qualityScore,
            incidentsJson = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(app.trazo.core.trip.TrackIncident.serializer()), s.incidents),
            simulated = s.simulated, startLat = geo.start?.lat, startLng = geo.start?.lng,
            endLat = geo.end?.lat, endLng = geo.end?.lng,
            previewPolyline = if (preview.size >= 2) Polyline.encode(preview) else null,
            updatedAt = System.currentTimeMillis(), dirty = true,
        )
    }

    // ------------------------------------------------------------------ reading

    /** Own trip points from Room; downloads them first if this phone has never seen them. */
    suspend fun pointsFor(tripId: String): List<TrackPoint> {
        val local = points.forTrip(tripId)
        if (local.isNotEmpty()) return local.map { it.toTrackPoint() }
        val remote = downloadPoints(tripId, own = true)
        points.insertAll(remote.map { it.copy(uploaded = true) })
        return remote.map { it.toTrackPoint() }
    }

    private suspend fun downloadPoints(tripId: String, own: Boolean): List<PointEntity> {
        val c = backend.client()
        val out = ArrayList<PointEntity>()
        if (own) {
            var from = 0L
            while (true) {
                val page = c.from("trip_points").select {
                    filter { eq("trip_id", tripId) }
                    order("seq", Order.ASCENDING)
                    range(from, from + PAGE - 1)
                }.decodeList<TripPointDto>()
                out += page.map { it.toEntity() }
                if (page.size < PAGE) break
                from += PAGE
            }
        } else {
            out += c.postgrest.rpc("trip_points_for", buildJsonObject { put("p_trip", tripId) })
                .decodeList<TripPointDto>().map { it.toEntity() }
        }
        return out
    }

    /** A friend's shared trips (server checks permissions and hides speed if they chose so). */
    suspend fun friendTrips(ownerId: String, beforeMs: Long? = null): List<TripEntity> =
        backend.client().postgrest.rpc("visible_trips", buildJsonObject {
            put("p_owner", ownerId)
            put("p_before", beforeMs?.let { millisToIso(it) })
            put("p_limit", 50)
        }).decodeList<TripDto>().map { it.toEntity(dirty = false) }

    suspend fun friendTripPoints(tripId: String): List<TrackPoint> = downloadPoints(tripId, own = false).map { it.toTrackPoint() }

    suspend fun convoyTrips(convoyId: String): List<TripEntity> =
        backend.client().postgrest.rpc("convoy_trips", buildJsonObject { put("p_convoy", convoyId) })
            .decodeList<TripDto>().map { it.toEntity(dirty = false) }

    // ------------------------------------------------------------------ editing

    suspend fun rename(id: String, title: String?) = edit(id) { it.copy(title = title?.trim()?.take(80)?.ifEmpty { null }) }
    suspend fun setVehicle(id: String, vehicleId: String?) = edit(id) { it.copy(vehicleId = vehicleId) }
    suspend fun setVisibility(id: String, visibility: String) = edit(id) { it.copy(visibility = visibility) }

    private suspend fun edit(id: String, f: (TripEntity) -> TripEntity) {
        val t = trips.get(id) ?: return
        trips.upsert(f(t).copy(dirty = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = trips.markDeleted(id)

    suspend fun deleteAllBefore(beforeMs: Long): Int = trips.markDeletedBefore(beforeMs)

    /** Merges [otherId] into [id]; the other trip is deleted. */
    suspend fun merge(id: String, otherId: String) {
        val a = trips.get(id) ?: return
        val b = trips.get(otherId) ?: return
        val merged = TripEditor.merge(pointsFor(id), pointsFor(otherId))
        points.replaceTrip(id, merged.map { PointEntity.from(id, it) })
        trips.upsert(summarize(a.copy(vehicleId = a.vehicleId ?: b.vehicleId), merged).copy(resetRemotePoints = true))
        delete(otherId)
    }

    /** Splits a trip at [timeMs]; returns the id of the new second part. */
    suspend fun split(id: String, timeMs: Long): String? {
        val t = trips.get(id) ?: return null
        val (left, right) = TripEditor.split(pointsFor(id), timeMs) ?: return null
        val newId = UUID.randomUUID().toString()
        points.replaceTrip(id, left.map { PointEntity.from(id, it) })
        points.replaceTrip(newId, right.map { PointEntity.from(newId, it) })
        trips.upsert(summarize(t, left).copy(resetRemotePoints = true))
        trips.upsert(summarize(t.copy(id = newId, title = null, endReason = "split"), right))
        return newId
    }

    // ------------------------------------------------------------------ sync

    /**
     * Pushes local changes. Idempotent: trips are upserted by their phone-generated id and
     * points by (trip_id, seq), so retries after a dropped connection never duplicate data.
     */
    suspend fun sync(): Int {
        val c = backend.client()
        val me = backend.requireUserId()
        var uploaded = 0
        for (t in trips.tombstones()) {
            c.from("trips").delete { filter { eq("id", t.id) } }
            points.deleteForTrip(t.id)
            trips.purge(t.id)
        }
        for (t in trips.dirty()) {
            if (t.ownerId != null && t.ownerId != me) continue
            c.from("trips").upsert(t.copy(ownerId = me).toDto(json)) { onConflict = "id" }
            if (t.resetRemotePoints) {
                c.from("trip_points").delete { filter { eq("trip_id", t.id) } }
                points.markAllPending(t.id)
                trips.upsert(t.copy(ownerId = me, resetRemotePoints = false))
            } else if (t.ownerId == null) {
                trips.upsert(t.copy(ownerId = me))
            }
            trips.markClean(t.id, t.updatedAt)
        }
        for (tripId in points.tripsWithPending()) {
            val t = trips.get(tripId) ?: continue
            if (t.deleted) continue
            while (true) {
                val batch = points.pendingUpload(tripId, BATCH)
                if (batch.isEmpty()) break
                c.from("trip_points").upsert(batch.map { it.toDto() }) {
                    onConflict = "trip_id,seq"
                    ignoreDuplicates = true
                }
                points.markUploaded(tripId, batch.map { it.seq })
                uploaded += batch.size
            }
        }
        return uploaded
    }

    /** First sign-in on a new phone: bring down the trip list (points are fetched lazily). */
    suspend fun pullOwnTrips() {
        val c = backend.client()
        val me = backend.requireUserId()
        var from = 0L
        while (true) {
            val page = c.from("trips").select {
                filter { eq("owner_id", me); eq("status", "completed") }
                order("started_at", Order.DESCENDING)
                range(from, from + PAGE - 1)
            }.decodeList<TripDto>()
            for (d in page) {
                val local = trips.get(d.id)
                if (local == null) trips.upsert(d.toEntity(dirty = false))
            }
            if (page.size < PAGE) break
            from += PAGE
        }
    }

    suspend fun clearLocal() {
        trips.purgeAll()
    }

    private fun TripEntity.toDto(json: Json) = TripDto(
        id = id, ownerId = ownerId!!, vehicleId = vehicleId, convoyId = convoyId, status = status,
        visibility = visibility, startedAt = millisToIso(startedAt), endedAt = endedAt?.let(::millisToIso),
        distanceM = distanceM, measuredDistanceM = measuredDistanceM, gapDistanceM = gapDistanceM,
        durationMs = durationMs, movingMs = movingMs, avgSpeedMps = avgSpeedMps, maxSpeedMps = maxSpeedMps,
        pointCount = pointCount, quality = quality,
        incidents = runCatching { json.parseToJsonElement(incidentsJson) }.getOrNull(),
        simulated = simulated, manual = manual, startLat = startLat, startLng = startLng, endLat = endLat, endLng = endLng,
        previewPolyline = previewPolyline, title = title,
    )

    private fun TripDto.toEntity(dirty: Boolean) = TripEntity(
        id = id, ownerId = ownerId, vehicleId = vehicleId, convoyId = convoyId, status = status, visibility = visibility,
        startedAt = isoToMillis(startedAt) ?: 0, endedAt = isoToMillis(endedAt), distanceM = distanceM,
        measuredDistanceM = measuredDistanceM, gapDistanceM = gapDistanceM, durationMs = durationMs, movingMs = movingMs,
        avgSpeedMps = avgSpeedMps, maxSpeedMps = maxSpeedMps, pointCount = pointCount, quality = quality,
        incidentsJson = incidents?.toString() ?: "[]", simulated = simulated, manual = manual,
        startLat = startLat, startLng = startLng, endLat = endLat, endLng = endLng,
        previewPolyline = previewPolyline, title = title, dirty = dirty,
    )

    private fun PointEntity.toDto() = TripPointDto(
        tripId, seq, millisToIso(timeMs), elapsedMs, lat, lng, accuracyM, speedMps, speedAccuracyMps,
        bearingDeg, bearingAccuracyDeg, altitudeM, flag, segmentStart, mock,
    )

    private fun TripPointDto.toEntity() = PointEntity(
        tripId, seq, isoToMillis(t) ?: 0, elapsedMs, lat, lng, accuracyM, speedMps, speedAccuracyMps,
        bearingDeg, bearingAccuracyDeg, altitudeM, flag, segmentStart, mock, uploaded = true,
    )

    private companion object {
        const val PAGE = 1000L
        const val BATCH = 500
    }
}
