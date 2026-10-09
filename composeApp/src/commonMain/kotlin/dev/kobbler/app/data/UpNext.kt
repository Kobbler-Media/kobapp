package dev.kobbler.app.data

import androidx.compose.runtime.Immutable

/**
 * Up Next reframes the server's continueWatching+readNext feeds as a single
 * mixed queue with a *reason* per item. The server doesn't expose reason today,
 * so the client tells what was started from what is merely next or wanted; future
 * server work can fill in newEp/newSeason/schedule/companion.
 */
enum class UpNextReason {
    Resume, NewEpisode, NewSeason, NextInSeries, Schedule, Live, Companion, Recommended, FollowUp,
}

@Immutable
data class UpNextItem(
    val reason: UpNextReason,
    val tag: String,
    val ctaLabel: String,
    val title: String,
    val subtitle: String?,
    val mediaType: MediaType,
    val mediaId: String,
    val progress: Float? = null,
    val mediaArtUrl: String? = null,
    val backdropUrl: String? = null,
    val link: String? = null,
    val tail: String? = null,
    val source: ContinueItem? = null,
)

fun ContinueItem.toUpNextItem(): UpNextItem {
    val (reason, tag) = when {
        started -> UpNextReason.Resume to "Resume"
        mediaType == MediaType.TvShow -> UpNextReason.NextInSeries to "Next episode"
        mediaType == MediaType.SportsEvent -> UpNextReason.NextInSeries to "Next event"
        mediaType == MediaType.Movie -> UpNextReason.Recommended to "Watchlist"
        else -> UpNextReason.NextInSeries to "Up next"
    }
    val cta = when (mediaType) {
        MediaType.Audiobook -> "Listen"
        MediaType.Book -> "Read"
        else -> if (started) "Resume" else "Play"
    }
    return UpNextItem(
        reason = reason,
        tag = tag,
        ctaLabel = cta,
        title = title,
        subtitle = subtitle,
        mediaType = mediaType,
        mediaId = id,
        progress = progress.takeIf { it > 0f && it < 1f },
        mediaArtUrl = imageUrl,
        backdropUrl = backdropUrl,
        link = link,
        source = this,
    )
}
