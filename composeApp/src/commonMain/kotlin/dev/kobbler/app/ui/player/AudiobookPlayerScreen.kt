package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import dev.kobbler.app.ui.components.LabelledIconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.PlaybackLifecycle
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.theme.Section
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import dev.kobbler.app.ui.player.audiobook.ChapterListPanel
import dev.kobbler.app.ui.player.audiobook.SleepMode
import dev.kobbler.app.platform.CastButton
import dev.kobbler.app.platform.castManager
import dev.kobbler.app.ui.player.audiobook.AudiobookCastHandoff
import dev.kobbler.app.ui.player.audiobook.SleepTimerMenu
import dev.kobbler.app.ui.player.audiobook.TransportRow
import dev.kobbler.app.ui.player.audiobook.rememberAudiobookState
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.kobplayer.ui.MediaSeekBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.kobplayer.ui.formatTime

@Composable
fun AudiobookPlayerScreen(
    book: Book,
    api: KobblerApi,
    onBack: () -> Unit,
    onOpenReader: (() -> Unit)? = null,
    fromStart: Boolean = false,
) {
    val state = rememberAudiobookState(book, api, fromStart = fromStart)
    AudiobookCastHandoff(remember { castManager() }, state)

    DisposableEffect(Unit) {
        PlaybackLifecycle.startBackgroundPlayback()
        onDispose { PlaybackLifecycle.stopBackgroundPlayback() }
    }
    DisposableEffect(state.player) {
        PlaybackLifecycle.setPlayer(state.player)
        PlaybackLifecycle.setControls(
            onPlayPause = state::togglePlayPause,
            onSkipForward = state::skipForward,
            onSkipBack = state::skipBack,
            onStop = {
                state.saveProgress()
                onBack()
            },
            timeline = state.playbackTimeline(),
        )
        onDispose {
            PlaybackLifecycle.setPlayer(null)
            PlaybackLifecycle.setControls(null, null, null, null)
        }
    }
    LaunchedEffect(state.player) {
        state.player.isPlaying.collectLatest { playing ->
            do {
                PlaybackLifecycle.updateNowPlaying(
                    title = book.title,
                    subtitle = book.author,
                    coverUrl = book.coverUrl,
                    isPlaying = playing,
                    currentMs = state.globalPositionMs,
                    durationMs = state.totalBookDurationMs,
                    isAudioOnly = true,
                )
                if (playing) delay(1000)
            } while (playing)
        }
    }
    LaunchedEffect(state.player) {
        while (true) {
            delay(30_000)
            if (state.player.currentMs.value > 0 && state.player.isPlaying.value) {
                state.saveProgress()
            }
        }
    }

    AudiobookPlayerContent(
        book = book,
        state = state,
        onBack = {
            state.saveProgress()
            onBack()
        },
        onOpenReader = onOpenReader,
    )
}

@Composable
internal fun AudiobookPlayerContent(
    book: Book,
    state: AudiobookPlayerState,
    onBack: () -> Unit,
    onOpenReader: (() -> Unit)?,
) {
    KobappTheme(formFactor = LocalFormFactor.current, section = Section.Audio) {
        val colors = KobappTheme.colors
        val dimens = KobappTheme.dimens
        val accent = colors.accAudio

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp || maxWidth > maxHeight
            StripedBackdrop(accent = accent, opacity = 0.32f, withRadialOverlay = true, cached = true)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to accent.copy(alpha = 0.16f),
                        0.5f to Color.Transparent,
                        1f to colors.bg.copy(alpha = 0.65f),
                    ),
                ),
            )

            Column(Modifier.fillMaxSize().safeTopPadding()) {
                TopBar(
                    book = book,
                    chapterTitle = state.currentChapter?.title,
                    castingTo = state.castingTo,
                    sleepMode = state.sleepMode,
                    sleepRemainingMs = { state.sleepRemainingMs },
                    onBack = onBack,
                    onAddBookmark = state::addBookmarkHere,
                    onSleepSelect = { state.sleepMode = it },
                    onOpenReader = onOpenReader.takeIf {
                        book.formats.any { f -> f.uppercase() in READER_FORMATS }
                    },
                )

                state.errorMessage?.let { msg ->
                    Text(
                        msg,
                        color = colors.err,
                        style = KobappTheme.type.bodySm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = dimens.s4, vertical = dimens.s1),
                    )
                }

                if (wide) {
                    WideLayout(book = book, state = state, modifier = Modifier.weight(1f))
                } else {
                    PhoneLayout(book = book, state = state, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun TopBar(
    book: Book,
    chapterTitle: String?,
    castingTo: String?,
    sleepMode: SleepMode,
    sleepRemainingMs: () -> Long,
    onBack: () -> Unit,
    onAddBookmark: () -> Unit,
    onSleepSelect: (SleepMode) -> Unit,
    onOpenReader: (() -> Unit)?,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    Row(
        modifier = Modifier
            .focusKeyGroup("topbar")
            .fillMaxWidth()
            .padding(horizontal = KobappTheme.dimens.s2, vertical = KobappTheme.dimens.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val touch = LocalFormFactor.current != FormFactor.TV
        if (touch) {
            LabelledIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.focusKey("back"), tint = colors.textHi)
        }
        Column(Modifier.weight(1f).padding(horizontal = if (touch) 6.dp else KobappTheme.dimens.s2)) {
            Text(
                castingTo?.let { "Casting to $it" } ?: "Now playing",
                color = if (castingTo != null) colors.accAudio else colors.textLo,
                style = type.bodySm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                chapterTitle ?: book.title,
                color = colors.textHi,
                style = type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CastButton(Modifier.focusKey("cast").tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f), colors.textMd)
        LabelledIconButton(Icons.Outlined.BookmarkAdd, "Add bookmark", onAddBookmark, Modifier.focusKey("bookmark"), tint = colors.textMd)
        SleepTimerMenu(mode = sleepMode, remainingMs = sleepRemainingMs, onSelect = onSleepSelect)
        if (onOpenReader != null) {
            LabelledIconButton(Icons.AutoMirrored.Outlined.MenuBook, "Follow along", onOpenReader, Modifier.focusKey("reader"), tint = colors.textHi)
        }
    }
}

@Composable
private fun PhoneLayout(
    book: Book,
    state: AudiobookPlayerState,
    modifier: Modifier = Modifier,
) {
    val dimens = KobappTheme.dimens
    val gutter = dimens.pageGutterPhone
    val hasPanel = state.hasPanelContent

    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = gutter)) {
        val coverSize = (maxHeight * if (hasPanel) 0.28f else 0.5f).coerceAtMost(maxWidth)
        val nowPlayingMaxHeight = if (hasPanel) maxHeight * 0.68f else maxHeight
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .heightIn(max = nowPlayingMaxHeight)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(dimens.s4))
                CoverHero(book = book, modifier = Modifier.size(coverSize))
                Spacer(Modifier.height(dimens.s5))
                BookTitleBlock(book = book, chapterTitle = state.currentChapter?.title, centered = true)
                Spacer(Modifier.height(dimens.s4))
                ProgressBlock(state = state)
                Spacer(Modifier.height(dimens.s4))
                TransportRow(
                    state = state.playerState,
                    speed = state.playbackSpeed,
                    volume = state.volume,
                    onPlayPause = state::togglePlayPause,
                    onSkipBack = state::skipBack,
                    onSkipForward = state::skipForward,
                    onSpeedChange = state::setSpeed,
                    onVolumeChange = { state.volume = it; state.player.setVolume(it) },
                    playSize = 88.dp,
                    sideSize = 64.dp,
                )
            }
            if (hasPanel) {
                Spacer(Modifier.height(dimens.s5))
                ChapterListPanel(
                    chapters = state.chapters,
                    transcriptSegments = state.transcriptSegments,
                    bookmarks = state.bookmarks,
                    currentChapter = state.currentChapter,
                    activeSegmentIndex = { state.activeSegmentIndex },
                    activeTab = state.activeTab,
                    onTabChange = { state.activeTab = it },
                    onChapterClick = state::jumpToChapter,
                    onSegmentClick = state::jumpToSegment,
                    onBookmarkClick = state::jumpToBookmark,
                    onBookmarkDelete = state::deleteBookmark,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(dimens.s3))
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WideLayout(
    book: Book,
    state: AudiobookPlayerState,
    modifier: Modifier = Modifier,
) {
    val dimens = KobappTheme.dimens
    val gutter = dimens.pageGutter
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = dimens.s4),
        horizontalArrangement = Arrangement.spacedBy(dimens.s6),
    ) {
        // The cover takes whatever height the transport leaves: a remote cannot scroll a
        // column, so on a 540dp TV a fixed-size cover pushed the controls off the screen.
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CoverHero(
                book = book,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 360.dp),
                matchHeight = true,
            )
            Spacer(Modifier.height(dimens.s5))
            BookTitleBlock(
                book = book,
                chapterTitle = state.currentChapter?.title,
                centered = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(dimens.s4))
            ProgressBlock(state = state, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(dimens.s4))
            TransportRow(
                state = state.playerState,
                speed = state.playbackSpeed,
                volume = state.volume,
                onPlayPause = state::togglePlayPause,
                onSkipBack = state::skipBack,
                onSkipForward = state::skipForward,
                onSpeedChange = state::setSpeed,
                onVolumeChange = { state.volume = it; state.player.setVolume(it) },
                playSize = 96.dp,
                sideSize = 68.dp,
            )
        }
        if (state.hasPanelContent) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                ChapterListPanel(
                    chapters = state.chapters,
                    transcriptSegments = state.transcriptSegments,
                    bookmarks = state.bookmarks,
                    currentChapter = state.currentChapter,
                    activeSegmentIndex = { state.activeSegmentIndex },
                    activeTab = state.activeTab,
                    onTabChange = { state.activeTab = it },
                    onChapterClick = state::jumpToChapter,
                    onSegmentClick = state::jumpToSegment,
                    onBookmarkClick = state::jumpToBookmark,
                    onBookmarkDelete = state::deleteBookmark,
                )
            }
        }
    }
}

@Composable
private fun CoverHero(book: Book, modifier: Modifier = Modifier, matchHeight: Boolean = false) {
    val colors = KobappTheme.colors
    Box(
        modifier = modifier
            .aspectRatio(1f, matchHeightConstraintsFirst = matchHeight)
            .clip(KobappTheme.shapes.hero)
            .background(
                Brush.linearGradient(
                    listOf(colors.accAudio.copy(alpha = 0.55f), colors.accBooks.copy(alpha = 0.45f)),
                ),
            ),
    ) {
        AsyncImage(
            model = book.coverUrl,
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.30f),
                    ),
                ),
        )
    }
}

@Composable
private fun BookTitleBlock(
    book: Book,
    chapterTitle: String?,
    centered: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (chapterTitle != null) {
            Text(
                chapterTitle,
                color = colors.textLo,
                style = type.bodySm,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            book.title,
            color = colors.textHi,
            style = type.h2,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            book.author,
            color = colors.textMd,
            style = type.bodySm,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgressBlock(state: AudiobookPlayerState, modifier: Modifier = Modifier) {
    val accent = KobappTheme.colors.accAudio
    var scrubMs by remember(state) { mutableStateOf<Long?>(null) }
    Column(modifier = modifier.focusKeyGroup("progress").fillMaxWidth()) {
        MediaSeekBar(
            progress = {
                if (state.totalBookDurationMs > 0) state.globalPositionMs.toFloat() / state.totalBookDurationMs else 0f
            },
            onSeek = { frac ->
                scrubMs = null
                state.seekToGlobal((frac * state.totalBookDurationMs).toLong())
            },
            onSeeking = { frac -> scrubMs = (frac * state.totalBookDurationMs).toLong() },
            activeColor = accent,
            modifier = Modifier.fillMaxWidth(),
            chapterMarkers = state.chapterMarkers,
            focusDecoration = Modifier.focusKey("seek"),
            dpadStepFraction = if (state.totalBookDurationMs > 0) 30_000f / state.totalBookDurationMs else 0f,
            // The bar is what a remote stops on, so it answers OK with the transport next to it
            // rather than leaving the press unanswered.
            onActivate = state::togglePlayPause,
        )
        ProgressLabels(state, scrubMs)
    }
}

@Composable
private fun ProgressLabels(state: AudiobookPlayerState, scrubMs: Long?) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val shownSec by remember(state, scrubMs) { derivedStateOf { (scrubMs ?: state.globalPositionMs) / 1000 } }
    val chapterRemainingSec by remember(state, scrubMs) {
        derivedStateOf { state.chapterRemainingAt(scrubMs ?: state.globalPositionMs)?.div(1000) }
    }
    val remaining = chapterRemainingSec.takeIf { state.currentChapter?.title != null }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatTime(shownSec * 1000), style = type.bodySm, color = colors.textHi)
        Text(
            remaining?.let { "${formatTime(it * 1000)} left in chapter" }.orEmpty(),
            style = type.caption,
            color = colors.textLo.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(formatTime(state.totalBookDurationMs), style = type.bodySm, color = colors.textLo)
    }
}

private val READER_FORMATS = setOf("EPUB", "KEPUB", "MOBI", "AZW3", "FB2")
