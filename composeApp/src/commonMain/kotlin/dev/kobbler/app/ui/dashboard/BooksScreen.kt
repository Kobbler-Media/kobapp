package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import dev.kobbler.app.ui.scrollsUnderTopChrome
import dev.kobbler.app.ui.LocalTopChromeOverlap
import dev.kobbler.app.ui.components.GridSkeleton
import dev.kobbler.app.ui.components.BrowseSkeleton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.kobbler.app.ui.components.SpotlightOnRowScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.*
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ClaimEntryFocus
import dev.kobbler.app.ui.components.Async
import dev.kobbler.app.ui.components.AlphabetBarWidth
import dev.kobbler.app.ui.components.alphabetLetterOf
import dev.kobbler.app.ui.components.AsyncContent
import dev.kobbler.app.ui.PinnedHeader
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.CachedPage
import dev.kobbler.app.ui.components.rowKeyFor
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.components.PosterCard
import dev.kobbler.app.ui.components.SegmentedFilter
import dev.kobbler.app.ui.components.rememberSpotlightState
import dev.kobbler.app.ui.components.spotlight
import dev.kobbler.app.ui.components.showingBadge
import dev.kobbler.app.ui.components.spotlightOn
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.components.bleedHorizontally
import dev.kobbler.app.ui.components.gridCount
import dev.kobbler.app.ui.components.BrowseGridHeading
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.components.LocalPageBackdrop
import dev.kobbler.app.ui.components.PageBackdropHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BooksTab { All, Authors, Series }

private fun isContentKey(key: String): Boolean =
    key.startsWith("book.") || key.startsWith("readnext.") || key.startsWith("recent.")

@Composable
fun BooksScreen(
    api: KobblerApi,
    audiobooksOnly: Boolean,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onBookClick: (Book) -> Unit,
    onContinueClick: (ContinueItem) -> Unit = {},
    onAuthorClick: ((String) -> Unit)? = null,
    onSeriesClick: ((String) -> Unit)? = null,
    onSearchClick: (() -> Unit)? = null,
) {
    var tab by remember { mutableStateOf(BooksTab.All) }
    // Latched by any deliberate tab press: after that, landing back on All must leave
    // focus on the strip the user is driving instead of diving into the grid.
    var entryFocusPending by remember { mutableStateOf(true) }
    val colors = KobappTheme.colors
    val accentColor = if (audiobooksOnly) colors.accAudio else colors.accBooks
    val compact = formFactor == FormFactor.Phone
    val dimens = KobappTheme.dimens
    val headerGutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }

    val page: @Composable (Modifier) -> Unit = { pageModifier -> PinnedHeader(header = {
        if (onAuthorClick != null && onSeriesClick != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = headerGutter, vertical = if (compact) 12.dp else 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrowseHeader(
                    title = if (audiobooksOnly) "Audiobooks" else "Books",
                    accent = accentColor,
                    compact = compact,
                    onSearch = onSearchClick,
                    trailing = {
                        SegmentedFilter(
                            options = BooksTab.entries.map { it.name },
                            selectedIndex = BooksTab.entries.indexOf(tab),
                            accent = accentColor,
                            onSelected = {
                                entryFocusPending = false
                                tab = BooksTab.entries[it]
                            },
                        )
                    },
                )
            }
        }
    }, modifier = pageModifier.fillMaxSize()) {
        when (tab) {
            BooksTab.All -> AllBooksGrid(
                api, audiobooksOnly, formFactor, onBookClick, onContinueClick, accentColor,
                entryFocusPending = entryFocusPending,
                onEntryFocused = { entryFocusPending = false },
            )
            BooksTab.Authors -> AuthorsGrid(api, headerGutter, onAuthorClick ?: {})
            BooksTab.Series -> SeriesGrid(api, headerGutter, onSeriesClick ?: {})
        }
    } }
    // The grid owns the spotlight but sits below the header, so on its own its picture
    // would start under the All/Authors/Series strip rather than at the top of the page.
    if (LocalPageBackdrop.current == null) PageBackdropHost(modifier) { page(Modifier) } else page(modifier)
}

@Composable
private fun AllBooksGrid(
    api: KobblerApi,
    audiobooksOnly: Boolean,
    formFactor: FormFactor,
    onBookClick: (Book) -> Unit,
    onContinueClick: (ContinueItem) -> Unit,
    accentColor: androidx.compose.ui.graphics.Color,
    entryFocusPending: Boolean,
    onEntryFocused: () -> Unit,
) {
    val cacheKey = if (audiobooksOnly) "books_audiobooks" else "books_all"
    // rememberCached hands back the cached page synchronously but the list itself is
    // only refilled inside `load`, so without this seed a warm revisit still renders an
    // empty grid on frame one — long enough for the host's restore/entry pass to expire.
    val books = remember(cacheKey) {
        mutableStateListOf<Book>().apply { DataCache.get<CachedPage<Book>>(cacheKey)?.let { addAll(it.items) } }
    }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var prevCursor by remember { mutableStateOf<String?>(null) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var progressBook by remember { mutableStateOf<Book?>(null) }
    val scope = rememberCoroutineScope()

    // Server-side, not a filter over a full page of books: the grid pages by cursor, and
    // a page filtered after the fact yields a handful of items, an alphabet jump that
    // lands on a book the grid then drops, and a backward cursor that stops at the first
    // all-ebook page.
    val format = if (audiobooksOnly) "audiobook" else null

    var totalCount by remember(cacheKey) { mutableStateOf<Long?>(null) }
    val (state, retry) = rememberCached(cacheKey) {
        val result = api.getBooks(format = format)
        val books = withContext(Dispatchers.Default) { api.bookListToModels(result.items) }
        CachedPage(books, result.nextCursor, result.prevCursor, result.totalCount)
    }

    // Not filled by the loader: a warm TTL entry skips it, and a consumer that loses the
    // in-flight race never runs one. The resolved page is the only signal both paths share.
    LaunchedEffect(state) {
        val page = (state as? Async.Ready)?.value ?: return@LaunchedEffect
        books.clear()
        books.addAll(page.items)
        nextCursor = page.next
        prevCursor = page.prev
        totalCount = page.total
    }

    // Read next from cached dashboard data
    val (dashState, _) = rememberCached("dashboard") { api.getDashboard() }
    val dash = (dashState as? Async.Ready)?.value
    val readNext = remember(dash, audiobooksOnly) {
        dash?.readNextItems?.filter {
            it.mediaType == if (audiobooksOnly) MediaType.Audiobook else MediaType.Book
        } ?: emptyList()
    }

    // Recently added books
    val recentBooks = (if (audiobooksOnly) dash?.audiobooks else dash?.books) ?: emptyList()

    // Alphabet letters
    var letters by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(format) { try { letters = api.getBookLetters(format) } catch (_: Exception) {} }

    // Entry focus goes to the first row that actually exists, in reading order.
    val continueIsEntry = readNext.isNotEmpty()
    val recentIsEntry = !continueIsEntry && recentBooks.isNotEmpty()
    val gridIsEntry = !continueIsEntry && !recentIsEntry && books.isNotEmpty()
    val entryKey = when {
        continueIsEntry -> "readnext.item.${readNext.first().id}"
        recentIsEntry -> "recent.book.${recentBooks.first().id}"
        gridIsEntry -> "book.${books.first().id}"
        else -> null
    }

    // Both rails sit above the grid on the dashboard's own request; inserting them
    // afterwards shunts the whole page down under the viewer's focus.
    val railsSettled = dashState !is Async.Loading
    val touch = formFactor == FormFactor.Phone
    val spotlight = rememberSpotlightState(
        readNext.firstOrNull()?.spotlight() ?: recentBooks.firstOrNull()?.spotlight() ?: books.firstOrNull()?.spotlight(),
    )
    val pointer = spotlight.takeUnless { touch }
    val swiper = spotlight.takeIf { touch }

    AsyncContent(if (railsSettled) state else Async.Loading, skeleton = { BrowseSkeleton() }, onRetry = retry) { _ ->
        val gridState = rememberLazyGridState()
        val readNextIndex = if (readNext.isNotEmpty()) 0 else -1
        val recentIndex = if (recentBooks.isNotEmpty()) (if (readNextIndex >= 0) 1 else 0) else -1
        // The rails, plus the grid's own heading when there is a grid to head.
        val headerCount = (if (readNext.isNotEmpty()) 1 else 0) + (if (recentBooks.isNotEmpty()) 1 else 0) + (if (books.isNotEmpty()) 1 else 0)
        val topRowKey: () -> Any? = {
            if (readNext.isNotEmpty()) "readnext" else if (recentBooks.isNotEmpty()) "recent" else
                gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == headerCount }?.row
        }

        val focus = remember { BrowseFocus() }
        CenterFocusedGridRow(gridState, focus)

        ClaimEntryFocus(
            entryKey = entryKey,
            pending = entryFocusPending,
            isContentKey = ::isContentKey,
            onClaimed = onEntryFocused,
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
                    val result = api.getBooks(cursor = nextCursor, format = format)
                    books.addAll(api.bookListToModels(result.items))
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
                    val result = api.getBooks(cursor = prevCursor, direction = "backward", format = format)
                    val newBooks = api.bookListToModels(result.items)
                    if (newBooks.isNotEmpty()) {
                        books.addAll(0, newBooks)
                        prevCursor = result.prevCursor
                    } else {
                        prevCursor = null
                    }
                } catch (_: Exception) {}
                isLoadingMore = false
            }
        }

        val dimens = KobappTheme.dimens
        val gutter = when (formFactor) {
            FormFactor.TV -> dimens.pageGutterTv
            FormFactor.Phone -> dimens.pageGutterPhone
            else -> dimens.pageGutter
        }
        // 22dp letters stacked 26 deep are a pointer/D-pad affordance; on touch they are
        // under half the minimum target and cost 44dp of a 360dp screen.
        val showAlphabet = letters.isNotEmpty() && formFactor != FormFactor.Phone
        val railEnd = if (showAlphabet) gutter + AlphabetBarWidth else gutter
        val density = LocalDensity.current
        var gridWidthPx by remember { mutableIntStateOf(0) }
        val railBleedEnd = railEnd + with(density) {
            fixedGridSlackPx(gridWidthPx, gutter.roundToPx(), railEnd.roundToPx(), dimens.posterWidth.roundToPx(), dimens.s3.roundToPx()).toDp()
        }

        Box(Modifier.fillMaxSize()) {
            BrowseBackdrop(spotlight, accentColor, gridState, touch) { key ->
                when (key) {
                    "read-next" -> readNext.map { it.spotlight() }
                    "recent" -> recentBooks.map { it.spotlight() }
                    else -> books.filter { it.id == key }.map { it.spotlight() }
                }
            }
            val gridFocus = remember { FocusRequester() }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.FixedSize(dimens.posterWidth),
                contentPadding = PaddingValues(
                    start = gutter, top = dimens.s4 + LocalTopChromeOverlap.current, bottom = dimens.s4,
                    end = railEnd,
                ),
                horizontalArrangement = Arrangement.spacedBy(dimens.s3),
                verticalArrangement = Arrangement.spacedBy(dimens.s4),
                modifier = Modifier.scrollsUnderTopChrome(gridState).onSizeChanged { gridWidthPx = it.width }.fillMaxSize().focusInert(progressBook != null).focusRequester(gridFocus).swallowUpAtTop(focus, topRowKey),
            ) {
                if (readNext.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "read-next") {
                        val readNextRow = rememberLazyListState()
                        SpotlightOnRowScroll(swiper, readNextRow) { readNext.getOrNull(it)?.spotlight() }
                        dev.kobbler.app.ui.components.MediaCarousel(
                            bleedStart = gutter,
                            bleedEnd = railBleedEnd,
                            rowState = readNextRow,
                            title = if (audiobooksOnly) "Continue Listening" else "Continue Reading",
                            accentColor = accentColor,
                            modifier = Modifier.focusKeyGroup("readnext"),
                        ) {
                            itemsIndexed(readNext, key = { _, item -> item.id }) { idx, item ->
                                dev.kobbler.app.ui.components.WideCard(title = item.title, subtitle = item.subtitle, imageUrl = item.imageUrl, progress = item.progress, onClick = { onContinueClick(item) }, modifier = Modifier.spotlightOn(pointer) { item.spotlight() }.showingBadge(swiper, item.spotlight()).focusKey("item.${item.id}", preferredEntry = continueIsEntry && idx == 0).onFocusChanged { if (it.isFocused) { focus.lazyIndex = readNextIndex; focus.rowKey = "readnext" } })
                            }
                        }
                    }
                }
                if (recentBooks.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "recent") {
                        val recentBooksRow = rememberLazyListState()
                        SpotlightOnRowScroll(swiper, recentBooksRow) { recentBooks.getOrNull(it)?.spotlight() }
                        dev.kobbler.app.ui.components.MediaCarousel(
                            bleedStart = gutter,
                            bleedEnd = railBleedEnd,
                            rowState = recentBooksRow,
                            title = "Recently Added",
                            accentColor = accentColor,
                            modifier = Modifier.focusKeyGroup("recent"),
                        ) {
                            itemsIndexed(recentBooks, key = { _, b -> b.id }) { idx, b ->
                                dev.kobbler.app.ui.components.PosterCard(title = b.title, imageUrl = b.coverUrl, subtitle = b.author, accentColor = accentColor, onClick = { onBookClick(b) }, modifier = Modifier.spotlightOn(pointer) { b.spotlight() }.showingBadge(swiper, b.spotlight()).focusKey("book.${b.id}", preferredEntry = recentIsEntry && idx == 0).onFocusChanged { if (it.isFocused) { focus.lazyIndex = recentIndex; focus.rowKey = "recent" } })
                            }
                        }
                    }
                }

                if (books.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "all-heading") {
                        BrowseGridHeading(
                            title = if (audiobooksOnly) "All audiobooks" else "All books",
                            count = gridCount(totalCount, books.size),
                            accent = accentColor,
                            modifier = Modifier.bleedHorizontally(start = 0.dp, end = railBleedEnd - railEnd),
                        )
                    }
                }

                itemsIndexed(books, key = { _, it -> it.id }) { bookIdx, book ->
                    PosterCard(
                        title = book.title,
                        imageUrl = book.coverUrl,
                        subtitle = book.author,
                        accentColor = accentColor,
                        onClick = { onBookClick(book) },
                        onLongClick = { progressBook = book },
                        modifier = Modifier
                            .spotlightOn(pointer) { book.spotlight() }.showingBadge(swiper, book.spotlight())
                            .focusKey("book.${book.id}", preferredEntry = gridIsEntry && bookIdx == 0)
                            .onFocusChanged { if (it.isFocused) { focus.letter = alphabetLetterOf(book.title); focus.lazyIndex = bookIdx + headerCount; focus.rowKey = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == bookIdx + headerCount }?.row.let { row -> rowKeyFor(row, focus.rowKey) } } },
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
                                val firstByLetter = api.getBookFirstByLetter(letter, format)
                                // around_before=0 makes the server drop the cursor and
                                // return page one (kobbler movies.rs/tv.rs `else` branch),
                                // so ask for one leading item and scroll past it.
                                val result = api.getBooks(around = firstByLetter.id, aroundBefore = 1, format = format)
                                val newBooks = api.bookListToModels(result.items)
                                books.clear()
                                books.addAll(newBooks)
                                nextCursor = result.nextCursor
                                prevCursor = result.prevCursor
                                val target = newBooks.indexOfFirst { it.id == firstByLetter.id }.coerceAtLeast(0)
                                gridState.scrollToItem(headerCount + target)
                            } catch (_: Exception) {}
                        }
                    },
                )
            }

            LoadingMoreIndicator({ isLoadingMore }, accentColor)

            val popoverBook = progressBook
            if (popoverBook != null) {
                val progressState by produceState(0f, popoverBook.id) {
                    value = try {
                        val pct = api.getReaderProgress(popoverBook.id).progressPercent ?: 0.0
                        (pct / 100.0).toFloat().coerceIn(0f, 1f)
                    } catch (_: Exception) { 0f }
                }
                dev.kobbler.app.ui.components.ReadingProgressPopover(
                    book = popoverBook,
                    progress = progressState,
                    onDismiss = { progressBook = null },
                )
            }
        }
    }
}

@Composable
private fun AuthorsGrid(api: KobblerApi, gutter: Dp, onAuthorClick: (String) -> Unit) {
    val authors = remember { mutableStateListOf<Author>() }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var isLoadingMore by remember { mutableStateOf(false) }

    val (state, retry) = rememberCached("authors") {
        val result = api.getAuthors()
        CachedPage(withContext(Dispatchers.Default) { api.authorListToModels(result.items) }, result.nextCursor)
    }

    LaunchedEffect(state) {
        val page = (state as? Async.Ready)?.value ?: return@LaunchedEffect
        authors.clear()
        authors.addAll(page.items)
        nextCursor = page.next
    }

    AsyncContent(state, skeleton = { GridSkeleton() }, onRetry = retry) { _ ->
        val gridState = rememberLazyGridState()

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
                    val result = api.getAuthors(cursor = nextCursor)
                    authors.addAll(api.authorListToModels(result.items))
                    nextCursor = result.nextCursor
                } catch (_: Exception) {}
                isLoadingMore = false
            }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = KobappTheme.dimens.posterWidth),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 16.dp + LocalTopChromeOverlap.current, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.scrollsUnderTopChrome(gridState).fillMaxSize(),
        ) {
            itemsIndexed(authors, key = { _, it -> it.id }) { idx, author ->
                AuthorCard(author = author, preferredEntry = idx == 0, onClick = { onAuthorClick(author.id) })
            }
        }
    }
}

@Composable
private fun SeriesGrid(api: KobblerApi, gutter: Dp, onSeriesClick: (String) -> Unit) {
    val seriesList = remember { mutableStateListOf<Series>() }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var isLoadingMore by remember { mutableStateOf(false) }

    val (state, retry) = rememberCached("series") {
        val result = api.getSeriesList()
        CachedPage(withContext(Dispatchers.Default) { api.seriesListToModels(result.items) }, result.nextCursor)
    }

    LaunchedEffect(state) {
        val page = (state as? Async.Ready)?.value ?: return@LaunchedEffect
        seriesList.clear()
        seriesList.addAll(page.items)
        nextCursor = page.next
    }

    AsyncContent(state, skeleton = { GridSkeleton() }, onRetry = retry) { _ ->
        val gridState = rememberLazyGridState()

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
                    val result = api.getSeriesList(cursor = nextCursor)
                    seriesList.addAll(api.seriesListToModels(result.items))
                    nextCursor = result.nextCursor
                } catch (_: Exception) {}
                isLoadingMore = false
            }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = KobappTheme.dimens.posterWidth),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 16.dp + LocalTopChromeOverlap.current, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.scrollsUnderTopChrome(gridState).fillMaxSize(),
        ) {
            itemsIndexed(seriesList, key = { _, it -> it.id }) { idx, series ->
                SeriesCard(series = series, preferredEntry = idx == 0, onClick = { onSeriesClick(series.id) })
            }
        }
    }
}

@Composable
private fun AuthorCard(author: Author, preferredEntry: Boolean, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    Column(
        modifier = Modifier
            .focusKey("author.${author.id}", preferredEntry = preferredEntry)
            .tvAutoGlow(cornerRadiusDp = 8f, scale = 1.03f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.elev2),
        ) {
            val covers = author.coverUrls.take(4)
            if (covers.size >= 4) {
                Column {
                    Row(Modifier.weight(1f)) {
                        covers.take(2).forEach { url ->
                            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    Row(Modifier.weight(1f)) {
                        covers.drop(2).take(2).forEach { url ->
                            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            } else if (covers.isNotEmpty()) {
                AsyncImage(model = covers.first(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = author.name,
            style = KobappTheme.type.bodySm,
            color = colors.textHi,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${author.bookCount} book${if (author.bookCount != 1L) "s" else ""}",
            style = KobappTheme.type.caption,
            color = colors.textLo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SeriesCard(series: Series, preferredEntry: Boolean, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    Column(
        modifier = Modifier
            .focusKey("series.${series.id}", preferredEntry = preferredEntry)
            .tvAutoGlow(cornerRadiusDp = 8f, scale = 1.03f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(2f / 3f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.elev2),
        ) {
            val covers = series.coverUrls.take(3)
            if (covers.size >= 3) {
                Row {
                    covers.forEach { url ->
                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else if (covers.isNotEmpty()) {
                AsyncImage(model = covers.first(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = series.name,
            style = KobappTheme.type.bodySm,
            color = colors.textHi,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append("${series.bookCount} book${if (series.bookCount != 1L) "s" else ""}")
                if (series.authorNames.isNotEmpty()) append(" \u2022 ${series.authorNames}")
            },
            style = KobappTheme.type.caption,
            color = colors.textLo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
