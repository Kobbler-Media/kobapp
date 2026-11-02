package dev.kobbler.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.Bookmark
import dev.kobbler.app.data.BookmarkRepository
import dev.kobbler.app.data.TtsVoiceDto
import dev.kobbler.app.ui.player.SharedAudioPlayer
import dev.kobbler.app.ui.reader.layout.ReaderTypography
import dev.kobbler.app.ui.reader.layout.TextHighlight
import dev.kobbler.app.ui.reader.pack.Link
import dev.kobbler.app.ui.reader.pack.Position
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

internal data class ReaderSettingsState(
    val themeMode: ReaderThemeMode,
    val fontSize: Float,
    val lineHeightMultiplier: Float,
    val horizontalPadding: Int,
    val readerFontFamily: ReaderFontFamily,
    val pageFit: ReaderPageFit,
    val styleMode: ReaderStyleMode,
    val alignment: ReaderAlignment,
    val brightness: Float,
    val onThemeModeChange: (ReaderThemeMode) -> Unit,
    val onFontSizeChange: (Float) -> Unit,
    val onLineHeightChange: (Float) -> Unit,
    val onPaddingChange: (Int) -> Unit,
    val onFontFamilyChange: (ReaderFontFamily) -> Unit,
    val onPageFitChange: (ReaderPageFit) -> Unit,
    val onStyleModeChange: (ReaderStyleMode) -> Unit,
    val onAlignmentChange: (ReaderAlignment) -> Unit,
    val onBrightnessChange: (Float) -> Unit,
)

@Composable
internal fun ReaderLoaded(
    readerBook: ReaderBook,
    paging: ReaderPaging,
    book: Book,
    onBack: () -> Unit,
    scope: CoroutineScope,
    theme: ReaderTheme,
    typography: ReaderTypography,
    settings: ReaderSettingsState,
    ttsController: TtsController,
    ttsState: TtsState,
    ttsVoices: List<TtsVoiceDto>,
    ttsWordIndex: () -> Int,
    synthStartWordIndex: Int,
    onSynthStartWordIndexChange: (Int) -> Unit,
    onStartReadAloud: () -> Unit,
    keyNav: Boolean,
    immersive: Boolean,
    syncController: AudiobookSyncController,
    syncMode: SyncMode,
    syncPlayer: MutableState<Player?>,
    audio: SharedAudioPlayer,
    activeHighlight: String?,
    showToc: Boolean,
    onShowTocChange: (Boolean) -> Unit,
    showSearch: Boolean,
    onShowSearchChange: (Boolean) -> Unit,
    showSettings: Boolean,
    onShowSettingsChange: (Boolean) -> Unit,
    readerBookmarks: List<Bookmark>,
    onBookmarksChange: (List<Bookmark>) -> Unit,
) {
    var sheet by remember(paging.chapter) { mutableStateOf<ReaderSheet?>(null) }
    val panelOpen = showToc || showSearch || showSettings || sheet != null
    val nav = rememberReaderNavState(keyNav = keyNav, immersive = immersive, holdChrome = panelOpen)
    val uriHandler = LocalUriHandler.current
    val currentChapter = paging.chapter
    val view = paging.view?.takeIf { it.chapterIndex == currentChapter }

    val wordHighlight = run {
        val index = ttsWordIndex()
        val word = if (index < 0) null else view?.tts?.words?.getOrNull(synthStartWordIndex + index)
        word?.highlightCharRange?.let { TtsWordHighlight(word.blockIndex, it) }
    }
    val sentenceHighlight = remember(view, activeHighlight) {
        val blocks = view?.blocks ?: return@remember null
        val index = findActiveBlockIndex(blocks, activeHighlight).takeIf { it >= 0 } ?: return@remember null
        fuzzyFind(blockPlainText(blocks[index]), activeHighlight)?.let { TtsWordHighlight(index, it) }
    }
    val highlights = listOfNotNull(
        sentenceHighlight?.let { TextHighlight(it.blockIndex, it.charRange, theme.highlightColor.copy(alpha = theme.highlightColor.alpha * 0.5f)) },
        wordHighlight?.let { TextHighlight(it.blockIndex, it.charRange, theme.highlightColor) },
    )

    // The page follows the voice: whatever is being read stays on screen.
    val follow = wordHighlight ?: sentenceHighlight
    LaunchedEffect(follow, paging.layout) {
        val target = follow ?: return@LaunchedEffect
        val layout = paging.layout ?: return@LaunchedEffect
        if (layout.pageOf(target.blockIndex, target.charRange.first) !in paging.shownPages) {
            paging.goTo(Position(currentChapter, target.blockIndex, target.charRange.first))
        }
    }

    val tocEntries = remember(readerBook) { tocEntries(readerBook) }
    val searchIndex = rememberBookSearchIndex(readerBook, active = showSearch)

    fun startReadingAt(blockIndex: Int, offset: Int = 0) {
        val tts = view?.tts ?: return
        startReadingAtBlock(tts, blockIndex, offset, ttsController::start)?.let(onSynthStartWordIndexChange)
    }

    val cursor = rememberPageCursor(paging, enabled = keyNav)
    LaunchedEffect(paging.chapter, paging.page, paging.layout) { cursor.followPage() }

    fun turn(delta: Int) {
        paging.turnPage(delta, readerBook.lastChapter)
    }

    val onLink: (Link) -> Unit = { link ->
        link.position?.let(paging::goTo) ?: link.href?.let { runCatching { uriHandler.openUri(it) } }
    }

    val onKeyAction: (ReaderKeyAction) -> Boolean = { action ->
        when (action) {
            ReaderKeyAction.PrevPage -> { turn(-1); true }
            ReaderKeyAction.NextPage -> { turn(1); true }
            ReaderKeyAction.CursorUp -> cursor.move(forward = false, onTurn = ::turn)
            ReaderKeyAction.CursorDown -> cursor.move(forward = true, onTurn = ::turn)
            ReaderKeyAction.CursorSelect -> cursor.select { block ->
                if (view?.blocks?.getOrNull(block)?.hasActions() == true) sheet = ReaderSheet.BlockActions(block)
                else startReadingAt(block)
            }
            ReaderKeyAction.CursorExit -> { cursor.block = null; true }
            ReaderKeyAction.OpenChapters -> { onShowTocChange(true); true }
            ReaderKeyAction.ClosePanel -> {
                when {
                    sheet != null -> sheet = null
                    showSettings -> onShowSettingsChange(false)
                    showSearch -> onShowSearchChange(false)
                    else -> onShowTocChange(false)
                }
                true
            }
            else -> false
        }
    }
    ReaderBackHandler(state = nav, panelOpen = panelOpen, cursorActive = cursor.block != null, onAction = onKeyAction)

    // Entry focus, and the way back after a panel that stole it closes. Reading must never
    // be left with nothing focused — Compose only delivers keys into the focused subtree.
    LaunchedEffect(panelOpen, keyNav) {
        if (keyNav && !panelOpen) nav.returnToBody()
    }

    fun stopListening() {
        syncController.stop()
        syncPlayer.value?.stop()
        syncPlayer.value = null
    }

    val topBar = @Composable {
        Box(Modifier.readerZone(nav, ReaderZone.TopBar)) {
            ReaderTopBar(
                title = readerBook.title(currentChapter) ?: book.title,
                onBack = {
                    ttsController.stop()
                    stopListening()
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
                        stopListening()
                    } else {
                        ttsController.stop()
                        val player = audio.get()
                        syncPlayer.value = player
                        scope.launch { syncController.start(book.id, player, readerBook.chapterCount) }
                    }
                },
                onSearch = { onShowSearchChange(!showSearch) },
                onBookmark = {
                    val position = paging.position
                    val chapterTitle = readerBook.title(currentChapter) ?: "Chapter ${currentChapter + 1}"
                    val pct = readingProgressPercent(currentChapter, readerBook.weights, paging.page, paging.pageCount - 1).toInt()
                    BookmarkRepository.addBookmark(
                        Bookmark(
                            id = BookmarkRepository.generateId(),
                            bookId = book.id,
                            label = "$chapterTitle (${pct}%)",
                            createdAt = Clock.System.now().toEpochMilliseconds(),
                            chapterIndex = position.chapter,
                            blockIndex = position.block,
                            blockOffset = position.offset,
                        ),
                    )
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
                ListenAlongBar(player = syncPlayer.value, theme = theme, onStop = ::stopListening)
            }
            ReaderBottomBar(
                pages = { paging.counter },
                currentChapter = currentChapter,
                totalChapters = readerBook.chapterCount,
                onSliderChange = { frac ->
                    paging.goToChapter((frac * readerBook.lastChapter).toInt().coerceIn(0, readerBook.lastChapter))
                },
                theme = theme,
                entryFocus = nav.bottomBarFocus,
                onChapters = if (immersive) { { onShowTocChange(true) } } else null,
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .readerKeyNav(
                state = nav,
                panelOpen = panelOpen,
                cursorActive = cursor.block != null,
                chaptersOnDown = immersive && syncMode != SyncMode.SYNCING,
                onAction = onKeyAction,
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!immersive) topBar()

            Box(Modifier.weight(1f)) {
                ReaderPagedBody(
                    book = readerBook,
                    paging = paging,
                    typography = typography,
                    pageFit = settings.pageFit,
                    highlights = highlights,
                    cursorBlock = cursor.block,
                    onLink = onLink,
                    onNote = { sheet = ReaderSheet.Note(it) },
                    onLongPress = { at -> startReadingAt(at.block, at.offset) },
                    modifier = Modifier.focusInert(panelOpen).padding(vertical = PageVerticalMargin),
                )

                when (val open = sheet) {
                    is ReaderSheet.Note -> ReaderNoteSheet(
                        blocks = view?.chapter?.notes?.get(open.key).orEmpty(),
                        theme = theme,
                        onDismiss = { sheet = null },
                    )
                    is ReaderSheet.BlockActions -> view?.blocks?.getOrNull(open.block)?.let { block ->
                        ReaderBlockActions(
                            block = block,
                            theme = theme,
                            onRead = {
                                sheet = null
                                startReadingAt(open.block)
                            },
                            onNote = { sheet = ReaderSheet.Note(it) },
                            onLink = {
                                sheet = null
                                onLink(it)
                            },
                            onDismiss = { sheet = null },
                        )
                    }
                    null -> {}
                }

                if (showToc) {
                    ReaderTocPanel(
                        entries = tocEntries,
                        currentChapter = currentChapter,
                        bookmarks = readerBookmarks,
                        onEntrySelect = { entry ->
                            paging.goTo(entry.position)
                            onShowTocChange(false)
                        },
                        onBookmarkSelect = { bm ->
                            bm.chapterIndex?.let { chapter ->
                                paging.goTo(Position(chapter, bm.blockIndex ?: 0, bm.blockOffset ?: 0))
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
                        onResultSelect = { hit ->
                            paging.goTo(hit.position)
                            onShowSearchChange(false)
                        },
                        onDismiss = { onShowSearchChange(false) },
                        theme = theme,
                        modifier = Modifier.focusInert(showSettings),
                    )
                }
                if (showSettings) {
                    ReaderSettingsPanel(
                        themeMode = settings.themeMode,
                        fontSize = settings.fontSize,
                        lineHeightMultiplier = settings.lineHeightMultiplier,
                        horizontalPadding = settings.horizontalPadding,
                        readerFontFamily = settings.readerFontFamily,
                        pageFit = settings.pageFit,
                        styleMode = settings.styleMode,
                        alignment = settings.alignment,
                        onThemeChange = settings.onThemeModeChange,
                        onFontSizeChange = settings.onFontSizeChange,
                        onLineHeightChange = settings.onLineHeightChange,
                        onPaddingChange = settings.onPaddingChange,
                        onFontFamilyChange = settings.onFontFamilyChange,
                        onPageFitChange = settings.onPageFitChange,
                        onStyleModeChange = settings.onStyleModeChange,
                        onAlignmentChange = settings.onAlignmentChange,
                        brightness = settings.brightness,
                        onBrightnessChange = settings.onBrightnessChange,
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
                modifier = Modifier.align(Alignment.BottomCenter).focusInert(panelOpen),
                enter = fadeIn(),
                exit = fadeOut(),
            ) { bottomBar() }
            AnimatedVisibility(
                visible = !nav.chromeVisible && !panelOpen,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ReaderTvStatus(
                    pages = { paging.counter },
                    currentChapter = currentChapter,
                    totalChapters = readerBook.chapterCount,
                    cursorActive = cursor.block != null,
                    downOpensChapters = syncMode != SyncMode.SYNCING,
                    theme = theme,
                )
            }
        }
    }
}

private val PageVerticalMargin = 12.dp
