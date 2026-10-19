package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.*
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.*
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TvShowsScreen(
    api: KobblerApi,
    sportsOnly: Boolean = false,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onShowClick: (TvShow) -> Unit,
    onContinueClick: (ContinueItem) -> Unit = {},
) {
    val shows = remember { mutableStateListOf<TvShow>() }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var prevCursor by remember { mutableStateOf<String?>(null) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var filterIndex by remember { mutableIntStateOf(0) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingCategory by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(true) }
    // The list is a cursor window, so `shows.size` is the page, not the library. The
    // server only counts on the first page (tv.rs is_first_page), so hold on to it.
    var totalCount by remember(filterIndex) { mutableStateOf<Long?>(null) }
    val colors = KobappTheme.colors
    val accentColor = if (sportsOnly) colors.accSports else colors.accTv
    // Leagues carry a hardcoded "continuing" status (thesportsdb.rs), so the filter
    // is meaningless for sports and the control is not offered there.
    val continuingOnly = !sportsOnly && filterIndex == 1

    suspend fun fetch(cursor: String? = null, direction: String? = null, around: String? = null, aroundBefore: Int? = null): PaginatedShows =
        if (sportsOnly) {
            api.getSportsList(cursor = cursor, direction = direction, around = around, aroundBefore = aroundBefore)
        } else {
            api.getShowsList(
                cursor = cursor, direction = direction, around = around, aroundBefore = aroundBefore,
                status = if (continuingOnly) "continuing" else null,
                genre = genre,
            )
        }

    val cacheKey = if (sportsOnly) "sports" else browseCacheKey("tvshows", genre)
    val (state, retry) = rememberCached(cacheKey, filterIndex) {
        isReloading = true
        try {
            val result = fetch()
            val items = withContext(Dispatchers.Default) { api.showListToModels(result.items) }
            CachedPage(items, result.nextCursor, result.prevCursor, result.totalCount)
        } finally {
            isReloading = false
        }
    }

    // Not filled by the loader: a warm TTL entry skips it, and a consumer that loses the
    // in-flight race never runs one. The resolved page is the only signal both paths share.
    LaunchedEffect(state) {
        val page = (state as? Async.Ready)?.value ?: return@LaunchedEffect
        shows.clear()
        shows.addAll(page.items)
        nextCursor = page.next
        prevCursor = page.prev
        totalCount = page.total
        isReloading = false
    }

    // Watch next (TV only) from cached home data
    val (homeState, _) = rememberCached("home") { api.getDiscover() }
    // Both rails sit above the grid, and each arrives on its own request. Rendering the
    // grid before they land means inserting a rail above it afterwards, which shunts the
    // whole page down under the viewer's focus. Wait for them to settle first.
    val watchNextType = if (sportsOnly) MediaType.SportsEvent else MediaType.TvShow
    // A category is a narrower question than the rails above the grid answer.
    val watchNext = if (genre != null) emptyList()
    else (homeState as? Async.Ready)?.value?.watchNext?.filter { it.mediaType == watchNextType } ?: emptyList()

    // Recently added from cached dashboard data
    val (dashState, _) = rememberCached("dashboard") { api.getDashboard() }
    val recentShows = if (genre != null) {
        emptyList()
    } else if (sportsOnly) {
        (dashState as? Async.Ready)?.value?.sports ?: emptyList()
    } else {
        (dashState as? Async.Ready)?.value?.shows ?: emptyList()
    }

    // Both rails sit above the grid, and each arrives on its own request. Rendering the
    // grid before they land means inserting a rail above it afterwards, which shunts the
    // whole page down under the viewer's focus. Wait for them to settle first.
    val railsSettled = homeState !is Async.Loading && dashState !is Async.Loading

    // Alphabet letters
    var letters by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        try { letters = if (sportsOnly) api.getSportsLetters() else api.getTvLetters() } catch (_: Exception) {}
    }

    // Entry focus goes to the first row that actually exists, in reading order.
    var entryFocusPending by remember(filterIndex) { mutableStateOf(true) }
    val continueIsEntry = watchNext.isNotEmpty()
    val recentIsEntry = !continueIsEntry && recentShows.isNotEmpty()
    val gridIsEntry = !continueIsEntry && !recentIsEntry && shows.isNotEmpty()
    val entryKey = when {
        continueIsEntry -> "watchnext.item.${watchNext.first().id}"
        recentIsEntry -> "recent.show.${recentShows.first().id}"
        gridIsEntry -> "grid.${shows.first().id}"
        else -> null
    }

    val touch = formFactor == FormFactor.Phone
    val spotlight = rememberSpotlightState(
        watchNext.firstOrNull()?.spotlight() ?: recentShows.firstOrNull()?.spotlight() ?: shows.firstOrNull()?.spotlight(),
    )
    val pointer = spotlight.takeUnless { touch }
    val swiper = spotlight.takeIf { touch }

    val gatedState = if (railsSettled) state else Async.Loading
    AsyncContent(gatedState, onRetry = retry, modifier = modifier) { _ ->
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()

        // Snapshot copy: the grid's key/content lambdas run after the click handler has
        // already emptied `shows`, so indexing the live list against a captured count
        // throws IndexOutOfBounds.
        val gridShows = shows.toList()
        val totalItems = gridShows.size
        val watchNextIndex = if (watchNext.isNotEmpty()) 1 else -1
        val recentIndex = if (recentShows.isNotEmpty()) 1 + (if (watchNextIndex >= 0) 1 else 0) else -1
        val headerCount = (if (watchNextIndex >= 0) 1 else 0) + (if (recentIndex >= 0) 1 else 0)
        val topRowKey: () -> Any? = {
            if (watchNext.isNotEmpty()) "watchnext" else if (recentIndex >= 0) "recent" else
                gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == headerCount + 1 }?.row
        }

        val focus = remember { BrowseFocus() }
        CenterFocusedGridRow(gridState, focus)
        ClaimEntryFocus(
            entryKey = entryKey,
            pending = entryFocusPending,
            isContentKey = { it.startsWith("grid.") || it.startsWith("watchnext.") || it.startsWith("recent.") },
            onClaimed = { entryFocusPending = false },
        )

        // Load more forward
        val shouldLoadMore by remember {
            derivedStateOf {
                val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val total = gridState.layoutInfo.totalItemsCount
                lastVisible >= total - 10 && !isLoadingMore && nextCursor != null
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore && nextCursor != null) {
                isLoadingMore = true
                try {
                    val result = fetch(cursor = nextCursor)
                    shows.addAll(api.showListToModels(result.items))
                    nextCursor = result.nextCursor
                } catch (_: Exception) {}
                isLoadingMore = false
            }
        }

        // Load more backward
        val shouldLoadPrev by remember {
            derivedStateOf {
                val firstVisible = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: Int.MAX_VALUE
                firstVisible <= headerCount + 5 && !isLoadingMore && prevCursor != null
            }
        }
        LaunchedEffect(shouldLoadPrev) {
            if (shouldLoadPrev && prevCursor != null) {
                isLoadingMore = true
                try {
                    val result = fetch(cursor = prevCursor, direction = "backward")
                    val newItems = api.showListToModels(result.items)
                    if (newItems.isNotEmpty()) {
                        shows.addAll(0, newItems)
                        prevCursor = result.prevCursor
                    } else {
                        prevCursor = null
                    }
                } catch (_: Exception) {}
                isLoadingMore = false
            }
        }

        val spacing = KobappTheme.dimens
        val compact = formFactor == FormFactor.Phone
        val gutter = when (formFactor) {
            FormFactor.TV -> spacing.pageGutterTv
            FormFactor.Phone -> spacing.pageGutterPhone
            else -> spacing.pageGutter
        }
        // first-by-letter/available-letters take no status (tv.rs:2794/2827), so the
        // jump targets do not exist in the continuing-only list.
        // 22dp letters stacked 26 deep are a pointer/D-pad affordance; on touch they are
        // under half the minimum target and cost 44dp of a 360dp screen.
        val showAlphabet = letters.isNotEmpty() && !continuingOnly && genre == null && formFactor != FormFactor.Phone
        val railEnd = if (showAlphabet) gutter + AlphabetBarWidth else gutter
        val density = LocalDensity.current
        var gridWidthPx by remember { mutableIntStateOf(0) }
        val railBleedEnd = railEnd + with(density) {
            fixedGridSlackPx(gridWidthPx, gutter.roundToPx(), railEnd.roundToPx(), spacing.posterWidth.roundToPx(), spacing.s3.roundToPx()).toDp()
        }

        Box(Modifier.fillMaxSize()) {
            BrowseBackdrop(spotlight, accentColor, gridState, touch) { key ->
                when (key) {
                    "watch-next" -> watchNext.map { it.spotlight() }
                    "recent" -> recentShows.map { it.spotlight() }
                    else -> gridShows.filter { it.id == key }.map { it.spotlight() }
                }
            }
            val gridFocus = remember { FocusRequester() }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.FixedSize(spacing.posterWidth),
                contentPadding = PaddingValues(
                    start = gutter, end = railEnd,
                    top = spacing.s4, bottom = spacing.s6,
                ),
                horizontalArrangement = Arrangement.spacedBy(spacing.s3),
                verticalArrangement = Arrangement.spacedBy(spacing.s4),
                modifier = Modifier.onSizeChanged { gridWidthPx = it.width }.fillMaxSize().focusRequester(gridFocus).swallowUpAtTop(focus, topRowKey),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Column {
                        BrowseHeader(
                            title = if (sportsOnly) "Sports" else "TV Shows",
                            accent = accentColor,
                            countLine = countLine(totalCount, shows.size, if (continuingOnly) "continuing" else "in library"),
                            compact = compact,
                            trailing = if (sportsOnly) null else {
                                {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CategoriesButton(genre, accentColor, onClick = { pickingCategory = true })
                                        SegmentedFilter(
                                            options = listOf("All library", "Continuing"),
                                            selectedIndex = filterIndex,
                                            accent = accentColor,
                                            onSelected = {
                                                if (it != filterIndex) {
                                                    filterIndex = it
                                                    shows.clear()
                                                    nextCursor = null
                                                    prevCursor = null
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(spacing.s4))
                    }
                }
                if (watchNext.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "watch-next") {
                        val watchNextRow = rememberLazyListState()
                        SpotlightOnRowScroll(swiper, watchNextRow) { watchNext.getOrNull(it)?.spotlight() }
                        MediaCarousel(
                            bleedStart = gutter,
                            bleedEnd = railBleedEnd,
                            rowState = watchNextRow,
                            title = "Continue Watching",
                            accentColor = accentColor,
                            badge = "Watching",
                            modifier = Modifier.focusKeyGroup("watchnext"),
                        ) {
                            itemsIndexed(watchNext, key = { _, item -> item.id }) { idx, item ->
                                WideCard(
                                    title = item.title, subtitle = item.subtitle,
                                    imageUrl = item.imageUrl, backdropUrl = item.backdropUrl,
                                    progress = item.progress,
                                    accentColor = accentColor,
                                    onClick = { onContinueClick(item) },
                                    modifier = Modifier
                                        .spotlightOn(pointer) { item.spotlight() }.showingBadge(swiper, item.spotlight())
                                        .focusKey("item.${item.id}", preferredEntry = continueIsEntry && idx == 0)
                                        .onFocusChanged { if (it.isFocused) { focus.lazyIndex = watchNextIndex; focus.rowKey = "watchnext" } },
                                )
                            }
                        }
                    }
                }
                if (recentShows.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "recent") {
                        val recentShowsRow = rememberLazyListState()
                        SpotlightOnRowScroll(swiper, recentShowsRow) { recentShows.getOrNull(it)?.spotlight() }
                        MediaCarousel(
                            bleedStart = gutter,
                            bleedEnd = railBleedEnd,
                            rowState = recentShowsRow,
                            title = "Recently Added",
                            accentColor = accentColor,
                            modifier = Modifier.focusKeyGroup("recent"),
                        ) {
                            itemsIndexed(recentShows, key = { _, show -> show.id }) { idx, show ->
                                PosterCard(
                                    title = show.title,
                                    imageUrl = show.posterUrl,
                                    subtitle = show.network ?: "",
                                    accentColor = accentColor,
                                    onClick = { onShowClick(show) },
                                    modifier = Modifier
                                        .spotlightOn(pointer) { show.spotlight() }.showingBadge(swiper, show.spotlight())
                                        .focusKey("show.${show.id}", preferredEntry = recentIsEntry && idx == 0)
                                        .onFocusChanged { if (it.isFocused) { focus.lazyIndex = recentIndex; focus.rowKey = "recent" } },
                                )
                            }
                        }
                    }
                }

                if (totalItems == 0 && !isReloading) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.LiveTv,
                            title = when {
                                continuingOnly -> "No continuing shows"
                                sportsOnly -> "No leagues yet"
                                else -> "No shows yet"
                            },
                            subtitle = if (continuingOnly) {
                                "Shows still airing new episodes will appear here."
                            } else {
                                "Titles appear here once they are in your library."
                            },
                        )
                    }
                }

                items(totalItems, key = { gridShows[it].id }) { index ->
                    val show = gridShows[index]
                    val lazyIndex = index + headerCount + 1
                    PosterCard(
                        title = show.title,
                        subtitle = show.network,
                        imageUrl = show.posterUrl,
                        accentColor = accentColor,
                        onClick = { onShowClick(show) },
                        modifier = Modifier
                            .spotlightOn(pointer) { show.spotlight() }.showingBadge(swiper, show.spotlight())
                            .focusKey("grid.${show.id}", preferredEntry = gridIsEntry && index == 0)
                            .onFocusChanged { if (it.isFocused) { focus.letter = alphabetLetterOf(show.title); focus.lazyIndex = lazyIndex; focus.rowKey = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndex }?.row.let { row -> rowKeyFor(row, focus.rowKey) } } },
                    )
                }
            }

            if (pickingCategory) {
                BrowseCategoryPage(
                    cacheKey = "show-genres",
                    loadGenres = api::getShowGenres,
                    selected = genre,
                    accent = accentColor,
                    onDismiss = { pickingCategory = false },
                    onPick = { picked ->
                        pickingCategory = false
                        if (picked != genre) {
                            genre = picked
                            shows.clear()
                            nextCursor = null
                            prevCursor = null
                        }
                    },
                )
            }

            if (showAlphabet) {
                BrowseAlphabetBar(
                    letters = letters,
                    exitLeft = gridFocus,
                    focus = focus,
                    onLetterClick = { letter ->
                        scope.launch {
                            try {
                                val firstByLetter = if (sportsOnly) api.getSportsFirstByLetter(letter) else api.getTvFirstByLetter(letter)
                                // around_before=0 makes the server drop the cursor and
                                // return page one (kobbler movies.rs/tv.rs `else` branch),
                                // so ask for one leading item and scroll past it.
                                val result = fetch(around = firstByLetter.id, aroundBefore = 1)
                                val items = api.showListToModels(result.items)
                                shows.clear()
                                shows.addAll(items)
                                nextCursor = result.nextCursor
                                prevCursor = result.prevCursor
                                val target = items.indexOfFirst { it.id == firstByLetter.id }.coerceAtLeast(0)
                                gridState.scrollToItem(headerCount + 1 + target)
                            } catch (_: Exception) {}
                        }
                    },
                )
            }

            if (isLoadingMore) {
                Box(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.BottomCenter), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accentColor, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * The focused cell, held off the screen's own composition. Shared by the three browse
 * screens in this package.
 *
 * Written from `onFocusChanged`, so anything that reads it as a value in a screen body
 * recomposes that whole grid on every D-pad press. Every reader below takes the holder
 * and reads inside its own scope instead.
 */
@Stable
internal class BrowseFocus {
    var lazyIndex by mutableIntStateOf(-1)
    var rowKey by mutableStateOf<Any?>(null)
    var letter by mutableStateOf<String?>(null)
}

@Composable
internal fun CenterFocusedGridRow(state: LazyGridState, focus: BrowseFocus) {
    CenterFocusedItem(state, focus.lazyIndex, focus.rowKey)
}

internal fun Modifier.swallowUpAtTop(focus: BrowseFocus, topRowKey: () -> Any?): Modifier =
    onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionUp &&
            swallowsUp(focus.rowKey, topRowKey())
    }

@Composable
internal fun BoxScope.BrowseAlphabetBar(
    letters: List<String>,
    exitLeft: FocusRequester,
    focus: BrowseFocus,
    onLetterClick: (String) -> Unit,
) {
    AlphabetBar(
        letters = letters,
        exitLeft = exitLeft,
        currentLetter = focus.letter,
        onLetterClick = onLetterClick,
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
}
