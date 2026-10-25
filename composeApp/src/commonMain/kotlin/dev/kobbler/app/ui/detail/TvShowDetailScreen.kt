package dev.kobbler.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.tvshow.episodeList
import dev.kobbler.app.ui.player.KeepAction

@Composable
fun TvShowDetailScreen(
    show: TvShow,
    formFactor: FormFactor,
    onBack: () -> Unit,
    onPlayEpisode: (seasonIdx: Int, episodeIdx: Int, fromStart: Boolean) -> Unit,
    api: KobblerApi? = null,
    modifier: Modifier = Modifier,
    onPersonClick: ((String) -> Unit)? = null,
    onStreamEpisode: ((seasonIdx: Int, episodeIdx: Int) -> Unit)? = null,
    onStreamEpisodePick: ((seasonIdx: Int, episodeIdx: Int) -> Unit)? = null,
    keep: KeepAction? = null,
    initialSeasonId: String? = null,
) {
    val colors = KobappTheme.colors
    // `show` is a fetched snapshot. Starting a show over moves Resume back to its first
    // episode, which the snapshot cannot know until the next fetch replaces it.
    val fetched = show
    var restartFrom by remember(fetched) { mutableStateOf<ContinueEpisode?>(null) }
    val shown = restartFrom?.let { fetched.copy(continueEpisode = it) } ?: fetched
    val accent = colors.accTv
    val toast = rememberToastState()

    // The season you are part-way through beats the newest one you own: the action stack
    // offers Resume from it, and the tab strip has to agree with the button next to it.
    val initialSeasonIdx = remember(fetched) {
        fetched.seasons.indexOfFirst { it.id != null && it.id == initialSeasonId }.takeIf { it >= 0 }
            ?: fetched.continueEpisode?.seasonIdx?.takeIf { it in fetched.seasons.indices }
            ?: fetched.seasons.indexOfLast { s -> s.episodes.any { it.filePath != null } }
                .takeIf { it >= 0 }
            ?: 0
    }
    var selectedSeasonIdx by remember { mutableStateOf(initialSeasonIdx) }
    // `show` is a fetched snapshot, so marking an episode watched changes nothing it
    // reports — the action that did it stayed on screen offering itself again. One
    // override map per screen keeps the rows, the season action and the header count
    // telling the same story until the next fetch.
    val watchedOverrides = remember(fetched.id) { mutableStateMapOf<String, Boolean>() }
    // Same reason: wanting an episode enqueues a search, so nothing in `show` changes
    // and the action would offer itself again on the very next frame.
    val wantOverrides = remember(fetched.id) { mutableStateMapOf<String, Boolean>() }
    val selectedSeason = shown.seasons.getOrNull(selectedSeasonIdx)
    val onRestarted: (ContinueEpisode?) -> Unit = { point ->
        restartFrom = point
        if (point != null) selectedSeasonIdx = point.seasonIdx
    }

    ShowDetailLayout(
        show = shown,
        episodeTotal = shown.seasons.sumOf { it.episodes.size },
        formFactor = formFactor,
        accent = accent,
        selectedSeasonIdx = selectedSeasonIdx,
        onSeasonSelected = { selectedSeasonIdx = it },
        onBack = onBack,
        onPersonClick = onPersonClick,
        modifier = modifier,
        actions = { wide, anchor, exitDown ->
            ShowActions(
                show = shown,
                accent = accent,
                selectedSeason = selectedSeason,
                selectedSeasonIdx = selectedSeasonIdx,
                api = api,
                watchedOverrides = watchedOverrides,
                wantOverrides = wantOverrides,
                toast = toast,
                onPlayEpisode = onPlayEpisode,
                onRestarted = onRestarted,
                wide = wide,
                exitDown = exitDown,
                modifier = anchor,
                keep = keep,
            )
        },
        seasonMenu = { fallback -> seasonMenu(shown, selectedSeason, api, watchedOverrides, wantOverrides, toast, accent, fallbackFocus = fallback) },
        episodes = { seasonEntry ->
            episodeList(
                season = selectedSeason,
                seasonIdx = selectedSeasonIdx,
                accent = accent,
                currentEpisodeId = shown.continueEpisode?.episodeId,
                api = api,
                watchedOverrides = watchedOverrides,
                wantOverrides = wantOverrides,
                onPlayEpisode = onPlayEpisode,
                onStreamEpisode = onStreamEpisode,
                onStreamEpisodePick = onStreamEpisodePick,
                seasonEntry = seasonEntry,
            )
        },
        topBarStatus = { WatchedCount(shown, watchedOverrides) },
        overlay = { ToastOverlay(toast, Modifier.align(Alignment.BottomCenter)) },
    )
}

@Composable
private fun WatchedCount(show: TvShow, watchedOverrides: Map<String, Boolean>) {
    val total = show.seasons.sumOf { it.episodes.size }
    if (total == 0) return
    val watched = show.seasons.sumOf { s -> s.episodes.count { it.isWatchedNow(watchedOverrides) } }
    TopBarStatus("$watched / $total watched", if (watched == total) KobappTheme.colors.ok else KobappTheme.colors.textLo)
}

/** Watched state after any change this screen has made since the show was fetched. */
internal fun Episode.isWatchedNow(overrides: Map<String, Boolean>): Boolean =
    id?.let { overrides[it] } ?: isWatched

internal fun Episode.isWantedNow(overrides: Map<String, Boolean>): Boolean =
    id?.let { overrides[it] } ?: isWanted
