package dev.kobbler.app.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverEpisodeRef
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.CastRow
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.PillStatus
import dev.kobbler.app.ui.components.StatusPill
import dev.kobbler.app.ui.components.rememberPalette
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
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
) {
    val colors = KobappTheme.colors
    val accent = colors.accWanted
    var currentDetail by remember { mutableStateOf(detail) }
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

    val palette = rememberPalette(currentDetail.posterUrl)
    val gutter = gutterFor(formFactor)

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

    val onStreamEpisode: (Int, Int) -> Unit = { season, episode -> streams.play(DiscoverEpisodeRef(season, episode)) }
    val onStreamEpisodePick: (Int, Int) -> Unit = { season, episode -> streams.pick(DiscoverEpisodeRef(season, episode)) }

    val pageScroll = rememberScrollState()
    val pageTopHold = rememberPageTopHold(pageScroll)
    val pageScope = rememberCoroutineScope()
    val pageFocusManager = LocalFocusManager.current
    BoxWithConstraints(modifier.fillMaxSize().background(colors.bg)) {
        val stacked = detailStacked(formFactor, maxWidth)
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageTopHold.spec) {
        Column(
            Modifier
                .fillMaxSize()
                .focusInert(streams.showOptions)
                .scrollWhenFocusIsStuck(pageScroll)
                .verticalScroll(pageScroll),
        ) {
            DetailHero(
                backdropUrl = currentDetail.backdropUrl,
                posterUrl = currentDetail.posterUrl,
                formFactor = formFactor,
                accentTint = palette.darkAccent,
                accent = accent,
            ) {
                HeroCrumb(currentDetail)
                Spacer(Modifier.height(8.dp))
                DiscoverTitleBlock(currentDetail.title, colors.textHi)
            }

            if (stacked) {
                PhoneBody(
                    detail = currentDetail,
                    accent = accent,
                    canStreamMedia = canStreamMedia,
                    wantLoading = wantLoading,
                    showWant = api.canWant,
                    api = api,
                    gutter = gutter,
                    onWant = onWantPressed,
                    onPlay = { streams.play(null) },
                    onShowStreams = { streams.pick(null) },
                    onStreamEpisode = onStreamEpisode,
                    onStreamEpisodePick = onStreamEpisodePick,
                )
            } else {
                WideBody(
                    detail = currentDetail,
                    actionsAnchor = pageTopHold.anchor,
                    formFactor = formFactor,
                    accent = accent,
                    canStreamMedia = canStreamMedia,
                    wantLoading = wantLoading,
                    showWant = api.canWant,
                    api = api,
                    gutter = gutter,
                    onWant = onWantPressed,
                    onPlay = { streams.play(null) },
                    onShowStreams = { streams.pick(null) },
                    onStreamEpisode = onStreamEpisode,
                    onStreamEpisodePick = onStreamEpisodePick,
                )
            }

            Spacer(Modifier.height(64.dp))
        }
        }

        Box(Modifier.focusInert(streams.showOptions)) {
            DetailBackButton(onBack, formFactor, accent = accent)
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
                api = api,
            )
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
    canStreamMedia: Boolean,
    wantLoading: Boolean,
    showWant: Boolean,
    api: KobblerApi,
    gutter: androidx.compose.ui.unit.Dp,
    onWant: () -> Unit,
    onPlay: () -> Unit,
    onShowStreams: () -> Unit,
    onStreamEpisode: (Int, Int) -> Unit,
    onStreamEpisodePick: (Int, Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        Spacer(Modifier.height(14.dp))
        StatRow(detail, formFactor = FormFactor.Phone)
        Spacer(Modifier.height(16.dp))
        if (detail.genres.isNotEmpty()) {
            GenreChips(detail.genres, accent = accent)
            Spacer(Modifier.height(14.dp))
        }
        ActionRow(
            detail = detail,
            accent = accent,
            canStreamMedia = canStreamMedia,
            wantLoading = wantLoading,
            showWant = showWant,
            onWant = onWant,
            onPlay = onPlay,
            onShowStreams = onShowStreams,
        )
        ReleaseAvailabilityStrip(
            detail = detail,
            accent = accent,
            modifier = Modifier.padding(top = 18.dp),
        )
        if (detail.description != null) {
            Spacer(Modifier.height(20.dp))
            ExpandableText(detail.description)
        }
        if (detail.mediaType == "tv" && detail.seasons.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            DiscoverSeasonsSection(
                seasons = detail.seasons.filter { it.seasonNumber > 0 },
                api = api,
                detail = detail,
                accent = accent,
                canStream = canStreamMedia,
                onStreamEpisode = onStreamEpisode,
                onStreamEpisodePick = onStreamEpisodePick,
            )
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
    actionsAnchor: Modifier,
    formFactor: FormFactor,
    accent: Color,
    canStreamMedia: Boolean,
    wantLoading: Boolean,
    showWant: Boolean,
    api: KobblerApi,
    gutter: androidx.compose.ui.unit.Dp,
    onWant: () -> Unit,
    onPlay: () -> Unit,
    onShowStreams: () -> Unit,
    onStreamEpisode: (Int, Int) -> Unit,
    onStreamEpisodePick: (Int, Int) -> Unit,
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
            ActionRow(
                detail = detail,
                accent = accent,
                canStreamMedia = canStreamMedia,
                wantLoading = wantLoading,
                showWant = showWant,
                onWant = onWant,
                onPlay = onPlay,
                onShowStreams = onShowStreams,
                modifier = actionsAnchor,
            )
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
            if (detail.mediaType == "tv" && detail.seasons.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                DiscoverSeasonsSection(
                    seasons = detail.seasons.filter { it.seasonNumber > 0 },
                    api = api,
                    detail = detail,
                    accent = accent,
                    canStream = canStreamMedia,
                    onStreamEpisode = onStreamEpisode,
                    onStreamEpisodePick = onStreamEpisodePick,
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
            ReleaseAvailabilityStrip(detail = detail, accent = accent)
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

@Composable
private fun ActionRow(
    detail: DiscoverDetail,
    accent: Color,
    canStreamMedia: Boolean,
    wantLoading: Boolean,
    showWant: Boolean,
    onWant: () -> Unit,
    onPlay: () -> Unit,
    onShowStreams: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = modifier.focusKeyGroup("action"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canStreamMedia && detail.mediaType == "movie") {
            KobButton(
                onClick = onPlay,
                style = KobButtonStyle.Default,
                accent = accent,
                modifier = Modifier.focusKey("play", preferredEntry = true),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = colors.textHi, modifier = Modifier.size(18.dp))
                Text("Play", style = KobappTheme.type.body, color = colors.textHi)
            }
            KobButton(
                onClick = onShowStreams,
                style = KobButtonStyle.Default,
                accent = accent,
                modifier = Modifier.focusKey("streams"),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = "Stream options", tint = colors.textMd, modifier = Modifier.size(18.dp))
            }
        }

        if (showWant) {
            when (detail.libraryStatus) {
                LibraryStatus.InLibrary -> Unit
                LibraryStatus.Wanted -> {
                    KobButton(
                        onClick = {},
                        style = KobButtonStyle.Default,
                        enabled = false,
                        accent = accent,
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                        Text(
                            "Wanted",
                            style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = accent,
                        )
                    }
                }
                LibraryStatus.NotInLibrary -> {
                    KobButton(
                        onClick = onWant,
                        style = KobButtonStyle.Accent,
                        enabled = !wantLoading,
                        accent = accent,
                        modifier = Modifier.focusKey("want", preferredEntry = true),
                    ) {
                        if (wantLoading) {
                            CircularProgressIndicator(
                                color = colors.bg,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, tint = colors.bg, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            if (wantLoading) "Adding…" else "Want",
                            style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.bg,
                        )
                    }
                }
            }
        }
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
