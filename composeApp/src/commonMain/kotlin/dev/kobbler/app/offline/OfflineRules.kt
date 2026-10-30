package dev.kobbler.app.offline

import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.Settings

enum class CleanupPolicy(val label: String) {
    Keep("Keep until removed"),
    AfterWatched("Remove once watched"),
    AfterWeek("Remove a week after downloading");

    companion object {
        private const val Key = "offline_cleanup"

        // A device setting, not a profile one: downloads belong to the device.
        var current: CleanupPolicy
            get() = Settings.getString(Key)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: Keep
            set(value) = Settings.putString(Key, value.name)
    }
}

enum class DownloadQuality(val wire: String, val label: String) {
    Original("original", "Original"),
    P1080("1080p", "1080p"),
    P720("720p", "720p"),
    P480("480p", "480p");

    companion object {
        private const val Key = "offline_quality"

        var current: DownloadQuality
            get() = Settings.getString(Key)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: Original
            set(value) = Settings.putString(Key, value.name)
    }
}

private const val WeekMs = 7 * 24 * 60 * 60 * 1000L

fun dueForCleanup(items: Collection<OfflineItem>, policy: CleanupPolicy, nowMs: Long, inUse: (String) -> Boolean): List<OfflineItem> =
    items.filter { item ->
        !item.keep && item.state == OfflineState.Complete && !inUse(item.key) && when (policy) {
            CleanupPolicy.Keep -> false
            CleanupPolicy.AfterWatched -> item.watched
            CleanupPolicy.AfterWeek -> (item.completedAtMs ?: nowMs) + WeekMs <= nowMs
        }
    }

/** An episode is new to a subscription once it has a file and has not been downloaded for it before. */
fun newEpisodes(show: TvShow, subscription: ShowSubscription): List<Episode> =
    show.seasons.flatMap { it.episodes }.filter { it.id != null && it.filePath != null && it.id !in subscription.knownEpisodeIds }

/** Device settings for downloads that run with the app closed; only Android has any. */
object BackgroundDownloads {
    private const val WifiOnlyKey = "offline_background_wifi_only"

    /** Set by a platform that schedules background downloads, to reschedule on a change. */
    var onPolicyChanged: (() -> Unit)? = null

    val supported: Boolean get() = onPolicyChanged != null

    var wifiOnly: Boolean
        get() = Settings.getString(WifiOnlyKey) != "false"
        set(value) {
            Settings.putString(WifiOnlyKey, value.toString())
            onPolicyChanged?.invoke()
        }
}
