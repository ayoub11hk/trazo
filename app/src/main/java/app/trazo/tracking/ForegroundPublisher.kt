package app.trazo.tracking

import android.os.BatteryManager
import android.os.SystemClock
import app.trazo.AppGraph
import app.trazo.core.location.LocationSample
import app.trazo.core.policy.LivePublishPolicy
import app.trazo.data.remote.LiveLocationDto
import app.trazo.data.remote.millisToIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shares my position while the map is open and the tracking service is not running (when it
 * runs, the service publishes instead). Same rules as the service: only real fixes, only when
 * sharing is on, at most every few seconds, and only when I actually moved (plus a heartbeat).
 * Ghost mode and per-friend permissions are enforced again on the server.
 */
class ForegroundPublisher(private val graph: AppGraph) {
    private val policy = LivePublishPolicy(minIntervalMs = 4_000, heartbeatMs = 60_000)
    private var inFlight: Job? = null

    fun onFix(s: LocationSample, shareLive: Boolean, batteryManager: BatteryManager?) {
        if (!shareLive || s.isMock) return
        if (graph.tracking.status.value.serviceRunning) return
        if (graph.profile.settings.value?.ghostMode == true) return
        if (inFlight?.isActive == true) return
        if (!policy.shouldPublish(s, SystemClock.elapsedRealtime())) return
        inFlight = graph.appScope.launch(Dispatchers.IO) {
            val uid = graph.backend.userIdOrNull() ?: return@launch
            runCatching {
                graph.live.publish(
                    LiveLocationDto(
                        userId = uid, sharing = true, lat = s.lat, lng = s.lng, accuracyM = s.accuracyM,
                        speedMps = s.reliableSpeedMps(), bearingDeg = s.reliableBearingDeg(), state = "idle",
                        vehicleId = graph.profile.profile.value?.activeVehicleId,
                        batteryPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
                        recordedAt = millisToIso(s.timeMs),
                    ),
                )
            }.onFailure { policy.reset() } // retry with the next fix once the network is back
        }
    }
}
