package app.trazo.data.remote

import android.content.Context
import app.trazo.config.SettingsRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

class BackendNotConfiguredException : IllegalStateException("Servidor no configurado")

/**
 * Owns the Supabase client. The URL and public key can be supplied at build time or entered
 * in the app, so the client is (re)created lazily whenever they change.
 */
class Backend(private val context: Context, private val settings: SettingsRepository) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val mutex = Mutex()
    private var key: Pair<String, String>? = null
    private val _client = MutableStateFlow<SupabaseClient?>(null)
    val clientFlow: StateFlow<SupabaseClient?> = _client

    suspend fun client(): SupabaseClient = mutex.withLock {
        val s = settings.current()
        if (!s.backendConfigured) throw BackendNotConfiguredException()
        val k = s.supabaseUrl to s.supabaseAnonKey
        _client.value?.takeIf { key == k }?.let { return it }
        val c = createSupabaseClient(s.supabaseUrl, s.supabaseAnonKey) {
            defaultSerializer = KotlinXSerializer(json)
            install(Auth) {
                sessionManager = SecureSessionManager(context, json)
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }
            install(Postgrest)
            install(Realtime) {
                reconnectDelay = 3.seconds
            }
            install(Storage)
        }
        key = k
        _client.value = c
        c
    }

    fun clientOrNull(): SupabaseClient? = _client.value

    suspend fun userIdOrNull(): String? = runCatching { client().auth.currentUserOrNull()?.id }.getOrNull()

    suspend fun requireUserId(): String = userIdOrNull() ?: throw IllegalStateException("Sesión no iniciada")
}
