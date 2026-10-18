package dev.kobbler.app.ui.player

import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.localToday
import kotlinx.datetime.LocalDate
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import kotlin.math.abs

/**
 * A live stream has no position worth saving, and a recording media_analyze has not
 * matched yet has no library row to save one against — a matched one plays as its
 * episode or movie and resumes like anything else.
 */
private val NonResumableMediaTypes = setOf("livetv", "iptv", "recording")

/**
 * A playhead this session put somewhere, and whether that somewhere was the end.
 *
 * "Finished" is carried rather than inferred: a title's metadata runtime and its file's
 * real duration routinely disagree by minutes, so no percentage of the former reliably
 * says the latter ran out.
 */
data class WatchedPoint(val seconds: Int, val finished: Boolean = false)

/**
 * Save the playhead, and remember locally that we did.
 *
 * Every screen that offers to resume a title reads a payload fetched when that screen
 * opened, and none of them refetch when the player hands control back — so the number a
 * viewer sees after watching is whatever the server was asked about before they did.
 * Recording it here is what closes that gap without a round trip on the way out.
 */
suspend fun GlobalPlayerState.reportProgress(
    api: KobblerApi,
    mediaType: String,
    mediaId: String,
    posMs: Long,
    finished: Boolean = false,
    mirror: Boolean = true,
) {
    if (mediaType in NonResumableMediaTypes) return
    // Another device got further and the viewer has not said which one wins yet.
    if (handoff != null) return
    val seconds = (posMs / 1000).toInt()
    // The screens beneath the player read the mirror, so a write redraws them, and on
    // Android 15+ a redraw pulls the panel to 60Hz where 24fps judders. Mid-play saves
    // leave it to the pause or exit after them, which write it even when the server
    // already has that second.
    if (mirror) reportedPositions[mediaId] = WatchedPoint(seconds, finished)
    val known = lastSaved?.takeIf { it.first == mediaId }?.second
    if (!finished && known != null && abs(known - seconds) <= 1) return
    lastSaved = mediaId to seconds
    try {
        when (mediaType) {
            "movie" -> api.reportMovieProgress(mediaId, seconds)
            "episode" -> api.reportEpisodeProgress(mediaId, seconds)
        }
    } catch (_: Exception) {}
}

/** Marking a title watched or not clears its saved position server-side; mirror that here. */
fun GlobalPlayerState.noteWatched(mediaId: String, watched: Boolean) {
    reportedPositions[mediaId] = WatchedPoint(0, finished = watched)
}

fun Movie.withReportedProgress(reported: Map<String, WatchedPoint>): Movie {
    val point = reported[id] ?: return this
    // Watched outright, so there is no part-way bar to draw and nothing to resume from.
    if (point.finished) return copy(isWatched = true, watchPositionSeconds = null)
    return copy(isWatched = false, watchPositionSeconds = point.seconds)
}

fun TvShow.withReportedProgress(reported: Map<String, WatchedPoint>, today: LocalDate = localToday()): TvShow {
    if (reported.isEmpty()) return this
    val updated = seasons.map { season ->
        season.copy(
            episodes = season.episodes.map { episode ->
                val point = episode.id?.let { reported[it] } ?: return@map episode
                if (point.finished) {
                    return@map episode.copy(isWatched = true, watchProgress = null)
                }
                // An episode carries a fraction, not a position: the seconds are only
                // meaningful against a runtime, and without one there is no bar to draw.
                val runtime = episode.runtimeMinutes?.times(60) ?: return@map episode
                if (runtime <= 0) return@map episode
                episode.copy(watchProgress = (point.seconds.toFloat() / runtime).coerceIn(0f, 1f))
            },
        )
    }
    return copy(seasons = updated, continueEpisode = continueAfter(updated, continueEpisode, reported, today))
}

/**
 * Left as fetched, Resume kept naming the episode just finished, and pressing it handed
 * back to the player still holding that episode at its last frame. Same pick as the
 * server's `compute_continue_episode`: the next unwatched episode after the furthest
 * one finished that has a file, or has aired with nobody wanting it.
 */
private fun continueAfter(
    seasons: List<Season>,
    current: ContinueEpisode?,
    reported: Map<String, WatchedPoint>,
    today: LocalDate,
): ContinueEpisode? {
    val order = seasons.flatMapIndexed { si, season -> season.episodes.mapIndexed { ei, ep -> Triple(si, ei, ep) } }
    val lastFinished = order.indexOfLast { (_, _, ep) -> ep.id?.let { reported[it] }?.finished == true }
    val currentPos = current?.let { c -> order.indexOfFirst { it.third.id == c.episodeId } } ?: -1
    if (lastFinished >= 0 && lastFinished >= currentPos) {
        return order.drop(lastFinished + 1)
            .firstOrNull { (_, _, ep) -> ep.id != null && !ep.isWatched && ep.isPlayable(today) }
            ?.let { (si, ei, ep) -> ContinueEpisode(si, ei, ep.id!!) }
    }
    val point = current?.let { reported[it.episodeId] } ?: return current
    return current.copy(positionSeconds = point.seconds)
}

private fun Episode.isPlayable(today: LocalDate): Boolean =
    filePath != null || (!isWanted && airDate != null && airDate <= today)
