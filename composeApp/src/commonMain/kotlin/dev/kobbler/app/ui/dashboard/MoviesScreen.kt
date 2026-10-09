package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val movies = remember { mutableStateListOf<Movie>() }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var prevCursor by remember { mutableStateOf<String?>(null) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(true) }
    var totalCount by remember { mutableStateOf<Long?>(null) }

    suspend fun fetch(cursor: String? = null, direction: String? = null, around: String? = null, aroundBefore: Int? = null) =
        api.getMoviesList(cursor = cursor, direction = direction, around = around, aroundBefore = aroundBefore)

    val (state, retry) = rememberCached("movies") {
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
    val watchNext = (homeState as? Async.Ready)?.value?.watchNext?.filter { it.mediaType == MediaType.Movie } ?: emptyList()

    // Recently added from cached dashboard data
    val (dashState, _) = rememberCached("dashboard") { api.getDashboard() }
    val recentMovies = (dashState as? Async.Ready)?.value?.movies ?: emptyList()

    // Both rails sit above the grid, and each arrives on its own request. Rendering the
    // grid before they land means inserting a rail above it afterwards, which shunts the
    // whole page down under the viewer's focus. Wait for them to settle first.
    val railsSettled = homeState !is Async.Loading && dashState !is Async.Loading

    var letters by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { try { letters = api.getMovieLetters() } catch (_: Exception) {} }

    // Entry focus goes to the first row that actually exists, in reading order.
    var entryFocusPending by remember { mutableStateOf(true) }
    val continueIsEntry = watchNext.isNotEmpty()
    val recentIsEntry = !continueIsEntry && recentMovies.isNotEmpty()
    val gridIsEntry = !continueIsEntry && !recentIsEntry && movies.isNotEmpty()
    val entryKey = when {
        continueIsEntry -> "watchnext.item.${watchNext.first().id}"
        recentIsEntry -> "recent.movie.${recentMovies.first().id}"
        gridIsEntry -> "grid.${movies.first().id}"
        else -> null
    }

    AsyncContent(if (railsSettled) state else Async.Loading, onRetry = retry, modifier = modifier) { _ ->
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
        val showAlphabet = letters.isNotEmpty() && formFactor != FormFactor.Phone

        Box(Modifier.fillMaxSize().background(colors.bg)) {
            val gridFocus = remember { FocusRequester() }
            LazyVerticalGrid(
                modifier = Modifier.focusRequester(gridFocus).swallowUpAtTop(focus, topRowKey),
                state = gridState,
                columns = GridCells.FixedSize(dimens.posterWidth),
                contentPadding = PaddingValues(
                    start = gutter, end = if (showAlphabet) (gutter + AlphabetBarWidth) else gutter,
                    top = dimens.s4, bottom = dimens.s6,
                ),
                horizontalArrangement = Arrangement.spacedBy(dimens.s3),
                verticalArrangement = Arrangement.spacedBy(dimens.s4),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Column {
                        BrowseHeader(
                            title = "Movies",
                            accent = accent,
                            countLine = countLine(totalCount, movies.size, "in library"),
                            compact = compact,
                        )
                        Spacer(Modifier.height(dimens.s4))
                    }
                }

                if (watchNext.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "watch-next") {
                        MediaCarousel(
                            title = "Continue Watching",
                            accentColor = accent,
                            badge = "Watching",
                            modifier = Modifier.focusKeyGroup("watchnext"),
                        ) {
                            itemsIndexed(watchNext, key = { _, item -> item.id }) { idx, item ->
                                WideCard(
                                    title = item.title, subtitle = item.subtitle,
                                    imageUrl = item.imageUrl, backdropUrl = item.backdropUrl,
                                    progress = item.progress,
                                    accentColor = accent,
                                    onClick = { onContinueClick(item) },
                                    modifier = Modifier
                                        .focusKey("item.${item.id}", preferredEntry = continueIsEntry && idx == 0)
                                        .onFocusChanged { if (it.isFocused) { focus.lazyIndex = 1; focus.rowKey = "watchnext" } },
                                )
                            }
                        }
                    }
                }

                if (recentMovies.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "recent") {
                        MediaCarousel(
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
                                    onClick = { onMovieClick(movie) },
                                    modifier = Modifier
                                        .focusKey("recent.movie.${movie.id}", preferredEntry = recentIsEntry && idx == 0)
                                        .onFocusChanged { if (it.isFocused) { focus.lazyIndex = recentIndex; focus.rowKey = "recent" } },
                                )
                            }
                        }
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
                        onClick = { onMovieClick(movie) },
                        modifier = Modifier
                            .focusKey("grid.${movie.id}", preferredEntry = gridIsEntry && index == 0)
                            .onFocusChanged { if (it.isFocused) { focus.letter = alphabetLetterOf(movie.title); focus.lazyIndex = lazyIndex; focus.rowKey = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndex }?.row.let { row -> rowKeyFor(row, focus.rowKey) } } },
                    )
                }
            }

            if (showAlphabet) {
                BrowseAlphabetBar(
                    letters = letters,
                    exitLeft = gridFocus,
                    focus = focus,
                    onLetterClick = { letter ->
                        scope.launch {
                            try {
                                val firstByLetter = api.getMovieFirstByLetter(letter)
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
                                gridState.scrollToItem(headerCount + 1 + target)
                            } catch (_: Exception) {}
                        }
                    },
                )
            }

            if (isLoadingMore) {
                Box(
                    Modifier.fillMaxWidth().padding(16.dp).align(Alignment.BottomCenter),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
