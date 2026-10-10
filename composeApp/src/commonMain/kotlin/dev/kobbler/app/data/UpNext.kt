package dev.kobbler.app.data

import androidx.compose.runtime.Immutable

enum class UpNextReason {
    Resume, NewEpisode, NewSeason, NextInSeries, Schedule, Live, Companion, Recommended, FollowUp,
}

@Immutable
data class UpNextItem(
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
    val cta = when (mediaType) {
        MediaType.Audiobook -> "Listen"
        MediaType.Book -> "Read"
        else -> if (started) "Resume" else "Play"
    }
    return UpNextItem(
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
