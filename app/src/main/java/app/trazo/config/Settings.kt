package app.trazo.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.trazo.BuildConfig
import app.trazo.core.trip.Sensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "trazo_settings")

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Map looks. All use OpenFreeMap (OpenStreetMap data, no API key, no usage fees; attribution
 * shown on the map). TRAZO is Trazo's own dark style, bundled in the app (assets/styles).
 */
enum class MapStyle(val label: String, val url: String, val dark: Boolean) {
    TRAZO("Trazo Noche", "asset://styles/trazo-night.json", true),
    LIBERTY("Detallado", "https://tiles.openfreemap.org/styles/liberty", false),
    BRIGHT("Brillante", "https://tiles.openfreemap.org/styles/bright", false),
    POSITRON("Claro", "https://tiles.openfreemap.org/styles/positron", false),
    DARK("Noche", "https://tiles.openfreemap.org/styles/dark", true),
    FIORD("Fiordo", "https://tiles.openfreemap.org/styles/fiord", true),
}

/** Device-local preferences. Privacy settings that friends depend on live on the server instead. */
data class AppSettings(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val onboardingDone: Boolean,
    val themeMode: ThemeMode,
    val mapStyleDay: MapStyle,
    val mapStyleNight: MapStyle,
    val autoNightMap: Boolean,
    val autoDetectTrips: Boolean,
    val sensitivity: Sensitivity,
    /** Keep a low-power background watcher running (for phones where activity recognition is unreliable). */
    val continuousMonitoring: Boolean,
    val shareLive: Boolean,
    /** Seconds between live position updates while driving (1–30). */
    val liveIntervalSec: Int,
    val notifyFriendDriving: Boolean,
    val notifyRequests: Boolean,
    val notifyTripSummary: Boolean,
    val snapToRoads: Boolean,
    val mapMatchingUrl: String,
    val defaultVehicleId: String?,
    /** Last place the map was looking at, to reopen there when no fix is available yet. */
    val lastCamera: SavedCamera?,
    /** People whose live trail I have hidden on my map. */
    val hiddenTrails: Set<String>,
) {
    val backendConfigured: Boolean get() = supabaseUrl.startsWith("https://") && supabaseAnonKey.isNotBlank()
}

data class SavedCamera(val lat: Double, val lng: Double, val zoom: Double)

class SettingsRepository(private val context: Context) {
    private object K {
        val url = stringPreferencesKey("supabase_url")
        val key = stringPreferencesKey("supabase_key")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme")
        // v2: the redesign makes Trazo's dark style the default, also for people who had picked a style before.
        val styleDay = stringPreferencesKey("map_style_day_v2")
        val styleNight = stringPreferencesKey("map_style_night_v2")
        val autoNight = booleanPreferencesKey("auto_night_map_v2")
        val camera = stringPreferencesKey("last_camera")
        val hiddenTrails = androidx.datastore.preferences.core.stringSetPreferencesKey("hidden_trails")
        val autoDetect = booleanPreferencesKey("auto_detect")
        val sensitivity = stringPreferencesKey("sensitivity")
        val continuous = booleanPreferencesKey("continuous")
        val shareLive = booleanPreferencesKey("share_live")
        val liveInterval = intPreferencesKey("live_interval")
        val nDriving = booleanPreferencesKey("notify_driving")
        val nRequests = booleanPreferencesKey("notify_requests")
        val nSummary = booleanPreferencesKey("notify_summary")
        val snap = booleanPreferencesKey("snap_roads")
        val matchUrl = stringPreferencesKey("match_url")
        val vehicle = stringPreferencesKey("default_vehicle")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            supabaseUrl = p[K.url] ?: BuildConfig.SUPABASE_URL,
            supabaseAnonKey = p[K.key] ?: BuildConfig.SUPABASE_ANON_KEY,
            onboardingDone = p[K.onboarding] ?: false,
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            mapStyleDay = p[K.styleDay]?.let { runCatching { MapStyle.valueOf(it) }.getOrNull() } ?: MapStyle.TRAZO,
            mapStyleNight = p[K.styleNight]?.let { runCatching { MapStyle.valueOf(it) }.getOrNull() } ?: MapStyle.TRAZO,
            autoNightMap = p[K.autoNight] ?: false,
            autoDetectTrips = p[K.autoDetect] ?: true,
            sensitivity = p[K.sensitivity]?.let { runCatching { Sensitivity.valueOf(it) }.getOrNull() } ?: Sensitivity.NORMAL,
            continuousMonitoring = p[K.continuous] ?: false,
            shareLive = p[K.shareLive] ?: true,
            liveIntervalSec = (p[K.liveInterval] ?: 2).coerceIn(1, 30),
            notifyFriendDriving = p[K.nDriving] ?: true,
            notifyRequests = p[K.nRequests] ?: true,
            notifyTripSummary = p[K.nSummary] ?: true,
            snapToRoads = p[K.snap] ?: false,
            mapMatchingUrl = p[K.matchUrl] ?: BuildConfig.MAP_MATCHING_URL,
            defaultVehicleId = p[K.vehicle],
            lastCamera = p[K.camera]?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }
                ?.let { (a, b, z) -> SavedCamera(a, b, z) },
            hiddenTrails = p[K.hiddenTrails] ?: emptySet(),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setBackend(url: String, key: String) = context.dataStore.edit {
        it[K.url] = url.trim().trimEnd('/')
        it[K.key] = key.trim()
    }
    suspend fun setOnboardingDone(v: Boolean = true) = context.dataStore.edit { it[K.onboarding] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setMapStyles(day: MapStyle, night: MapStyle, autoNight: Boolean) = context.dataStore.edit {
        it[K.styleDay] = day.name; it[K.styleNight] = night.name; it[K.autoNight] = autoNight
    }
    suspend fun setAutoDetect(v: Boolean) = context.dataStore.edit { it[K.autoDetect] = v }
    suspend fun setSensitivity(v: Sensitivity) = context.dataStore.edit { it[K.sensitivity] = v.name }
    suspend fun setContinuous(v: Boolean) = context.dataStore.edit { it[K.continuous] = v }
    suspend fun setShareLive(v: Boolean) = context.dataStore.edit { it[K.shareLive] = v }
    suspend fun setLiveInterval(v: Int) = context.dataStore.edit { it[K.liveInterval] = v.coerceIn(1, 30) }
    suspend fun setNotifications(driving: Boolean, requests: Boolean, summary: Boolean) = context.dataStore.edit {
        it[K.nDriving] = driving; it[K.nRequests] = requests; it[K.nSummary] = summary
    }
    suspend fun setSnapToRoads(v: Boolean) = context.dataStore.edit { it[K.snap] = v }
    suspend fun setMapMatchingUrl(v: String) = context.dataStore.edit { it[K.matchUrl] = v.trim().trimEnd('/') }
    suspend fun setLastCamera(c: SavedCamera) = context.dataStore.edit { it[K.camera] = "${c.lat},${c.lng},${c.zoom}" }
    suspend fun setTrailHidden(userId: String, hidden: Boolean) = context.dataStore.edit {
        val cur = it[K.hiddenTrails] ?: emptySet()
        it[K.hiddenTrails] = if (hidden) cur + userId else cur - userId
    }
    suspend fun setDefaultVehicle(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(K.vehicle) else it[K.vehicle] = id
    }
}
