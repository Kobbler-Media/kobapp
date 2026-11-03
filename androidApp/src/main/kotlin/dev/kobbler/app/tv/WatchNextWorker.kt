package dev.kobbler.app.tv

import android.content.Context
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.kobbler.app.platform.Settings
import dev.kobbler.app.watchnext.watchNextForStoredProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

private val syncLock = Mutex()

@RequiresApi(26)
class WatchNextWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // The periodic and the on-demand request can run at once, and the row is read-modify-write.
    override suspend fun doWork(): Result = syncLock.withLock {
        Settings.init(applicationContext)
        val desired = try {
            watchNextForStoredProfile()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return if (runAttemptCount < MaxAttempts) Result.retry() else Result.success()
        }
        WatchNextRow(applicationContext.contentResolver, applicationContext.packageName).sync(desired)
        Result.success()
    }

    companion object {
        private const val RefreshWork = "tv-watch-next-refresh"
        private const val PeriodicWork = "tv-watch-next-periodic"
        private const val MaxAttempts = 3

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchNextWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PeriodicWork, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun refresh(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<WatchNextWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RefreshWork, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
