package dev.kobbler.app.watchnext

import dev.kobbler.app.data.InProgressItem
import dev.kobbler.app.data.resolveUrlOrNull
import dev.kobbler.app.offline.stableHash
import kotlin.time.Instant

enum class WatchNextKind { Movie, Episode }

enum class WatchNextReason { Continue, Next, Watchlist }

data class WatchNextProgramSpec(
    val internalId: String,
    val kind: WatchNextKind,
    val reason: WatchNextReason,
    val title: String,
    val episodeTitle: String?,
    val season: Int?,
    val episode: Int?,
    val description: String?,
    val artUrl: String?,
    val artIsLandscape: Boolean,
    val durationMs: Long?,
    val positionMs: Long?,
    val lastEngagementMs: Long,
    val launch: WatchNextLaunch,
) {
    val fingerprint: String get() = stableHash(toString())
}

private const val MaxPrograms = 10
private const val FinishedFraction = 0.97
private val SeasonEpisode = Regex("""^S(\d+)E(\d+)(?:\s*[-·]\s*(.*))?$""")

fun watchNextPrograms(items: List<InProgressItem>, profileId: String, apiUrl: String): List<WatchNextProgramSpec> =
    items.mapNotNull { it.toSpec(profileId, apiUrl) }
        .sortedByDescending { it.lastEngagementMs }
        .take(MaxPrograms)

private fun InProgressItem.toSpec(profileId: String, apiUrl: String): WatchNextProgramSpec? {
    val kind = when (mediaType) {
        "movie" -> WatchNextKind.Movie
        "episode", "event" -> WatchNextKind.Episode
        else -> return null
    }
    val durationMs = durationSeconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
    val positionMs = positionSeconds?.takeIf { it > 0 }?.let { it * 1000L }
    // The server keeps a title in progress until something marks it watched, so a stream
    // that ran into its credits still shows up here at 99%.
    if (positionMs != null && durationMs != null && positionMs >= durationMs * FinishedFraction) return null
    val reason = when {
        positionMs != null -> WatchNextReason.Continue
        kind == WatchNextKind.Episode -> WatchNextReason.Next
        else -> WatchNextReason.Watchlist
    }
    val numbered = if (mediaType == "episode") subtitle?.let { SeasonEpisode.matchEntire(it.trim()) } else null
    val backdrop = resolveUrlOrNull(apiUrl, backdropUrl)
    return WatchNextProgramSpec(
        internalId = "$profileId/$mediaType/$mediaId",
        kind = kind,
        reason = reason,
        title = title,
        episodeTitle = when {
            kind != WatchNextKind.Episode -> null
            numbered != null -> numbered.groupValues[3].ifBlank { null }
            else -> subtitle
        },
        season = numbered?.groupValues?.get(1)?.toIntOrNull(),
        episode = numbered?.groupValues?.get(2)?.toIntOrNull(),
        description = if (kind == WatchNextKind.Movie) subtitle else null,
        artUrl = backdrop ?: resolveUrlOrNull(apiUrl, imageUrl),
        artIsLandscape = backdrop != null,
        durationMs = durationMs,
        positionMs = positionMs,
        lastEngagementMs = updatedAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L,
        launch = WatchNextLaunch(profileId, link, title, subtitle),
    )
}
