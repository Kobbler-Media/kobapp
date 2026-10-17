package dev.kobbler.app.ui.detail

import dev.kobbler.app.ui.components.spotlight

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import dev.kobbler.app.ui.components.CastRow
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.WatchProgressBar
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.detail.tvshow.EpisodeList
import dev.kobbler.app.ui.detail.tvshow.SeasonTabs
import dev.kobbler.kobfocus.LocalFocusScope
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.launch
import dev.kobbler.app.ui.kobTopInset
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.app.ui.player.resumeRequestSeconds

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
    val accent = colors.accTv
    val toast = rememberToastState()
    val gutter = gutterFor(formFactor)

    // The season you are part-way through beats the newest one you own: the action stack
    // offers Resume from it, and the tab strip has to agree with the button next to it.
    val initialSeasonIdx = remember(show) {
        show.continueEpisode?.seasonIdx?.takeIf { it in show.seasons.indices }
            ?: show.seasons.indexOfLast { s -> s.episodes.any { it.filePath != null } }
                .takeIf { it >= 0 }
            ?: 0
    }
    var selectedSeasonIdx by remember { mutableStateOf(initialSeasonIdx) }
    // `show` is a fetched snapshot, so marking an episode watched changes nothing it
    // reports — the action that did it stayed on screen offering itself again. One
    // override map per screen keeps the rows, the season action and the header count
    // telling the same story until the next fetch.
    val watchedOverrides = remember(show.id) { mutableStateMapOf<String, Boolean>() }
    // Same reason: wanting an episode enqueues a search, so nothing in `show` changes
    // and the action would offer itself again on the very next frame.
    val wantOverrides = remember(show.id) { mutableStateMapOf<String, Boolean>() }
    val selectedSeason = show.seasons.getOrNull(selectedSeasonIdx)
    val currentEpisodeId = show.continueEpisode?.episodeId

    val pageScroll = rememberScrollState()
    val pageTopHold = rememberPageTopHold(pageScroll)
    val pageScope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = detailStacked(formFactor, maxWidth)
        DetailBackdrop(show.spotlight(), accent, pageScroll)

        CompositionLocalProvider(LocalBringIntoViewSpec provides pageTopHold.spec) {
        Column(Modifier.fillMaxSize().scrollWhenFocusIsStuck(pageScroll).verticalScroll(pageScroll)) {
            Spacer(Modifier.height(kobTopInset + if (formFactor == FormFactor.TV) 56.dp else 72.dp))

            if (stacked) {
                PhoneLayout(
                    show = show,
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
                    onPersonClick = onPersonClick,
                    onPlayEpisode = onPlayEpisode,
                    onStreamEpisode = onStreamEpisode,
                    onStreamEpisodePick = onStreamEpisodePick,
                )
            } else {
                WideLayout(
                    show = show,
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
            show = show,
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
                TitleBlock(show.title, colors.textHi)
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
                ActionStack(
                    show = show,
                    accent = accent,
                    selectedSeason = selectedSeason,
                    selectedSeasonIdx = selectedSeasonIdx,
                    api = api,
                    watchedOverrides = watchedOverrides,
                    wantOverrides = wantOverrides,
                    toast = toast,
                    onPlayEpisode = onPlayEpisode,
                    horizontal = true,
                    exitDown = seasonEntry.takeIf { show.seasons.isNotEmpty() },
                    modifier = actionsAnchor,
                )
                if (api != null) ShowKidsAccess(show, api, accent, toast, Modifier.padding(top = 16.dp))
            }
        }

        // Full width, below the hero rather than beside it: as a column next to the
        // actions the episode rows kept their narrow measure all the way down the page,
        // clipping every synopsis against a screen of empty gutter.
        if (show.seasons.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            SeasonTabs(show.seasons, selectedSeasonIdx, accent, formFactor, onSeasonSelected, entryFocus = seasonEntry)
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
                TitleBlock(show.title, colors.textHi)
                Spacer(Modifier.height(8.dp))
                MetaRow(show)
            }
        }
        Spacer(Modifier.height(16.dp))
        ActionStack(
            show = show,
            accent = accent,
            selectedSeason = selectedSeason,
            selectedSeasonIdx = selectedSeasonIdx,
            api = api,
            watchedOverrides = watchedOverrides,
            wantOverrides = wantOverrides,
            toast = toast,
            onPlayEpisode = onPlayEpisode,
            horizontal = false,
            modifier = actionsAnchor,
        )
        if (api != null) ShowKidsAccess(show, api, accent, toast, Modifier.padding(top = 14.dp))
        if (show.synopsis != null) {
            Spacer(Modifier.height(18.dp))
            ExpandableText(show.synopsis)
        }
        if (show.seasons.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SeasonTabs(show.seasons, selectedSeasonIdx, accent, FormFactor.Phone, onSeasonSelected)
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
private fun TitleBlock(title: String, color: Color) {
    // The two-line split is a 10-foot/desktop flourish: it halves a title that already
    // wraps three times in a phone hero's 194dp column.
    val (head, tail) = if (LocalFormFactor.current == FormFactor.Phone) title.trim() to null else splitShowTitle(title)
    Column {
        SerifTitle(head, color, italic = false)
        if (tail != null) SerifTitle(tail, color, italic = true)
    }
}

@Composable
private fun SerifTitle(text: String, color: Color, italic: Boolean) {
    Text(
        text,
        style = KobappTheme.displayTitle.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            letterSpacing = (-0.03).em,
        ),
        color = color,
    )
}

@Composable
private fun MetaRow(show: TvShow) {
    val colors = KobappTheme.colors
    // Flow, not Row: on a phone these overflow, and a Row squeezed the last item until
    // it broke mid-word. Each separator travels with the item it precedes so a wrapped
    // line never opens with a dangling dot.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(show.year.toString(), style = KobappTheme.type.bodySm, color = colors.textMd)
        if (show.network != null) {
            MetaItem { Text(show.network, style = KobappTheme.type.bodySm, color = colors.textMd) }
        }
        if (show.rating != null) {
            MetaItem { RatingBadge(show.rating) }
        }
        if (show.contentRating != null) {
            MetaItem { Text(show.contentRating, style = KobappTheme.type.bodySm, color = colors.textMd) }
        }
        if (show.seasons.isNotEmpty()) {
            val total = show.seasons.sumOf { it.episodes.size }
            MetaItem {
                Text(
                    "${show.seasons.size} season${if (show.seasons.size != 1) "s" else ""} · $total ep",
                    style = KobappTheme.type.bodySm,
                    color = colors.textMd,
                )
            }
        }
        if (show.nextAiring != null) {
            MetaItem {
                Text("Next: ${show.nextAiring}", style = KobappTheme.type.bodySm, color = colors.textMd)
            }
        }
    }
}

@Composable
private fun MetaItem(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetaDot()
        content()
    }
}

@Composable
private fun ActionStack(
    show: TvShow,
    accent: Color,
    selectedSeason: Season?,
    selectedSeasonIdx: Int,
    api: KobblerApi?,
    watchedOverrides: SnapshotStateMap<String, Boolean>,
    wantOverrides: SnapshotStateMap<String, Boolean>,
    toast: ToastState,
    onPlayEpisode: (Int, Int, Boolean) -> Unit,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
    exitDown: FocusRequester? = null,
) {
    val colors = KobappTheme.colors
    val resume = show.continueEpisode
    val firstUnwatchedIdx = selectedSeason?.episodes?.indexOfFirst {
        it.filePath != null && !it.isWatchedNow(watchedOverrides)
    }?.takeIf { it >= 0 }

    // Beside the poster the buttons measure to their labels; stacked on a phone they
    // share one edge instead.
    val btn = if (horizontal) Modifier else Modifier.fillMaxWidth()
    // Marking a season watched removes the action that did it, so whatever focus it held
    // goes with it. Hand it back to the play control rather than let the page go dead.
    val playFocus = remember { FocusRequester() }
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

    val hasPlay = supportsVideoPlayback && (resume != null || firstUnwatchedIdx != null)
    val playButton: @Composable () -> Unit = {
        if (resume != null && supportsVideoPlayback) {
            val fg = if (resumeIsStream) colors.textHi else colors.bg
            KobButton(
                onClick = { onPlayEpisode(resume.seasonIdx, resume.episodeIdx, false) },
                style = if (resumeIsStream) KobButtonStyle.Default else KobButtonStyle.Accent,
                accent = accent,
                modifier = Modifier.focusKey("action.resume").focusRequester(playFocus).then(btn),
            ) {
                Icon(
                    if (resumeIsStream) Icons.Outlined.CloudDownload else Icons.Outlined.PlayArrow,
                    null,
                    tint = fg,
                    modifier = Modifier.size(20.dp),
                )
                val eNum = resumeEpisode?.number
                val label = buildString {
                    append(
                        when {
                            resumeIsStream -> "Stream"
                            canRestart -> "Resume"
                            else -> "Play"
                        },
                    )
                    if (resumeSeasonNumber != null && eNum != null) {
                        append(" · S${resumeSeasonNumber.pad2()} E${eNum.pad2()}")
                    }
                }
                Text(label, style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = fg)
            }
        } else if (firstUnwatchedIdx != null && supportsVideoPlayback) {
            KobButton(
                onClick = { onPlayEpisode(selectedSeasonIdx, firstUnwatchedIdx, false) },
                style = KobButtonStyle.Accent,
                accent = accent,
                modifier = Modifier.focusKey("action.play").focusRequester(playFocus).then(btn),
            ) {
                Icon(Icons.Outlined.PlayArrow, null, tint = colors.bg, modifier = Modifier.size(20.dp))
                Text("Play", style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.bg)
            }
        }
    }

    // The Resume button names the episode; this says how far into it you are. Streams
    // get it too — the position is the same fact whether the copy is local or debrid.
    val resumeProgress: @Composable () -> Unit = {
        if (resumePosition > 0 && resumeEpisode?.isWatchedNow(watchedOverrides) != true) {
            WatchProgressBar(
                positionSeconds = resumePosition,
                totalSeconds = resumeEpisode?.runtimeMinutes?.times(60),
                accent = accent,
            )
        }
    }

    val fromStartButton: @Composable () -> Unit = {
        if (resume != null && canRestart) {
            KobButton(
                onClick = { onPlayEpisode(resume.seasonIdx, resume.episodeIdx, true) },
                style = KobButtonStyle.Default,
                accent = accent,
                modifier = Modifier.focusKey("action.fromStart").then(btn),
            ) {
                Icon(Icons.Outlined.Replay, null, tint = colors.textHi, modifier = Modifier.size(18.dp))
                Text("From start", style = KobappTheme.type.body, color = colors.textHi)
            }
        }
    }

    // Streaming a copy and asking for the file are separate things — the episode the
    // page offers to stream is exactly the one worth offering to want.
    val wantableResume = resumeEpisode
        ?.takeIf { api != null && it.id != null && it.canBeWanted(api.canWant) && !it.isWantedNow(wantOverrides) }
    val hasWant = wantableResume != null
    val wantButton: @Composable () -> Unit = {
        val ep = wantableResume
        if (ep?.id != null && api != null) {
            KobButton(
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
                style = KobButtonStyle.Default,
                accent = accent,
                modifier = Modifier.focusKey("action.want").then(btn),
            ) {
                Icon(Icons.Outlined.BookmarkAdd, null, tint = colors.textHi, modifier = Modifier.size(18.dp))
                val label = buildString {
                    append("Want")
                    if (resumeSeasonNumber != null) {
                        append(" · S${resumeSeasonNumber.pad2()} E${ep.number.pad2()}")
                    }
                }
                Text(label, style = KobappTheme.type.body, color = colors.textHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    val nextIdx = resume?.let { r ->
        val eps = show.seasons.getOrNull(r.seasonIdx)?.episodes ?: return@let null
        val from = (r.episodeIdx + 1).coerceAtMost(eps.size)
        eps.subList(from, eps.size).indexOfFirst { it.filePath != null }
            .takeIf { it >= 0 }?.plus(from)
    }
    val hasNext = resume != null && nextIdx != null && supportsVideoPlayback
    val nextButton: @Composable () -> Unit = {
        if (resume != null && nextIdx != null && supportsVideoPlayback) {
            KobButton(
                onClick = { onPlayEpisode(resume.seasonIdx, nextIdx, false) },
                style = KobButtonStyle.Default,
                accent = accent,
                modifier = Modifier.focusKey("action.playNext").then(btn),
            ) {
                Icon(Icons.Outlined.SkipNext, null, tint = colors.textHi, modifier = Modifier.size(18.dp))
                Text(
                    "Play next episode",
                    style = KobappTheme.type.body,
                    color = colors.textHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    val canWatchlist = api != null && api.canWant
    // Held out here, not in the menu item: menu content is disposed on close, so state
    // remembered in there resets to the fetched snapshot and the entry offers "Add"
    // again on the very next open.
    var inList by remember(show.inWatchlist) { mutableStateOf(show.inWatchlist) }
    var showWantPicker by remember { mutableStateOf(false) }
    var wantMode by remember(show.autoWantMode) { mutableStateOf(show.autoWantMode) }
    val seasonToMark = selectedSeason?.takeIf { season ->
        api != null && season.episodes.any { it.id != null && !it.isWatchedNow(watchedOverrides) }
    }
    val seasonToWant = selectedSeason?.takeIf { season ->
        api != null && season.id != null &&
            season.episodes.any { it.canBeWanted(api.canWant) && !it.isWantedNow(wantOverrides) }
    }
    val canWantShow = api != null && api.canWant
    // Seeded from the show, not false: the entry used to appear only when unmonitored,
    // so starting false was harmless. It is always shown now and has to say which way
    // round it is.
    var monitored by remember(show.isMonitored) { mutableStateOf(show.isMonitored) }

    // None of these is why anyone opened the page, and as buttons beside Resume they
    // read as a wall rather than a choice. The season one also sat above the season
    // strip it acts on, so its menu entry names the season instead of assuming you know
    // which is selected.
    val hasOverflow = api != null && (seasonToMark != null || seasonToWant != null || canWantShow || canWatchlist)
    val overflowButton: @Composable () -> Unit = {
        if (api != null && hasOverflow) {
            OverflowActions(accent = accent) { close ->
                if (canWatchlist) {
                    OverflowItem(
                        key = "watchlist",
                        icon = if (inList) Icons.Outlined.PlaylistAddCheck else Icons.Outlined.PlaylistAdd,
                        label = if (inList) "Remove from your list" else "Add to your list",
                        accent = accent,
                        onClick = {
                            val next = !inList
                            inList = next
                            close()
                            actionScope.launch {
                                runCatching {
                                    if (next) api.addToWatchlist("show", show.id)
                                    else api.removeFromWatchlist("show", show.id)
                                }
                                    .onSuccess {
                                        toast.show(if (next) "Added to your list" else "Removed from your list")
                                    }
                                    .onFailure {
                                        inList = !next
                                        toast.show("Could not update your list")
                                    }
                            }
                        },
                    )
                }

                if (seasonToMark != null) {
                    OverflowItem(
                        key = "markSeasonWatched",
                        icon = Icons.Outlined.Check,
                        label = "Mark season ${seasonToMark.number} watched",
                        accent = accent,
                        onClick = {
                            close()
                            actionScope.launch {
                                try {
                                    seasonToMark.episodes.forEach { ep ->
                                        if (ep.id != null && !ep.isWatchedNow(watchedOverrides)) {
                                            runCatching { api.setEpisodeWatched(ep.id, true) }
                                                .onSuccess { watchedOverrides[ep.id] = true }
                                        }
                                    }
                                } finally {
                                    runCatching { playFocus.requestFocus() }
                                }
                            }
                        },
                    )
                }

                if (seasonToWant?.id != null) {
                    OverflowItem(
                        key = "wantSeason",
                        icon = Icons.Outlined.BookmarkAdd,
                        label = "Want season ${seasonToWant.number}",
                        accent = accent,
                        onClick = {
                            close()
                            val missing = seasonToWant.episodes.filter {
                                it.id != null && it.canBeWanted(api.canWant)
                            }
                            missing.forEach { wantOverrides[it.id!!] = true }
                            actionScope.launch {
                                runCatching { api.wantSeason(show.id, seasonToWant.id) }
                                    .onSuccess { toast.show("Season ${seasonToWant.number} added to wanted") }
                                    .onFailure {
                                        missing.forEach { wantOverrides.remove(it.id!!) }
                                        toast.show("Could not want that season")
                                    }
                            }
                        },
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
            }
        }
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

    if (horizontal) {
        // The season strip is directly below the whole block, so a geometric search down
        // out of it lands on whichever tab is nearest -- season one, which on a remote
        // selects it too. Redirect on exit rather than per button: exit fires only once
        // focus genuinely leaves, so a wrapped second row of buttons still steps through.
        val exit = if (exitDown != null) {
            Modifier.focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Down) exitDown.requestFocus()
                }
            }
        } else {
            Modifier
        }
        // Flow, not Row: at TV type sizes four labelled actions overrun the width left
        // beside the poster.
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusKeyGroup("actions")
                    .then(exit)
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                playButton()
                fromStartButton()
                wantButton()
                nextButton()
                overflowButton()
            }
            resumeProgress()
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth().focusKeyGroup("actions"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasPlay || hasOverflow) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasPlay) Box(Modifier.weight(1f)) { playButton() }
                    overflowButton()
                }
                resumeProgress()
            }
            if (canRestart) fromStartButton()
            if (hasWant) wantButton()
            if (hasNext) nextButton()
        }
    }
}

@Composable
private fun TopBar(
    show: TvShow,
    watchedOverrides: Map<String, Boolean>,
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
            .detailTopScrim()
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

private fun Int.pad2(): String = if (this < 10) "0$this" else this.toString()

private fun splitShowTitle(title: String): Pair<String, String?> {
    val trimmed = title.trim()
    if (trimmed.length < 18) return trimmed to null
    val mid = trimmed.length / 2
    val splitAt = (mid until trimmed.length).firstOrNull { trimmed[it] == ' ' }
        ?: (mid downTo 0).firstOrNull { trimmed[it] == ' ' }
        ?: return trimmed to null
    return trimmed.substring(0, splitAt) to trimmed.substring(splitAt + 1)
}


/** Watched state after any change this screen has made since the show was fetched. */
internal fun Episode.isWatchedNow(overrides: Map<String, Boolean>): Boolean =
    id?.let { overrides[it] } ?: isWatched

internal fun Episode.isWantedNow(overrides: Map<String, Boolean>): Boolean =
    id?.let { overrides[it] } ?: isWanted

/**
 * The "everything else" control at the end of the action row.
 *
 * [content] receives a close callback; an item that starts work is responsible for
 * dismissing the menu itself, so a slow request does not hold it open.
 */
@Composable
private fun OverflowActions(
    accent: Color,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val colors = KobappTheme.colors
    var open by remember { mutableStateOf(false) }
    val anchor = remember { FocusRequester() }
    // The menu is its own window: it swallows the remote's keys, but nothing in it holds
    // focus until asked. One requester shared by every item resolves to the first of
    // them, so the item list stays free to be conditional.
    val entry = remember { FocusRequester() }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        repeat(10) {
            if (runCatching { entry.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    val close = {
        open = false
        runCatching { anchor.requestFocus() }
        Unit
    }
    Box {
        KobButton(
            onClick = { open = true },
            style = KobButtonStyle.Default,
            accent = accent,
            modifier = Modifier.focusKey("action.more").focusRequester(anchor),
        ) {
            Icon(Icons.Outlined.MoreHoriz, "More actions", tint = colors.textHi, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = close,
            shape = KobappTheme.shapes.small,
            containerColor = colors.elev2,
            // Material's own surface treatment shows through as a pale outline behind the
            // panel on this theme; the app draws its own edges.
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, colors.line),
        ) {
            CompositionLocalProvider(LocalOverflowEntry provides entry) {
                content(close)
            }
        }
    }
}

private val LocalOverflowEntry = compositionLocalOf<FocusRequester?> { null }

@Composable
private fun OverflowItem(
    key: String,
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = KobappTheme.colors
    // A filled row rather than the ring the rest of the app uses: the ring is drawn
    // outside the element's bounds and a menu row has none to spare — it escaped the
    // panel as a stray white outline.
    var focused by remember { mutableStateOf(false) }
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = KobappTheme.type.body,
                color = if (enabled) colors.textHi else accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(icon, null, tint = if (enabled) colors.textMd else accent, modifier = Modifier.size(18.dp))
        },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .focusKey("action.more.$key")
            .then(LocalOverflowEntry.current?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) accent.copy(alpha = 0.18f) else Color.Transparent),
    )
}
