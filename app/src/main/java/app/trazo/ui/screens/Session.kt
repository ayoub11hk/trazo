package app.trazo.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.trazo.AppGraph
import app.trazo.data.local.KeyValueEntity
import app.trazo.tracking.ActivityRecognitionManager
import app.trazo.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs once per signed-in user: loads remote state, restores data on a new phone, arms tracking. */
@Composable
fun SessionBootstrap(graph: AppGraph, userId: String) {
    val ctx = LocalContext.current
    LaunchedEffect(userId) { bootstrap(graph, userId, ctx) }
    DisposableEffect(userId) {
        graph.social.startListening()
        onDispose { graph.social.stopListening() }
    }
}

suspend fun bootstrap(graph: AppGraph, userId: String, ctx: Context) = withContext(Dispatchers.IO) {
    val kv = graph.db.kv()
    val lastUser = kv.get("last_user")
    if (lastUser != null && lastUser != userId) {
        // Another account used this phone before: never mix their history with this one.
        graph.trips.clearLocal()
        graph.vehicles.clearLocal()
        graph.live.clearCache()
        kv.delete("pulled")
    }
    kv.put(KeyValueEntity("last_user", userId))
    runCatching { graph.profile.refresh() }
    runCatching { graph.social.refresh() }
    runCatching { graph.convoys.refresh() }
    if (kv.get("pulled") == null) {
        val ok = runCatching { graph.vehicles.pull(); graph.trips.pullOwnTrips() }.isSuccess
        if (ok) kv.put(KeyValueEntity("pulled", "1"))
    }
    val s = graph.settings.current()
    if (s.autoDetectTrips && Permissions.activityRecognition(ctx)) ActivityRecognitionManager.register(ctx)
    if (graph.trips.recording() != null || s.continuousMonitoring) graph.tracking.ensureServiceRunning("session")
    graph.sync.requestSync()
}

/** Signs out and removes this account's data from the phone (after a last sync attempt). */
suspend fun signOutAndWipe(graph: AppGraph, ctx: Context) = withContext(Dispatchers.IO) {
    graph.tracking.shutdown()
    runCatching { graph.vehicles.sync(); graph.trips.sync() }
    ActivityRecognitionManager.unregister(ctx)
    graph.social.clear()
    graph.convoys.clear()
    graph.profile.clear()
    graph.trips.clearLocal()
    graph.vehicles.clearLocal()
    graph.live.clearCache()
    graph.db.kv().delete("pulled")
    graph.db.kv().delete("last_user")
    graph.db.kv().delete("recorder_state")
    graph.sync.cancelAll()
    graph.auth.signOut()
}
