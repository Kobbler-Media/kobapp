package dev.kobbler.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.BookmarkRepository
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.player.LocalSharedAudioPlayer
import dev.kobbler.app.ui.reader.layout.ReaderTypography
import dev.kobbler.app.ui.reader.pack.Position
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Outlives the reader composition so the exit save is not cancelled by navigation. */
private val readerExitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal data class ReadingSpot(val position: Position, val percent: Double)

private inline fun <reified T : Enum<T>> storedEnum(key: String, default: T): T =
    UserSettings.getString(key)?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: default

@Composable
fun ReaderScreen(
    book: Book,
    api: KobblerApi,
    formFactor: FormFactor,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var readerBook by remember { mutableStateOf<ReaderBook?>(null) }
    var paging by remember { mutableStateOf<ReaderPaging?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var readerBookmarks by remember { mutableStateOf(BookmarkRepository.getBookmarks(book.id)) }

    var brightness by remember {
        mutableStateOf(UserSettings.getString("reader_brightness")?.toFloatOrNull() ?: 1f)
    }
    var themeMode by remember { mutableStateOf(storedEnum("reader_theme", ReaderThemeMode.Dark)) }
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
    var readerFontFamily by remember { mutableStateOf(storedEnum("reader_font_family", ReaderFontFamily.Default)) }
    var pageFit by remember { mutableStateOf(storedEnum("reader_page_fit", ReaderPageFit.Spread)) }
    var styleMode by remember { mutableStateOf(storedEnum("reader_style_mode", ReaderStyleMode.Publisher)) }
    var alignment by remember { mutableStateOf(storedEnum("reader_alignment", ReaderAlignment.Justified)) }
    fun saveSettings() {
        UserSettings.putString("reader_theme", themeMode.name)
        UserSettings.putString("reader_font_size", fontSize.toString())
        UserSettings.putString("reader_line_height", lineHeightMultiplier.toString())
        UserSettings.putString("reader_h_padding", horizontalPadding.toString())
        UserSettings.putString("reader_font_family", readerFontFamily.name)
        UserSettings.putString("reader_brightness", brightness.toString())
        UserSettings.putString("reader_page_fit", pageFit.name)
        UserSettings.putString("reader_style_mode", styleMode.name)
        UserSettings.putString("reader_alignment", alignment.name)
    }
    SaveWhenSettled(
        listOf(themeMode, fontSize, lineHeightMultiplier, horizontalPadding, readerFontFamily, brightness, pageFit, styleMode, alignment),
    ) {
        saveSettings()
    }

    val progressSaver = remember(book.id, api) {
        ProgressSaver<ReadingSpot> { api.saveReaderProgress(book.id, it.position, it.percent) }
    }
    val persistProgress: suspend () -> Unit = persist@{
        val loaded = readerBook ?: return@persist
        val at = paging ?: return@persist
        val percent = readingProgressPercent(
            chapterIndex = at.chapter,
            chapterWeights = loaded.weights,
            scrollOffset = at.page,
            maxScroll = at.pageCount - 1,
        )
        progressSaver.save(ReadingSpot(at.position, percent))
    }

    val ttsController = remember { TtsController(api, scope) }
    val ttsState by ttsController.state.collectAsState()
    val ttsSentence by ttsController.currentSentence.collectAsState()
    val ttsWordIndex = ttsController.currentWordIndex.collectAsState()
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
        val at = paging ?: return@LaunchedEffect
        if (syncMode == SyncMode.SYNCING && syncSuggestedChapter >= 0 && syncSuggestedChapter != at.chapter) {
            at.goToChapter(syncSuggestedChapter)
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
    suspend fun readAloudFrom(loaded: ReaderBook, at: ReaderPaging, from: Int) {
        var dwelt = 0
        val next = nextSpokenChapter(
            from = from,
            lastIndex = loaded.lastChapter,
            textAt = { buildChapterView(loaded, it)?.tts?.plainText.orEmpty() },
            onSkip = { index ->
                at.goToChapter(index)
                if (dwelt++ < BlankChapterDwellCount) delay(BlankChapterDwellMs)
            },
        ) ?: return
        readAloudChapter = next.index
        at.goToChapter(next.index)
        synthStartWordIndex = 0
        ttsController.start(next.text)
    }

    LaunchedEffect(ttsController) {
        ttsController.onFinished = onFinished@{
            val loaded = readerBook ?: return@onFinished
            val at = paging ?: return@onFinished
            scope.launch { readAloudFrom(loaded, at, at.chapter + 1) }
        }
    }
    val currentChapter = paging?.chapter ?: 0
    LaunchedEffect(currentChapter) {
        // The read advancing itself is not a reason to stop it; anyone else moving is.
        if (currentChapter == readAloudChapter) return@LaunchedEffect
        if (syncMode != SyncMode.SYNCING) ttsController.stop()
    }
    LaunchedEffect(currentChapter, readerBook) {
        val chapterTitle = readerBook?.title(currentChapter) ?: "Chapter ${currentChapter + 1}"
        ttsController.setNowPlayingMetadata(book.title, chapterTitle, book.coverUrl)
    }
    DisposableEffect(Unit) {
        onDispose {
            readerExitScope.launch { persistProgress() }
            ttsController.stop()
            syncController.stop()
            syncPlayer.value?.stop()
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
    val publisherFonts = rememberPublisherFonts(readerBook, enabled = styleMode == ReaderStyleMode.Publisher)
    val typography = remember(theme, styleMode, alignment, publisherFonts) {
        ReaderTypography(
            theme = theme,
            publisherStyles = styleMode == ReaderStyleMode.Publisher,
            justify = alignment == ReaderAlignment.Justified,
            publisherFonts = publisherFonts,
        )
    }

    LaunchedEffect(book.id) {
        try {
            val loaded = ReaderBook(book.id, api.readerPackManifest(book.id), api)
            if (loaded.chapterCount == 0) throw Exception("This book has nothing to read.")
            val progress = api.readerProgressOrNull(book.id)
            val start = progress?.position?.takeIf { it.chapter in 0..loaded.lastChapter }
                ?: progress?.progressPercent?.takeIf { it > 0 }
                    ?.let { Position(chapterForProgressPercent(it, loaded.weights)) }
                ?: Position()
            paging = ReaderPaging(start)
            readerBook = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Failed to load book"
        }
    }

    LaunchedEffect(currentChapter, readerBook) {
        if (readerBook == null) return@LaunchedEffect
        while (isActive) {
            delay(30_000)
            persistProgress()
        }
    }

    Box(Modifier.fillMaxSize().background(theme.backgroundColor)) {
        val loaded = readerBook
        val at = paging
        when {
            error != null -> ReaderErrorState(message = error!!, onBack = onBack, theme = theme)
            loaded == null || at == null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = theme.linkColor,
            )
            else -> ReaderLoaded(
                readerBook = loaded,
                paging = at,
                book = book,
                onBack = onBack,
                scope = scope,
                theme = theme,
                typography = typography,
                settings = ReaderSettingsState(
                    themeMode = themeMode,
                    fontSize = fontSize,
                    lineHeightMultiplier = lineHeightMultiplier,
                    horizontalPadding = horizontalPadding,
                    readerFontFamily = readerFontFamily,
                    pageFit = pageFit,
                    styleMode = styleMode,
                    alignment = alignment,
                    brightness = brightness,
                    onThemeModeChange = { themeMode = it },
                    onFontSizeChange = { fontSize = it },
                    onLineHeightChange = { lineHeightMultiplier = it },
                    onPaddingChange = { horizontalPadding = it },
                    onFontFamilyChange = { readerFontFamily = it },
                    onPageFitChange = { pageFit = it },
                    onStyleModeChange = { styleMode = it },
                    onAlignmentChange = { alignment = it },
                    onBrightnessChange = { brightness = it },
                ),
                ttsController = ttsController,
                ttsState = ttsState,
                ttsVoices = ttsVoices,
                ttsWordIndex = { ttsWordIndex.value },
                synthStartWordIndex = synthStartWordIndex,
                onSynthStartWordIndexChange = { synthStartWordIndex = it },
                onStartReadAloud = { scope.launch { readAloudFrom(loaded, at, at.chapter) } },
                keyNav = formFactor == FormFactor.TV || formFactor == FormFactor.Desktop,
                // Chrome only gets out of the way where there is no pointer to aim with.
                immersive = formFactor == FormFactor.TV,
                syncController = syncController,
                syncMode = syncMode,
                syncPlayer = syncPlayer,
                audio = LocalSharedAudioPlayer.current,
                activeHighlight = activeHighlight,
                showToc = showToc,
                onShowTocChange = { showToc = it },
                showSearch = showSearch,
                onShowSearchChange = { showSearch = it },
                showSettings = showSettings,
                onShowSettingsChange = { showSettings = it },
                readerBookmarks = readerBookmarks,
                onBookmarksChange = { readerBookmarks = it },
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

// Long enough for a plate to register as a page turned rather than a flicker, short
// enough not to read as the app having given up.
private const val BlankChapterDwellMs = 1200L
private const val BlankChapterDwellCount = 3
