package dev.kobbler.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.CastRow
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.components.PillStatus
import dev.kobbler.app.ui.components.StatusPill
import dev.kobbler.app.ui.components.rememberPalette
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.WarmStream
import dev.kobbler.app.data.StreamTarget
import dev.kobbler.app.ui.player.isInstant
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.launch

@Composable
fun DiscoverDetailScreen(
    detail: DiscoverDetail,
    api: KobblerApi,
    formFactor: FormFactor,
    player: Player?,
    gps: GlobalPlayerState,
    onBack: () -> Unit,
    onViewInLibrary: (mediaType: String, libraryId: String) -> Unit,
    modifier: Modifier = Modifier,
    refusals: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val colors = KobappTheme.colors
    val accent = when (detail.mediaType) {
        "tv" -> colors.accTv
        "movie" -> colors.accMovies
        else -> colors.accWanted
    }
    var currentDetail by remember(detail) { mutableStateOf(detail) }
    var wantLoading by remember { mutableStateOf(false) }
    var showWantPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Route on libraryId, not isInLibrary: a wanted title with nothing downloaded has a
    // library row but no files, and the nav layer already sends it there. Keyed on the
    // current one rather than the fetched one so a want lands on the same page — the
    // button that did it is replaced by a status pill nothing can focus, which on a
    // remote leaves the page dead.
    LaunchedEffect(currentDetail.libraryId) {
        currentDetail.libraryId?.let { onViewInLibrary(currentDetail.mediaType, it) }
    }
    if (detail.libraryId != null) return

    val canStreamMedia = api.canStream && supportsVideoPlayback &&
        currentDetail.mediaType in listOf("movie", "tv")
    val streams = rememberDiscoverStreams(currentDetail, api, gps, player, onViewInLibrary)

    LaunchedEffect(currentDetail.providerId) {
        if (canStreamMedia && currentDetail.mediaType == "movie") streams.prefetch(null)
    }
    if (currentDetail.mediaType == "movie") {
        WarmStream(gps, api, StreamTarget.discover(currentDetail, null).takeIf { canStreamMedia && player != null })
    }

    // A show is asked for the same way as one already in the library: how much of it to
    // want is the question, and "monitor" — which collects nothing that has already aired
    // — was the only answer this page could give.
    val onWant: (String) -> Unit = { autoWantMode ->
        wantLoading = true
        showWantPicker = false
        scope.launch {
            try {
                val newId = when (currentDetail.mediaType) {
                    "movie" -> api.wantMovie(currentDetail)
                    "tv" -> api.wantTvShow(currentDetail, autoWantMode)
                    "book" -> api.wantBook(currentDetail)
                    else -> null
                }
                if (newId != null) {
                    currentDetail = currentDetail.copy(libraryId = newId, wantStatus = "wanted")
                }
            } catch (_: Exception) {
            } finally {
                wantLoading = false
            }
        }
    }

    // A show is a question — how much of it — and everything else is a single answer.
    val onWantPressed: () -> Unit = {
        if (currentDetail.mediaType == "tv") showWantPicker = true else onWant("new_only")
    }

    val actions: @Composable (Boolean, Modifier, FocusRequester?, DiscoverStreamAction?) -> Unit = { wide, anchor, exitDown, stream ->
        DiscoverActions(
            detail = currentDetail,
            accent = accent,
            stream = stream,
            wantLoading = wantLoading,
            showWant = api.canWant,
            onWant = onWantPressed,
            wide = wide,
            exitDown = exitDown,
            modifier = anchor,
            refusals = refusals,
        )
    }

    Box(modifier.fillMaxSize()) {
        when (currentDetail.mediaType) {
            "tv" -> DiscoverShowPage(
                detail = currentDetail,
                api = api,
                formFactor = formFactor,
                accent = accent,
                onBack = onBack,
                onStreamEpisode = if (canStreamMedia) { ref -> streams.play(ref) } else null,
                onStreamEpisodePick = if (canStreamMedia) { ref -> streams.pick(ref) } else null,
                actions = { wide, anchor, exitDown, target ->
                    WarmStream(gps, api, target?.takeIf { canStreamMedia && player != null }?.let { StreamTarget.discover(currentDetail, it) })
                    val stream = target?.takeIf { canStreamMedia }?.let {
                        DiscoverStreamAction("Stream", episodeTag(it.season, it.episode), { streams.play(it) }, { streams.pick(it) })
                    }
                    actions(wide, anchor, exitDown, stream)
                },
                modifier = Modifier.focusInert(streams.showOptions),
            )
            "movie" -> MovieDetailLayout(
                movie = currentDetail.asMovie(),
                formFactor = formFactor,
                accent = accent,
                onBack = onBack,
                onPersonClick = null,
                actions = { wide, anchor ->
                    val stream = if (canStreamMedia) DiscoverStreamAction("Play", null, { streams.play(null) }, { streams.pick(null) }) else null
                    actions(wide, anchor, null, stream)
                },
                topBarStatus = { DiscoverStatus(currentDetail.libraryStatus) },
                aside = { wide ->
                    val streaming = streamingAvailability(
                        canStream = canStreamMedia,
                        searching = streams.loading,
                        found = streams.result?.options?.map { it.isInstant },
                    )
                    ReleaseAvailabilityStrip(currentDetail.availability(streaming), accent)
                    if (wide) DiscoverDetailsPanel(currentDetail, formFactor)
                },
                modifier = Modifier.focusInert(streams.showOptions),
            )
            else -> DiscoverHeroPage(
                detail = currentDetail,
                formFactor = formFactor,
                accent = accent,
                inert = streams.showOptions,
                onBack = onBack,
                actions = { wide, anchor -> actions(wide, anchor, null, null) },
            )
        }

        if (showWantPicker) {
            WantShowDialog(
                currentMode = "none",
                monitored = false,
                accent = accent,
                onDismiss = { showWantPicker = false },
                onPickMode = onWant,
                // A show that is not here yet cannot have gaps, and its episodes do not
                // exist at the moment of asking — the want that survives that wait is a
                // mode on the show, which enrichment applies when they land.
                onWantMissingOnce = null,
            )
        }

        if (streams.showOptions) {
            StreamOptionsOverlay(
                options = streams.result?.options ?: emptyList(),
                errors = streams.result?.errors ?: emptyList(),
                loading = streams.loading,
                onSelect = streams::playPicked,
                onDismiss = { streams.showOptions = false },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverHeroPage(
    detail: DiscoverDetail,
    formFactor: FormFactor,
    accent: Color,
    inert: Boolean,
    onBack: () -> Unit,
    actions: @Composable (wide: Boolean, anchor: Modifier) -> Unit,
) {
    val colors = KobappTheme.colors
    val palette = rememberPalette(detail.posterUrl)
    val gutter = gutterFor(formFactor)
    val pageScroll = rememberScrollState()
    val pageTopHold = rememberPageTopHold(pageScroll.asPageScroll())
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = detailStacked(formFactor, maxWidth)
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageTopHold.spec) {
        Column(
            Modifier
                .fillMaxSize()
                .focusInert(inert)
                .scrollWhenFocusIsStuck(pageScroll.asPageScroll())
                .verticalScroll(pageScroll),
        ) {
            DetailHero(
                backdropUrl = detail.backdropUrl,
                posterUrl = detail.posterUrl,
                formFactor = formFactor,
                accentTint = palette.darkAccent,
                accent = accent,
            ) {
                HeroCrumb(detail)
                Spacer(Modifier.height(8.dp))
                DiscoverTitleBlock(detail.title, colors.textHi)
            }

            if (stacked) {
                PhoneBody(detail = detail, accent = accent, gutter = gutter, actions = { actions(false, Modifier) })
            } else {
                WideBody(
                    detail = detail,
                    formFactor = formFactor,
                    accent = accent,
                    gutter = gutter,
                    actions = { actions(true, pageTopHold.anchor) },
                )
            }

            Spacer(Modifier.height(64.dp))
        }
        }

        Box(Modifier.focusInert(inert)) {
            DetailBackButton(onBack, formFactor, accent = accent)
        }
    }
}

@Composable
private fun ColumnScope.HeroCrumb(detail: DiscoverDetail) {
    val crumb = buildString {
        append(when (detail.mediaType) {
            "movie" -> "Movie"
            "tv" -> "TV"
            "book" -> "Book"
            else -> "Discover"
        })
        append(" · ")
        append(detail.provider.uppercase())
        when (detail.libraryStatus) {
            LibraryStatus.Wanted -> append(" · WANTED")
            LibraryStatus.NotInLibrary -> append(" · NOT IN LIBRARY")
            LibraryStatus.InLibrary -> Unit
        }
    }
    Text(
        crumb.uppercase(),
        style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
        color = KobappTheme.colors.textLo,
    )
}

@Composable
private fun PhoneBody(
    detail: DiscoverDetail,
    accent: Color,
    gutter: Dp,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        Spacer(Modifier.height(14.dp))
        StatRow(detail, formFactor = FormFactor.Phone)
        Spacer(Modifier.height(16.dp))
        if (detail.genres.isNotEmpty()) {
            GenreChips(detail.genres, accent = accent)
            Spacer(Modifier.height(14.dp))
        }
        actions()
        ReleaseAvailabilityStrip(
            availability = detail.availability(StreamingAvailability.Hidden),
            accent = accent,
            modifier = Modifier.padding(top = 18.dp),
        )
        if (detail.description != null) {
            Spacer(Modifier.height(20.dp))
            ExpandableText(detail.description)
        }
        if (detail.cast.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            CastRow(detail.cast, accent = accent)
        }
    }
}

@Composable
private fun WideBody(
    detail: DiscoverDetail,
    formFactor: FormFactor,
    accent: Color,
    gutter: Dp,
    actions: @Composable () -> Unit,
) {
    Spacer(Modifier.height(32.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.widthIn(max = 1000.dp).weight(1f, fill = true)) {
            StatRow(detail, formFactor = formFactor)
            if (detail.description != null) {
                Spacer(Modifier.height(22.dp))
                Text(
                    detail.description,
                    style = KobappTheme.type.body,
                    color = KobappTheme.colors.textMd,
                )
            }
            Spacer(Modifier.height(26.dp))
            actions()
            if (detail.genres.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                GenreChips(detail.genres, accent = accent)
            }
            if (detail.authors.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    detail.authors.joinToString(", "),
                    style = KobappTheme.type.bodySm,
                    color = KobappTheme.colors.textMd,
                )
            }
            if (detail.cast.isNotEmpty()) {
                Spacer(Modifier.height(36.dp))
                CastRow(detail.cast, accent = accent)
            }
        }

        Column(
            modifier = Modifier.widthIn(min = 280.dp, max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusPill(PillStatus.NotInLibrary)
            ReleaseAvailabilityStrip(availability = detail.availability(StreamingAvailability.Hidden), accent = accent)
            DiscoverDetailsPanel(detail = detail, formFactor = formFactor)
        }
    }
}

@Composable
private fun DiscoverTitleBlock(title: String, color: Color) {
    // The two-line split is a 10-foot/desktop flourish: it halves a title that already
    // wraps three times in a phone hero's 194dp column.
    val parts = if (LocalFormFactor.current == FormFactor.Phone) DiscoverTitleParts(title.trim(), null) else splitDiscoverTitle(title)
    Column {
        SerifLine(parts.head, color)
        if (parts.tail != null) SerifLine(parts.tail, color, italic = true)
    }
}

@Composable
private fun SerifLine(text: String, color: Color, italic: Boolean = false) {
    Text(
        text,
        style = KobappTheme.heroTitle.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        ),
        color = color,
    )
}

@Composable
private fun StatRow(detail: DiscoverDetail, formFactor: FormFactor) {
    val colors = KobappTheme.colors
    // Flow, not Row: four stats do not fit a phone's width, and a Row squeezed the last
    // one until its value broke mid-word.
    FlowRow(
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (formFactor == FormFactor.Phone) 12.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (detail.year != null) Stat("Year", detail.year.toString())
        if (detail.runtime != null) {
            val h = detail.runtime / 60
            val m = detail.runtime % 60
            Stat("Runtime", if (h > 0) "${h}h ${m}m" else "${m}m")
        }
        if (detail.network != null) Stat("Network", detail.network)
        if (detail.rating != null) {
            Column {
                Text(
                    "RATING",
                    style = KobappTheme.type.label.copy(letterSpacing = 0.12.em),
                    color = colors.textLo,
                )
                Spacer(Modifier.height(4.dp))
                RatingBadge(detail.rating)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val colors = KobappTheme.colors
    Column {
        Text(
            label.uppercase(),
            style = KobappTheme.type.label.copy(letterSpacing = 0.12.em),
            color = colors.textLo,
        )
        Spacer(Modifier.height(4.dp))
        Text(value, style = KobappTheme.type.body, color = colors.textHi)
    }
}

private data class DiscoverTitleParts(val head: String, val tail: String?)

private fun splitDiscoverTitle(title: String): DiscoverTitleParts {
    val trimmed = title.trim()
    if (trimmed.length < 22) return DiscoverTitleParts(trimmed, null)
    val mid = trimmed.length / 2
    val splitAt = (mid until trimmed.length).firstOrNull { trimmed[it] == ' ' }
        ?: (mid downTo 0).firstOrNull { trimmed[it] == ' ' }
        ?: return DiscoverTitleParts(trimmed, null)
    return DiscoverTitleParts(trimmed.substring(0, splitAt), trimmed.substring(splitAt + 1))
}
