package dev.kobbler.app.tv

import android.content.ContentValues
import android.media.tv.TvContract.WatchNextPrograms
import androidx.annotation.RequiresApi
import dev.kobbler.app.watchnext.WatchNextKind
import dev.kobbler.app.watchnext.WatchNextProgramSpec
import dev.kobbler.app.watchnext.WatchNextReason

@RequiresApi(26)
fun WatchNextProgramSpec.toContentValues(host: String): ContentValues = ContentValues().apply {
    put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, internalId)
    put(WatchNextPrograms.COLUMN_CONTENT_ID, internalId)
    put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_DATA, fingerprint.encodeToByteArray())
    put(WatchNextPrograms.COLUMN_TYPE, if (kind == WatchNextKind.Movie) WatchNextPrograms.TYPE_MOVIE else WatchNextPrograms.TYPE_TV_EPISODE)
    put(
        WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
        when (reason) {
            WatchNextReason.Continue -> WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
            WatchNextReason.Next -> WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
            WatchNextReason.Watchlist -> WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST
        },
    )
    put(WatchNextPrograms.COLUMN_TITLE, title)
    put(WatchNextPrograms.COLUMN_EPISODE_TITLE, episodeTitle)
    put(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, season?.toString())
    put(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, episode?.toString())
    put(WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, description)
    put(WatchNextPrograms.COLUMN_POSTER_ART_URI, artUrl)
    put(
        WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
        if (artIsLandscape) WatchNextPrograms.ASPECT_RATIO_16_9 else WatchNextPrograms.ASPECT_RATIO_2_3,
    )
    put(WatchNextPrograms.COLUMN_DURATION_MILLIS, durationMs?.toMillisColumn())
    put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, positionMs?.toMillisColumn())
    put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, lastEngagementMs)
    put(WatchNextPrograms.COLUMN_INTENT_URI, launch.toUri(host))
}

private fun Long.toMillisColumn(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
