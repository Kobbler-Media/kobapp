package dev.kobbler.app.offline

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while downloads run with the app in the background, and shows
 * how they are going. Stops itself once nothing is left that the connection would let run.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ensureDownloadChannel(this)
        val engine = Offline.engine
        val first = downloadNotification(this, engine?.store?.index?.value, engine?.downloader?.active?.value)
        // Refused once the day's data-sync allowance is spent; downloads then carry on only
        // while the app is open, and the service tries again the next time it is started.
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(DownloadNotificationId, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(DownloadNotificationId, first)
            }
        }.isSuccess
        if (engine == null || !started) return stopSelf()
        val notifications = getSystemService(NotificationManager::class.java)
        scope.launch {
            combine(engine.store.index, engine.downloader.active) { index, active -> index to active }
                .sample(NotificationSpacingMs)
                .collect { (index, active) -> notifications.notify(DownloadNotificationId, downloadNotification(this@DownloadService, index, active)) }
        }
        scope.launch {
            engine.hasPendingWork.collect { pending -> if (!pending) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15 gives data sync six hours a day and kills a service that outlives them.
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NotificationSpacingMs = 1_000L

        /** Only from the foreground: Android refuses a foreground service started from behind. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
        }
    }
}
