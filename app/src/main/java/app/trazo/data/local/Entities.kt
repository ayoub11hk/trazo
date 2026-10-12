package app.trazo.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.trazo.core.location.LocationSample
import app.trazo.core.location.PointFlag
import app.trazo.core.stats.TripFacts
import app.trazo.core.trip.TrackPoint

object TripStatus {
    const val RECORDING = "recording"
    const val COMPLETED = "completed"
}

@Entity(tableName = "trips", indices = [Index("startedAt"), Index("vehicleId")])
data class TripEntity(
    @PrimaryKey val id: String,
    val ownerId: String?,
    val vehicleId: String?,
    val convoyId: String?,
    val status: String,
    val visibility: String = "friends",
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceM: Double = 0.0,
    val measuredDistanceM: Double = 0.0,
    val gapDistanceM: Double = 0.0,
    val durationMs: Long = 0,
    val movingMs: Long = 0,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val pointCount: Int = 0,
    val quality: Int? = null,
    val incidentsJson: String = "[]",
    val simulated: Boolean = false,
    val manual: Boolean = false,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    val previewPolyline: String? = null,
    val title: String? = null,
    val endReason: String? = null,
    /** Summary changed locally and must be pushed. */
    val dirty: Boolean = true,
    /** Deleted locally; the row is kept until the server deletion succeeds. */
    val deleted: Boolean = false,
    /** Points were edited (merge/split); the server copy must be replaced, not appended to. */
    val resetRemotePoints: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun facts() = TripFacts(id, startedAt, endedAt ?: startedAt, distanceM, movingMs, durationMs, maxSpeedMps, vehicleId)
}

@Entity(
    tableName = "points",
    primaryKeys = ["tripId", "seq"],
    indices = [Index(value = ["tripId", "uploaded"])],
)
data class PointEntity(
    val tripId: String,
    val seq: Int,
    val timeMs: Long,
    val elapsedMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val bearingDeg: Float?,
    val bearingAccuracyDeg: Float?,
    val altitudeM: Double?,
    val flag: Int,
    val segmentStart: Boolean,
    val mock: Boolean,
    val uploaded: Boolean = false,
) {
    fun toTrackPoint() = TrackPoint(
        seq,
        LocationSample(timeMs, elapsedMs, lat, lng, accuracyM, speedMps, speedAccuracyMps, bearingDeg, bearingAccuracyDeg, altitudeM, null, mock),
        PointFlag.fromCode(flag),
        segmentStart,
    )

    companion object {
        fun from(tripId: String, p: TrackPoint) = PointEntity(
            tripId, p.seq, p.sample.timeMs, p.sample.elapsedRealtimeMs, p.sample.lat, p.sample.lng,
            p.sample.accuracyM, p.sample.speedMps, p.sample.speedAccuracyMps, p.sample.bearingDeg,
            p.sample.bearingAccuracyDeg, p.sample.altitudeM, p.flag.code, p.segmentStart, p.sample.isMock,
        )
    }
}

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val id: String,
    val ownerId: String?,
    val name: String,
    val make: String?,
    val model: String?,
    val year: Int?,
    val color: String,
    val bodyType: String,
    val photoUrl: String? = null,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Key/value store for small internal state (recorder snapshot, sync cursors). */
@Entity(tableName = "kv")
data class KeyValueEntity(@PrimaryKey val key: String, val value: String)

/** Last known data about friends so the map and lists work offline (always shown with its age). */
@Entity(tableName = "friend_cache")
data class FriendCacheEntity(@PrimaryKey val userId: String, val json: String, val updatedAt: Long)
