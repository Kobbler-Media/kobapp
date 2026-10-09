package dev.kobbler.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.Bookmark
import dev.kobbler.app.data.BookmarkRepository
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.TtsVoiceDto
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FileCache
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.reader.epub.TtsWordHighlight
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerConfig
import dev.kobbler.kobplayer.PlayerState
import dev.kobbler.kobplayer.createPlayer
import kotlin.math.abs
import kotlin.time.Clock
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.kobbler.app.platform.openZipArchive

/** Outlives the reader composition so the exit save is not cancelled by navigation. */
private val readerExitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

@Composable
fun ReaderScreen(
    book: Book,
    api: KobblerApi,
    formFactor: FormFactor,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var epubBook by remember { mutableStateOf<EpubBook?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentChapter by remember { mutableIntStateOf(0) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var readerBookmarks by remember { mutableStateOf(BookmarkRepository.getBookmarks(book.id)) }
    // The web reader writes a character-precise CFI to the same reading state; this app has
    // no CFI machinery, so it echoes back whatever it fetched rather than nulling it out.
    var savedCfi by remember { mutableStateOf<String?>(null) }
    var readingStateLoaded by remember { mutableStateOf(false) }

    var brightness by remember {
        mutableStateOf(UserSettings.getString("reader_brightness")?.toFloatOrNull() ?: 1f)
    }
    var themeMode by remember {
        mutableStateOf(
            UserSettings.getString("reader_theme")?.let {
                try { ReaderThemeMode.valueOf(it) } catch (_: Exception) { null }
            } ?: ReaderThemeMode.Dark
        )
    }
    // Only the first-run default is form-factor aware; a stored value always wins, so
    // changing this cannot fight a size the user already chose.
    val defaultFontSize = if (formFactor == FormFactor.TV) 28f else 16f
    var fontSize by remember {
        mutableStateOf(UserSettings.getString("reader_font_size")?.toFloatOrNull() ?: defaultFontSize)
    }
    var lineHeightMultiplier by remember {
        mutableStateOf(UserSettings.getString("reader_line_height")?.toFloatOrNull() ?: 1.625f)
    }
    var horizontalPadding by remember {
        mutableStateOf(UserSettings.getString("reader_h_padding")?.toIntOrNull() ?: 20)
    }
    var readerFontFamily by remember {
        mutableStateOf(
            UserSettings.getString("reader_font_family")?.let {
                try { ReaderFontFamily.valueOf(it) } catch (_: Exception) { null }
            } ?: ReaderFontFamily.Default
        )
    }
    fun saveSettings() {
        UserSettings.putString("reader_theme", themeMode.name)
        UserSettings.putString("reader_font_size", fontSize.toString())
        UserSettings.putString("reader_line_height", lineHeightMultiplier.toString())
        UserSettings.putString("reader_h_padding", horizontalPadding.toString())
        UserSettings.putString("reader_font_family", readerFontFamily.name)
        UserSettings.putString("reader_brightness", brightness.toString())
    }

    val scrollState = rememberScrollState()
    var contentHeight by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val (currentPage, totalPages) = pageCounter(scrollState.value, viewportHeight, scrollState.maxValue)

    val weights = remember(epubBook) { epubBook?.let { chapterWeights(it.chapters) } ?: emptyList() }

    val persistProgress: suspend () -> Unit = persist@{
        if (epubBook == null) return@persist
        if (!readingStateLoaded) return@persist
        val percent = readingProgressPercent(
            chapterIndex = currentChapter,
            chapterWeights = weights,
            scrollOffset = scrollState.value,
            maxScroll = scrollState.maxValue,
        )
        try { api.saveReaderProgress(book.id, savedCfi, percent) } catch (_: Exception) {}
    }

    val ttsController = remember { TtsController(api, scope) }
    val ttsState by ttsController.state.collectAsState()
    val ttsSentence by ttsController.currentSentence.collectAsState()
    val ttsWordIndex by ttsController.currentWordIndex.collectAsState()
    val ttsVoices by ttsController.voices.collectAsState()
    val ttsError by ttsController.error.collectAsState()

    // Chapter-global word index the current synth began at. The controller only reports
    // an index within the text it was handed, so every highlight lookup adds this back.
    var synthStartWordIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(ttsError) {
        if (ttsError != null) {
            delay(5000)
            ttsController.clearError()
        }
    }
    LaunchedEffect(Unit) { ttsController.loadVoices() }

    val syncController = remember { AudiobookSyncController(api, scope) }
    val syncMode by syncController.mode.collectAsState()
    val syncHighlight by syncController.highlightedText.collectAsState()
    val syncSuggestedChapter by syncController.suggestedChapter.collectAsState()
    val syncPlayer = remember { mutableStateOf<Player?>(null) }

    LaunchedEffect(syncSuggestedChapter) {
        if (syncMode == SyncMode.SYNCING && syncSuggestedChapter >= 0 && syncSuggestedChapter != currentChapter) {
            currentChapter = syncSuggestedChapter
        }
    }
    val syncPlayerState = syncPlayer.value?.state?.collectAsState()
    LaunchedEffect(syncPlayerState?.value) {
        if (syncPlayerState?.value == PlayerState.FINISHED && syncMode == SyncMode.SYNCING) {
            syncController.handlePlayerFinished(book.id)
        }
    }

    val activeHighlight = if (syncMode == SyncMode.SYNCING) syncHighlight else ttsSentence

    // Which chapter the read moved itself to, so turning the page for the listener is
    // not mistaken below for the listener turning it themselves.
    var readAloudChapter by remember { mutableIntStateOf(-1) }

    /**
     * Read from [from] on, walking past anything with nothing to say.
     *
     * A blank chapter is a plate, a divider or a title page, not the end of the book.
     * The pages still turn as they are passed, and the first few of a run are held
     * briefly so a reader watching the screen sees a page rather than a flicker — only
     * the first few, or a book with a plate section between its parts spends a minute
     * in silence.
     */
    suspend fun readAloudFrom(epub: EpubBook, from: Int) {
        var dwelt = 0
        val next = nextSpokenChapter(
            from = from,
            lastIndex = epub.chapters.lastIndex,
            textAt = { chapterPlainText(epub, it) },
            onSkip = { index ->
                currentChapter = index
                scrollState.scrollTo(0)
                if (dwelt++ < BlankChapterDwellCount) delay(BlankChapterDwellMs)
            },
        ) ?: return
        readAloudChapter = next.index
        currentChapter = next.index
        scrollState.scrollTo(0)
        synthStartWordIndex = 0
        ttsController.start(next.text)
    }

    LaunchedEffect(ttsController) {
        ttsController.onFinished = onFinished@{
            val epub = epubBook ?: return@onFinished
            scope.launch { readAloudFrom(epub, currentChapter + 1) }
        }
    }
    LaunchedEffect(currentChapter) {
        // The read advancing itself is not a reason to stop it; anyone else moving is.
        if (currentChapter == readAloudChapter) return@LaunchedEffect
        if (syncMode != SyncMode.SYNCING) ttsController.stop()
    }
    LaunchedEffect(currentChapter, epubBook) {
        val epub = epubBook
        val chapterTitle = epub?.chapters?.getOrNull(currentChapter)?.title
            ?: "Chapter ${currentChapter + 1}"
        ttsController.setNowPlayingMetadata(book.title, chapterTitle, book.coverUrl)
    }
    DisposableEffect(Unit) {
        onDispose {
            readerExitScope.launch { persistProgress() }
            ttsController.stop()
            syncController.stop()
            syncPlayer.value?.release()
        }
    }

    val theme = remember(themeMode, fontSize, lineHeightMultiplier, horizontalPadding, readerFontFamily) {
        themeMode.toTheme(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * lineHeightMultiplier).sp,
            horizontalPadding = horizontalPadding,
            fontFamily = readerFontFamily.compose,
        )
    }

    // Cache IO, ZIP inflation and per-chapter XHTML parsing are all multi-second on a large
    // book — none of it may touch the main thread.
    LaunchedEffect(book.id) {
        try {
            val cacheKey = "epub_${book.id}"
            val payload = fetchBookFile(api, book.id, cacheKey, listOf("EPUB", "KEPUB"))
            val parsed = withContext(Dispatchers.Default) {
                // Disk-backed where the platform allows it: the archive stays on disk and
                // only the requested entry is inflated. EpubBook keeps the handle for images.
                val onDisk = payload.path?.let { openZipArchive(it) }
                when {
                    onDisk != null -> parseEpub(onDisk)
                    payload.bytes != null -> parseEpub(payload.bytes)
                    else -> parseEpub(
                        FileCache.get(cacheKey)
                            ?: throw Exception("No readable file available for this book.")
                    )
                }
            }
            val progress = api.readerProgressOrNull(book.id)
            epubBook = parsed
            if (progress != null) {
                savedCfi = progress.cfi
                readingStateLoaded = true
                val pct = progress.progressPercent ?: 0.0
                if (pct > 0 && parsed.chapters.isNotEmpty()) {
                    currentChapter = chapterForProgressPercent(pct, chapterWeights(parsed.chapters))
                }
            }
            loading = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load book"
            loading = false
        }
    }

    LaunchedEffect(currentChapter, epubBook) {
        if (epubBook == null) return@LaunchedEffect
        while (isActive) {
            delay(30_000)
            persistProgress()
        }
    }

    LaunchedEffect(currentChapter) { scrollState.scrollTo(0) }

    Box(Modifier.fillMaxSize().background(theme.backgroundColor)) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = theme.linkColor,
            )
            error != null -> ReaderErrorState(message = error!!, onBack = onBack, theme = theme)
            epubBook != null -> ReaderLoaded(
                epub = epubBook!!,
                book = book,
                onBack = onBack,
                scope = scope,
                theme = theme,
                themeMode = themeMode,
                fontSize = fontSize,
                lineHeightMultiplier = lineHeightMultiplier,
                horizontalPadding = horizontalPadding,
                readerFontFamily = readerFontFamily,
                brightness = brightness,
                ttsController = ttsController,
                ttsState = ttsState,
                ttsVoices = ttsVoices,
                ttsWordIndex = ttsWordIndex,
                synthStartWordIndex = synthStartWordIndex,
                onSynthStartWordIndexChange = { synthStartWordIndex = it },
                onStartReadAloud = { scope.launch { readAloudFrom(epubBook!!, currentChapter) } },
                keyNav = formFactor == FormFactor.TV || formFactor == FormFactor.Desktop,
                // Chrome only gets out of the way where there is no pointer to aim with.
                immersive = formFactor == FormFactor.TV,
                syncController = syncController,
                syncMode = syncMode,
                syncPlayer = syncPlayer,
                activeHighlight = activeHighlight,
                currentChapter = currentChapter,
                onChapterChange = { currentChapter = it },
                scrollState = scrollState,
                viewportHeight = viewportHeight,
                onViewportHeightChange = { viewportHeight = it },
                contentHeight = contentHeight,
                onContentHeightChange = { contentHeight = it },
                currentPage = currentPage,
                totalPages = totalPages,
                showToc = showToc,
                onShowTocChange = { showToc = it },
                showSearch = showSearch,
                onShowSearchChange = { showSearch = it },
                showSettings = showSettings,
                onShowSettingsChange = { showSettings = it },
                readerBookmarks = readerBookmarks,
                onBookmarksChange = { readerBookmarks = it },
                onThemeModeChange = { themeMode = it; saveSettings() },
                onFontSizeChange = { fontSize = it; saveSettings() },
                onLineHeightChange = { lineHeightMultiplier = it; saveSettings() },
                onPaddingChange = { horizontalPadding = it; saveSettings() },
                onFontFamilyChange = { readerFontFamily = it; saveSettings() },
                onBrightnessChange = { brightness = it; saveSettings() },
            )
        }

        if (ttsError != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(KobappTheme.colors.err.copy(alpha = 0.18f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    ttsError!!,
                    style = KobappTheme.type.caption.copy(color = KobappTheme.colors.err),
                )
            }
        }

        if (brightness < 1f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (1f - brightness).coerceIn(0f, 0.85f)))
                    .clickable(enabled = false) {},
            )
        }
    }
}

@Composable
private fun ReaderErrorState(message: String, onBack: () -> Unit, theme: ReaderTheme) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = KobappTheme.type.body.copy(color = theme.textColor))
        Spacer(Modifier.height(16.dp))
        KobButton(text = "Go Back", onClick = onBack, modifier = Modifier.focusKey("error.back"))
    }
}

@Composable
private fun ReaderLoaded(
    epub: EpubBook,
    book: Book,
    onBack: () -> Unit,
    scope: CoroutineScope,
    theme: ReaderTheme,
    themeMode: ReaderThemeMode,
    fontSize: Float,
    lineHeightMultiplier: Float,
    horizontalPadding: Int,
    readerFontFamily: ReaderFontFamily,
    brightness: Float,
    ttsController: TtsController,
    ttsState: TtsState,
    ttsVoices: List<TtsVoiceDto>,
    ttsWordIndex: Int,
    synthStartWordIndex: Int,
    onSynthStartWordIndexChange: (Int) -> Unit,
    onStartReadAloud: () -> Unit,
    keyNav: Boolean,
    immersive: Boolean,
    syncController: AudiobookSyncController,
    syncMode: SyncMode,
    syncPlayer: MutableState<Player?>,
    activeHighlight: String?,
    currentChapter: Int,
    onChapterChange: (Int) -> Unit,
    scrollState: ScrollState,
    viewportHeight: Int,
    onViewportHeightChange: (Int) -> Unit,
    contentHeight: Int,
    onContentHeightChange: (Int) -> Unit,
    currentPage: Int,
    totalPages: Int,
    showToc: Boolean,
    onShowTocChange: (Boolean) -> Unit,
    showSearch: Boolean,
    onShowSearchChange: (Boolean) -> Unit,
    showSettings: Boolean,
    onShowSettingsChange: (Boolean) -> Unit,
    readerBookmarks: List<Bookmark>,
    onBookmarksChange: (List<Bookmark>) -> Unit,
    onThemeModeChange: (ReaderThemeMode) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onPaddingChange: (Int) -> Unit,
    onFontFamilyChange: (ReaderFontFamily) -> Unit,
    onBrightnessChange: (Float) -> Unit,
) {
    val panelOpen = showToc || showSearch || showSettings
    val nav = rememberReaderNavState(keyNav = keyNav, immersive = immersive, holdChrome = panelOpen)

    val chapterView = rememberChapterView(epub, currentChapter)
    val activeWordHighlight = remember(ttsWordIndex, chapterView, synthStartWordIndex) {
        val words = chapterView?.tts?.words
        val word = if (ttsWordIndex < 0 || words == null) null
            else words.getOrNull(synthStartWordIndex + ttsWordIndex)
        word?.highlightCharRange?.let { TtsWordHighlight(word.blockIndex, it) }
    }
    val tocEntries = rememberTocEntries(epub)
    val searchIndex = rememberEpubSearchIndex(epub, active = showSearch)

    // Content-relative Y range of every measured block, fed by EpubContent and read by
    // both the long-press hit-test and the reading cursor.
    val blockBounds = remember(currentChapter) { mutableStateMapOf<Int, IntRange>() }
    val cursorBlock = remember(currentChapter) { mutableStateOf<Int?>(null) }
    var committedScroll by remember(currentChapter) { mutableStateOf<Int?>(null) }
    var pendingScroll by remember(epub) { mutableStateOf<PendingScroll?>(null) }

    fun scrollTo(target: Int) {
        committedScroll = target
        scope.launch { scrollState.animateScrollTo(target) }
    }

    fun startReadingAt(blockIndex: Int) {
        val view = chapterView ?: return
        startReadingAtBlock(view.tts, blockIndex, ttsController::start)
            ?.let(onSynthStartWordIndexChange)
    }

    val cursor = if (!keyNav) null else ReaderCursorConfig(
        bounds = blockBounds,
        scrollState = scrollState,
        viewportHeight = viewportHeight,
        bandColor = theme.highlightColor.copy(alpha = theme.highlightColor.alpha * 0.55f),
        onScrollTo = { target -> scrollTo(target) },
        onStartReadingAt = { idx -> startReadingAt(idx) },
    )

    fun goNextPage() {
        val base = pageTurnBase(committedScroll, scrollState.value, viewportHeight)
        val target = nextScrollTarget(base, viewportHeight, scrollState.maxValue)
        cursor?.followPageTurn(cursorBlock, target)
        if (target != null) {
            scrollTo(target)
        } else if (currentChapter < epub.chapters.lastIndex) {
            onChapterChange(currentChapter + 1)
        }
    }

    fun goPrevPage() {
        val base = pageTurnBase(committedScroll, scrollState.value, viewportHeight)
        val target = previousScrollTarget(base, viewportHeight)
        cursor?.followPageTurn(cursorBlock, target)
        if (target != null) {
            scrollTo(target)
        } else if (currentChapter > 0) {
            pendingScroll = PendingScroll.Bottom
            onChapterChange(currentChapter - 1)
        }
    }

    // The next chapter parses off-thread, so anything that wants to land somewhere other
    // than the top has to wait for the new content to be measured.
    LaunchedEffect(chapterView) {
        val pending = pendingScroll ?: return@LaunchedEffect
        if (chapterView == null) return@LaunchedEffect
        pendingScroll = null
        val extent = awaitScrollExtent(scrollState)
        val target = when (pending) {
            PendingScroll.Bottom -> extent
            is PendingScroll.Fraction -> (pending.value * (extent + viewportHeight)).toInt().coerceIn(0, extent)
        }
        scrollState.scrollTo(target)
        committedScroll = target
    }

    val onKeyAction: (ReaderKeyAction) -> Boolean = { action ->
        when (action) {
            ReaderKeyAction.PrevPage -> { goPrevPage(); true }
            ReaderKeyAction.NextPage -> { goNextPage(); true }
            ReaderKeyAction.CursorUp -> { cursor?.moveCursor(cursorBlock, forward = false); true }
            ReaderKeyAction.CursorDown -> { cursor?.moveCursor(cursorBlock, forward = true); true }
            ReaderKeyAction.CursorSelect -> cursor?.selectCursor(cursorBlock) ?: false
            ReaderKeyAction.CursorExit -> { cursorBlock.value = null; true }
            else -> false
        }
    }

    // Entry focus, and the way back after a panel that stole it closes. Reading must never
    // be left with nothing focused — Compose only delivers keys into the focused subtree.
    LaunchedEffect(panelOpen, keyNav) {
        if (keyNav && !panelOpen) nav.returnToBody()
    }

    val topBar = @Composable {
        Box(Modifier.readerZone(nav, ReaderZone.TopBar)) {
            ReaderTopBar(
                title = epub.chapters.getOrNull(currentChapter)?.title ?: book.title,
                onBack = {
                    ttsController.stop()
                    syncController.stop()
                    syncPlayer.value?.release()
                    syncPlayer.value = null
                    onBack()
                },
                onToc = { onShowTocChange(!showToc) },
                onSettings = { onShowSettingsChange(!showSettings) },
                onTts = {
                    if (syncMode == SyncMode.SYNCING) return@ReaderTopBar
                    when (ttsState) {
                        TtsState.IDLE -> onStartReadAloud()
                        TtsState.LOADING -> {}
                        TtsState.PLAYING -> ttsController.pause()
                        TtsState.PAUSED -> ttsController.resume()
                    }
                },
                onListenAlong = {
                    if (!book.hasAudiobook) return@ReaderTopBar
                    if (syncMode == SyncMode.SYNCING) {
                        syncController.stop()
                        syncPlayer.value?.release()
                        syncPlayer.value = null
                    } else {
                        ttsController.stop()
                        val player = createPlayer(PlayerConfig(audioOnly = true))
                        syncPlayer.value = player
                        scope.launch {
                            syncController.start(book.id, player, epub.chapters)
                        }
                    }
                },
                onSearch = { onShowSearchChange(!showSearch) },
                onBookmark = {
                    val scrollFrac = if (contentHeight > 0) scrollState.value.toFloat() / contentHeight else 0f
                    val chapterTitle = epub.chapters.getOrNull(currentChapter)?.title ?: "Chapter ${currentChapter + 1}"
                    val totalChapters = epub.chapters.size
                    val pct = if (totalChapters > 0) ((currentChapter.toFloat() / totalChapters) * 100).toInt() else 0
                    val bookmark = Bookmark(
                        id = BookmarkRepository.generateId(),
                        bookId = book.id,
                        label = "$chapterTitle (${pct}%)",
                        createdAt = Clock.System.now().toEpochMilliseconds(),
                        chapterIndex = currentChapter,
                        scrollFraction = scrollFrac,
                    )
                    BookmarkRepository.addBookmark(bookmark)
                    onBookmarksChange(BookmarkRepository.getBookmarks(book.id))
                },
                ttsState = ttsState,
                syncMode = syncMode,
                hasAudiobook = book.hasAudiobook,
                theme = theme,
                entryFocus = nav.topBarFocus,
            )
        }
    }

    val bottomBar = @Composable {
        Column(Modifier.readerZone(nav, ReaderZone.BottomBar)) {
            if (syncMode == SyncMode.SYNCING) {
                ListenAlongBar(
                    player = syncPlayer.value,
                    theme = theme,
                    onStop = {
                        syncController.stop()
                        syncPlayer.value?.release()
                        syncPlayer.value = null
                    },
                )
            }
            ReaderBottomBar(
                currentPage = currentPage,
                totalPages = totalPages,
                currentChapter = currentChapter,
                totalChapters = epub.chapters.size,
                onSliderChange = { frac ->
                    if (epub.chapters.isEmpty()) return@ReaderBottomBar
                    val idx = (frac * epub.chapters.lastIndex).toInt().coerceIn(0, epub.chapters.lastIndex)
                    onChapterChange(idx)
                },
                theme = theme,
                entryFocus = nav.bottomBarFocus,
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .readerKeyNav(
                state = nav,
                enabled = !panelOpen,
                cursorActive = cursorBlock.value != null,
                onAction = onKeyAction,
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!immersive) topBar()

            Box(
                Modifier
                    .weight(1f)
                    .onSizeChanged { onViewportHeightChange(it.height) }
                    .pointerInput(currentChapter, epub.chapters.size, viewportHeight) {
                        var cumDrag = 0f
                        val threshold = 80.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { cumDrag = 0f },
                            onDragEnd = {
                                if (cumDrag < -threshold) goNextPage()
                                else if (cumDrag > threshold) goPrevPage()
                            },
                            onDragCancel = { cumDrag = 0f },
                            onHorizontalDrag = { _, dragAmount -> cumDrag += dragAmount },
                        )
                    }
            ) {
                Box(
                    Modifier
                        .focusInert(panelOpen)
                        .fillMaxSize()
                        .clipToBounds()
                        .verticalScroll(scrollState)
                ) {
                    if (chapterView != null) {
                        val imageResolver = remember(chapterView) {
                            { src: String -> chapterView.images[src] }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onSizeChanged { onContentHeightChange(it.height) }
                        ) {
                            EpubContent(
                                blocks = chapterView.blocks,
                                theme = theme,
                                highlightedText = activeHighlight,
                                wordHighlight = activeWordHighlight,
                                imageResolver = imageResolver,
                                pageHeight = viewportHeight,
                                onActiveBlockY = { y ->
                                    if (viewportHeight > 0) {
                                        val targetPage = y / viewportHeight
                                        val targetScroll = (targetPage * viewportHeight)
                                            .coerceAtMost(scrollState.maxValue)
                                        if (abs(scrollState.value - targetScroll) > 1) {
                                            scrollTo(targetScroll)
                                        }
                                    }
                                },
                                onBlockPositioned = { idx, topY, height ->
                                    blockBounds[idx] = topY..(topY + height - 1).coerceAtLeast(topY)
                                },
                            )
                        }
                    }
                }

                ReaderBodyInput(
                    modifier = Modifier.focusInert(panelOpen),
                    onPrevPage = { goPrevPage() },
                    onNextPage = { goNextPage() },
                    onLongPressAt = { pos: Offset ->
                        blockAt(blockBounds, scrollState.value + pos.y.toInt())?.let { startReadingAt(it) }
                    },
                    cursor = cursor,
                    cursorBlock = cursorBlock.value,
                )

                if (showToc) {
                    ReaderTocPanel(
                        entries = tocEntries,
                        currentChapter = currentChapter,
                        bookmarks = readerBookmarks,
                        onChapterSelect = { idx ->
                            onChapterChange(idx)
                            onShowTocChange(false)
                        },
                        onBookmarkSelect = { bm ->
                            if (bm.chapterIndex != null) {
                                bm.scrollFraction?.let { pendingScroll = PendingScroll.Fraction(it) }
                                onChapterChange(bm.chapterIndex)
                            }
                            onShowTocChange(false)
                        },
                        onBookmarkDelete = { bm ->
                            BookmarkRepository.removeBookmark(book.id, bm.id)
                            onBookmarksChange(BookmarkRepository.getBookmarks(book.id))
                        },
                        onDismiss = { onShowTocChange(false) },
                        theme = theme,
                        modifier = Modifier.focusInert(showSearch || showSettings),
                    )
                }
                if (showSearch) {
                    ReaderSearchPanel(
                        index = searchIndex,
                        onResultSelect = { idx ->
                            onChapterChange(idx)
                            onShowSearchChange(false)
                        },
                        onDismiss = { onShowSearchChange(false) },
                        theme = theme,
                        modifier = Modifier.focusInert(showSettings),
                    )
                }
                if (showSettings) {
                    ReaderSettingsPanel(
                        themeMode = themeMode,
                        fontSize = fontSize,
                        lineHeightMultiplier = lineHeightMultiplier,
                        horizontalPadding = horizontalPadding,
                        readerFontFamily = readerFontFamily,
                        onThemeChange = onThemeModeChange,
                        onFontSizeChange = onFontSizeChange,
                        onLineHeightChange = onLineHeightChange,
                        onPaddingChange = onPaddingChange,
                        onFontFamilyChange = onFontFamilyChange,
                        brightness = brightness,
                        onBrightnessChange = onBrightnessChange,
                        ttsController = ttsController,
                        ttsVoices = ttsVoices,
                        onDismiss = { onShowSettingsChange(false) },
                        theme = theme,
                    )
                }
            }

            if (!immersive) bottomBar()
        }

        if (immersive) {
            AnimatedVisibility(
                visible = nav.chromeVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) { topBar() }
            AnimatedVisibility(
                visible = nav.chromeVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) { bottomBar() }
        }
    }
}

@Composable
private fun ListenAlongBar(player: Player?, theme: ReaderTheme, onStop: () -> Unit) {
    if (player == null) return
    val spState by player.state.collectAsState()
    val spMs by player.currentMs.collectAsState()
    val spDur by player.durationMs.collectAsState()
    val spSpeed by player.playbackSpeed.collectAsState()

    Row(
        modifier = Modifier
            .focusKeyGroup("listenalong")
            .fillMaxWidth()
            .background(theme.backgroundColor)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Headphones, null, tint = theme.linkColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { player.togglePlayPause() },
            modifier = Modifier
                .focusKey("playpause")
                .size(32.dp)
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(
                if (spState == PlayerState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                "Play/Pause", tint = theme.textColor, modifier = Modifier.size(20.dp),
            )
        }
        Slider(
            value = if (spDur > 0) spMs.toFloat() / spDur else 0f,
            onValueChange = { player.seekTo((it * spDur).toLong()) },
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(thumbColor = theme.linkColor, activeTrackColor = theme.linkColor),
        )
        Box(
            Modifier
                .focusKey("speed")
                .width(48.dp)
                .tvAutoGlow(cornerRadiusDp = 6f, scale = 1.03f)
                .clickable {
                    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                    val nextIdx = (speeds.indexOf(spSpeed) + 1) % speeds.size
                    player.setPlaybackSpeed(speeds[nextIdx])
                }
                .padding(PaddingValues(horizontal = 4.dp, vertical = 6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("${spSpeed}x", style = KobappTheme.type.caption.copy(color = theme.textColor))
        }
        IconButton(
            onClick = onStop,
            modifier = Modifier
                .focusKey("stop")
                .size(32.dp)
                .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f),
        ) {
            Icon(Icons.Outlined.Close, "Stop", tint = theme.textColor, modifier = Modifier.size(18.dp))
        }
    }
}

// Long enough for a plate to register as a page turned rather than a flicker, short
// enough not to read as the app having given up.
private const val BlankChapterDwellMs = 1200L
private const val BlankChapterDwellCount = 3
