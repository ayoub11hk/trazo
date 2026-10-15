package app.trazo.data.repo

import app.trazo.data.remote.Backend
import app.trazo.data.remote.ProfileDto
import app.trazo.data.remote.SettingsDto
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** Own profile and server-side privacy settings. */
class ProfileRepository(private val backend: Backend) {
    private val _profile = MutableStateFlow<ProfileDto?>(null)
    val profile: StateFlow<ProfileDto?> = _profile
    private val _settings = MutableStateFlow<SettingsDto?>(null)
    val settings: StateFlow<SettingsDto?> = _settings

    suspend fun refresh() {
        val c = backend.client()
        val uid = backend.requireUserId()
        _profile.value = c.from("profiles").select { filter { eq("id", uid) } }.decodeSingle<ProfileDto>()
        _settings.value = c.from("user_settings").select { filter { eq("user_id", uid) } }.decodeSingle<SettingsDto>()
    }

    fun clear() {
        _profile.value = null
        _settings.value = null
    }

    suspend fun updateDisplayName(name: String) {
        val uid = backend.requireUserId()
        backend.client().from("profiles").update(buildJsonObject { put("display_name", name.trim().take(40)) }) {
            filter { eq("id", uid) }
        }
        refresh()
    }

    /** User name (lower-case, unique) and short bio. Empty values clear them. */
    suspend fun updateIdentity(username: String?, bio: String?) {
        val uid = backend.requireUserId()
        backend.client().from("profiles").update(buildJsonObject {
            if (username != null) put("username", username.trim().removePrefix("@").lowercase().ifEmpty { null })
            if (bio != null) put("bio", bio.trim().take(140).ifEmpty { null })
        }) { filter { eq("id", uid) } }
        refresh()
    }

    suspend fun setActiveVehicle(vehicleId: String?) {
        val uid = backend.requireUserId()
        backend.client().from("profiles").update(buildJsonObject { put("active_vehicle_id", vehicleId) }) {
            filter { eq("id", uid) }
        }
        _profile.value = _profile.value?.copy(activeVehicleId = vehicleId)
    }

    /** Uploads a (pre-compressed) JPEG to avatars/<uid>/<random>.jpg and points the profile at it. */
    suspend fun uploadAvatar(jpeg: ByteArray) {
        val c = backend.client()
        val uid = backend.requireUserId()
        val bucket = c.storage.from("avatars")
        val old = _profile.value?.avatarUrl
        val path = "$uid/${UUID.randomUUID()}.jpg"
        bucket.upload(path, jpeg) { upsert = true }
        val url = bucket.publicUrl(path)
        c.from("profiles").update(buildJsonObject { put("avatar_url", url) }) { filter { eq("id", uid) } }
        old?.substringAfter("/avatars/", "")?.takeIf { it.startsWith(uid) }?.let { runCatching { bucket.delete(listOf(it)) } }
        refresh()
    }

    suspend fun removeAvatarFiles() {
        val c = backend.client()
        val uid = backend.requireUserId()
        val bucket = c.storage.from("avatars")
        runCatching {
            val files = bucket.list(uid).map { "$uid/${it.name}" }
            if (files.isNotEmpty()) bucket.delete(files)
        }
    }

    suspend fun updateSettings(
        ghostMode: Boolean? = null,
        shareSpeed: Boolean? = null,
        defaultTripVisibility: String? = null,
        statsVisible: Boolean? = null,
        retentionDays: Int? = null,
        clearRetention: Boolean = false,
    ) {
        val uid = backend.requireUserId()
        val body = buildJsonObject {
            ghostMode?.let { put("ghost_mode", it) }
            shareSpeed?.let { put("share_speed", it) }
            defaultTripVisibility?.let { put("default_trip_visibility", it) }
            statsVisible?.let { put("stats_visible", it) }
            if (clearRetention) put("retention_days", null as String?) else retentionDays?.let { put("retention_days", it) }
        }
        backend.client().from("user_settings").update(body) { filter { eq("user_id", uid) } }
        refresh()
    }

    suspend fun regenerateInviteCode(): String {
        val code = backend.client().postgrest.rpc("regenerate_invite_code").decodeAs<String>()
        refresh()
        return code
    }

    suspend fun exportServerData(): JsonObject = backend.client().postgrest.rpc("export_my_data").decodeAs<JsonObject>()

    suspend fun deleteServerTrips(beforeMs: Long?): Int {
        val params = buildJsonObject {
            put("p_before", beforeMs?.let { JsonPrimitive(app.trazo.data.remote.millisToIso(it)) } ?: JsonPrimitive(null as String?))
        }
        return backend.client().postgrest.rpc("delete_my_trips", params).decodeAs<JsonPrimitive>().jsonPrimitive.content.toIntOrNull() ?: 0
    }

    suspend fun deleteAccount() {
        removeAvatarFiles()
        backend.client().postgrest.rpc("delete_my_account")
    }
}
