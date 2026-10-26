package dev.kobbler.app.ui.player.audiobook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.Bookmark
import dev.kobbler.app.data.BookmarkRepository
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.TranscriptSegmentDto
import dev.kobbler.app.data.UnifiedChapterDto
import dev.kobbler.app.platform.PlaybackTimeline
import dev.kobbler.kobplayer.MediaSource
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerConfig
import dev.kobbler.kobplayer.PlayerState
import dev.kobbler.kobplayer.rememberPlayer
import dev.kobbler.app.ui.player.RESUME_MIN_SECONDS
import dev.kobbler.app.ui.player.settleAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import dev.kobbler.kobplayer.ui.formatTime

/**
 * How long one audio file gets to start before the silence is called a failure.
 *
 * Audio has no surface to allocate and no transcode behind it — the server hands over the
 * file — so anything much past this is not a slow start.
 */
const val AUDIOBOOK_START_BUDGET_MS = 12_000L

const val AUDIOBOOK_SKIP_BACK_MS = 30_000L
const val AUDIOBOOK_SKIP_FORWARD_MS = 10_000L

@Stable
class AudiobookPlayerState internal constructor(
    val book: Book,
    val api: KobblerApi,
    val player: Player,
    private val scope: CoroutineScope,
) {
    var audioFiles by mutableStateOf<List<AudioFileDto>>(emptyList())
    private var playbackOrderChapters by mutableStateOf<List<UnifiedChapterDto>>(emptyList())
    var chapters: List<UnifiedChapterDto>
        get() = playbackOrderChapters
        set(value) {
            playbackOrderChapters = value.sortedBy { it.globalStartSeconds }
        }
    var transcriptSegments by mutableStateOf<List<TranscriptSegmentDto>>(emptyList())
    var currentFileIndex by mutableIntStateOf(0)
    var dataLoaded by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var volume by mutableFloatStateOf(1f)
    var sleepMode by mutableStateOf(SleepMode.Off)
    var sleepRemainingMs by mutableLongStateOf(0L)
    var bookmarks by mutableStateOf(BookmarkRepository.getBookmarks(book.id))
    var activeTab by mutableStateOf(PanelTab.Chapters)

    var playerState by mutableStateOf(PlayerState.IDLE)
        internal set
    var currentMs by mutableLongStateOf(0L)
        internal set
    var playbackSpeed by mutableFloatStateOf(1f)
        internal set

    val fileTimeOffsetsMs: List<Long> by derivedStateOf {
        var offset = 0L
        audioFiles.map { f ->
            val o = offset
            offset += ((f.durationSeconds ?: 0.0) * 1000).toLong()
            o
        }
    }

    val totalBookDurationMs: Long by derivedStateOf {
        audioFiles.sumOf { ((it.durationSeconds ?: 0.0) * 1000).toLong() }
    }

    val globalPositionMs: Long
        get() = (fileTimeOffsetsMs.getOrNull(currentFileIndex) ?: 0L) + currentMs

    val chapterMarkers: List<Float> by derivedStateOf {
        val total = totalBookDurationMs
        if (total <= 0) emptyList()
        else chapters.map { (it.globalStartSeconds * 1000 / total).toFloat() }
    }

    val currentChapter: UnifiedChapterDto? by derivedStateOf { chapterAt(globalPositionMs) }

    val chapterRemainingMs: Long?
        get() = chapterRemainingAt(globalPositionMs)

    fun chapterAt(globalMs: Long): UnifiedChapterDto? =
        chapters.lastOrNull { (it.globalStartSeconds * 1000).toLong() <= globalMs }

    fun chapterRemainingAt(globalMs: Long): Long? = chapterAt(globalMs)?.let { ch ->
        ((ch.globalEndSeconds * 1000).toLong() - globalMs).coerceAtLeast(0)
    }

    private val segmentIndicesByFile: Map<String, List<Int>> by derivedStateOf {
        transcriptSegments.indices.groupBy { transcriptSegments[it].fileId }
    }

    val activeSegmentIndex: Int by derivedStateOf {
        val fileId = audioFiles.getOrNull(currentFileIndex)?.id ?: return@derivedStateOf -1
        val indices = segmentIndicesByFile[fileId] ?: return@derivedStateOf -1
        val posSeconds = currentMs / 1000.0
        var result = -1
        var lo = 0
        var hi = indices.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (transcriptSegments[indices[mid]].startSeconds <= posSeconds) {
                result = indices[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        result
    }

    val hasPanelContent: Boolean
        get() = chapters.isNotEmpty() || transcriptSegments.isNotEmpty() || bookmarks.isNotEmpty()

    /**
     * Open one file and start it at [positionMs].
     *
     * The position goes in as an open-time option rather than a seek after the fact: a
     * seek needs a duration to clamp against, and a duration does not exist until the
     * initial parse is done. Every caller used to hand the stream over and then sleep for
     * half a second before seeking, which meant any source slower than that to open began
     * the book again from the top.
     */
    suspend fun playFileAt(index: Int, positionMs: Long) {
        val file = audioFiles.getOrNull(index) ?: return
        currentFileIndex = index
        errorMessage = null
        val startSec = (positionMs / 1000).toInt().takeIf { it > RESUME_MIN_SECONDS }
        player.load(api.audioSource(book.id, file.id).copy(startTimeSec = startSec))
        player.play()
    }

    /**
     * Wait for the file to be playing, then put the playhead right if the source opened
     * at the top anyway — and say something if it never plays at all.
     *
     * A demuxer is free to ignore an open-time position, so this checks once, after there
     * is a duration to seek against. The silence is the other half: an audio file that
     * cannot be served leaves a player that reports nothing, which is how a dead book
     * looked exactly like a quiet one.
     */
    suspend fun awaitFileStart(positionMs: Long, budgetMs: Long = AUDIOBOOK_START_BUDGET_MS) {
        val playing = withTimeoutOrNull(budgetMs) {
            player.state.first { it == PlayerState.PLAYING }
        } != null
        if (!playing) {
            errorMessage = "Nothing played within ${budgetMs / 1000}s — the file may be unavailable"
            return
        }
        if (positionMs <= RESUME_MIN_SECONDS * 1000L) return
        player.settleAt(positionMs)
    }

    /**
     * The player publishes an unopenable stream on a flow of its own, and nothing here was
     * reading it — so the only failure that ever reached the screen was a metadata fetch
     * that threw. A file the server cannot serve produced a dead player and a blank page.
     */
    fun watchPlayerFailures(watchScope: CoroutineScope) {
        watchScope.launch {
            player.error.collect { throwable ->
                if (throwable != null) {
                    errorMessage = throwable.message ?: "Playback failed (${throwable::class.simpleName})"
                }
            }
        }
    }

    private fun play(index: Int, positionMs: Long) {
        scope.launch {
            playFileAt(index, positionMs)
            awaitFileStart(positionMs)
        }
    }

    fun seekToGlobal(targetGlobalMs: Long) {
        if (audioFiles.isEmpty()) return
        val offsets = fileTimeOffsetsMs
        var targetFileIdx = 0
        for (i in offsets.indices) {
            val fileEnd = offsets[i] + ((audioFiles[i].durationSeconds ?: 0.0) * 1000).toLong()
            if (targetGlobalMs < fileEnd || i == offsets.lastIndex) {
                targetFileIdx = i
                break
            }
        }
        val localMs = (targetGlobalMs - (offsets.getOrNull(targetFileIdx) ?: 0L)).coerceAtLeast(0)
        if (targetFileIdx != currentFileIndex) play(targetFileIdx, localMs)
        else player.seekTo(localMs)
    }

    fun skipBack() = seekToGlobal((globalPositionMs - AUDIOBOOK_SKIP_BACK_MS).coerceAtLeast(0))
    fun skipForward() = seekToGlobal((globalPositionMs + AUDIOBOOK_SKIP_FORWARD_MS).coerceAtMost(totalBookDurationMs))

    fun playbackTimeline() = PlaybackTimeline(
        positionMs = { (fileTimeOffsetsMs.getOrNull(currentFileIndex) ?: 0L) + player.currentMs.value },
        durationMs = { totalBookDurationMs },
        seekTo = ::seekToGlobal,
        skipBackMs = AUDIOBOOK_SKIP_BACK_MS,
        skipForwardMs = AUDIOBOOK_SKIP_FORWARD_MS,
    )

    /**
     * Go to a position in a named file, which is how every target that knows its own file
     * gets there.
     *
     * The summed timeline is deliberately not in this path. It is built from per-file
     * durations, those come from a probe that has not necessarily run, and a missing one
     * counts as zero — which drags every later file's offset back and turns a chapter tap
     * into a jump to the wrong place in the wrong file.
     */
    private fun seekInFile(fileId: String, localMs: Long) {
        val fileIdx = audioFiles.indexOfFirst { it.id == fileId }.takeIf { it >= 0 } ?: return
        val target = localMs.coerceAtLeast(0)
        if (fileIdx != currentFileIndex) play(fileIdx, target) else player.seekTo(target)
    }

    fun jumpToChapter(chapter: UnifiedChapterDto) =
        seekInFile(chapter.fileId, (chapter.startSeconds * 1000).toLong())

    fun jumpToSegment(segment: TranscriptSegmentDto) =
        seekInFile(segment.fileId, (segment.startSeconds * 1000).toLong())

    fun jumpToBookmark(bm: Bookmark) {
        val fileId = bm.fileId ?: return
        val pos = bm.positionSeconds ?: return
        seekInFile(fileId, (pos * 1000).toLong())
    }

    fun deleteBookmark(bm: Bookmark) {
        BookmarkRepository.removeBookmark(book.id, bm.id)
        bookmarks = BookmarkRepository.getBookmarks(book.id)
    }

    fun addBookmarkHere() {
        val fileId = audioFiles.getOrNull(currentFileIndex)?.id
        val chapterName = currentChapter?.title ?: "Position"
        val label = "$chapterName @ ${formatTime(globalPositionMs)}"
        val bookmark = Bookmark(
            id = BookmarkRepository.generateId(),
            bookId = book.id,
            label = label,
            createdAt = Clock.System.now().toEpochMilliseconds(),
            fileId = fileId,
            positionSeconds = currentMs / 1000.0,
        )
        BookmarkRepository.addBookmark(bookmark)
        bookmarks = BookmarkRepository.getBookmarks(book.id)
        activeTab = PanelTab.Bookmarks
    }

    fun saveProgress() {
        val fileId = audioFiles.getOrNull(currentFileIndex)?.id ?: return
        val pos = player.currentMs.value / 1000.0
        val curGlobal = (fileTimeOffsetsMs.getOrNull(currentFileIndex) ?: 0L) + player.currentMs.value
        val progress = if (totalBookDurationMs > 0) (curGlobal.toDouble() / totalBookDurationMs * 100) else 0.0
        scope.launch { runCatching { api.saveAudioPosition(book.id, fileId, pos, progress) } }
    }
}

@Composable
fun rememberAudiobookState(
    book: Book,
    api: KobblerApi,
    player: Player = rememberPlayer(PlayerConfig(audioOnly = true)),
): AudiobookPlayerState {
    val scope = rememberCoroutineScope()
    val state = remember(book.id) { AudiobookPlayerState(book, api, player, scope) }

    LaunchedEffect(state) {
        launch { player.state.collect { state.playerState = it } }
        launch { player.currentMs.collect { state.currentMs = it } }
        launch { player.playbackSpeed.collect { state.playbackSpeed = it } }
    }

    LaunchedEffect(book.id) {
        try {
            // Before the first file is opened: an audio URL carries its credential in the
            // query, and opening one is not a place to wait for a round trip.
            api.warmPlaybackToken()
            state.audioFiles = pickBestAudioSource(api.getAudioFiles(book.id))
            state.chapters = api.getUnifiedChapters(book.id).let { all ->
                val ids = state.audioFiles.map { it.id }.toSet()
                all.filter { it.fileId in ids }
            }
            state.transcriptSegments = runCatching { api.getTranscriptSegments(book.id) }.getOrElse { emptyList() }

            if (state.audioFiles.isNotEmpty()) {
                val savedPos = api.getAudioPosition(book.id)
                val fileIdx = savedPos.fileId?.let { fid ->
                    state.audioFiles.indexOfFirst { it.id == fid }
                }?.takeIf { it >= 0 } ?: 0
                val resumeMs = ((savedPos.positionSeconds ?: 0.0) * 1000).toLong()
                state.playFileAt(fileIdx, resumeMs)
                state.dataLoaded = true
                scope.launch { state.awaitFileStart(resumeMs) }
            }
            state.activeTab = when {
                state.chapters.isNotEmpty() -> PanelTab.Chapters
                state.transcriptSegments.isNotEmpty() -> PanelTab.Transcript
                state.bookmarks.isNotEmpty() -> PanelTab.Bookmarks
                else -> PanelTab.Chapters
            }
        } catch (e: Exception) {
            state.errorMessage = e.message ?: "Failed to load audiobook"
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.playerState }.collectLatest { playerState ->
            if (playerState == PlayerState.FINISHED && state.dataLoaded && state.audioFiles.isNotEmpty()) {
                if (state.currentFileIndex < state.audioFiles.lastIndex) {
                    state.playFileAt(state.currentFileIndex + 1, positionMs = 0)
                    state.awaitFileStart(positionMs = 0)
                }
            }
        }
    }

    LaunchedEffect(player) { state.watchPlayerFailures(this) }

    LaunchedEffect(state.sleepMode) {
        if (state.sleepMode == SleepMode.Off || state.sleepMode == SleepMode.EndOfChapter) return@LaunchedEffect
        val totalMs = when (state.sleepMode) {
            SleepMode.Min15 -> 15 * 60 * 1000L
            SleepMode.Min30 -> 30 * 60 * 1000L
            SleepMode.Min45 -> 45 * 60 * 1000L
            SleepMode.Min60 -> 60 * 60 * 1000L
            else -> 0L
        }
        state.sleepRemainingMs = totalMs
        while (state.sleepRemainingMs > 0) {
            delay(1000)
            state.sleepRemainingMs -= 1000
        }
        player.pause()
        state.sleepMode = SleepMode.Off
    }

    LaunchedEffect(state.sleepMode) {
        if (state.sleepMode != SleepMode.EndOfChapter) return@LaunchedEffect
        val sleepChapter = state.currentChapter ?: return@LaunchedEffect
        snapshotFlow { state.currentChapter }.first { it != sleepChapter }
        player.pause()
        state.sleepMode = SleepMode.Off
    }

    return state
}
