package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.HomeData
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.data.RecentlyAddedItem
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.data.toUpNextItem
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.CenterFocusedItem
import dev.kobbler.app.ui.components.FocusEdgeSlack
import dev.kobbler.app.ui.components.HoldPageTop
import dev.kobbler.app.ui.components.PosterCard
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.SpotlightBackdrop
import dev.kobbler.app.ui.components.SpotlightState
import dev.kobbler.app.ui.components.WideCard
import dev.kobbler.app.ui.components.pageGutter
import dev.kobbler.app.ui.components.rememberSpotlightState
import dev.kobbler.app.ui.components.spotlight
import dev.kobbler.app.ui.components.spotlightOn
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val HeroBarFade = 200.dp

// Once the hero's words have scrolled off, rows sit straight on the picture; this much dim
// keeps their headings legible while the picture still follows focus.
private const val ScrolledBackdropDim = 0.6f

const val HomeHeroTag = "home-hero"

const val UpNextCardTag = "upnext-card"

const val RecentCardTag = "recent-card"

@Composable
fun HomeWide(
    homeData: HomeData,
    api: KobblerApi,
    onContinueClick: (Int) -> Unit,
    onRecentClick: (RecentlyAddedItem) -> Unit,
    onDiscoverItemClick: (DiscoverItem) -> Unit,
    topBar: (@Composable (transparent: Boolean) -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val tv = LocalFormFactor.current == FormFactor.TV
    val upNext = remember(homeData) { homeData.watchNext.map { it.toUpNextItem() } }

    // Read only inside the composables below that need it: as a value here, one D-pad
    // press recomposes the page and every card on it.
    val spotlight = rememberSpotlightState(
        upNext.firstOrNull()?.spotlight() ?: homeData.recentlyAdded.firstOrNull()?.spotlight(),
    )
    val hasSpotlight by remember(spotlight) { derivedStateOf { spotlight.shown != null } }
    val focusedRow = remember { mutableIntStateOf(-1) }

    PrefetchBackdrops(upNext)

    val listState = rememberLazyListState()

    var lazyIndex = 0
    val upNextRow = if (upNext.isNotEmpty()) lazyIndex++ else -1
    // Up Next is the row entry focus lands on, and the hero above it is the thing that
    // row is describing — hold the page at the top until focus moves past it.
    CenterFocusedPageRow(listState, focusedRow, topRow = upNextRow)
    val recentRow = if (homeData.recentlyAdded.isNotEmpty()) lazyIndex++ else -1
    val stremioRow = if (homeData.stremioContinue.isNotEmpty()) lazyIndex++ else -1
    val sectionRows = homeData.sections.map { lazyIndex++ }

    val density = LocalDensity.current
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var viewportPx by remember { mutableIntStateOf(0) }
    var stripPx by remember { mutableIntStateOf(0) }
    var recentPx by remember { mutableIntStateOf(0) }
    val barFadePx = with(density) { HeroBarFade.toPx() }
    val scrolledFraction = {
        if (listState.firstVisibleItemIndex > 0) 1f
        else (listState.firstVisibleItemScrollOffset / barFadePx).coerceIn(0f, 1f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewportPx = it.height },
    ) {
        if (hasSpotlight) {
            SpotlightBackdrop(
                spotlight = { spotlight.shown },
                accent = upNext.firstOrNull()?.let { colors.accentForMediaType(it.mediaType) } ?: colors.accMovies,
                dim = { scrolledFraction() * ScrolledBackdropDim },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // TV only: on a pointer platform the hold would fight the wheel.
        HoldPageTop(hold = { tv && focusedRow.intValue >= 0 && focusedRow.intValue == upNextRow }) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // With a hero the bar floats over its backdrop; without one nothing would
                // show through, so the page starts below it instead.
                contentPadding = PaddingValues(
                    top = if (topBar != null && upNext.isEmpty()) with(density) { topBarHeightPx.toDp() } else 0.dp,
                    bottom = 36.dp,
                ),
            ) {
                if (upNext.isNotEmpty()) {
                    item(key = "hero") {
                        Column(Modifier.testTag(HomeHeroTag)) {
                            Spacer(Modifier.height(heroHeight(viewportPx, stripPx, recentPx, density)))
                            Box(Modifier.onSizeChanged { stripPx = it.height }) {
                                UpNextStrip(
                                    items = upNext,
                                    onClick = { idx -> onContinueClick(idx) },
                                    spotlight = spotlight,
                                    onFocused = { focusedRow.intValue = upNextRow },
                                )
                            }
                        }
                    }
                }

                if (homeData.recentlyAdded.isNotEmpty()) {
                    item(key = "recent") {
                        val rail = rememberRailFocus()
                        Box(Modifier.onSizeChanged { recentPx = it.height }) {
                            HomeRail(
                                title = "Recently Added",
                                state = rail.state,
                                key = "recent",
                            ) {
                                itemsIndexed(homeData.recentlyAdded) { c, item ->
                                    PosterCard(
                                        title = item.title,
                                        imageUrl = item.imageUrl,
                                        subtitle = item.subtitle,
                                        accentColor = colors.accentForRecentlyAdded(item.itemType),
                                        onClick = { onRecentClick(item) },
                                        modifier = Modifier
                                            .testTag("$RecentCardTag.$c")
                                            .focusKey("item.$c")
                                            .spotlightOn(spotlight) { item.spotlight() }
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    rail.focused = c
                                                    focusedRow.intValue = recentRow
                                                }
                                            },
                                    )
                                }
                            }
                        }
                    }
                }

                if (homeData.stremioContinue.isNotEmpty()) {
                    item(key = "stremio") {
                        val rail = rememberRailFocus()
                        HomeRail(title = StremioContinueTitle, state = rail.state, key = "stremio") {
                            itemsIndexed(homeData.stremioContinue) { c, entry ->
                                StremioContinueCard(
                                    entry = entry,
                                    onClick = { onDiscoverItemClick(entry.item) },
                                    modifier = Modifier
                                        .focusKey("item.$c")
                                        .spotlightOn(spotlight) { entry.item.spotlight() }
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                rail.focused = c
                                                focusedRow.intValue = stremioRow
                                            }
                                        },
                                )
                            }
                        }
                    }
                }

                homeData.sections.forEachIndexed { i, section ->
                    item(key = "section-$i") {
                        val sectionAccent = colors.accentForSection(section.sectionKey)
                        val wantScope = rememberCoroutineScope()
                        val rail = rememberRailFocus()
                        HomeRail(
                            title = section.title,
                            state = rail.state,
                            key = "section-$i",
                        ) {
                            itemsIndexed(section.items) { c, item ->
                                // buildList hands back a fresh List every call, and List
                                // compares by reference under strong skipping — inline, no
                                // card in this rail can ever skip.
                                val menu = remember(item, api) {
                                    discoverContextMenu(item, api, wantScope, onDiscoverItemClick)
                                }
                                PosterCard(
                                    title = item.title,
                                    imageUrl = item.posterUrl,
                                    subtitle = when (item.mediaType) {
                                        "book" -> item.authors.firstOrNull() ?: ""
                                        else -> item.year?.toString() ?: ""
                                    },
                                    accentColor = sectionAccent,
                                    statusBadge = item.libraryStatus,
                                    contextMenuItems = menu,
                                    onClick = { onDiscoverItemClick(item) },
                                    modifier = Modifier
                                        .focusKey("item.$c")
                                        .spotlightOn(spotlight) { item.spotlight() }
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                rail.focused = c
                                                focusedRow.intValue = sectionRows[i]
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (topBar != null) {
            val hasHero = upNext.isNotEmpty()
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { topBarHeightPx = it.height }
                    // Fills in as the hero scrolls away, so rows never pass under a see-through
                    // bar. Read at draw time so scrolling repaints the bar without recomposing the page.
                    .drawBehind {
                        if (!hasHero) return@drawBehind
                        drawRect(colors.bg, alpha = scrolledFraction() * 0.92f)
                    },
            ) {
                topBar(hasHero)
            }
        }
    }
}

// The open stretch of backdrop above Up Next: what the screen has left once Up Next and
// the first rail are on it, bounded so the page never turns into a poster.
internal val HeroMinHeight = 120.dp
internal val HeroMaxHeight = 300.dp

private fun heroHeight(viewportPx: Int, stripPx: Int, recentPx: Int, density: Density): Dp {
    if (viewportPx == 0 || stripPx == 0) return HeroMaxHeight
    val left = with(density) { (viewportPx - stripPx - recentPx).toDp() }
    return left.coerceIn(HeroMinHeight, HeroMaxHeight)
}

@Stable
private class RailFocus(val state: LazyListState) {
    var focused by mutableIntStateOf(-1)
}

// Returns a value, so it is not restartable: a state read here would invalidate whichever
// lazy item called it. The centring read lives in its own scope instead.
@Composable
private fun rememberRailFocus(): RailFocus {
    val state = rememberLazyListState()
    val rail = remember(state) { RailFocus(state) }
    CenterFocusedRailItem(rail)
    return rail
}

@Composable
private fun CenterFocusedRailItem(rail: RailFocus) {
    CenterFocusedItem(rail.state, rail.focused)
}

@Composable
private fun CenterFocusedPageRow(state: LazyListState, row: IntState, topRow: Int) {
    CenterFocusedItem(state, row.intValue, topRow = topRow)
}

@Composable
private fun UpNextStrip(
    items: List<UpNextItem>,
    spotlight: SpotlightState,
    onClick: (Int) -> Unit,
    onFocused: () -> Unit,
) {
    val colors = KobappTheme.colors
    val gutter = pageGutter()
    val rail = rememberRailFocus()
    // The hero's own bottom padding already separates it; a full gap on top of that read as
    // a hole between the title and the strip.
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp).focusKeyGroup("upnext")) {
        Text(
            "Up Next",
            color = colors.textHi,
            style = KobappTheme.type.h3,
            modifier = Modifier.padding(start = gutter),
        )
        Spacer(Modifier.height(10.dp))
        // Re-provides a spec of its own so the page-top hold, which is in force exactly
        // while this row has focus, does not also freeze its horizontal scrolling.
        FocusEdgeSlack {
            RailPager(rail.state, gutter, enabled = LocalFormFactor.current != FormFactor.TV) {
                LazyRow(
                    state = rail.state,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(KobappTheme.dimens.railGap),
                ) {
                    itemsIndexed(items) { i, item ->
                        WideCard(
                            title = item.title,
                            subtitle = item.subtitle,
                            imageUrl = item.mediaArtUrl.orEmpty(),
                            backdropUrl = item.backdropUrl,
                            progress = item.progress ?: 0f,
                            accentColor = colors.accentForMediaType(item.mediaType),
                            aspectRatio = 3f / 2f,
                            onClick = { onClick(i) },
                            modifier = Modifier
                                // A poster from the rows below turned on its side, so the
                                // page reads as one grid rather than two sizes of card.
                                .width(KobappTheme.dimens.posterWidth * 1.5f)
                                .testTag("$UpNextCardTag.$i")
                                .focusKey("item.$i", preferredEntry = i == 0)
                                .spotlightOn(spotlight) { item.spotlight() }
                                .onFocusChanged {
                                    if (it.isFocused) { rail.focused = i; onFocused() }
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRail(
    title: String,
    state: LazyListState,
    key: String,
    content: LazyListScope.() -> Unit,
) {
    val gutter = pageGutter()
    val railGap = KobappTheme.dimens.railGap
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).focusKeyGroup(key)) {
        SectionHeading(title = title, accent = null, modifier = Modifier.padding(horizontal = gutter))
        Spacer(Modifier.height(10.dp))
        RailPager(state, gutter, enabled = LocalFormFactor.current != FormFactor.TV) {
            LazyRow(
                state = state,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(railGap),
                content = content,
            )
        }
    }
}

internal fun discoverContextMenu(
    item: DiscoverItem,
    api: KobblerApi,
    scope: kotlinx.coroutines.CoroutineScope,
    onClick: (DiscoverItem) -> Unit,
): List<Pair<String, () -> Unit>> = buildList {
    add("Info" to { onClick(item) })
    if (item.libraryStatus == LibraryStatus.NotInLibrary && api.canWant) {
        add("Want" to {
            scope.launch {
                try {
                    val detail = api.getDiscoverDetail(item.mediaType, item.provider, item.providerId)
                    when (item.mediaType) {
                        "movie" -> api.wantMovie(detail)
                        "tv" -> api.wantTvShow(detail)
                        "book" -> api.wantBook(detail)
                    }
                } catch (_: Exception) {}
            }
        })
    }
}
