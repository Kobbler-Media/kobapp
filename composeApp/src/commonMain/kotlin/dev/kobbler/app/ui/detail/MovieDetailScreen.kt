package dev.kobbler.app.ui.detail

import dev.kobbler.app.ui.offline.LocalOfflineEngine

import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.downloadMovie
import dev.kobbler.app.ui.offline.OfflineAction
import dev.kobbler.app.ui.offline.rememberOfflineItem

import dev.kobbler.app.ui.components.PrefetchToDisk
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
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
import dev.kobbler.app.ui.player.resumeSeconds
import dev.kobbler.kobfocus.focusKey
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
    MovieDetailLayout(
        movie = movie,
        formFactor = formFactor,
        accent = accent,
        onBack = onBack,
        onPersonClick = onPersonClick,
        modifier = modifier,
        aside = if (movie.filePath == null && movie.isWanted) { _ ->
            ReleaseAvailabilityStrip(
                releaseAvailability(
                    movie.releaseDate, movie.year.takeIf { it > 0 }, movie.releaseDates, deviceRegion,
                    localToday(), streaming, LibraryStatus.Wanted,
                ),
                accent,
            )
        } else null,
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
    val canPlay = supportsVideoPlayback && (movie.filePath != null || onStream != null)
    val play: (Boolean) -> Unit = if (movie.filePath != null) onPlay else (onStream ?: onPlay)
    val resumeAt = movie.resumeSeconds
    val total = movie.runtimeMinutes?.times(60)
    var inList by remember(movie.inWatchlist) { mutableStateOf(movie.inWatchlist) }
    // Owned by the row, not the menu: an item closes the menu, disposing its content,
    // before its request finishes.
    val actionScope = rememberCoroutineScope()
    DetailActionRow(
        wide = wide,
        modifier = modifier,
        primary = if (!canPlay) null else { fill ->
            PrimaryAction(
                icon = Icons.Outlined.PlayArrow,
                label = if (resumeAt != null) "Resume" else "Play",
                progress = if (resumeAt != null && total != null && total > 0) resumeAt.toFloat() / total else null,
                remaining = if (resumeAt != null && total != null) remainingLabel(total - resumeAt) else null,
                onClick = { play(resumeAt == null) },
                modifier = Modifier.focusKey("play", preferredEntry = true).then(fill),
            )
        },
    ) {
        if (canPlay && resumeAt != null) {
            IconAction(
                icon = Icons.Outlined.Replay,
                label = "From start",
                accent = accent,
                onClick = { play(true) },
                modifier = Modifier.focusKey("fromStart"),
            )
        }

        if (onStreamPick != null) {
            IconAction(
                icon = Icons.Outlined.Tune,
                label = "Streams",
                accent = accent,
                onClick = onStreamPick,
                modifier = Modifier.focusKey("streams"),
            )
        }

        val offline = LocalOfflineEngine.current
        if (offline != null && movie.filePath != null && supportsVideoPlayback) {
            OfflineAction(
                item = rememberOfflineItem(OfflineKind.Movie, movie.id),
                accent = accent,
                onDownload = { offline.downloadMovie(movie) },
            )
        }

        if (movie.canBeWanted(api.canWant)) {
            var wanted by remember { mutableStateOf(false) }
            val wantScope = rememberCoroutineScope()
            IconAction(
                icon = if (wanted) Icons.Outlined.Check else Icons.Outlined.BookmarkAdd,
                label = if (wanted) "Wanted" else "Want",
                tint = if (wanted) colors.warn else colors.textHi,
                accent = accent,
                onClick = {
                    if (!wanted) {
                        wantScope.launch {
                            try { api.wantMovieById(movie.id); wanted = true } catch (_: Exception) {}
                        }
                    }
                },
                modifier = Modifier.focusKey("want"),
            )
        }

        // Rarely-reached state toggles, kept off the row so Resume has the room on a phone.
        if (api.canWant || canPlay || kids != null || keep != null) OverflowActions(accent = accent) { close ->
            if (api.canWant) {
                WatchlistItem(
                    inList = inList,
                    accent = accent,
                    toast = toast,
                    scope = actionScope,
                    onChanged = { inList = it },
                    toggle = { on -> if (on) api.addToWatchlist("movie", movie.id) else api.removeFromWatchlist("movie", movie.id) },
                    close = close,
                )
            }
            if (canPlay) {
                OverflowItem(
                    key = "watched",
                    icon = if (movie.isWatched) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    label = if (movie.isWatched) "Mark unwatched" else "Mark watched",
                    accent = accent,
                    onClick = {
                        val newState = !movie.isWatched
                        close()
                        onWatchedChanged(newState)
                        actionScope.launch {
                            try { api.setMovieWatched(movie.id, newState) }
                            catch (_: Exception) { onWatchedChanged(!newState) }
                        }
                    },
                )
            }
            if (keep != null) KeepItem(keep, accent, close)
            if (kids != null) KidsAccessItem(kids, accent, close)
        }
        if (kids != null) KidsAccessDialog(kids, accent)
    }
}

