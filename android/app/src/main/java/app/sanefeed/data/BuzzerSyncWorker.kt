package app.sanefeed.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sanefeed.SaneFeedApp
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException

class BuzzerSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = (applicationContext as SaneFeedApp).graph
        try {
            graph.blocklist.sync(graph.api())
            Result.success()
        } catch (_: IOException) {
            // Failure is recorded in SyncStatus; the periodic request tries again in 6 hours.
            Result.failure()
        } catch (_: JSONException) {
            Result.failure()
        }
    }

    companion object {
        const val ONE_OFF = "buzzer-sync-now"
        private const val PERIODIC = "buzzer-sync-periodic"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<BuzzerSyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** [force] replaces a queued sync (user tapped "Sync now"); otherwise an existing one is kept. */
        fun syncNow(context: Context, force: Boolean) {
            val request = OneTimeWorkRequestBuilder<BuzzerSyncWorker>().setConstraints(online).build()
            val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF, policy, request)
        }
    }
}
