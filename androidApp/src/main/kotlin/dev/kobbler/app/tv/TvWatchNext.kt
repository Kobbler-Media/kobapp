package dev.kobbler.app.tv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.kobbler.app.watchnext.HomeWatchNext
import dev.kobbler.app.watchnext.HomeWatchNextPublisher

object TvWatchNext {
    fun install(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < 26 || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        HomeWatchNext.publisher = HomeWatchNextPublisher { delayMs -> WatchNextWorker.refresh(app, delayMs) }
        WatchNextWorker.schedulePeriodic(app)
    }
}
