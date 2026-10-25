package dev.kobbler.app.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.CastRow
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.components.spotlight
import dev.kobbler.app.ui.detail.tvshow.SeasonTabs
import dev.kobbler.app.ui.kobTopInset
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusReveal
import kotlinx.coroutines.launch

internal typealias SeasonMenu = @Composable ColumnScope.(close: () -> Unit) -> Unit

/**
 * A show's page, whether the library holds it or Discover is previewing it: everything
 * but what its actions do and where its episodes come from.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShowDetailLayout(
    show: TvShow,
    episodeTotal: Int,
    formFactor: FormFactor,
    accent: Color,
    selectedSeasonIdx: Int,
    onSeasonSelected: (Int) -> Unit,
    onBack: () -> Unit,
    onPersonClick: ((String) -> Unit)?,
    actions: @Composable (wide: Boolean, anchor: Modifier, exitDown: FocusRequester?) -> Unit,
    seasonMenu: @Composable (fallbackFocus: FocusRequester?) -> SeasonMenu?,
    episodes: LazyListScope.(seasonEntry: FocusRequester?) -> Unit,
    topBarStatus: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val gutter = gutterFor(formFactor)
    val listState = rememberLazyListState()
    val pageScroll = listState.asPageScroll()
    val pageTopHold = rememberPageTopHold(pageScroll)
    val pageScope = rememberCoroutineScope()
    val seasonEntry = remember { FocusRequester() }
    val season = show.seasons.getOrNull(selectedSeasonIdx)
    val body = ShowBody(show, episodeTotal, accent, selectedSeasonIdx, onSeasonSelected, actions, seasonMenu)
    // The head is a lazy item, so its actions register during measure; a back button
    // composed before then would be all entry focus could find.
    var headPlaced by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = detailStacked(formFactor, maxWidth)
        DetailBackdrop(show.spotlight(), accent, pageScroll)

        CompositionLocalProvider(LocalBringIntoViewSpec provides pageTopHold.spec) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollWhenFocusIsStuck(pageScroll, asFocusTarget = false)
                    .focusReveal { key -> listState.revealEpisode(key, season) },
                state = listState,
                contentPadding = PaddingValues(
                    start = gutter,
                    end = gutter,
                    top = kobTopInset + if (formFactor == FormFactor.TV) 56.dp else 72.dp,
                    bottom = 64.dp,
                ),
            ) {
                item(key = HeadKey, contentType = HeadKey) {
                    Box(Modifier.onPlaced { headPlaced = true }) {
                        if (stacked) PhoneHead(body, pageTopHold.anchor) else WideHead(body, formFactor, pageTopHold.anchor, seasonEntry)
                    }
                }
                if (show.seasons.isNotEmpty()) episodes(seasonEntry.takeIf { !stacked })
                if (show.cast.isNotEmpty()) {
                    item(key = CastKey, contentType = CastKey) {
                        Spacer(Modifier.height(if (stacked) 22.dp else 36.dp))
                        CastRow(show.cast, onPersonClick = onPersonClick)
                    }
                }
            }
        }

        TopBar(
            scroll = pageScroll,
            show = show,
            formFactor = formFactor,
            gutter = gutter,
            accent = accent,
            status = topBarStatus,
            // Not on TV: nothing in the D-pad graph reaches this pinned bar, and the
            // remote's BACK already unwinds the detail destination.
            showBack = formFactor != FormFactor.TV && headPlaced,
            modifier = Modifier.pageTopOverlay(pageTopHold),
            onBack = onBack,
            // The bar is pinned over the scrolling body, so focusing it while the page
            // is scrolled would otherwise leave the title stranded off-screen.
            onBackFocused = { pageScope.launch { pageScroll.scrollToTop() } },
        )

        overlay()
    }
}

private const val HeadKey = "head"
private const val CastKey = "cast"

/**
 * Scrolls a remembered episode row into composition so a restore can land on it. Assumes
 * the `episodes` slot lists [season]'s episodes in order, straight after the head item.
 */
private suspend fun LazyListState.revealEpisode(key: String, season: Season?): Boolean {
    if (season == null) return false
    val number = key.removePrefix("season.${season.number}.episode.").takeIf { it != key }
        ?.substringBefore('.')?.toIntOrNull() ?: return false
    val idx = season.episodes.indexOfFirst { it.number == number }
    if (idx < 0) return false
    scrollToItem(idx + 1)
    return true
}

private class ShowBody(
    val show: TvShow,
    val episodeTotal: Int,
    val accent: Color,
    val selectedSeasonIdx: Int,
    val onSeasonSelected: (Int) -> Unit,
    val actions: @Composable (wide: Boolean, anchor: Modifier, exitDown: FocusRequester?) -> Unit,
    val seasonMenu: @Composable (fallbackFocus: FocusRequester?) -> SeasonMenu?,
)

@Composable
private fun WideHead(body: ShowBody, formFactor: FormFactor, actionsAnchor: Modifier, seasonEntry: FocusRequester) {
    val show = body.show
    val colors = KobappTheme.colors
    val posterWidth = when (formFactor) {
        FormFactor.TV -> 200.dp
        FormFactor.Phone -> 160.dp
        FormFactor.Desktop -> 280.dp
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DetailPoster(show.posterUrl, body.accent, Modifier.width(posterWidth))

            Column(modifier = Modifier.weight(1f)) {
                DetailTitle(show.title, show.year)
                Spacer(Modifier.height(14.dp))
                MetaRow(show, body.episodeTotal)
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
                body.actions(true, actionsAnchor, seasonEntry.takeIf { show.seasons.isNotEmpty() })
            }
        }

        // Full width, below the hero rather than beside it: as a column next to the
        // actions the episode rows kept their narrow measure all the way down the page,
        // clipping every synopsis against a screen of empty gutter.
        if (show.seasons.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            SeasonTabs(
                show.seasons, body.selectedSeasonIdx, body.accent, formFactor, body.onSeasonSelected,
                entryFocus = seasonEntry,
                menu = body.seasonMenu(seasonEntry),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PhoneHead(body: ShowBody, actionsAnchor: Modifier) {
    val show = body.show
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            DetailPoster(show.posterUrl, body.accent, Modifier.width(120.dp))
            Column(modifier = Modifier.weight(1f)) {
                DetailTitle(show.title, show.year)
                Spacer(Modifier.height(8.dp))
                MetaRow(show, body.episodeTotal)
            }
        }
        Spacer(Modifier.height(16.dp))
        body.actions(false, actionsAnchor, null)
        if (show.synopsis != null) {
            Spacer(Modifier.height(18.dp))
            ExpandableText(show.synopsis)
        }
        if (show.seasons.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SeasonTabs(
                show.seasons, body.selectedSeasonIdx, body.accent, FormFactor.Phone, body.onSeasonSelected,
                menu = body.seasonMenu(null),
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MetaRow(show: TvShow, episodeTotal: Int) {
    DetailMeta(
        metaText(show.network),
        metaRating(show.rating),
        metaText(show.contentRating),
        metaText(show.seasons.size.takeIf { it > 0 }?.let { n -> "$n season${if (n != 1) "s" else ""} · $episodeTotal ep" }),
        metaText(show.nextAiring?.let { "Next: $it" }),
    )
}

@Composable
private fun TopBar(
    show: TvShow,
    scroll: PageScroll,
    formFactor: FormFactor,
    gutter: Dp,
    accent: Color,
    status: @Composable () -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
    onBackFocused: () -> Unit,
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
        if (showBack) {
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
            status()
        }
    }
}

@Composable
internal fun TopBarStatus(text: String, color: Color = KobappTheme.colors.textLo) {
    Text(text.uppercase(), style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em), color = color)
}
