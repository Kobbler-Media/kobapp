package dev.kobbler.app.ui.detail

import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.OfflineState
import dev.kobbler.app.offline.downloadMovie
import dev.kobbler.app.ui.detail.actions.MovieAction
import dev.kobbler.app.ui.detail.actions.MovieActionState
import dev.kobbler.app.ui.detail.actions.movieActions
import dev.kobbler.app.ui.offline.LocalOfflineEngine
import dev.kobbler.app.ui.offline.offlineRowAction
import dev.kobbler.app.ui.offline.rememberOfflineItem
import dev.kobbler.app.ui.offline.rememberOnline

import dev.kobbler.app.ui.components.PrefetchToDisk
import dev.kobbler.app.ui.components.remainingLabel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.data.localToday
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.deviceRegion
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.KeepAction
import kotlinx.coroutines.launch

@Composable
fun MovieDetailScreen(
    movie: Movie,
    api: KobblerApi,
    formFactor: FormFactor,
    onBack: () -> Unit,
    onPlay: (fromStart: Boolean) -> Unit,
    onWatchedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onPersonClick: ((String) -> Unit)? = null,
    onStream: ((fromStart: Boolean) -> Unit)? = null,
    onStreamPick: (() -> Unit)? = null,
    streaming: StreamingAvailability = StreamingAvailability.Hidden,
    keep: KeepAction? = null,
) {
    val accent = KobappTheme.colors.accMovies
    val toast = rememberToastState()
    // The player opens on the logo; fetched only then, it was the last thing to appear.
    PrefetchToDisk(listOfNotNull(movie.logoUrl))
    val releaseStrip: @Composable () -> Unit = {
        ReleaseAvailabilityStrip(
            releaseAvailability(
                movie.releaseDate, movie.year.takeIf { it > 0 }, movie.releaseDates, deviceRegion,
                localToday(), streaming, LibraryStatus.Wanted,
            ),
            accent,
        )
    }
    MovieDetailLayout(
        movie = movie,
        formFactor = formFactor,
        accent = accent,
        onBack = onBack,
        onPersonClick = onPersonClick,
        modifier = modifier,
        aside = releaseStrip.takeIf { movie.filePath == null && movie.isWanted },
        actions = { wide, anchor ->
            ActionRow(
                movie = movie, api = api, accent = accent,
                onPlay = onPlay, onStream = onStream, onStreamPick = onStreamPick,
                onWatchedChanged = onWatchedChanged,
                keep = keep,
                toast = toast,
                wide = wide,
                modifier = anchor,
            )
        },
        overlay = { ToastOverlay(toast, Modifier.align(Alignment.BottomCenter)) },
    )
}

@Composable
private fun ActionRow(
    movie: Movie,
    api: KobblerApi,
    accent: Color,
    onPlay: (fromStart: Boolean) -> Unit,
    onStream: ((fromStart: Boolean) -> Unit)?,
    onStreamPick: (() -> Unit)?,
    onWatchedChanged: (Boolean) -> Unit,
    keep: KeepAction?,
    toast: ToastState,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val kids = rememberMovieKidsAccess(movie, api, toast)
    val play: (Boolean) -> Unit = if (movie.filePath != null) onPlay else (onStream ?: onPlay)
    val total = movie.runtimeMinutes?.times(60)
    var inList by remember(movie.inWatchlist) { mutableStateOf(movie.inWatchlist) }
    var wanted by remember { mutableStateOf(false) }
    // Owned by the row, not the menu: an item closes the menu, disposing its content,
    // before its request finishes.
    val actionScope = rememberCoroutineScope()
    val engine = LocalOfflineEngine.current
    val download = rememberOfflineItem(OfflineKind.Movie, movie.id)

    val plan = movieActions(
        MovieActionState(
            movie = movie,
            canPlayVideo = supportsVideoPlayback,
            canStream = onStream != null,
            canPickStream = onStreamPick != null,
            offline = !rememberOnline(),
            playableOffline = download?.state == OfflineState.Complete,
            canDownload = engine != null,
            downloadStarted = download != null,
            canWant = api.canWant,
            canKeep = keep != null,
            canManageKids = kids != null,
        ),
    )
    val actions = plan.map { action ->
        when (action) {
            is MovieAction.Resume -> RowAction(
                key = "play", label = "Resume", icon = Icons.Outlined.PlayArrow, onClick = { play(false) },
                progress = total?.takeIf { it > 0 }?.let { action.positionSeconds.toFloat() / it },
                remaining = total?.let { remainingLabel(it - action.positionSeconds) },
            )
            MovieAction.Play -> RowAction("play", "Play", Icons.Outlined.PlayArrow, { play(true) })
            MovieAction.WatchAgain -> RowAction("play", "Watch again", Icons.Outlined.Replay, { play(true) })
            MovieAction.FromStart -> RowAction("fromStart", "From start", Icons.Outlined.Replay, { play(true) })
            MovieAction.Download -> offlineRowAction(download, accent, { engine?.downloadMovie(movie) }, label = "Download", key = "download")
            MovieAction.Streams -> RowAction("streams", "Streams", Icons.Outlined.Tune, { onStreamPick?.invoke() })
            MovieAction.Want -> RowAction(
                key = "want",
                label = if (wanted) "Wanted" else "Want",
                icon = if (wanted) Icons.Outlined.Check else Icons.Outlined.Download,
                tint = if (wanted) colors.warn else null,
                onClick = {
                    if (!wanted) actionScope.launch {
                        try { api.wantMovieById(movie.id); wanted = true } catch (_: Exception) {}
                    }
                },
            )
            is MovieAction.SetWatched -> RowAction(
                key = "watched",
                label = if (action.watched) "Mark watched" else "Mark unwatched",
                icon = if (action.watched) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                onClick = {
                    onWatchedChanged(action.watched)
                    actionScope.launch {
                        try { api.setMovieWatched(movie.id, action.watched) }
                        catch (_: Exception) { onWatchedChanged(!action.watched) }
                    }
                },
            )
            MovieAction.Watchlist -> watchlistAction(
                inList = inList,
                toast = toast,
                scope = actionScope,
                onChanged = { inList = it },
                toggle = { on -> if (on) api.addToWatchlist("movie", movie.id) else api.removeFromWatchlist("movie", movie.id) },
            )
            MovieAction.Keep -> keepAction(keep!!)
            MovieAction.Kids -> kidsAction(kids!!)
        }
    }
    if (kids != null) KidsAccessDialog(kids, accent)
    DetailActionRow(actions = actions, accent = accent, wide = wide, modifier = modifier)
}
