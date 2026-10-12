package app.trazo.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.trazo.TrazoApp
import java.util.concurrent.TimeUnit

/**
 * Uploads everything recorded offline once a network is available. WorkManager persists the
 * request across process death and reboots and retries with exponential backoff.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as TrazoApp).graph
        if (graph.backend.userIdOrNull() == null) return Result.success()
        return try {
            graph.vehicles.sync()
            graph.trips.sync()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 20) Result.retry() else Result.failure()
        }
    }
}

class SyncScheduler(private val ctx: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun requestSync() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Safety net in case a one-off request was lost. */
    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancelAll() = WorkManager.getInstance(ctx).cancelAllWork()
}
