package dev.kobbler.app.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.kobbler.app.android.R

internal const val DownloadNotificationId = 4201
private const val ChannelId = "kobbler_downloads"

internal fun ensureDownloadChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val channel = NotificationChannel(ChannelId, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Titles being kept for offline use"
        setShowBadge(false)
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

internal fun downloadNotification(context: Context, index: OfflineIndex?, activeKey: String?): Notification {
    val pending = index?.items?.values?.filter { it.state != OfflineState.Complete && it.state != OfflineState.Failed }.orEmpty()
    val active = activeKey?.let { index?.items?.get(it) }
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val content = launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
    return NotificationCompat.Builder(context, ChannelId)
        .setSmallIcon(R.drawable.ic_notification_app)
        .setContentTitle(active?.let { it.showTitle?.let { show -> "$show · ${it.title}" } ?: it.title } ?: "Preparing downloads")
        .setContentText(if (pending.size > 1) "${pending.size - 1} more queued" else "Downloading for offline use")
        .setProgress(100, ((active?.progress ?: 0f) * 100).toInt(), active == null || active.totalBytes == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(content)
        .build()
}
