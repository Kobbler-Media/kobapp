package dev.kobbler.app.ui.detail

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.kobbler.app.ui.components.spotlight

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.CastRow
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.detail.tvshow.EpisodeList
import dev.kobbler.app.ui.detail.tvshow.SeasonTabs
import dev.kobbler.kobfocus.LocalFocusScope
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.launch
import dev.kobbler.app.ui.kobTopInset
import dev.kobbler.app.ui.safeTopPadding

@OptIn(ExperimentalFoundationApi::class)
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
) {
    val colors = KobappTheme.colors
    // `show` is a fetched snapshot. Starting a show over moves Resume back to its first
    // episode, which the snapshot cannot know until the next fetch replaces it.
    val fetched = show
    var restartFrom by remember(fetched) { mutableStateOf<ContinueEpisode?>(null) }
    val shown = restartFrom?.let { fetched.copy(continueEpisode = it) } ?: fetched
    val accent = colors.accTv
    val toast = rememberToastState()
    val gutter = gutterFor(formFactor)

    // The season you are part-way through beats the newest one you own: the action stack
    // offers Resume from it, and the tab strip has to agree with the button next to it.
    val initialSeasonIdx = remember(fetched) {
        fetched.continueEpisode?.seasonIdx?.takeIf { it in fetched.seasons.indices }
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
    val currentEpisodeId = shown.continueEpisode?.episodeId

    val pageScroll = rememberScrollState()
    val pageTopHold = rememberPageTopHold(pageScroll)
    val pageScope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = detailStacked(formFactor, maxWidth)
        DetailBackdrop(shown.spotlight(), accent, pageScroll)

        CompositionLocalProvider(LocalBringIntoViewSpec provides pageTopHold.spec) {
        Column(Modifier.fillMaxSize().scrollWhenFocusIsStuck(pageScroll).verticalScroll(pageScroll)) {
            Spacer(Modifier.height(kobTopInset + if (formFactor == FormFactor.TV) 56.dp else 72.dp))

            if (stacked) {
                PhoneLayout(
                    show = shown,
                    accent = accent,
                    gutter = gutter,
                    api = api,
                    watchedOverrides = watchedOverrides,
                    wantOverrides = wantOverrides,
                    toast = toast,
                    actionsAnchor = pageTopHold.anchor,
                    selectedSeasonIdx = selectedSeasonIdx,
                    selectedSeason = selectedSeason,
                    currentEpisodeId = currentEpisodeId,
                    onSeasonSelected = { selectedSeasonIdx = it },
                    onRestarted = onRestarted,
                    onPersonClick = onPersonClick,
                    onPlayEpisode = onPlayEpisode,
                    onStreamEpisode = onStreamEpisode,
                    onStreamEpisodePick = onStreamEpisodePick,
                )
            } else {
                WideLayout(
                    show = shown,
                    accent = accent,
                    gutter = gutter,
                    formFactor = formFactor,
                    api = api,
                    watchedOverrides = watchedOverrides,
                    wantOverrides = wantOverrides,
                    toast = toast,
                    actionsAnchor = pageTopHold.anchor,
                    selectedSeasonIdx = selectedSeasonIdx,
                    selectedSeason = selectedSeason,
                    currentEpisodeId = currentEpisodeId,
                    onSeasonSelected = { selectedSeasonIdx = it },
                    onRestarted = onRestarted,
                    onPersonClick = onPersonClick,
                    onPlayEpisode = onPlayEpisode,
                    onStreamEpisode = onStreamEpisode,
                    onStreamEpisodePick = onStreamEpisodePick,
                )
            }

            Spacer(Modifier.height(64.dp))
        }
        }

        TopBar(
            scroll = pageScroll,
            show = shown,
            watchedOverrides = watchedOverrides,
            formFactor = formFactor,
            gutter = gutter,
            accent = accent,
            modifier = Modifier.pageTopOverlay(pageTopHold),
            onBack = onBack,
            // The bar is pinned over the scrolling body, so focusing it while the page
            // is scrolled would otherwise leave the title stranded off-screen.
            onBackFocused = { pageScope.launch { pageScroll.animateScrollTo(0) } },
        )

        ToastOverlay(toast, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun WideLayout(
    show: TvShow,
    accent: Color,
    gutter: Dp,
    watchedOverrides: SnapshotStateMap<String, Boolean>,
    wantOverrides: SnapshotStateMap<String, Boolean>,
    formFactor: FormFactor,
    api: KobblerApi?,
    toast: ToastState,
    actionsAnchor: Modifier,
    selectedSeasonIdx: Int,
    selectedSeason: Season?,
    currentEpisodeId: String?,
    onSeasonSelected: (Int) -> Unit,
    onRestarted: (ContinueEpisode?) -> Unit,
    onPersonClick: ((String) -> Unit)?,
    onPlayEpisode: (Int, Int, Boolean) -> Unit,
    onStreamEpisode: ((Int, Int) -> Unit)?,
    onStreamEpisodePick: ((Int, Int) -> Unit)?,
) {
    val colors = KobappTheme.colors
    val seasonEntry = remember { FocusRequester() }
    val posterWidth = when (formFactor) {
        FormFactor.TV -> 200.dp
        FormFactor.Phone -> 160.dp
        FormFactor.Desktop -> 280.dp
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DetailPoster(show.posterUrl, accent, Modifier.width(posterWidth))

            Column(modifier = Modifier.weight(1f)) {
                DetailTitle(show.title, show.year)
                Spacer(Modifier.height(14.dp))
                MetaRow(show)
                if (show.synopsis != null) {
                    Spacer(Modifier.height(16.dp))
                    // Capped where the episode list is not: a synopsis set across a
                    // 1600dp window is one long line the eye loses its place in.
                    Text(
                        show.synopsis,
                        style = KobappTheme.type.body,
                        color = colors.textMd,
                        modifier = Modifier.widthIn(max = 900.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                ShowActions(
                    show = show,
                    accent = accent,
                    selectedSeason = selectedSeason,
                    selectedSeasonIdx = selectedSeasonIdx,
                    api = api,
                    watchedOverrides = watchedOverrides,
                    wantOverrides = wantOverrides,
                    toast = toast,
                    onPlayEpisode = onPlayEpisode,
                    onRestarted = onRestarted,
                    wide = true,
                    exitDown = seasonEntry.takeIf { show.seasons.isNotEmpty() },
                    modifier = actionsAnchor,
                )
            }
        }

        // Full width, below the hero rather than beside it: as a column next to the
        // actions the episode rows kept their narrow measure all the way down the page,
        // clipping every synopsis against a screen of empty gutter.
        if (show.seasons.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            SeasonTabs(
                show.seasons, selectedSeasonIdx, accent, formFactor, onSeasonSelected, entryFocus = seasonEntry,
                menu = seasonMenu(show, selectedSeason, api, watchedOverrides, wantOverrides, toast, accent, fallbackFocus = seasonEntry),
            )
            Spacer(Modifier.height(8.dp))
            EpisodeList(
                season = selectedSeason,
                seasonIdx = selectedSeasonIdx,
                accent = accent,
                currentEpisodeId = currentEpisodeId,
                api = api,
                watchedOverrides = watchedOverrides,
                wantOverrides = wantOverrides,
                onPlayEpisode = onPlayEpisode,
                onStreamEpisode = onStreamEpisode,
                onStreamEpisodePick = onStreamEpisodePick,
                seasonEntry = seasonEntry,
            )
        }

        if (show.cast.isNotEmpty()) {
            Spacer(Modifier.height(36.dp))
            CastRow(show.cast, onPersonClick = onPersonClick)
        }
    }
}

@Composable
private fun PhoneLayout(
    show: TvShow,
    accent: Color,
    gutter: Dp,
    watchedOverrides: SnapshotStateMap<String, Boolean>,
    wantOverrides: SnapshotStateMap<String, Boolean>,
    api: KobblerApi?,
    toast: ToastState,
    actionsAnchor: Modifier,
    selectedSeasonIdx: Int,
    selectedSeason: Season?,
    currentEpisodeId: String?,
    onSeasonSelected: (Int) -> Unit,
    onRestarted: (ContinueEpisode?) -> Unit,
    onPersonClick: ((String) -> Unit)?,
    onPlayEpisode: (Int, Int, Boolean) -> Unit,
    onStreamEpisode: ((Int, Int) -> Unit)?,
    onStreamEpisodePick: ((Int, Int) -> Unit)?,
) {
    val colors = KobappTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            DetailPoster(show.posterUrl, accent, Modifier.width(120.dp))
            Column(modifier = Modifier.weight(1f)) {
                DetailTitle(show.title, show.year)
                Spacer(Modifier.height(8.dp))
                MetaRow(show)
            }
        }
        Spacer(Modifier.height(16.dp))
        ShowActions(
            show = show,
            accent = accent,
            selectedSeason = selectedSeason,
            selectedSeasonIdx = selectedSeasonIdx,
            api = api,
            watchedOverrides = watchedOverrides,
            wantOverrides = wantOverrides,
            toast = toast,
            onPlayEpisode = onPlayEpisode,
            onRestarted = onRestarted,
            wide = false,
            modifier = actionsAnchor,
        )
        if (show.synopsis != null) {
            Spacer(Modifier.height(18.dp))
            ExpandableText(show.synopsis)
        }
        if (show.seasons.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SeasonTabs(
                show.seasons, selectedSeasonIdx, accent, FormFactor.Phone, onSeasonSelected,
                menu = seasonMenu(show, selectedSeason, api, watchedOverrides, wantOverrides, toast, accent),
            )
            Spacer(Modifier.height(6.dp))
            EpisodeList(
                season = selectedSeason,
                seasonIdx = selectedSeasonIdx,
                accent = accent,
                currentEpisodeId = currentEpisodeId,
                api = api,
                watchedOverrides = watchedOverrides,
                wantOverrides = wantOverrides,
                onPlayEpisode = onPlayEpisode,
                onStreamEpisode = onStreamEpisode,
                onStreamEpisodePick = onStreamEpisodePick,
            )
        }
        if (show.cast.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            CastRow(show.cast, onPersonClick = onPersonClick)
        }
    }
}

@Composable
private fun MetaRow(show: TvShow) {
    val total = show.seasons.sumOf { it.episodes.size }
    DetailMeta(
        metaText(show.network),
        metaRating(show.rating),
        metaText(show.contentRating),
        metaText(show.seasons.size.takeIf { it > 0 }?.let { n -> "$n season${if (n != 1) "s" else ""} · $total ep" }),
        metaText(show.nextAiring?.let { "Next: $it" }),
    )
}

@Composable
private fun TopBar(
    show: TvShow,
    watchedOverrides: Map<String, Boolean>,
    scroll: ScrollState,
    formFactor: FormFactor,
    gutter: Dp,
    accent: Color,
    onBack: () -> Unit,
    onBackFocused: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .detailTopScrim(scroll)
            .safeTopPadding()
            .padding(start = gutter, end = gutter, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Not on TV: nothing in the D-pad graph reaches this pinned bar, and the
        // remote's BACK already unwinds the detail destination.
        if (formFactor != FormFactor.TV) {
            val contentScope = LocalFocusScope.current
            val barScope = rememberCoroutineScope()
            CircleBackButton(
                onBack = onBack,
                accent = accent,
                modifier = Modifier
                    .focusKey("action.back")
                    .onFocusChanged { if (it.isFocused) onBackFocused() }
                    // The body is fillMaxSize and so overlaps this pinned bar rather
                    // than sitting below it, which makes a Down focus search find no
                    // candidate at all. Hand focus back to the content explicitly.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            barScope.launch { contentScope.focusFirst() }
                            true
                        } else {
                            false
                        }
                    },
            )
        }

        if (formFactor != FormFactor.Phone) {
            val crumb = buildString {
                append("TV · ${show.title}")
                if (show.year != 0) append(" · ${show.year}")
            }
            Text(
                crumb.uppercase(),
                style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val total = show.seasons.sumOf { it.episodes.size }
            val watchedTotal = show.seasons.sumOf { s -> s.episodes.count { it.isWatchedNow(watchedOverrides) } }
            if (total > 0) {
                Text(
                    "$watchedTotal / $total watched".uppercase(),
                    style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
                    color = if (watchedTotal == total) colors.ok else colors.textLo,
                )
            }
        }
    }
}



/** Watched state after any change this screen has made since the show was fetched. */
internal fun Episode.isWatchedNow(overrides: Map<String, Boolean>): Boolean =
    id?.let { overrides[it] } ?: isWatched

internal fun Episode.isWantedNow(overrides: Map<String, Boolean>): Boolean =
    id?.let { overrides[it] } ?: isWanted
