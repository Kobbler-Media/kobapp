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
        chapters: List<EpubChapter>,
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
                startPositionTracking(bookId, chapters)
            }
        } catch (e: Exception) {
            _mode.value = SyncMode.IDLE
        }
    }

    private fun startPositionTracking(bookId: String, chapters: List<EpubChapter>) {
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

                // Find sync point to suggest chapter position
                val activeSyncPoint = syncPoints.lastOrNull { sp ->
                    sp.audioSeconds <= currentSec
                }
                if (activeSyncPoint?.ebookCfi != null && chapters.isNotEmpty()) {
                    // Use CFI to find matching chapter by href prefix
                    val cfi = activeSyncPoint.ebookCfi
                    // CFI format is typically like "/6/4!/..." or contains chapter path
                    // Try matching via the sync point's chapter reference if available
                    val chapterIdx = activeSyncPoint.chapterIndex
                        ?: estimateChapterFromProgress(activeSyncPoint, chapters)
                    if (chapterIdx in chapters.indices) {
                        _suggestedChapter.value = chapterIdx
                    }
                }
            }
        }
    }

    /**
     * Seek audio to match a reader position (e.g., user tapped text in reader).
     */
    suspend fun seekFromReader(bookId: String, cfi: String) {
        try {
            val result = api.getSyncPosition(bookId, from = "ebook", cfi = cfi)
            result?.audioSeconds?.let { sec ->
                player?.seekTo((sec * 1000).toLong())
            }
        } catch (_: Exception) {}
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
     * Fallback when sync points don't include explicit chapter indices.
     */
    private fun estimateChapterFromProgress(
        syncPoint: SyncPointDto,
        chapters: List<EpubChapter>,
    ): Int {
        val totalDuration = audioFiles.sumOf { it.durationSeconds ?: 0.0 }
        if (totalDuration <= 0 || chapters.isEmpty()) return -1
        val elapsedBefore = audioFiles
            .take(audioFiles.indexOfFirst { it.id == syncPoint.audioFileId }.coerceAtLeast(0))
            .sumOf { it.durationSeconds ?: 0.0 }
        val progress = (elapsedBefore + syncPoint.audioSeconds) / totalDuration
        return (progress * chapters.size).toInt().coerceIn(0, chapters.lastIndex)
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
