package dev.kobbler.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.player.KeepAction
import dev.kobbler.app.ui.player.resumeRequestSeconds
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.launch

@Composable
internal fun ShowActions(
    show: TvShow,
    accent: Color,
    selectedSeason: Season?,
    selectedSeasonIdx: Int,
    api: KobblerApi?,
    watchedOverrides: SnapshotStateMap<String, Boolean>,
    wantOverrides: SnapshotStateMap<String, Boolean>,
    toast: ToastState,
    onPlayEpisode: (Int, Int, Boolean) -> Unit,
    onRestarted: (ContinueEpisode?) -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
    exitDown: FocusRequester? = null,
    keep: KeepAction? = null,
) {
    val resume = show.continueEpisode
    // A sport is followed for what is newest: its first unwatched event is a test session
    // from months ago, not where the viewer wants to start.
    val unwatched: (Episode) -> Boolean = { it.filePath != null && !it.isWatchedNow(watchedOverrides) }
    val firstUnwatchedIdx = selectedSeason?.episodes?.let { eps ->
        if (show.isSport) eps.indexOfLast(unwatched) else eps.indexOfFirst(unwatched)
    }?.takeIf { it >= 0 }
    // Owned out here, not inside the menu: an item closes the menu before its request
    // finishes, and menu content is disposed on close -- a scope remembered in there
    // cancels the work that just started.
    val actionScope = rememberCoroutineScope()

    // The server offers an episode with no file when the show is one being streamed
    // rather than collected (aired, unwanted, already being watched). That is a debrid
    // search on tap, not a library play, so it must not wear the word Resume or the
    // accent fill — see compute_continue_episode.
    val resumeEpisode = resume?.let { show.seasons.getOrNull(it.seasonIdx)?.episodes?.getOrNull(it.episodeIdx) }
    val resumeSeasonNumber = resume?.let { show.seasons.getOrNull(it.seasonIdx)?.number }
    val resumeIsStream = resumeEpisode != null && resumeEpisode.filePath == null
    // Toggling watched on the episode clears its saved position, which `show` cannot know.
    val resumePosition = if (resumeEpisode?.id in watchedOverrides) 0 else resume?.positionSeconds ?: 0
    val canRestart = resume != null && supportsVideoPlayback && resumeRequestSeconds(resumePosition) != null
    val resumeTag = if (resumeSeasonNumber != null && resumeEpisode != null) episodeTag(resumeSeasonNumber, resumeEpisode.number) else null

    val playButton: @Composable (Modifier) -> Unit = { playFill ->
        if (resume != null && supportsVideoPlayback) {
            val total = resumeEpisode?.runtimeMinutes?.times(60)
            val inProgress = canRestart && resumeEpisode?.isWatchedNow(watchedOverrides) != true
            PrimaryAction(
                icon = if (resumeIsStream) Icons.Outlined.CloudDownload else Icons.Outlined.PlayArrow,
                label = when {
                    resumeIsStream -> "Stream"
                    canRestart -> "Resume"
                    else -> "Play"
                },
                detail = resumeTag,
                progress = if (inProgress && total != null && total > 0) resumePosition.toFloat() / total else null,
                remaining = if (inProgress && total != null) remainingLabel(total - resumePosition) else null,
                onClick = { onPlayEpisode(resume.seasonIdx, resume.episodeIdx, false) },
                modifier = Modifier.focusKey("action.resume", preferredEntry = true).then(playFill),
            )
        } else if (firstUnwatchedIdx != null && supportsVideoPlayback) {
            PrimaryAction(
                icon = Icons.Outlined.PlayArrow,
                label = "Play",
                onClick = { onPlayEpisode(selectedSeasonIdx, firstUnwatchedIdx, false) },
                modifier = Modifier.focusKey("action.play", preferredEntry = true).then(playFill),
            )
        }
    }

    val fromStartButton: @Composable () -> Unit = {
        if (resume != null && canRestart) {
            IconAction(
                icon = Icons.Outlined.Replay,
                label = "From start",
                accent = accent,
                onClick = { onPlayEpisode(resume.seasonIdx, resume.episodeIdx, true) },
                modifier = Modifier.focusKey("action.fromStart"),
            )
        }
    }

    // Streaming a copy and asking for the file are separate things — the episode the
    // page offers to stream is exactly the one worth offering to want.
    val wantableResume = resumeEpisode
        ?.takeIf { api != null && it.id != null && it.canBeWanted(api.canWant) && !it.isWantedNow(wantOverrides) }
    val wantButton: @Composable () -> Unit = {
        val ep = wantableResume
        if (ep?.id != null && api != null) {
            IconAction(
                icon = Icons.Outlined.BookmarkAdd,
                label = listOfNotNull("Want", resumeTag).joinToString(" "),
                accent = accent,
                onClick = {
                    // Optimistic: the request only enqueues a search, so `show` reports
                    // the same thing back and the button would re-offer itself.
                    wantOverrides[ep.id] = true
                    actionScope.launch {
                        runCatching { api.wantEpisode(ep.id) }
                            .onSuccess { toast.show("Added to wanted") }
                            .onFailure {
                                wantOverrides.remove(ep.id)
                                toast.show("Could not want that episode")
                            }
                    }
                },
                modifier = Modifier.focusKey("action.want"),
            )
        }
    }

    val nextIdx = resume?.let { r ->
        val eps = show.seasons.getOrNull(r.seasonIdx)?.episodes ?: return@let null
        val from = (r.episodeIdx + 1).coerceAtMost(eps.size)
        eps.subList(from, eps.size).indexOfFirst { it.filePath != null }
            .takeIf { it >= 0 }?.plus(from)
    }
    val nextButton: @Composable () -> Unit = {
        if (resume != null && nextIdx != null && supportsVideoPlayback) {
            IconAction(
                icon = Icons.Outlined.SkipNext,
                label = "Next episode",
                accent = accent,
                onClick = { onPlayEpisode(resume.seasonIdx, nextIdx, false) },
                modifier = Modifier.focusKey("action.playNext"),
            )
        }
    }

    val canWatchlist = api != null && api.canWant
    // Held out here, not in the menu item: menu content is disposed on close, so state
    // remembered in there resets to the fetched snapshot and the entry offers "Add"
    // again on the very next open.
    var inList by remember(show.inWatchlist) { mutableStateOf(show.inWatchlist) }
    var showWantPicker by remember { mutableStateOf(false) }
    var confirmRewatch by remember { mutableStateOf(false) }
    var wantMode by remember(show.autoWantMode) { mutableStateOf(show.autoWantMode) }
    val canWantShow = api != null && api.canWant
    // Seeded from the show, not false: the entry used to appear only when unmonitored,
    // so starting false was harmless. It is always shown now and has to say which way
    // round it is.
    var monitored by remember(show.isMonitored) { mutableStateOf(show.isMonitored) }
    val canRewatch = api != null && (
        resumePosition > 0 ||
            show.seasons.any { s -> s.episodes.any { it.id != null && it.isWatchedNow(watchedOverrides) } }
        )
    val kids = rememberShowKidsAccess(show, api, toast)

    val hasOverflow = api != null && (canWantShow || canWatchlist || canRewatch || kids != null || keep != null)
    val overflowButton: @Composable () -> Unit = {
        if (api != null && hasOverflow) {
            OverflowActions(accent = accent) { close ->
                if (canWatchlist) {
                    WatchlistItem(
                        inList = inList,
                        accent = accent,
                        toast = toast,
                        scope = actionScope,
                        onChanged = { inList = it },
                        toggle = { on -> if (on) api.addToWatchlist("show", show.id) else api.removeFromWatchlist("show", show.id) },
                        close = close,
                    )
                }

                if (canWantShow) {
                    OverflowItem(
                        key = "monitor",
                        icon = if (monitored) Icons.Outlined.Check else Icons.Outlined.BookmarkAdd,
                        // Naming the mode is the only way to tell "wanting everything"
                        // from "wanting new ones" once it is already on.
                        label = if (monitored) "Wanting: ${wantModeLabel(wantMode)}" else "Want",
                        accent = accent,
                        onClick = {
                            close()
                            showWantPicker = true
                        },
                    )
                }

                if (canRewatch) {
                    OverflowItem(
                        key = "rewatch",
                        icon = Icons.Outlined.RestartAlt,
                        label = "Rewatch from the start",
                        accent = accent,
                        onClick = {
                            close()
                            confirmRewatch = true
                        },
                    )
                }

                if (keep != null) KeepItem(keep, accent, close)
                if (kids != null) KidsAccessItem(kids, accent, close)
            }
        }
    }

    if (kids != null) KidsAccessDialog(kids, accent)

    if (confirmRewatch && api != null) {
        RewatchDialog(
            title = show.title,
            accent = accent,
            onDismiss = { confirmRewatch = false },
            onConfirm = {
                confirmRewatch = false
                actionScope.launch {
                    runCatching { api.setShowWatched(show.id, false) }
                        .onSuccess {
                            show.seasons.forEach { s -> s.episodes.forEach { ep -> ep.id?.let { watchedOverrides[it] = false } } }
                            onRestarted(show.restartPoint())
                            toast.show("Starting ${show.title} over")
                        }
                        .onFailure { toast.show("Could not reset your progress") }
                }
            },
        )
    }

    if (showWantPicker && api != null) {
        WantShowDialog(
            currentMode = wantMode,
            monitored = monitored,
            accent = accent,
            onDismiss = { showWantPicker = false },
            onPickMode = { mode ->
                showWantPicker = false
                val previous = wantMode
                wantMode = mode
                monitored = mode != "none"
                actionScope.launch {
                    runCatching { api.monitorShow(show.id, autoWantMode = mode, monitored = mode != "none") }
                        .onSuccess { toast.show("Wanting ${wantModeLabel(mode).lowercase()}") }
                        .onFailure {
                            wantMode = previous
                            monitored = previous != "none"
                            toast.show("Could not change what this show wants")
                        }
                }
            },
            onWantMissingOnce = {
                showWantPicker = false
                actionScope.launch {
                    runCatching { api.wantAllMissingEpisodes(show.id) }
                        .onSuccess { toast.show("Wanted every missing episode") }
                        .onFailure { toast.show("Could not want the missing episodes") }
                }
            },
        )
    }

    // The season strip is directly below, so a geometric search down out of the row
    // lands on whichever tab is nearest -- season one, which on a remote selects it too.
    DetailActionRow(
        wide = wide,
        modifier = modifier,
        exitDown = exitDown,
        primary = { fill -> playButton(fill) },
    ) {
        fromStartButton()
        nextButton()
        wantButton()
        overflowButton()
    }
}

@Composable
private fun RewatchDialog(title: String, accent: Color, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = KobappTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rewatch $title?", color = colors.textHi, style = KobappTheme.type.h3) },
        text = {
            Text(
                "Every episode goes back to unwatched and Resume starts again from the first episode.",
                color = colors.textMd,
                style = KobappTheme.type.body,
            )
        },
        confirmButton = {
            KobButton(
                text = "Start over",
                onClick = onConfirm,
                style = KobButtonStyle.Accent,
                accent = accent,
                modifier = Modifier.focusKey("rewatch.confirm", preferredEntry = true),
            )
        },
        dismissButton = {
            KobButton(text = "Cancel", onClick = onDismiss, style = KobButtonStyle.Ghost, modifier = Modifier.focusKey("rewatch.cancel"))
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}

/** Where a show picks up once everything is unwatched: the first episode outside the specials. */
internal fun TvShow.restartPoint(): ContinueEpisode? {
    val seasonIdx = seasons.indexOfFirst { it.number > 0 && it.episodes.isNotEmpty() }
        .takeIf { it >= 0 } ?: seasons.indexOfFirst { it.episodes.isNotEmpty() }.takeIf { it >= 0 } ?: return null
    val season = seasons[seasonIdx]
    val episodeIdx = season.episodes.indexOfFirst { it.filePath != null }.takeIf { it >= 0 } ?: 0
    val id = season.episodes[episodeIdx].id ?: return null
    return ContinueEpisode(seasonIdx = seasonIdx, episodeIdx = episodeIdx, episodeId = id)
}

internal fun remainingLabel(seconds: Int): String? {
    if (seconds < 60) return null
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
}

internal fun episodeTag(season: Int, episode: Int) = "S${season.pad2()} E${episode.pad2()}"

internal fun Int.pad2(): String = if (this < 10) "0$this" else this.toString()
