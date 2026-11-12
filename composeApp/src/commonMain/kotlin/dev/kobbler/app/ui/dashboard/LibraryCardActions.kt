package dev.kobbler.app.ui.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.ui.components.CardAction
import kotlinx.coroutines.launch

@Composable
internal fun rememberMovieCardActions(movie: Movie, api: KobblerApi, onPlay: ((Movie) -> Unit)?): List<CardAction> =
    rememberLibraryCardActions("movie", movie.id, api, onPlay?.let { { it(movie) } }) { api.setMovieWatched(movie.id, true) }

@Composable
internal fun rememberShowCardActions(show: TvShow, api: KobblerApi, onPlay: ((TvShow) -> Unit)?): List<CardAction> =
    rememberLibraryCardActions("show", show.id, api, onPlay?.let { { it(show) } }, markWatched = null)

/** The list reports no watch or list state per title, so only actions that are safe to repeat are offered. */
@Composable
private fun rememberLibraryCardActions(
    kind: String,
    id: String,
    api: KobblerApi,
    play: (() -> Unit)?,
    markWatched: (suspend () -> Unit)?,
): List<CardAction> {
    val scope = rememberCoroutineScope()
    return remember(kind, id, api, play != null, markWatched != null) {
        listOfNotNull(
            play?.let { CardAction("Play", it, Icons.Outlined.PlayArrow) },
            CardAction("Add to my list", { scope.launch { runCatching { api.addToWatchlist(kind, id) } } }, Icons.Outlined.BookmarkAdd),
            markWatched?.let { mark -> CardAction("Mark watched", { scope.launch { runCatching { mark() } } }, Icons.Outlined.Check) },
        )
    }
}
