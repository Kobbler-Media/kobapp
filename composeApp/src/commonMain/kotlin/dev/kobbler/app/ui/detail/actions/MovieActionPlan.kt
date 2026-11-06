package dev.kobbler.app.ui.detail.actions

import dev.kobbler.app.data.Movie
import dev.kobbler.app.ui.detail.actions.MovieAction.*
import dev.kobbler.app.ui.player.resumeSeconds

internal sealed interface MovieAction {
    data class Resume(val positionSeconds: Int) : MovieAction
    data object Play : MovieAction
    data object WatchAgain : MovieAction
    data object FromStart : MovieAction
    data object Download : MovieAction
    data object Streams : MovieAction
    data object Want : MovieAction
    data class SetWatched(val watched: Boolean) : MovieAction
    data object Watchlist : MovieAction
    data object Keep : MovieAction
    data object Kids : MovieAction
}

internal data class MovieActionState(
    val movie: Movie,
    val canPlayVideo: Boolean = true,
    val canStream: Boolean = false,
    val canPickStream: Boolean = false,
    val offline: Boolean = false,
    val playableOffline: Boolean = false,
    val canDownload: Boolean = false,
    val downloadStarted: Boolean = false,
    val canWant: Boolean = false,
    val canKeep: Boolean = false,
    val canManageKids: Boolean = false,
)

internal fun movieActions(state: MovieActionState): List<MovieAction> = with(state) {
    val hasFile = movie.filePath != null
    val canPlay = canPlayVideo && (if (offline) playableOffline else hasFile || canStream)
    val resumeAt = movie.resumeSeconds
    buildList {
        if (canPlay) {
            add(
                when {
                    resumeAt != null -> Resume(resumeAt)
                    movie.isWatched -> WatchAgain
                    else -> Play
                },
            )
            if (resumeAt != null) add(FromStart)
        }
        if (canDownload && hasFile && canPlayVideo && (!offline || downloadStarted)) add(Download)
        if (offline) return@buildList
        if (canPickStream) add(Streams)
        if (movie.canBeWanted(canWant)) add(Want)
        if (canPlay) add(SetWatched(!movie.isWatched))
        if (canWant) add(Watchlist)
        if (canKeep) add(Keep)
        if (canManageKids) add(Kids)
    }
}
