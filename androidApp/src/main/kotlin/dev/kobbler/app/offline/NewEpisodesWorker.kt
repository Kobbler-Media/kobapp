package dev.kobbler.app.offline

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.kobbler.app.platform.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Fetches new episodes of followed shows while the app is closed, so they are there the
 * next time the device leaves the house.
 */
class NewEpisodesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Settings.init(applicationContext)
        val engine = Offline.engine ?: return Result.success()
        if (engine.store.index.value.subscriptions.isEmpty() && engine.store.index.value.items.isEmpty()) return Result.success()
        if (!engine.attachStoredProfile()) return Result.success()
        engine.maintenance.runOnce()
        if (!engine.hasPendingWork.first()) return Result.success()
        ensureDownloadChannel(applicationContext)
        // From the background Android may refuse the foreground promotion; a plain worker still
        // gets its ten minutes, and whatever is left resumes on the next run or app start.
        val promoted = runCatching { setForeground(foregroundInfo()) }.isSuccess
        withTimeoutOrNull(if (promoted) MaxRunMs else PlainRunMs) { engine.hasPendingWork.first { !it } }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val engine = Offline.engine
        val notification = downloadNotification(applicationContext, engine?.store?.index?.value, engine?.downloader?.active?.value)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(DownloadNotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(DownloadNotificationId, notification)
        }
    }

    companion object {
        private const val WorkName = "offline-new-episodes"
        private const val MaxRunMs = 4 * 60 * 60 * 1000L
        private const val PlainRunMs = 9 * 60 * 1000L

        fun schedule(context: Context) {
            val app = context.applicationContext
            BackgroundDownloads.onPolicyChanged = { enqueue(app, ExistingPeriodicWorkPolicy.UPDATE) }
            enqueue(app, ExistingPeriodicWorkPolicy.KEEP)
        }

        private fun enqueue(context: Context, policy: ExistingPeriodicWorkPolicy) {
            val network = if (BackgroundDownloads.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = PeriodicWorkRequestBuilder<NewEpisodesWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WorkName, policy, request)
        }
    }
}
