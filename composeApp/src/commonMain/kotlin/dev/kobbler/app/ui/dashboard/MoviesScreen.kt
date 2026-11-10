package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.ui.offline.continueAvailableOffline
import dev.kobbler.app.ui.offline.isAvailableOffline

import androidx.compose.foundation.layout.*
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.LocalTopChromeOverlap
import dev.kobbler.app.ui.components.BrowseSkeleton
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
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
fun MoviesScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onMovieClick: (Movie) -> Unit,
    onContinueClick: (ContinueItem) -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
) {
    val movies = remember { mutableStateListOf<Movie>() }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var prevCursor by remember { mutableStateOf<String?>(null) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(true) }
    var totalCount by remember { mutableStateOf<Long?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingCategory by remember { mutableStateOf(false) }
    val pickGenre: (String?) -> Unit = { picked ->
        if (picked != genre) {
            genre = picked
            movies.clear()
            nextCursor = null
            prevCursor = null
        }
    }

    suspend fun fetch(cursor: String? = null, direction: String? = null, around: String? = null, aroundBefore: Int? = null) =
        api.getMoviesList(cursor = cursor, direction = direction, around = around, aroundBefore = aroundBefore, genre = genre)

    val (state, retry) = rememberCached(browseCacheKey("movies", genre)) {
        isReloading = true
        try {
            val result = fetch()
            val items = withContext(Dispatchers.Default) { api.movieListToModels(result.items) }
            CachedPage(items, result.nextCursor, result.prevCursor, result.totalCount)
        } finally {
            isReloading = false
        }
    }

    // Not filled by the loader: a warm TTL entry skips it, and a consumer that loses the
    // in-flight race never runs one. The resolved page is the only signal both paths share.
    LaunchedEffect(state) {
        val page = (state as? Async.Ready)?.value ?: return@LaunchedEffect
        movies.clear()
        movies.addAll(page.items)
        nextCursor = page.next
        prevCursor = page.prev
        totalCount = page.total
        isReloading = false
    }

    val (homeState, _) = rememberCached("home") { api.getDiscover() }
    // A category is a narrower question than the rails above the grid answer.
    val watchNext = remember(homeState, genre) {
        if (genre != null) emptyList()
        else (homeState as? Async.Ready)?.value?.watchNext?.filter { it.mediaType == MediaType.Movie } ?: emptyList()
    }

    // Recently added from cached dashboard data
    val (dashState, _) = rememberCached("dashboard") { api.getDashboard() }
    val recentMovies = if (genre != null) emptyList() else (dashState as? Async.Ready)?.value?.movies ?: emptyList()

    // Both rails sit above the grid, and each arrives on its own request. Rendering the
    // grid before they land means inserting a rail above it afterwards, which shunts the
    // whole page down under the viewer's focus. Wait for them to settle first.
    val railsSettled = homeState !is Async.Loading && dashState !is Async.Loading

    var letters by remember(genre) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(genre) { try { letters = api.getMovieLetters(genre) } catch (_: Exception) {} }

    // Entry focus goes to the first row that actually exists, in reading order. A new
    // category re-arms it: the picker's dialog closes onto a selector that has just been
    // replaced and a grid still loading, which leaves the D-pad focused on nothing.
    var entryFocusPending by remember(genre) { mutableStateOf(true) }
    val continueIsEntry = watchNext.isNotEmpty()
    val recentIsEntry = !continueIsEntry && recentMovies.isNotEmpty()
    val gridIsEntry = !continueIsEntry && !recentIsEntry && movies.isNotEmpty()
    val entryKey = when {
        continueIsEntry -> "watchnext.item.${watchNext.first().id}"
        recentIsEntry -> "recent.movie.${recentMovies.first().id}"
        gridIsEntry -> "grid.${movies.first().id}"
        else -> null
    }

    val touch = formFactor == FormFactor.Phone
    val spotlight = rememberSpotlightState(
        watchNext.firstOrNull()?.spotlight() ?: recentMovies.firstOrNull()?.spotlight() ?: movies.firstOrNull()?.spotlight(),
    )
    val pointer = spotlight.takeUnless { touch }
    val swiper = spotlight.takeIf { touch }

    AsyncContent(if (railsSettled) state else Async.Loading, skeleton = { BrowseSkeleton() }, onRetry = retry, modifier = modifier) { _ ->
        val gridState = rememberLazyGridState()
        val colors = KobappTheme.colors
        val dimens = KobappTheme.dimens
        val scope = rememberCoroutineScope()
        val compact = formFactor == FormFactor.Phone

        // Snapshot copy: the grid's key/content lambdas run after a reload has already
        // emptied `movies`, so indexing the live list against a captured count throws
        // IndexOutOfBounds.
        val gridMovies = movies.toList()
        val totalItems = gridMovies.size
        val watchNextIndex = if (watchNext.isNotEmpty()) 1 else -1
        val recentIndex = if (recentMovies.isNotEmpty()) 1 + (if (watchNextIndex >= 0) 1 else 0) else -1
        // The rails, plus the grid's own heading when there is a grid to head.
        val headerCount = (if (watchNextIndex >= 0) 1 else 0) + (if (recentIndex >= 0) 1 else 0) + (if (totalItems > 0) 1 else 0)
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
                    movies.addAll(api.movieListToModels(result.items))
                    nextCursor = result.nextCursor
                } catch (_: Exception) {}
                isLoadingMore = false
            }
        }

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
                    val newItems = api.movieListToModels(result.items)
                    if (newItems.isNotEmpty()) {
                        movies.addAll(0, newItems)
                        prevCursor = result.prevCursor
                    } else {
                        prevCursor = null
                    }
                } catch (_: Exception) {}
                isLoadingMore = false
            }
        }

        val gutter = when (formFactor) {
            FormFactor.TV -> dimens.pageGutterTv
            FormFactor.Phone -> dimens.pageGutterPhone
            else -> dimens.pageGutter
        }
        val accent = colors.accMovies
        // 22dp letters stacked 26 deep are a pointer/D-pad affordance; on touch they are
        // under half the minimum target and cost 44dp of a 360dp screen.
        // The bar's slot is held before its letters arrive: the header ends at it, and
        // letters landing late slid the selector sideways.
        val alphabetSlot = formFactor != FormFactor.Phone
        val showAlphabet = alphabetSlot && letters.isNotEmpty()
        val railEnd = if (alphabetSlot) gutter + AlphabetBarWidth else gutter
        val density = LocalDensity.current
        var gridWidthPx by remember { mutableIntStateOf(0) }
        val railBleedEnd = railEnd + with(density) {
            fixedGridSlackPx(gridWidthPx, gutter.roundToPx(), railEnd.roundToPx(), dimens.posterWidth.roundToPx(), dimens.s3.roundToPx()).toDp()
        }

        Box(Modifier.fillMaxSize()) {
            BrowseBackdrop(spotlight, accent, gridState, touch) { key ->
                when (key) {
                    "watch-next" -> watchNext.map { it.spotlight() }
                    "recent" -> recentMovies.map { it.spotlight() }
                    else -> gridMovies.filter { it.id == key }.map { it.spotlight() }
                }
            }
            val gridFocus = remember { FocusRequester() }
            FocusEdgeSlack {
            LazyVerticalGrid(
                modifier = Modifier.scrollsUnderTopChrome(gridState).onSizeChanged { gridWidthPx = it.width }.focusRequester(gridFocus).swallowUpAtTop(focus, topRowKey),
                state = gridState,
                columns = GridCells.FixedSize(dimens.posterWidth),
                contentPadding = PaddingValues(
                    start = gutter, end = railEnd,
                    top = dimens.s4 + LocalTopChromeOverlap.current, bottom = dimens.s6,
                ),
                horizontalArrangement = Arrangement.spacedBy(dimens.s3),
                verticalArrangement = Arrangement.spacedBy(dimens.s4),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    // Fixed-size columns leave the grid's slack past the last one, and a
                    // full-span item is only as wide as the columns: the header's selector
                    // stopped short of the rails' edge, and on a phone wrapped under the title.
                    Column(Modifier.bleedHorizontally(start = 0.dp, end = railBleedEnd - railEnd)) {
                        BrowseHeader(
                            title = "Movies",
                            accent = accent,
                            compact = compact,
                            onSearch = onSearchClick,
                            trailing = {
                                BrowseScopeFilter(
                                    scopes = listOf(allLabel(compact)),
                                    selectedScope = 0,
                                    category = genre,
                                    accent = accent,
                                    onScope = { pickGenre(null) },
                                    onCategories = { pickingCategory = true },
                                )
                            },
                        )
                        Spacer(Modifier.height(dimens.s4))
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
                            accentColor = accent,
                            modifier = Modifier.focusKeyGroup("watchnext"),
                        ) {
                            itemsIndexed(watchNext, key = { _, item -> item.id }) { idx, item ->
                                WideCard(
                                    title = item.title, subtitle = item.subtitle,
                                    imageUrl = item.imageUrl, backdropUrl = item.backdropUrl,
                                    progress = item.progress,
                                    accentColor = accent,
                                    offline = continueAvailableOffline(item.mediaType, item.id),
                                    onClick = { onContinueClick(item) },
                                    modifier = Modifier
                                        .spotlightOn(pointer) { item.spotlight() }.showingBadge(swiper, item.spotlight())
                                        .focusKey("item.${item.id}", preferredEntry = continueIsEntry && idx == 0)
                                        .onFocusChanged { if (it.isFocused) { focus.lazyIndex = 1; focus.rowKey = "watchnext" } },
                                )
                            }
                        }
                    }
                }

                if (recentMovies.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "recent") {
                        val recentMoviesRow = rememberLazyListState()
                        SpotlightOnRowScroll(swiper, recentMoviesRow) { recentMovies.getOrNull(it)?.spotlight() }
                        MediaCarousel(
                            bleedStart = gutter,
                            bleedEnd = railBleedEnd,
                            rowState = recentMoviesRow,
                            title = "Recently Added",
                            accentColor = accent,
                            modifier = Modifier.focusKeyGroup("recent"),
                        ) {
                            itemsIndexed(recentMovies, key = { _, movie -> movie.id }) { idx, movie ->
                                PosterCard(
                                    title = movie.title,
                                    imageUrl = movie.posterUrl,
                                    subtitle = if (movie.year != 0) movie.year.toString() else null,
                                    accentColor = accent,
                                    offline = isAvailableOffline(OfflineKind.Movie, movie.id),
                                    onClick = { onMovieClick(movie) },
                                    modifier = Modifier
                                        .spotlightOn(pointer) { movie.spotlight() }.showingBadge(swiper, movie.spotlight())
                                        .focusKey("movie.${movie.id}", preferredEntry = recentIsEntry && idx == 0)
                                        .onFocusChanged { if (it.isFocused) { focus.lazyIndex = recentIndex; focus.rowKey = "recent" } },
                                )
                            }
                        }
                    }
                }

                if (totalItems > 0) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "all-heading") {
                        BrowseGridHeading(
                            title = genre ?: "All movies",
                            count = gridCount(totalCount, totalItems),
                            accent = accent,
                            modifier = Modifier.bleedHorizontally(start = 0.dp, end = railBleedEnd - railEnd),
                        )
                    }
                }

                if (totalItems == 0 && !isReloading) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.Movie,
                            title = "No movies yet",
                            subtitle = "Movies appear here once they are in your library.",
                        )
                    }
                }

                items(totalItems, key = { gridMovies[it].id }) { index ->
                    val movie = gridMovies[index]
                    val lazyIndex = index + headerCount + 1
                    PosterCard(
                        title = movie.title,
                        imageUrl = movie.posterUrl,
                        subtitle = if (movie.year != 0) movie.year.toString() else null,
                        accentColor = accent,
                        offline = isAvailableOffline(OfflineKind.Movie, movie.id),
                        onClick = { onMovieClick(movie) },
                        modifier = Modifier
                            .spotlightOn(pointer) { movie.spotlight() }.showingBadge(swiper, movie.spotlight())
                            .focusKey("grid.${movie.id}", preferredEntry = gridIsEntry && index == 0)
                            .browseCell(focus, movie.id, movie.title, lazyIndex, gridState),
                    )
                }
            }
            }

            if (showAlphabet) {
                BrowseAlphabetBar(
                    letters = letters,
                    grid = gridFocus,
                    focus = focus,
                    onLetterClick = { letter ->
                        scope.launch {
                            try {
                                val firstByLetter = api.getMovieFirstByLetter(letter, genre)
                                // around_before=0 makes the server drop the cursor and
                                // return page one (kobbler movies.rs/tv.rs `else` branch),
                                // so ask for one leading item and scroll past it.
                                val result = fetch(around = firstByLetter.id, aroundBefore = 1)
                                val items = api.movieListToModels(result.items)
                                movies.clear()
                                movies.addAll(items)
                                nextCursor = result.nextCursor
                                prevCursor = result.prevCursor
                                val target = items.indexOfFirst { it.id == firstByLetter.id }.coerceAtLeast(0)
                                items.getOrNull(target)?.let { focus.letterJump = LetterJump(headerCount + 1 + target, it.id) }
                            } catch (_: Exception) {}
                        }
                    },
                )
            }

            if (pickingCategory) {
                BrowseCategoryPage(
                    cacheKey = "movie-genres",
                    loadGenres = api::getMovieGenres,
                    selected = genre,
                    accent = accent,
                    onDismiss = { pickingCategory = false },
                    onPick = { picked ->
                        pickingCategory = false
                        pickGenre(picked)
                    },
                )
            }

            LoadingMoreIndicator({ isLoadingMore }, accent)
        }
    }
}
