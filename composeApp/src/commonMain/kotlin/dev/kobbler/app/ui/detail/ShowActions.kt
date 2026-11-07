package dev.kobbler.app.ui.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.offline.downloadShow
import dev.kobbler.app.offline.setAutoDownload
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.remainingLabel
import dev.kobbler.app.ui.detail.actions.EpisodeRef
import dev.kobbler.app.ui.detail.actions.ShowAction
import dev.kobbler.app.ui.detail.actions.ShowActionState
import dev.kobbler.app.ui.detail.actions.episodeAt
import dev.kobbler.app.ui.detail.actions.firstEpisode
import dev.kobbler.app.ui.detail.actions.showActions
import dev.kobbler.app.ui.offline.LocalOfflineEngine
import dev.kobbler.app.ui.offline.episodesPlayableOffline
import dev.kobbler.app.ui.offline.rememberShowDownloads
import dev.kobbler.app.ui.player.KeepAction
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.launch

@Composable
internal fun ShowActions(
    show: TvShow,
    accent: Color,
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
    // Owned out here, not inside the menu: an item closes the menu before its request
    // finishes, and menu content is disposed on close -- a scope remembered in there
    // cancels the work that just started.
    val actionScope = rememberCoroutineScope()
    // Held out here, not in the action: menu content is disposed on close, so state
    // remembered in there resets to the fetched snapshot and offers "Add" again.
    var inList by remember(show.inWatchlist) { mutableStateOf(show.inWatchlist) }
    var showWantPicker by remember { mutableStateOf(false) }
    var confirmRewatch by remember { mutableStateOf(false) }
    var wantMode by remember(show.autoWantMode) { mutableStateOf(show.autoWantMode) }
    var monitored by remember(show.isMonitored) { mutableStateOf(show.isMonitored) }
    val kids = rememberShowKidsAccess(show, api, toast)
    val engine = LocalOfflineEngine.current
    val downloads = rememberShowDownloads(show)

    val plan = showActions(
        ShowActionState(
            show = show,
            selectedSeasonIdx = selectedSeasonIdx,
            watched = watchedOverrides,
            wanted = wantOverrides,
            canPlayVideo = supportsVideoPlayback,
            onDevice = episodesPlayableOffline(),
            connected = api != null,
            canWant = api?.canWant == true,
            downloads = downloads,
            canKeep = keep != null,
            canManageKids = kids != null,
        ),
    )
    val tag: (EpisodeRef) -> String? = { at ->
        show.seasons.getOrNull(at.seasonIdx)?.let { season -> show.episodeAt(at)?.let { episodeTag(season.number, it.number) } }
    }
    val play: (EpisodeRef, Boolean) -> () -> Unit = { at, fromStart -> { onPlayEpisode(at.seasonIdx, at.episodeIdx, fromStart) } }
    val startOver: () -> Unit = {
        if (api != null) actionScope.launch {
            runCatching { api.setShowWatched(show.id, false) }
                .onSuccess {
                    show.seasons.forEach { s -> s.episodes.forEach { ep -> ep.id?.let { watchedOverrides[it] = false } } }
                    onRestarted(show.restartPoint())
                    toast.show("Starting ${show.title} over")
                }
                .onFailure { toast.show("Could not reset your progress") }
        }
    }

    val resumeAction: (String, ImageVector, EpisodeRef, Int) -> RowAction = { key, icon, at, position ->
        val total = show.episodeAt(at)?.runtimeMinutes?.times(60)
        RowAction(
            key = key, label = "Resume", icon = icon, onClick = play(at, false),
            detail = tag(at),
            progress = total?.takeIf { it > 0 }?.let { position.toFloat() / it },
            remaining = total?.let { remainingLabel(it - position) },
        )
    }

    val actions = plan.map { action ->
        when (action) {
            is ShowAction.Resume -> resumeAction("action.resume", Icons.Outlined.PlayArrow, action.at, action.positionSeconds)
            // The server offers an episode with no file when the show is one being streamed
            // rather than collected: a debrid search on tap, so it keeps the stream glyph even
            // when it picks up where the viewer stopped.
            is ShowAction.Stream ->
                if (action.positionSeconds > 0) resumeAction("action.stream", Icons.Outlined.CloudDownload, action.at, action.positionSeconds)
                else RowAction("action.stream", "Stream", Icons.Outlined.CloudDownload, play(action.at, false), detail = tag(action.at))
            is ShowAction.Play ->
                RowAction("action.play", "Play", Icons.Outlined.PlayArrow, play(action.at, false), detail = tag(action.at))
            // One tap, no confirm: the button says what it does, and next-up is the first
            // unwatched episode after the last watched one, so replaying the first episode
            // of a show left watched would never move on from it.
            is ShowAction.Rewatch -> RowAction(
                key = "action.rewatch", label = "Rewatch", icon = Icons.Outlined.Replay, detail = tag(action.at),
                onClick = {
                    startOver()
                    play(action.at, true)()
                },
            )
            is ShowAction.NextEpisode ->
                RowAction("action.playNext", "Next episode", Icons.Outlined.SkipNext, play(action.at, false), detail = tag(action.at))
            is ShowAction.FromStart ->
                RowAction("action.fromStart", "From start", Icons.Outlined.Replay, play(action.at, true))
            is ShowAction.WantEpisode -> RowAction(
                key = "action.want",
                label = listOfNotNull("Want", tag(action.at)).joinToString(" "),
                icon = Icons.Outlined.Download,
                onClick = {
                    // Optimistic: the request only enqueues a search, so `show` reports the
                    // same thing back and the action would re-offer itself.
                    wantOverrides[action.episodeId] = true
                    actionScope.launch {
                        runCatching { api?.wantEpisode(action.episodeId) }
                            .onSuccess { toast.show("Added to wanted") }
                            .onFailure {
                                wantOverrides.remove(action.episodeId)
                                toast.show("Could not want that episode")
                            }
                    }
                },
            )
            ShowAction.DownloadAll ->
                RowAction("downloadShow", "Save all episodes offline", Icons.Outlined.DownloadForOffline, { engine?.downloadShow(show) })
            ShowAction.AutoDownload -> {
                val following = downloads?.following == true
                RowAction(
                    "autoDownload", "Save new episodes offline", Icons.Outlined.SystemUpdateAlt,
                    { engine?.setAutoDownload(show, !following) }, selected = following,
                )
            }
            ShowAction.StartOver ->
                RowAction("rewatch", "Rewatch from the start", Icons.Outlined.RestartAlt, { confirmRewatch = true })
            ShowAction.WantShow -> RowAction(
                key = "monitor",
                // Naming the mode is the only way to tell "wanting everything" from "wanting
                // new ones" once it is already on.
                label = if (monitored) "Wanting: ${wantModeLabel(wantMode)}" else "Want",
                icon = if (monitored) Icons.Outlined.Check else Icons.Outlined.Download,
                selected = monitored,
                onClick = { showWantPicker = true },
            )
            ShowAction.Watchlist -> watchlistAction(
                inList = inList,
                toast = toast,
                scope = actionScope,
                onChanged = { inList = it },
                toggle = { on -> if (on) api?.addToWatchlist("show", show.id) else api?.removeFromWatchlist("show", show.id) },
            )
            ShowAction.Keep -> keepAction(keep!!)
            ShowAction.Kids -> kidsAction(kids!!)
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
                startOver()
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

    DetailActionRow(actions = actions, accent = accent, wide = wide, modifier = modifier, exitDown = exitDown)
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

internal fun TvShow.restartPoint(): ContinueEpisode? {
    val at = firstEpisode { it.filePath != null } ?: firstEpisode { true } ?: return null
    val id = episodeAt(at)?.id ?: return null
    return ContinueEpisode(seasonIdx = at.seasonIdx, episodeIdx = at.episodeIdx, episodeId = id)
}

internal fun episodeTag(season: Int, episode: Int) = "S${season.pad2()} E${episode.pad2()}"

internal fun Int.pad2(): String = if (this < 10) "0$this" else this.toString()
