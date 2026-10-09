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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.PosterCard
import dev.kobbler.app.ui.components.ReasonChip
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Long enough that stepping through Up Next does not swap the backdrop under every press,
// short enough to feel like the hero is following the strip rather than lagging it.
private const val HeroSwapDelayMs = 250L

private val HeroBarFade = 200.dp

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
    val heroIndex = remember(homeData) { mutableIntStateOf(0) }
    val focusedUpNextIdx = remember(homeData) { mutableIntStateOf(0) }
    val focusedRow = remember { mutableIntStateOf(-1) }
    // The hero is a full-width backdrop, so following focus directly means one image
    // load and decode per press while RIGHT is held down. Settle first, then swap.
    LaunchedEffect(heroIndex, focusedUpNextIdx) {
        snapshotFlow { focusedUpNextIdx.intValue }.collectLatest { idx ->
            delay(HeroSwapDelayMs)
            heroIndex.intValue = idx
        }
    }

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
    val barFadePx = with(density) { HeroBarFade.toPx() }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
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
                        WideHero(
                            items = upNext,
                            heroIndex = heroIndex,
                            onResume = onContinueClick.takeIf { onDetailsClick != null },
                            onDetails = onDetailsClick,
                            onActionsFocused = { focusedRow.intValue = upNextRow },
                        ) {
                            UpNextStrip(
                                items = upNext,
                                onClick = { idx -> onContinueClick(idx) },
                                onFocused = { idx ->
                                    focusedUpNextIdx.intValue = idx
                                    focusedRow.intValue = upNextRow
                                },
                                onHovered = { idx -> focusedUpNextIdx.intValue = idx },
                            )
                        }
                    }
                }

                if (homeData.recentlyAdded.isNotEmpty()) {
                    item(key = "recent") {
                        val rail = rememberRailFocus()
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
                                        .onFocusChanged {
                                            if (it.isFocused) { rail.focused = c; focusedRow.intValue = recentRow }
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
                                        .onFocusChanged {
                                            if (it.isFocused) { rail.focused = c; focusedRow.intValue = sectionRows[i] }
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
                        val solidity = if (listState.firstVisibleItemIndex > 0) 1f
                        else (listState.firstVisibleItemScrollOffset / barFadePx).coerceIn(0f, 1f)
                        drawRect(colors.bg, alpha = solidity * 0.92f)
                    },
            ) {
                topBar(hasHero)
            }
        }
    }
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

/**
 * The backdrop runs under [strip] as well as the hero copy: the strip is what the hero
 * is describing, and stopping the picture above it cropped most of it away.
 *
 * The copy area is fixed and kept modest — the rows are what the page is for. On TV,
 * 300dp leaves room for the whole strip below it (540dp viewport at density 2.0), which
 * keeps the page pinned to the top while that strip has focus — see CenterFocusedItem.
 * BoxWithConstraints inside a LazyColumn item gets unbounded maxHeight, so it is not
 * sized from the viewport.
 */
@Composable
private fun WideHero(
    items: List<UpNextItem>,
    heroIndex: IntState,
    onResume: ((Int) -> Unit)?,
    onDetails: ((Int) -> Unit)?,
    onActionsFocused: () -> Unit,
    strip: @Composable () -> Unit,
) {
    val index = heroIndex.intValue
    val item = items.getOrNull(index) ?: return
    val totalCount = items.size
    val colors = KobappTheme.colors
    val accent = colors.accentForMediaType(item.mediaType)
    val gutter = homeGutter()
    val copyHeight = if (LocalFormFactor.current == FormFactor.TV) 300.dp else 440.dp

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize()) {
            StripedBackdrop(accent = accent, opacity = 0.55f, withRadialOverlay = false) {}

            if (!item.backdropUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.35f to Color.Transparent,
                            0.65f to colors.bg.copy(alpha = 0.70f),
                            0.85f to colors.bg.copy(alpha = 0.92f),
                            1f to colors.bg,
                        ),
                    ),
                ),
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to colors.bg.copy(alpha = 0.88f),
                            0.55f to Color.Transparent,
                        ),
                    ),
                ),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(copyHeight)
                    .then(
                        if (onResume != null) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                // The buttons inside are the real stops; a focusable backdrop
                                // would add an invisible step to every traversal.
                                .focusProperties { canFocus = false }
                                .clickable { onResume(index) }
                        } else Modifier,
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = gutter, end = gutter, top = 24.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Up next · ${index + 1} of $totalCount".uppercase(),
                            color = colors.textMd,
                            style = KobappTheme.type.caption.copy(
                                letterSpacing = androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em),
                            ),
                        )
                        ReasonChip(reason = item.reason, label = item.tag)
                        if (!item.subtitle.isNullOrBlank()) {
                            Text(
                                item.subtitle,
                                color = colors.textMd,
                                style = KobappTheme.type.caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (!item.tail.isNullOrBlank()) {
                            Text(
                                "· ${item.tail}",
                                color = colors.textLo,
                                style = KobappTheme.type.caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.title,
                        color = colors.textHi,
                        style = KobappTheme.type.h1.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onResume != null && onDetails != null) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .focusKeyGroup("hero")
                                .onFocusChanged { if (it.hasFocus) onActionsFocused() },
                        ) {
                            KobButton(
                                text = item.ctaLabel,
                                onClick = { onResume(index) },
                                style = KobButtonStyle.Accent,
                                accent = accent,
                                leading = { Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.focusKey("resume"),
                            )
                            KobButton(
                                text = "Details",
                                onClick = { onDetails(index) },
                                // The search pill sits up and to the right; it is reachable with Up.
                                modifier = Modifier
                                    .focusKey("details")
                                    .focusProperties { right = FocusRequester.Cancel },
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = gutter, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    repeat(totalCount) { i ->
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(if (i == index) 18.dp else 8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (i == index) accent else colors.elev3),
                        )
                    }
                }
            }
            strip()
        }
    }
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
    val tileWidth = dimens.wideCardWidth
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
