package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Long enough that stepping along a row does not swap the backdrop under every press,
// short enough to feel like the hero is following focus rather than lagging it.
private const val HeroSwapDelayMs = 250L

private val HeroBarFade = 200.dp

const val HomeHeroTag = "home-hero"

@Composable
fun HomeWide(
    homeData: HomeData,
    api: KobblerApi,
    onContinueClick: (Int) -> Unit,
    onRecentClick: (RecentlyAddedItem) -> Unit,
    onDiscoverItemClick: (DiscoverItem) -> Unit,
    // Pointer platforms get a clickable hero with Resume/Details; on TV the hero is
    // display-only and the Up Next strip below it is the navigable row.
    onDetailsClick: ((Int) -> Unit)? = null,
    topBar: (@Composable (transparent: Boolean) -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val tv = LocalFormFactor.current == FormFactor.TV
    val upNext = remember(homeData) { homeData.watchNext.map { it.toUpNextItem() } }

    // Held as state objects and read only inside the composables below that need them:
    // as values here, one D-pad press recomposes the page and every card on it.
    val initial = remember(homeData) {
        upNext.firstOrNull()?.spotlight(0) ?: homeData.recentlyAdded.firstOrNull()?.spotlight()
    }
    val pointedAt = remember(homeData) { mutableStateOf(initial) }
    val shown = remember(homeData) { mutableStateOf(initial) }
    val focusedRow = remember { mutableIntStateOf(-1) }
    // The backdrop is full screen, so following focus directly means one image load and
    // decode per press while RIGHT is held down. Settle first, then swap.
    LaunchedEffect(pointedAt) {
        snapshotFlow { pointedAt.value }.collectLatest { spot ->
            delay(HeroSwapDelayMs)
            shown.value = spot
        }
    }

    PrefetchBackdrops(upNext)

    val listState = rememberLazyListState()

    var lazyIndex = 0
    val upNextRow = if (upNext.isNotEmpty()) lazyIndex++ else -1
    // Up Next is the row entry focus lands on, and the hero above it is the thing that
    // row is describing — hold the page at the top until focus moves past it.
    CenterFocusedPageRow(listState, focusedRow, topRow = upNextRow)
    val recentRow = if (homeData.recentlyAdded.isNotEmpty()) lazyIndex++ else -1
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
        if (initial != null) {
            HomeBackdrop(
                spotlight = { shown.value },
                accent = upNext.firstOrNull()?.let { colors.accentForMediaType(it.mediaType) } ?: colors.accMovies,
                // Past Up Next and the first rail the rows below are what is being read.
                dim = { if (listState.firstVisibleItemIndex >= 2) 1f else scrolledFraction() * 0.7f },
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
                        val spot = shown.value ?: return@item
                        Column(Modifier.testTag(HomeHeroTag)) {
                            HeroCopy(
                                spotlight = spot,
                                upNext = upNext,
                                height = heroCopyHeight(tv, viewportPx, stripPx, recentPx, density),
                                onResume = onContinueClick.takeIf { onDetailsClick != null },
                                onDetails = onDetailsClick,
                                onActionsFocused = { focusedRow.intValue = upNextRow },
                            )
                            Box(Modifier.onSizeChanged { stripPx = it.height }) {
                                UpNextStrip(
                                    items = upNext,
                                    onClick = { idx -> onContinueClick(idx) },
                                    onFocused = { idx ->
                                        pointedAt.value = upNext[idx].spotlight(idx)
                                        focusedRow.intValue = upNextRow
                                    },
                                    onHovered = { idx -> pointedAt.value = upNext[idx].spotlight(idx) },
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
                                accent = colors.accMovies,
                                state = rail.state,
                                key = "recent",
                                subtitle = if (homeData.recentlyAdded.size > 1) "${homeData.recentlyAdded.size} new this week" else null,
                            ) {
                                itemsIndexed(homeData.recentlyAdded) { c, item ->
                                    PosterCard(
                                        title = item.title,
                                        imageUrl = item.imageUrl,
                                        subtitle = item.subtitle,
                                        accentColor = colors.accentForRecentlyAdded(item.itemType),
                                        onClick = { onRecentClick(item) },
                                        modifier = Modifier
                                            .focusKey("item.$c")
                                            .onHoverEnter { pointedAt.value = item.spotlight() }
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    rail.focused = c
                                                    focusedRow.intValue = recentRow
                                                    pointedAt.value = item.spotlight()
                                                }
                                            },
                                    )
                                }
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
                            accent = sectionAccent,
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
                                        .onHoverEnter { pointedAt.value = item.spotlight(section.title) }
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                rail.focused = c
                                                focusedRow.intValue = sectionRows[i]
                                                pointedAt.value = item.spotlight(section.title)
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

// What the screen has left once Up Next and the first rail are on it, within bounds that
// keep the title legible on one side and the page from turning into a poster on the other.
private fun heroCopyHeight(tv: Boolean, viewportPx: Int, stripPx: Int, recentPx: Int, density: Density): Dp {
    val (min, max) = if (tv) 120.dp to 300.dp else 200.dp to 440.dp
    if (viewportPx == 0 || stripPx == 0) return max
    val left = with(density) { (viewportPx - stripPx - recentPx).toDp() }
    return left.coerceIn(min, max)
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
    onClick: (Int) -> Unit,
    onFocused: (Int) -> Unit,
    onHovered: (Int) -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val gutter = homeGutter()
    // The tile is art, a title and a tag: past this width it is only empty card.
    val tileWidth = minOf(dimens.wideCardWidth, 240.dp)
    val tileGap = dimens.s3
    val rail = rememberRailFocus()
    // The hero's own bottom padding already separates it; a full gap on top of that read as
    // a hole between the title and the strip.
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp).focusKeyGroup("upnext")) {
        Text(
            "Up Next".uppercase(),
            color = colors.textLo,
            style = KobappTheme.type.caption.copy(
                letterSpacing = androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em),
            ),
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
                    horizontalArrangement = Arrangement.spacedBy(tileGap),
                ) {
                    itemsIndexed(items) { i, item ->
                        UpNextStripTile(
                            item = item,
                            onClick = { onClick(i) },
                            modifier = Modifier
                                .width(tileWidth)
                                .focusKey("item.$i", preferredEntry = i == 0)
                                .onHoverEnter { onHovered(i) }
                                .onFocusChanged {
                                    if (it.isFocused) { rail.focused = i; onFocused(i) }
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
    accent: Color,
    state: LazyListState,
    key: String,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = KobappTheme.colors
    val gutter = homeGutter()
    val railGap = KobappTheme.dimens.railGap
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).focusKeyGroup(key)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeading(title = title, accent = accent, modifier = Modifier.weight(1f))
            if (subtitle != null) {
                Text(subtitle, color = colors.textLo, style = KobappTheme.type.caption)
            }
        }
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

@Composable
private fun homeGutter(): Dp =
    if (LocalFormFactor.current == FormFactor.TV) KobappTheme.dimens.pageGutterTv else KobappTheme.dimens.pageGutter

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
