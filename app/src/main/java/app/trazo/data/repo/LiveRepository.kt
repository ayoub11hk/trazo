package app.trazo.data.repo

import app.trazo.data.local.FriendCacheDao
import app.trazo.data.local.FriendCacheEntity
import app.trazo.data.remote.Backend
import app.trazo.data.remote.LiveLocationDto
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeOldRecord
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class LinkState { OFFLINE, CONNECTING, LIVE }

/**
 * Live positions of everyone I am allowed to see, kept current through Supabase Realtime
 * (Postgres changes filtered by row-level security on the server). A periodic re-sync
 * covers missed events after reconnects. The last state is cached for offline display.
 */
class LiveRepository(
    private val backend: Backend,
    private val cache: FriendCacheDao,
    private val scope: CoroutineScope,
    private val json: Json,
) {
    private val _positions = MutableStateFlow<Map<String, LiveLocationDto>>(emptyMap())
    val positions: StateFlow<Map<String, LiveLocationDto>> = _positions
    private val _link = MutableStateFlow(LinkState.OFFLINE)
    val link: StateFlow<LinkState> = _link
    private var job: Job? = null
    private var channel: RealtimeChannel? = null
    private var subscribers = 0

    /** Reference-counted: the map screen and the tracking service may both need the feed. */
    @Synchronized
    fun acquire() {
        subscribers++
        if (job == null) start()
    }

    @Synchronized
    fun release() {
        subscribers = (subscribers - 1).coerceAtLeast(0)
        if (subscribers == 0) stop()
    }

    private fun start() {
        job = scope.launch {
            if (_positions.value.isEmpty()) loadCache()
            while (isActive) {
                _link.value = LinkState.CONNECTING
                val ok = runCatching { connect(this) }.isSuccess
                // Full re-sync every minute (cheap, and repairs anything missed while reconnecting).
                var waited = 0
                while (isActive && ok && waited < 60) {
                    delay(1_000); waited++
                    if (channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED) _link.value = LinkState.LIVE
                }
                if (!ok) { _link.value = LinkState.OFFLINE; delay(5_000) }
                else runCatching { fullSync() }.onFailure { _link.value = LinkState.OFFLINE }
            }
        }
    }

    private suspend fun connect(cs: CoroutineScope) {
        if (channel != null) return
        val c = backend.client()
        val me = backend.requireUserId()
        fullSync()
        val ch = c.channel("live-$me")
        ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "live_locations" }
            .onEach { a ->
                when (a) {
                    is PostgresAction.Insert -> put(a.decodeRecord<LiveLocationDto>())
                    is PostgresAction.Update -> put(a.decodeRecord<LiveLocationDto>())
                    is PostgresAction.Delete -> runCatching { a.decodeOldRecord<LiveLocationDto>() }.getOrNull()
                        ?.let { old -> _positions.value = _positions.value - old.userId }
                    else -> Unit
                }
            }.launchIn(cs)
        ch.subscribe()
        channel = ch
    }

    private fun put(dto: LiveLocationDto) {
        _positions.value = _positions.value + (dto.userId to dto)
    }

    suspend fun fullSync() {
        val rows = backend.client().from("live_locations").select().decodeList<LiveLocationDto>()
        _positions.value = rows.associateBy { it.userId }
        cache.upsertAll(rows.map { FriendCacheEntity(it.userId, json.encodeToString(LiveLocationDto.serializer(), it), System.currentTimeMillis()) })
        _link.value = LinkState.LIVE
    }

    private suspend fun loadCache() {
        _positions.value = cache.all().mapNotNull {
            runCatching { json.decodeFromString(LiveLocationDto.serializer(), it.json) }.getOrNull()
        }.associateBy { it.userId }
    }

    private fun stop() {
        job?.cancel()
        job = null
        val ch = channel
        channel = null
        _link.value = LinkState.OFFLINE
        if (ch != null) scope.launch { runCatching { backend.client().realtime.removeChannel(ch) } }
    }

    /** Publishes my own position (or a paused/ghost marker). The server strips anything not shared. */
    suspend fun publish(dto: LiveLocationDto) {
        backend.client().from("live_locations").upsert(dto) { onConflict = "user_id" }
    }

    suspend fun clearCache() {
        cache.clear()
        _positions.value = emptyMap()
    }
}

