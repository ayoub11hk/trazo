package app.trazo.tracking

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.trazo.core.location.LocationSample
import app.trazo.core.location.PointFlag
import app.trazo.core.trip.ActivityHint
import app.trazo.core.trip.DetectorState
import app.trazo.util.Permissions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TrackingStatus(
    val serviceRunning: Boolean = false,
    val detectorState: DetectorState = DetectorState.IDLE,
    val recordingTripId: String? = null,
    val tripStartMs: Long? = null,
    val tripDistanceM: Double = 0.0,
    val lastFix: LocationSample? = null,
    /** Wall-clock time when [lastFix] arrived on this phone. */
    val lastFixReceivedMs: Long? = null,
    val lastFlag: PointFlag? = null,
    val sourceName: String? = null,
    val gnss: GnssInfo = GnssInfo(),
    val sharingLive: Boolean = false,
    val lastPublishOk: Boolean? = null,
    val simulation: Boolean = false,
    val message: String? = null,
)

sealed interface TrackingCommand {
    data object StartTrip : TrackingCommand
    data object StopTrip : TrackingCommand
    data class Simulate(val speedFactor: Int) : TrackingCommand
    data object Reconfigure : TrackingCommand
    data object Shutdown : TrackingCommand
}

/** Process-wide entry point to the tracking service; the UI talks to this, never to the service. */
class TrackingController(private val ctx: Context) {
    private val _status = MutableStateFlow(TrackingStatus())
    val status: StateFlow<TrackingStatus> = _status

    private val _activity = MutableSharedFlow<Pair<ActivityHint, Long>>(extraBufferCapacity = 16)
    val activity: SharedFlow<Pair<ActivityHint, Long>> = _activity

    private val _commands = MutableSharedFlow<TrackingCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<TrackingCommand> = _commands

    /** Commands issued before the service was up; drained by the service on start. */
    private val pending = ArrayList<TrackingCommand>()

    fun update(f: (TrackingStatus) -> TrackingStatus) = _status.update(f)

    fun onActivity(hint: ActivityHint, elapsedMs: Long) {
        _activity.tryEmit(hint to elapsedMs)
    }

    fun ensureServiceRunning(reason: String): Boolean {
        if (!Permissions.fineLocation(ctx)) return false
        if (_status.value.serviceRunning) return true
        return try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java).putExtra("reason", reason))
            true
        } catch (e: Exception) {
            // Android 12+ may refuse to start a foreground service from the background.
            Log.w("Trazo", "could not start tracking service ($reason)", e)
            _status.update { it.copy(message = "Android no ha permitido iniciar el seguimiento en segundo plano.") }
            false
        }
    }

    fun send(cmd: TrackingCommand) {
        if (_status.value.serviceRunning) {
            _commands.tryEmit(cmd)
        } else {
            synchronized(pending) { pending += cmd }
            ensureServiceRunning(reason = cmd.javaClass.simpleName)
        }
    }

    fun drainPending(): List<TrackingCommand> = synchronized(pending) { pending.toList().also { pending.clear() } }

    fun startTrip() = send(TrackingCommand.StartTrip)
    fun stopTrip() = send(TrackingCommand.StopTrip)
    fun simulate(speedFactor: Int) = send(TrackingCommand.Simulate(speedFactor))
    fun reconfigure() { if (_status.value.serviceRunning) _commands.tryEmit(TrackingCommand.Reconfigure) }
    fun shutdown() { if (_status.value.serviceRunning) _commands.tryEmit(TrackingCommand.Shutdown) }
}
