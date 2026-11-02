package dev.kobbler.app.ui.reader

import dev.kobbler.app.data.*
import dev.kobbler.kobplayer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SyncMode { IDLE, SYNCING }

/**
 * Coordinates cross-format sync between audiobook playback and EPUB reader position.
 *
 * Loads sync points from the backend, then monitors audio playback position to
 * determine which sentence/chapter in the EPUB should be highlighted. Also supports
 * reader-to-audio sync (tap text to seek audio).
 */
class AudiobookSyncController(
    private val api: KobblerApi,
    private val scope: CoroutineScope,
) {
    private val _mode = MutableStateFlow(SyncMode.IDLE)
    val mode: StateFlow<SyncMode> = _mode.asStateFlow()

    private val _highlightedText = MutableStateFlow<String?>(null)
    val highlightedText: StateFlow<String?> = _highlightedText.asStateFlow()

    private val _suggestedChapter = MutableStateFlow(-1)
    val suggestedChapter: StateFlow<Int> = _suggestedChapter.asStateFlow()

    private var player: Player? = null
    private var syncPoints: List<SyncPointDto> = emptyList()
    private var transcriptSegments: List<TranscriptSegmentDto> = emptyList()
    private var audioFiles: List<AudioFileDto> = emptyList()
    private var currentFileIndex = 0
    private var syncJob: Job? = null

    /**
     * Start syncing: load audio data and sync points, begin playback.
     */
    suspend fun start(
        bookId: String,
        player: Player,
        chapterCount: Int,
    ) {
        this.player = player
        _mode.value = SyncMode.SYNCING

        try {
            // An audio URL carries its credential in the query; have it before the first one.
            api.warmPlaybackToken()
            val readAlongData = api.getReadAlongData(bookId)
            syncPoints = readAlongData.syncPoints
            transcriptSegments = readAlongData.transcriptSegments
            audioFiles = api.getAudioFiles(bookId)

            if (audioFiles.isNotEmpty()) {
                // Load first audio file
                val file = audioFiles[0]
                currentFileIndex = 0
                player.load(api.audioSource(bookId, file.id))
                player.play()

                // Start position tracking
                startPositionTracking(bookId, chapterCount)
            }
        } catch (e: Exception) {
            _mode.value = SyncMode.IDLE
        }
    }

    private fun startPositionTracking(bookId: String, chapterCount: Int) {
        syncJob?.cancel()
        syncJob = scope.launch {
            val player = this@AudiobookSyncController.player ?: return@launch

            player.currentMs.collect { posMs ->
                if (_mode.value != SyncMode.SYNCING) return@collect

                val currentSec = posMs / 1000.0
                val fileId = audioFiles.getOrNull(currentFileIndex)?.id ?: return@collect

                // Find active transcript segment for text highlighting
                val activeSegment = transcriptSegments
                    .filter { it.fileId == fileId }
                    .lastOrNull { it.startSeconds <= currentSec && it.endSeconds >= currentSec }

                _highlightedText.value = activeSegment?.text

                // Timestamps restart in every file of a multi-file audiobook.
                val activeSyncPoint = syncPoints.lastOrNull { sp ->
                    sp.audioFileId == fileId && sp.audioSeconds <= currentSec
                } ?: return@collect
                val chapterIdx = activeSyncPoint.position?.chapter
                    ?: estimateChapterFromProgress(activeSyncPoint, chapterCount)
                if (chapterIdx in 0 until chapterCount) _suggestedChapter.value = chapterIdx
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        player?.pause()
        player = null
        _mode.value = SyncMode.IDLE
        _highlightedText.value = null
        _suggestedChapter.value = -1
    }

    /**
     * Estimate chapter from sync point audio position relative to total audio duration.
     * Fallback when the server could not place the point in the book's reader pack.
     */
    private fun estimateChapterFromProgress(
        syncPoint: SyncPointDto,
        chapterCount: Int,
    ): Int {
        val totalDuration = audioFiles.sumOf { it.durationSeconds ?: 0.0 }
        if (totalDuration <= 0 || chapterCount == 0) return -1
        val elapsedBefore = audioFiles
            .take(audioFiles.indexOfFirst { it.id == syncPoint.audioFileId }.coerceAtLeast(0))
            .sumOf { it.durationSeconds ?: 0.0 }
        val progress = (elapsedBefore + syncPoint.audioSeconds) / totalDuration
        return (progress * chapterCount).toInt().coerceIn(0, chapterCount - 1)
    }

    /** Auto-advance to next audio file when current finishes. */
    fun handlePlayerFinished(bookId: String) {
        val nextIdx = currentFileIndex + 1
        if (nextIdx >= audioFiles.size) {
            stop()
            return
        }
        currentFileIndex = nextIdx
        val file = audioFiles[nextIdx]
        player?.load(api.audioSource(bookId, file.id))
        player?.play()
    }
}
