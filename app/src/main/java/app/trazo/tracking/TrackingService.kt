package app.trazo.tracking

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.trazo.R
import app.trazo.TrazoApp
import app.trazo.config.AppSettings
import app.trazo.core.geo.GeoMath
import app.trazo.core.geo.LatLng
import app.trazo.core.location.GpsFilterConfig
import app.trazo.core.location.LocationSample
import app.trazo.core.policy.AdaptiveLocationPolicy
import app.trazo.core.policy.DeviceContext
import app.trazo.core.policy.LivePublishPolicy
import app.trazo.core.policy.LocationRequestSpec
import app.trazo.core.sim.DriveSimulator
import app.trazo.core.trip.DetectorState
import app.trazo.core.trip.EndReason
import app.trazo.core.trip.RecorderSnapshot
import app.trazo.core.trip.TrackPoint
import app.trazo.core.trip.TripDetectorConfig
import app.trazo.core.trip.TripRecorder
import app.trazo.core.trip.TripSink
import app.trazo.data.local.KeyValueEntity
import app.trazo.data.remote.LiveLocationDto
import app.trazo.data.remote.millisToIso
import app.trazo.notify.Notifications
import app.trazo.util.Format
import app.trazo.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
private data class SavedRecorder(
    val snapshot: RecorderSnapshot,
    val tripId: String?,
    val savedWallMs: Long,
    val savedElapsedMs: Long,
)

/**
 * Foreground service that owns GPS while Trazo is watching for or recording a trip.
 *
 * Every fix goes through [TripRecorder] (filtering + trip detection) and is written to Room
 * in order by a single writer coroutine, so nothing is lost when the network is down; the
 * sync worker uploads later. Live position is published to friends separately and only
 * when it changed meaningfully.
 */
class TrackingService : LifecycleService() {
    private val graph by lazy { (application as TrazoApp).graph }
    private lateinit var recorder: TripRecorder
    private lateinit var source: LocationSource
    private lateinit var gnss: GnssMonitor
    private val publishPolicy = LivePublishPolicy()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var settings: AppSettings? = null
    private var currentTripId: String? = null
    private var currentSpec: LocationRequestSpec? = null
    private var idleSinceMs = SystemClock.elapsedRealtime()
    private var pointsSinceSave = 0
    private var publishing: Job? = null
    private var simulation: Job? = null
    private var lastNotifyMs = 0L
    private var liveAcquired = false

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        graph.tracking.update { it.copy(serviceRunning = true, message = null) }
        gnss = GnssMonitor(this)
        source = LocationSource.best(this)

        // Single ordered writer for everything that touches the database.
        lifecycleScope.launch(Dispatchers.IO) { for (w in writes) runCatching { w() }.onFailure { Log.e(TAG, "write failed", it) } }

        lifecycleScope.launch {
            val s = graph.settings.current()
            settings = s
            recorder = TripRecorder(GpsFilterConfig(), TripDetectorConfig.forSensitivity(s.sensitivity), sink)
            restoreState()
            startLocation()
            gnss.start()
            launch { gnss.info.collect { g -> graph.tracking.update { it.copy(gnss = g) } } }
            launch { tickLoop() }
            launch { graph.tracking.activity.collect { (hint, at) -> recorder.onActivity(hint, at); afterEvent() } }
            launch { graph.tracking.commands.collect { handle(it) } }
            launch {
                graph.settings.settings.map { Triple(it.sensitivity, it.liveIntervalSec, it.shareLive) }.distinctUntilChanged().collect {
                    settings = graph.settings.current()
                    applySettings()
                }
            }
            launch { watchFriends() }
            graph.tracking.drainPending().forEach { handle(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_TRIP) graph.tracking.stopTrip()
        if (intent?.action == ACTION_TOGGLE_SHARING) lifecycleScope.launch {
            graph.settings.setShareLive(!(settings?.shareLive ?: true))
        }
        // Restarted by the system after being killed: recover and keep going.
        return START_STICKY
    }

    override fun onDestroy() {
        if (::recorder.isInitialized) saveState(blocking = true)
        if (::source.isInitialized) source.stop()
        if (::gnss.isInitialized) gnss.stop()
        if (liveAcquired) graph.live.release()
        writes.close()
        graph.tracking.update { it.copy(serviceRunning = false, detectorState = DetectorState.IDLE, simulation = false) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ fixes

    private fun startLocation() {
        if (!Permissions.fineLocation(this)) {
            graph.tracking.update { it.copy(message = "Falta el permiso de ubicación precisa.") }
            stopSelf()
            return
        }
        val spec = computeSpec()
        currentSpec = spec
        source.start(spec) { onFix(it) }
        graph.tracking.update { it.copy(sourceName = source.name) }
    }

    private fun onFix(s: LocationSample) {
        if (simulation?.isActive == true && !s.isMock) return
        val r = recorder.onFix(s)
        graph.tracking.update {
            it.copy(lastFix = s, lastFixReceivedMs = System.currentTimeMillis(), lastFlag = r.flag)
        }
        if (r.usable) maybePublish(s)
        if (++pointsSinceSave >= 15) saveState()
        afterEvent()
    }

    private fun afterEvent() {
        val st = recorder.state
        graph.tracking.update { it.copy(detectorState = st) }
        if (st == DetectorState.IDLE) {
            if (idleSinceMs == 0L) idleSinceMs = SystemClock.elapsedRealtime()
        } else {
            idleSinceMs = 0L
        }
        val spec = computeSpec()
        if (spec != currentSpec) {
            currentSpec = spec
            if (simulation?.isActive != true) source.update(spec)
        }
        refreshNotification()
    }

    private suspend fun tickLoop() {
        while (lifecycleScope.coroutineContext.isActive) {
            delay(5_000)
            if (simulation?.isActive == true) continue
            recorder.onTick(SystemClock.elapsedRealtime())
            afterEvent()
            maybeStopWhenIdle()
        }
    }

    private fun maybeStopWhenIdle() {
        val s = settings ?: return
        if (recorder.state != DetectorState.IDLE || currentTripId != null) return
        val keepWatching = s.continuousMonitoring || (s.autoDetectTrips && !ActivityRecognitionManager.available(this))
        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val idleFor = SystemClock.elapsedRealtime() - idleSinceMs
        if (!keepWatching && !visible && idleSinceMs != 0L && idleFor > IDLE_STOP_MS) {
            // Activity recognition will wake us at the next drive.
            publishState(force = true)
            stopSelf()
        }
    }

    private fun computeSpec(): LocationRequestSpec {
        val s = settings
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        val charging = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val state = if (::recorder.isInitialized) recorder.state else DetectorState.IDLE
        return AdaptiveLocationPolicy.requestFor(
            DeviceContext(state, visible, battery, charging, s?.shareLive ?: false, userDrivingIntervalSec = 1),
        )
    }

    private fun applySettings() {
        val s = settings ?: return
        if (::recorder.isInitialized) recorder.updateDetectorConfig(TripDetectorConfig.forSensitivity(s.sensitivity))
        publishPolicy.minIntervalMs = s.liveIntervalSec * 1000L
        publishPolicy.reset()
        graph.tracking.update { it.copy(sharingLive = s.shareLive) }
        publishState(force = true)
        afterEvent()
    }

    // ------------------------------------------------------------------ recorder output

    private val sink = object : TripSink {
        private var lastForDistance: LocationSample? = null

        override fun tripStarted(startTimeMs: Long, manual: Boolean) {
            lastForDistance = null
            val simulated = simulation?.isActive == true
            writes.trySend {
                val s = graph.settings.current()
                val vehicle = s.defaultVehicleId?.takeIf { graph.vehicles.get(it)?.deleted == false }
                    ?: graph.profile.profile.value?.activeVehicleId
                val visibility = graph.profile.settings.value?.defaultTripVisibility ?: "friends"
                val id = graph.trips.startTrip(startTimeMs, vehicle, graph.convoys.activeConvoyId, manual, visibility)
                currentTripId = id
                graph.tracking.update { it.copy(recordingTripId = id, tripStartMs = startTimeMs, tripDistanceM = 0.0, simulation = simulated) }
                saveStateNow()
            }
        }

        override fun point(p: TrackPoint) {
            writes.trySend {
                val id = currentTripId ?: return@trySend
                graph.trips.addPoint(id, p)
                if (p.flag.code == 0) {
                    val prev = lastForDistance
                    lastForDistance = p.sample
                    // Rough running figure for the notification; the saved summary is recomputed properly.
                    if (prev != null && !p.segmentStart) graph.tracking.update { st ->
                        st.copy(tripDistanceM = st.tripDistanceM + GeoMath.distanceM(prev.lat, prev.lng, p.sample.lat, p.sample.lng))
                    }
                }
                if (p.seq % 30 == 0) graph.trips.refreshRecordingSummary(id)
                if (p.seq % 60 == 0) graph.sync.requestSync() // keeps friends' live-trail backlog about a minute behind
            }
        }

        override fun tripEnded(lastMovingSeq: Int?, reason: EndReason) {
            writes.trySend {
                val id = currentTripId ?: return@trySend
                graph.trips.finishTrip(id, lastMovingSeq, reason.name.lowercase())
                currentTripId = null
                graph.tracking.update { it.copy(recordingTripId = null, tripStartMs = null, tripDistanceM = 0.0) }
                saveStateNow()
                graph.sync.requestSync()
                val t = graph.trips.get(id)
                if (t != null && graph.settings.current().notifyTripSummary) {
                    Notifications.post(
                        this@TrackingService, Notifications.CH_TRIPS, "Viaje guardado",
                        "${Format.distance(t.distanceM)} · ${Format.duration(t.durationMs)} · media ${Format.speed(t.avgSpeedMps)}",
                        route = "trip/$id",
                    )
                }
            }
        }

        override fun tripDiscarded() {
            writes.trySend {
                currentTripId?.let { graph.trips.discardTrip(it) }
                currentTripId = null
                graph.tracking.update { it.copy(recordingTripId = null, tripStartMs = null, tripDistanceM = 0.0) }
                saveStateNow()
            }
        }
    }

    // ------------------------------------------------------------------ commands

    private fun handle(cmd: TrackingCommand) {
        when (cmd) {
            TrackingCommand.StartTrip -> {
                if (!recorder.detector.isInTrip) recorder.startManual(System.currentTimeMillis())
                afterEvent()
            }
            TrackingCommand.StopTrip -> {
                recorder.stopManual(SystemClock.elapsedRealtime())
                afterEvent()
            }
            is TrackingCommand.Simulate -> startSimulation(cmd.speedFactor)
            TrackingCommand.Reconfigure -> lifecycleScope.launch { settings = graph.settings.current(); applySettings() }
            TrackingCommand.Shutdown -> {
                if (recorder.detector.isInTrip) recorder.stopManual(SystemClock.elapsedRealtime())
                lifecycleScope.launch { delay(500); publishState(force = true); stopSelf() }
            }
        }
    }

    /**
     * Drives a synthetic route through the real pipeline (filter, detection, storage, sync).
     * Every point is marked as simulated and the trip is labelled as such everywhere.
     */
    private fun startSimulation(speedFactor: Int) {
        if (simulation?.isActive == true) return
        source.stop()
        simulation = lifecycleScope.launch {
            graph.tracking.update { it.copy(simulation = true) }
            val start = graph.tracking.status.value.lastFix?.latLng ?: LatLng(40.4168, -3.7038)
            val a = DriveSimulator.cityL(start, 700.0)
            val b = DriveSimulator.straight(a.last(), 2500.0, 60.0)
            val c = DriveSimulator.straight(b.last(), 1500.0, 150.0)
            val samples = DriveSimulator(seed = (System.currentTimeMillis() % 10_000).toInt()).simulate(
                listOf(
                    DriveSimulator.Leg(a, 11.0, stopAfterMs = 45_000),
                    DriveSimulator.Leg(b, 22.0),
                    DriveSimulator.Leg(c, 13.0, stopAfterMs = 6 * 60_000L),
                ),
                DriveSimulator.Options(
                    startTimeMs = System.currentTimeMillis(),
                    startElapsedMs = SystemClock.elapsedRealtime(),
                    gaps = listOf(150_000L..175_000L),
                ),
            )
            recorder.resetFilter()
            val step = (1000L / speedFactor.coerceIn(1, 60)).coerceAtLeast(16)
            for (s in samples) {
                onFix(s)
                recorder.onTick(s.elapsedRealtimeMs)
                delay(step)
            }
            var t = samples.last().elapsedRealtimeMs
            while (recorder.detector.isInTrip) { t += 5_000; recorder.onTick(t); afterEvent(); delay(step) }
            recorder.resetFilter()
            graph.tracking.update { it.copy(simulation = false) }
            startLocation()
        }
    }

    // ------------------------------------------------------------------ live sharing

    private fun maybePublish(s: LocationSample) {
        val set = settings ?: return
        if (!set.shareLive) return
        if (s.isMock && simulation?.isActive != true) return
        if (!publishPolicy.shouldPublish(s, SystemClock.elapsedRealtime())) return
        publish(s)
    }

    private fun publishState(force: Boolean) {
        val last = graph.tracking.status.value.lastFix
        if (force || last != null) publish(last)
    }

    private fun publish(s: LocationSample?) {
        if (publishing?.isActive == true) return // never queue stale positions behind a slow request
        val set = settings ?: return
        publishing = lifecycleScope.launch(Dispatchers.IO) {
            val uid = graph.backend.userIdOrNull() ?: return@launch
            val state = when (recorder.state) {
                DetectorState.DRIVING -> "driving"
                DetectorState.STOPPED -> "stopped"
                else -> "idle"
            }
            val dto = LiveLocationDto(
                userId = uid,
                sharing = set.shareLive && s != null,
                lat = s?.lat, lng = s?.lng, accuracyM = s?.accuracyM,
                speedMps = s?.reliableSpeedMps(),
                bearingDeg = s?.reliableBearingDeg(),
                state = state,
                vehicleId = graph.profile.profile.value?.activeVehicleId ?: set.defaultVehicleId,
                tripId = currentTripId,
                batteryPct = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
                recordedAt = s?.let { millisToIso(it.timeMs) },
            )
            val ok = runCatching { graph.live.publish(dto) }.isSuccess
            graph.tracking.update { it.copy(lastPublishOk = ok) }
        }
    }

    /** Optional "a friend started driving" notifications while the service runs. */
    private suspend fun watchFriends() {
        val s = graph.settings.current()
        if (!s.notifyFriendDriving || graph.backend.userIdOrNull() == null) return
        graph.live.acquire()
        liveAcquired = true
        val lastState = HashMap<String, String>()
        graph.live.positions.collect { map ->
            val me = graph.backend.userIdOrNull()
            for ((uid, dto) in map) {
                if (uid == me) continue
                val prev = lastState.put(uid, dto.state)
                if (prev != null && prev != "driving" && dto.state == "driving" && dto.sharing) {
                    val name = graph.social.state.value.people[uid]?.displayName ?: "Un amigo"
                    Notifications.post(this, Notifications.CH_SOCIAL, "$name ha empezado a conducir", "Toca para verlo en el mapa.", "map/$uid")
                }
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    private suspend fun restoreState() = withContext(Dispatchers.IO) {
        val raw = graph.db.kv().get(KEY_STATE)
        val recording = graph.trips.recording()
        val saved = raw?.let { runCatching { graph.json.decodeFromString(SavedRecorder.serializer(), it) }.getOrNull() }
        if (recording == null) return@withContext
        val nowWall = System.currentTimeMillis()
        if (saved == null || saved.tripId != recording.id || nowWall - saved.savedWallMs > INTERRUPTED_MS) {
            // We were away too long to continue honestly: close the trip at its last fix.
            graph.trips.finishTrip(recording.id, null, "interrupted")
            graph.sync.requestSync()
            return@withContext
        }
        val rebooted = SystemClock.elapsedRealtime() < saved.savedElapsedMs
        recorder.restore(saved.snapshot, rebooted)
        currentTripId = recording.id
        graph.tracking.update {
            it.copy(recordingTripId = recording.id, tripStartMs = recording.startedAt, tripDistanceM = recording.distanceM)
        }
    }

    private fun saveState(blocking: Boolean = false) {
        pointsSinceSave = 0
        if (blocking) runBlocking(Dispatchers.IO) { saveStateNow() } else writes.trySend { saveStateNow() }
    }

    private suspend fun saveStateNow() {
        val saved = SavedRecorder(recorder.snapshot(), currentTripId, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        graph.db.kv().put(KeyValueEntity(KEY_STATE, graph.json.encodeToString(SavedRecorder.serializer(), saved)))
    }

    // ------------------------------------------------------------------ notification

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, Notifications.ID_TRACKING, buildNotification(), type)
    }

    private fun refreshNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyMs < 5_000) return
        lastNotifyMs = now
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(Notifications.ID_TRACKING, buildNotification())
    }

    private fun buildNotification(): Notification {
        val st = graph.tracking.status.value
        val sharing = settings?.shareLive == true
        val title = when {
            st.simulation -> "Simulación de viaje en curso"
            st.recordingTripId != null && st.detectorState == DetectorState.STOPPED -> "Viaje en pausa (detenido)"
            st.recordingTripId != null -> "Grabando viaje"
            else -> "Trazo está atento a tus viajes"
        }
        val text = buildString {
            if (st.recordingTripId != null && st.tripStartMs != null) {
                append(Format.distance(st.tripDistanceM)).append(" · ")
                append(Format.duration(System.currentTimeMillis() - st.tripStartMs))
            } else {
                append("Empezará a grabar cuando detecte que conduces")
            }
            append(if (sharing) " · Compartiendo ubicación" else " · Ubicación no compartida")
        }
        val b = NotificationCompat.Builder(this, Notifications.CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_trazo)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openApp(this, "map"))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        if (st.recordingTripId != null) {
            b.addAction(0, "Finalizar viaje", servicePending(ACTION_STOP_TRIP))
        }
        b.addAction(0, if (sharing) "Dejar de compartir" else "Compartir ubicación", servicePending(ACTION_TOGGLE_SHARING))
        return b.build()
    }

    private fun servicePending(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(), Intent(this, TrackingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val TAG = "TrazoTracking"
        private const val KEY_STATE = "recorder_state"
        const val ACTION_STOP_TRIP = "app.trazo.STOP_TRIP"
        const val ACTION_TOGGLE_SHARING = "app.trazo.TOGGLE_SHARING"
        private const val IDLE_STOP_MS = 3 * 60_000L
        private const val INTERRUPTED_MS = 20 * 60_000L
    }
}
