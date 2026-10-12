package app.trazo

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import app.trazo.config.SettingsRepository
import app.trazo.data.local.TrazoDatabase
import app.trazo.data.remote.Backend
import app.trazo.data.repo.AuthRepository
import app.trazo.data.repo.ConvoyRepository
import app.trazo.data.repo.LiveRepository
import app.trazo.data.repo.ProfileRepository
import app.trazo.data.repo.SocialRepository
import app.trazo.data.repo.TripRepository
import app.trazo.data.repo.VehicleRepository
import app.trazo.notify.Notifications
import app.trazo.sync.SyncScheduler
import app.trazo.tracking.TrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

/** Manual dependency graph: one instance of each service for the whole process. */
class AppGraph(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val db = TrazoDatabase.build(app)
    val backend = Backend(app, settings)
    val json = backend.json
    val auth = AuthRepository(backend, settings)
    val profile = ProfileRepository(backend)
    val social = SocialRepository(backend, appScope)
    val live = LiveRepository(backend, db.friendCache(), appScope, json)
    val trips = TripRepository(db.trips(), db.points(), backend, json)
    val vehicles = VehicleRepository(db.vehicles(), backend)
    val convoys = ConvoyRepository(backend)
    val tracking = TrackingController(app)
    val sync = SyncScheduler(app)
}

class TrazoApp : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        WorkManager.getInstance(this)
        MapLibre.getInstance(this)
        Notifications.createChannels(this)
        graph.appScope.launch {
            if (graph.settings.current().backendConfigured) runCatching { graph.backend.client() }
            graph.sync.schedulePeriodic()
        }
    }
}
