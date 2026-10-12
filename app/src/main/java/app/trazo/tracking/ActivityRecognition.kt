package app.trazo.tracking

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.trazo.TrazoApp
import app.trazo.core.trip.ActivityHint
import app.trazo.util.Permissions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.launch

/**
 * Android's activity-transition API: the OS watches the motion sensors at almost no battery
 * cost and wakes Trazo when the phone starts travelling in a vehicle. Registrations do not
 * survive a reboot, so [BootReceiver] re-registers them.
 */
object ActivityRecognitionManager {
    private const val TAG = "TrazoAR"

    fun available(ctx: Context) =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS

    private fun pendingIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 7, Intent(ctx, ActivityTransitionReceiver::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        if (!available(ctx) || !Permissions.activityRecognition(ctx)) return
        val t = listOf(
            DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_EXIT,
            DetectedActivity.ON_FOOT to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            DetectedActivity.ON_BICYCLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            DetectedActivity.STILL to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
        ).map { (type, tr) -> ActivityTransition.Builder().setActivityType(type).setActivityTransition(tr).build() }
        ActivityRecognition.getClient(ctx)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(t), pendingIntent(ctx))
            .addOnFailureListener { Log.w(TAG, "transition registration failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun unregister(ctx: Context) {
        if (!available(ctx) || !Permissions.activityRecognition(ctx)) return
        ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(pendingIntent(ctx))
    }

    fun hintFor(type: Int, transition: Int): ActivityHint = when {
        type == DetectedActivity.IN_VEHICLE && transition == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> ActivityHint.IN_VEHICLE
        type == DetectedActivity.IN_VEHICLE -> ActivityHint.ON_FOOT // exit vehicle
        type == DetectedActivity.ON_FOOT -> ActivityHint.ON_FOOT
        type == DetectedActivity.ON_BICYCLE -> ActivityHint.ON_BICYCLE
        type == DetectedActivity.STILL -> ActivityHint.STILL
        else -> ActivityHint.UNKNOWN
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val app = context.applicationContext as TrazoApp
        val pending = goAsync()
        app.graph.appScope.launch {
            try {
                val settings = app.graph.settings.current()
                for (e in result.transitionEvents) {
                    val hint = ActivityRecognitionManager.hintFor(e.activityType, e.transitionType)
                    app.graph.tracking.onActivity(hint, SystemClock.elapsedRealtime())
                    if (hint == ActivityHint.IN_VEHICLE && settings.autoDetectTrips) {
                        app.graph.tracking.ensureServiceRunning(reason = "vehicle")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as TrazoApp
        val pending = goAsync()
        app.graph.appScope.launch {
            try {
                val s = app.graph.settings.current()
                if (s.autoDetectTrips) ActivityRecognitionManager.register(context)
                // Resume an interrupted trip, or the continuous watcher, if the user wanted it.
                val interrupted = app.graph.trips.recording() != null
                if (interrupted || s.continuousMonitoring) app.graph.tracking.ensureServiceRunning(reason = "boot")
                app.graph.sync.requestSync()
            } finally {
                pending.finish()
            }
        }
    }
}
