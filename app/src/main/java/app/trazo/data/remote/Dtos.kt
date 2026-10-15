package app.trazo.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

fun isoToMillis(iso: String?): Long? = iso?.let { s ->
    runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(s).toEpochMilli() }
        .getOrNull()
}

fun millisToIso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

@Serializable
data class ProfileDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("active_vehicle_id") val activeVehicleId: String? = null,
    /** Optional unique handle for search (added 15/10/2026; null on databases without the migration). */
    val username: String? = null,
    val bio: String? = null,
)

/** A result of the user-name search: never carries a location. */
@Serializable
data class FoundUser(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** friend, pending_out, pending_in or none. */
    val relation: String = "none",
)

@Serializable
data class LiveTrailPointDto(
    val t: String,
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
)

@Serializable
data class SettingsDto(
    @SerialName("user_id") val userId: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("ghost_mode") val ghostMode: Boolean = false,
    @SerialName("share_speed") val shareSpeed: Boolean = true,
    @SerialName("default_trip_visibility") val defaultTripVisibility: String = "friends",
    @SerialName("stats_visible") val statsVisible: Boolean = true,
    @SerialName("retention_days") val retentionDays: Int? = null,
)

@Serializable
data class FriendshipDto(
    @SerialName("user_id") val userId: String,
    @SerialName("friend_id") val friendId: String,
    @SerialName("share_location") val shareLocation: Boolean = true,
    @SerialName("share_trips") val shareTrips: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class FriendRequestDto(
    val id: String,
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class SendRequestResult(
    @SerialName("request_id") val requestId: String? = null,
    val status: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class ResolvedCode(
    val kind: String,
    @SerialName("target_id") val targetId: String,
    val name: String? = null,
)

@Serializable
data class CreatedWithCode(
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("convoy_id") val convoyId: String? = null,
    @SerialName("invite_code") val inviteCode: String,
)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class GroupMemberDto(
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    val role: String = "member",
    @SerialName("share_location") val shareLocation: Boolean = true,
    @SerialName("joined_at") val joinedAt: String? = null,
)

@Serializable
data class ConvoyDto(
    val id: String,
    val name: String,
    @SerialName("owner_id") val ownerId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class ConvoyMemberDto(
    @SerialName("convoy_id") val convoyId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("left_at") val leftAt: String? = null,
    @SerialName("share_trip") val shareTrip: Boolean = true,
)

@Serializable
data class ConvoyInviteDto(
    val id: String,
    @SerialName("convoy_id") val convoyId: String,
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class VehicleDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val color: String = "#C8D1DC",
    @SerialName("body_type") val bodyType: String = "sedan",
    @SerialName("photo_url") val photoUrl: String? = null,
)

@Serializable
data class TripDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("vehicle_id") val vehicleId: String? = null,
    @SerialName("convoy_id") val convoyId: String? = null,
    val status: String = "completed",
    val visibility: String = "friends",
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("distance_m") val distanceM: Double = 0.0,
    @SerialName("measured_distance_m") val measuredDistanceM: Double = 0.0,
    @SerialName("gap_distance_m") val gapDistanceM: Double = 0.0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("moving_ms") val movingMs: Long = 0,
    @SerialName("avg_speed_mps") val avgSpeedMps: Double = 0.0,
    @SerialName("max_speed_mps") val maxSpeedMps: Double = 0.0,
    @SerialName("point_count") val pointCount: Int = 0,
    val quality: Int? = null,
    val incidents: kotlinx.serialization.json.JsonElement? = null,
    val simulated: Boolean = false,
    val manual: Boolean = false,
    @SerialName("start_lat") val startLat: Double? = null,
    @SerialName("start_lng") val startLng: Double? = null,
    @SerialName("end_lat") val endLat: Double? = null,
    @SerialName("end_lng") val endLng: Double? = null,
    @SerialName("preview_polyline") val previewPolyline: String? = null,
    val title: String? = null,
)

@Serializable
data class TripPointDto(
    @SerialName("trip_id") val tripId: String,
    val seq: Int,
    val t: String,
    @SerialName("elapsed_ms") val elapsedMs: Long,
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    @SerialName("speed_mps") val speedMps: Float? = null,
    @SerialName("speed_accuracy_mps") val speedAccuracyMps: Float? = null,
    @SerialName("bearing_deg") val bearingDeg: Float? = null,
    @SerialName("bearing_accuracy_deg") val bearingAccuracyDeg: Float? = null,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    val flag: Int = 0,
    @SerialName("segment_start") val segmentStart: Boolean = false,
    val mock: Boolean = false,
)

@Serializable
data class LiveLocationDto(
    @SerialName("user_id") val userId: String,
    val sharing: Boolean = true,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    @SerialName("speed_mps") val speedMps: Float? = null,
    @SerialName("bearing_deg") val bearingDeg: Float? = null,
    val state: String = "idle",
    @SerialName("vehicle_id") val vehicleId: String? = null,
    @SerialName("trip_id") val tripId: String? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class LeaderboardRow(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val trips: Long = 0,
    @SerialName("distance_m") val distanceM: Double = 0.0,
    @SerialName("moving_ms") val movingMs: Long = 0,
)
