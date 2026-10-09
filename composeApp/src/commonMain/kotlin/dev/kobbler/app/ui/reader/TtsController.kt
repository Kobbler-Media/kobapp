package dev.kobbler.app.ui.reader

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.TtsStreamEvent
import dev.kobbler.app.data.TtsVoiceDto
import dev.kobbler.app.data.TtsWordTiming
import dev.kobbler.app.platform.ChunkPlayer
import dev.kobbler.app.platform.PcmStreamPlayer
import dev.kobbler.app.platform.PlaybackLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class TtsState { IDLE, LOADING, PLAYING, PAUSED }

private const val BYTES_PER_SAMPLE = 2
private const val MAX_BUFFER_AHEAD_MS = 90_000L

/**
 * Rate to bound the newest chunk anchor with, until a later anchor closes it
 * exactly. Measured on this engine at ~175 wpm; being wrong here costs a
 * slightly early or late page turn on the chunk currently being spoken, and
 * nothing at all on any chunk behind it.
 */
private const val ANCHOR_SEED_WPM = 175.0

/** One chunk anchor, with offsets global to the whole read. */
private data class ChunkAnchor(val wordOffset: Int, val wordCount: Int, val audioOffsetMs: Int)

/**
 * Controls TTS read-aloud for the EPUB reader.
 *
 * Two backend envelopes (auto-detected from the first NDJSON event):
 *  - **Sentence path** (chatterbox / kokoro / openai): per-sentence MP3, played via [ChunkPlayer],
 *    sentence-level highlight via [currentSentence].
 *  - **Paragraph path** (breeze via koblem): streaming s16le PCM via [PcmStreamPlayer], plus forced
 *    alignment events that drive [currentWordIndex] (global word index since synth start).
 */
class TtsController(
    private val api: KobblerApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(TtsState.IDLE)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _currentSentence = MutableStateFlow<String?>(null)
    val currentSentence: StateFlow<String?> = _currentSentence.asStateFlow()

    /** -1 when no word active. Global index across the whole synth (paragraph word_offset + per-word index). */
    private val _currentWordIndex = MutableStateFlow(-1)
    val currentWordIndex: StateFlow<Int> = _currentWordIndex.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(-1)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _voices = MutableStateFlow<List<TtsVoiceDto>>(emptyList())
    val voices: StateFlow<List<TtsVoiceDto>> = _voices.asStateFlow()

    /**
     * The voice to ask for, or null to leave the choice to the server.
     *
     * Null is the right default and the list is not a menu to pick a default from. The
     * server resolves the active preset, the admin default and — on an aligning engine —
     * a minted narrator that holds across requests. An engine handed no name at all picks
     * a speaker per REQUEST, which is why the answer has to be one the server settles
     * rather than whichever voice happened to sort first here: that one only starts being
     * sent once the list has loaded, so the first chapter and the second came back in
     * different voices.
     */
    var selectedVoice: String? = null

    /// Observable so the settings slider actually tracks it — both when the user
    /// drags it and when a refused rate snaps it back to what was rendered.
    private val _selectedSpeed = MutableStateFlow(1.0)
    val selectedSpeed: StateFlow<Double> = _selectedSpeed.asStateFlow()

    fun setSpeed(speed: Double) {
        // Applies to the NEXT render: rate is baked in server-side, and a
        // chapter restart to change it would throw away the listener's place.
        // The engine's own WSOLA range — clamping to it rather than something
        // narrower means a request never gets rejected and re-rendered at 1x.
        _selectedSpeed.value = speed.coerceIn(0.25, 4.0)
    }

    private var synthesizeJob: Job? = null
    private var currentChunkJob: Job? = null
    private var wordTickerJob: Job? = null
    private val chunkPlayer = ChunkPlayer()
    private val pcmPlayer = PcmStreamPlayer()
    private var backgroundActive = false
    private var nowPlayingTitle: String = "Kobbler Reader"
    private var nowPlayingSubtitle: String? = null
    private var nowPlayingCoverUrl: String? = null

    // Replaced wholesale rather than mutated: the word ticker reads this from its own
    // coroutine while the stream writes it, and iterating a map another thread is putting
    // into throws.
    @Volatile
    private var paragraphs: Map<Int, ParagraphAlignment> = emptyMap()
    private var paragraphMode = false

    // A chapter is ONE request now: the engine's long-form mode chunks it
    // internally with rolling context, which is what holds a single voice
    // across the whole chapter and keeps word indices absolute over all of it.
    // These bases are therefore always zero on the paragraph path; they survive
    // because the per-sentence fallback path (non-aligning engines) still emits
    // sentence-numbered events.
    /**
     * Chunk anchors, in the order the engine reached them, with offsets global
     * to the whole read.
     *
     * They are the position signal that does not depend on the aligner: it
     * lands a chunk at a time and can be minutes behind the voice on the CPU,
     * or switched off entirely, and the reader still has to know which page the
     * listener is on. [rebuildAnchorWords] turns them into coarse per-word
     * timings so everything downstream can stay word-indexed.
     */
    private val anchors = mutableListOf<ChunkAnchor>()

    private var paragraphKeyBase = 0
    private var wordBase = 0
    private var sentenceBase = 0
    private var segmentAudioBaseMs = 0L
    private var appendedBytes = 0L
    private var streamSampleRate = 0

    /// Rate the CURRENT render was REQUESTED at, kept only so the rate the
    /// server reports back can be compared against it.
    ///
    /// Rate is a server-side render parameter: the PCM arrives already at speed
    /// and the aligner's timings are already in that timebase, so nothing here
    /// rescales either. What can happen is the engine refusing the rate and
    /// rendering at 1.0 anyway — the one outcome that looks like success — which
    /// is why the requested value is held rather than trusted.
    private var requestedRate = 1.0

    /** Called when TTS finishes the current text — used for auto-advancing pages/chapters. */
    var onFinished: (() -> Unit)? = null

    fun loadVoices() {
        scope.launch {
            try {
                _voices.value = api.getTtsStatus().voices
            } catch (_: Exception) {}
        }
    }

    fun setNowPlayingMetadata(bookTitle: String, chapterTitle: String?, coverUrl: String?) {
        nowPlayingTitle = bookTitle
        nowPlayingSubtitle = chapterTitle
        nowPlayingCoverUrl = coverUrl
        if (backgroundActive) refreshNowPlaying()
    }

    /**
     * Start TTS from the given text. Backend envelope is auto-detected from
     * the first event received.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun start(text: String) {
        stop()
        _state.value = TtsState.LOADING
        _error.value = null
        requestedRate = _selectedSpeed.value

        beginBackgroundSession()

        synthesizeJob = scope.launch {
            val streamScope = this
            try {
                var receivedAudio = false
                var fatal = false
                var incomplete = false
                var sentencesSeen = 0

                fun noteAudio() {
                    if (receivedAudio) return
                    receivedAudio = true
                    _state.value = TtsState.PLAYING
                    refreshNowPlaying()
                }

                // The WHOLE chapter goes in one request. Splitting it here used
                // to mean one synthesis per segment, and without a cloned voice
                // each one picked its own speaker — the narrator changed mid
                // chapter. Long-form mode carries the voice instead.
                api.streamTtsSynthesize(
                    text = text,
                    voice = selectedVoice,
                    speed = requestedRate,
                ) { event ->
                    when (event) {
                        is TtsStreamEvent.Sentence -> {
                            noteAudio()
                            sentencesSeen = maxOf(sentencesSeen, event.index + 1)
                            handleSentence(event, streamScope)
                        }
                        is TtsStreamEvent.ParagraphStart -> handleParagraphStart(event)
                        is TtsStreamEvent.AudioPcm -> {
                            noteAudio()
                            // Backpressure lives here now that there is no gap
                            // between segments to gate on. Suspending inside the
                            // event callback stops draining the response body,
                            // which throttles the socket — so a long chapter
                            // never buffers more than MAX_BUFFER_AHEAD_MS of PCM.
                            awaitBufferHeadroom()
                            appendPcm(event.pcm)
                        }
                        is TtsStreamEvent.Chunk -> handleChunkAnchor(event)
                        is TtsStreamEvent.AlignmentPartial -> updateAlignment(event.paragraphIndex, event.words)
                        is TtsStreamEvent.AlignmentFinal -> updateAlignment(event.paragraphIndex, event.words)
                        is TtsStreamEvent.Error -> {
                            // Non-fatal ones are surfaced too: a truncated
                            // passage plays fine and just stops early, so
                            // swallowing it leaves the reader wondering why
                            // the chapter ended mid-sentence. Whatever already
                            // streamed still plays out.
                            _error.value = event.message
                            if (event.fatal) fatal = true
                            // Fatal or not, the chapter was not read to the end.
                            incomplete = true
                        }
                        TtsStreamEvent.Done -> {
                            // Drain handled once the stream completes.
                        }
                        TtsStreamEvent.Other -> { /* forward-compat */ }
                    }
                }
                sentenceBase += sentencesSeen

                if (!receivedAudio && _error.value == null) {
                    _error.value = "TTS returned no audio"
                }

                if (paragraphMode && receivedAudio && !fatal) {
                    pcmPlayer.awaitDrain()
                }

                _state.value = TtsState.IDLE
                _currentSentence.value = null
                _currentSentenceIndex.value = -1
                _currentWordIndex.value = -1
                // A chapter that produced no audio is not a chapter that was read.
                // Advancing on one walks the reader through the rest of the book at
                // network speed, a failed request per chapter.
                //
                // Neither is a chapter the backend gave up on part way. A
                // cancelled or truncated render ends the stream cleanly with
                // real audio behind it, so the only thing separating it from a
                // finished read is the error the backend sent alongside — and
                // auto-advancing past it silently skips the rest of the chapter.
                if (receivedAudio && !fatal && !incomplete) onFinished?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "TTS failed: ${e.message}"
                _state.value = TtsState.IDLE
                _currentSentence.value = null
                _currentWordIndex.value = -1
            } finally {
                wordTickerJob?.cancel()
                wordTickerJob = null
                if (paragraphMode) pcmPlayer.stop()
                paragraphMode = false
                paragraphs = emptyMap()
                resetSegmentBases()
            }
        }
    }

    private suspend fun appendPcm(pcm: ByteArray) {
        pcmPlayer.appendPcm(pcm)
        appendedBytes += pcm.size
    }

    /** Total audio handed to the sink, which is also where the next segment's timeline starts. */
    private fun appendedMs(): Long =
        if (streamSampleRate <= 0) 0L else appendedBytes / BYTES_PER_SAMPLE * 1000 / streamSampleRate

    /**
     * Hold the next request until playback has caught up. The sink queue is unbounded and
     * synthesis outruns realtime, so an unpaced chapter would buffer hours of PCM.
     */
    private suspend fun awaitBufferHeadroom() {
        if (!paragraphMode) return
        while (appendedMs() - pcmPlayer.currentPositionMs().coerceAtLeast(0) > MAX_BUFFER_AHEAD_MS) {
            delay(500)
        }
    }

    /**
     * Fold a chunk anchor into coarse word timings.
     *
     * A chunk's duration is spread across its words by count. That cannot know
     * one word took longer than another, but it tracks the voice to within a
     * sentence — enough to keep the right page in front of the listener, which
     * is the part of read-along that is not decoration. The real alignment
     * overwrites it wherever the two cover the same words; see
     * [resolveWordIndex].
     *
     * The newest anchor has no successor to bound it, so it is closed with the
     * rate the earlier ones actually read at. Every anchor before it is bounded
     * exactly, by where the next one said its audio starts — so the estimate
     * only ever applies to the chunk currently being spoken, and is corrected
     * the moment the next anchor lands.
     */
    private fun handleChunkAnchor(event: TtsStreamEvent.Chunk) {
        anchors += ChunkAnchor(
            wordOffset = wordBase + event.wordOffset,
            wordCount = event.wordCount,
            audioOffsetMs = segmentAudioBaseMs.toInt() + event.audioOffsetMs,
        )
        rebuildAnchorWords()
    }

    private fun rebuildAnchorWords() {
        if (anchors.isEmpty() || paragraphs.isEmpty()) return

        var closedWords = 0
        var closedMs = 0L
        for (i in 0 until anchors.size - 1) {
            closedWords += anchors[i].wordCount
            closedMs += (anchors[i + 1].audioOffsetMs - anchors[i].audioOffsetMs).toLong()
        }
        val wpm = if (closedMs > 0L && closedWords > 0) {
            closedWords * 60_000.0 / closedMs
        } else {
            ANCHOR_SEED_WPM
        }

        val byParagraph = mutableMapOf<Int, MutableList<TtsWordTiming>>()
        for ((i, a) in anchors.withIndex()) {
            if (a.wordCount <= 0) continue
            val key = paragraphKeyForWord(a.wordOffset) ?: continue
            val p = paragraphs[key] ?: continue
            val endMs = anchors.getOrNull(i + 1)?.audioOffsetMs
                ?: (a.audioOffsetMs + a.wordCount * 60_000.0 / wpm).toInt()
            val perWordMs = (endMs - a.audioOffsetMs).toDouble() / a.wordCount
            if (perWordMs <= 0.0) continue
            // Paragraph-local, like the aligner's own: the caller adds
            // `wordOffset` / `audioOffsetMs` back on.
            val baseWord = a.wordOffset - p.wordOffset
            val baseMs = (a.audioOffsetMs - p.audioOffsetMs).toDouble()
            val list = byParagraph.getOrPut(key) { mutableListOf() }
            for (k in 0 until a.wordCount) {
                list += TtsWordTiming(
                    wordIndex = baseWord + k,
                    // No text: nothing reads it off an anchor, and the engine
                    // gives the chunk as one string rather than per word.
                    text = "",
                    t0Ms = (baseMs + k * perWordMs).toInt(),
                    t1Ms = (baseMs + (k + 1) * perWordMs).toInt(),
                )
            }
        }

        paragraphs = paragraphs.mapValues { (key, p) ->
            byParagraph[key]?.let { p.copy(anchorWords = it) } ?: p
        }
    }

    /** The paragraph a global word index falls in — the last one starting at or before it. */
    private fun paragraphKeyForWord(wordOffset: Int): Int? =
        paragraphs.entries
            .filter { it.value.wordOffset <= wordOffset }
            .maxByOrNull { it.value.wordOffset }
            ?.key

    private fun resetSegmentBases() {
        anchors.clear()
        paragraphKeyBase = 0
        wordBase = 0
        sentenceBase = 0
        segmentAudioBaseMs = 0
        appendedBytes = 0
        streamSampleRate = 0
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun handleSentence(event: TtsStreamEvent.Sentence, streamScope: CoroutineScope) {
        noteRenderedRate(event.speed)
        _currentSentence.value = event.text
        _currentSentenceIndex.value = sentenceBase + event.index

        val audioBytes = Base64.decode(event.audioB64)
        val chunkJob = streamScope.launch(start = CoroutineStart.UNDISPATCHED) {
            chunkPlayer.playChunk(audioBytes)
        }
        currentChunkJob = chunkJob
        try {
            chunkJob.join()
        } finally {
            currentChunkJob = null
        }
    }

    private fun handleParagraphStart(event: TtsStreamEvent.ParagraphStart) {
        if (!paragraphMode) {
            paragraphMode = true
            streamSampleRate = event.sampleRate
            pcmPlayer.start(event.sampleRate)
            startWordTicker()
        }
        noteRenderedRate(event.speed)
        paragraphs = paragraphs + mapOf(
            paragraphKeyBase + event.index to ParagraphAlignment(
                // No rate arithmetic: the aligner ran against the audio as rendered,
                // so these already match what the sink reports as its position.
                wordOffset = wordBase + event.wordOffset,
                audioOffsetMs = segmentAudioBaseMs.toInt() + event.audioOffsetMs,
            ),
        )
        // No _currentSentence here: in paragraph mode the renderer's fallback path would
        // fuzzy-match the whole paragraph against blocks and light up an entire block
        // whenever the word ticker is between words. The ticker is the sole highlight source.
    }

    /**
     * Store the aligner's word timings for a paragraph.
     *
     * ⚠️ A partial carries only the PREFIX the voice has reached. The prefix is
     * monotonic (the engine clamps it to a high-water mark) and every partial
     * re-sends its words from index 0, so replacing wholesale is safe; a word's
     * TIMINGS are re-estimated each pass, which is what makes replacing
     * necessary. What must never happen is appending, which duplicates the
     * passage.
     *
     * Timings arrive in the rendered audio's own timebase — the aligner ran
     * against the same audio the sink is playing — so they are stored as-is.
     */
    private fun updateAlignment(paragraphIndex: Int, words: List<TtsWordTiming>) {
        val key = paragraphKeyBase + paragraphIndex
        val p = paragraphs[key] ?: return
        paragraphs = paragraphs + (key to p.copy(words = words))
    }

    /**
     * Reconcile the rate the server rendered at with the one the UI is claiming.
     *
     * A refused rate is not an error — real audio comes back, just at 1x — so
     * the only thing that goes wrong is the speed slider sitting somewhere the
     * audio plainly isn't. Snap it to the truth and say why once.
     */
    private fun noteRenderedRate(rendered: Double) {
        if (rendered <= 0.0 || kotlin.math.abs(rendered - requestedRate) < 1e-9) return
        requestedRate = rendered
        _selectedSpeed.value = rendered
        _error.value = "This voice can't do that speed — playing at ${rendered}x."
    }

    /**
     * Polls the PCM player's playback position at ~30 Hz, finds which paragraph
     * we're inside (by audio_offset_ms), and the word inside that paragraph.
     * Pushes the global word index into [_currentWordIndex].
     */
    private fun startWordTicker() {
        wordTickerJob?.cancel()
        wordTickerJob = scope.launch {
            while (isActive) {
                val posMs = pcmPlayer.currentPositionMs()
                if (posMs >= 0) {
                    val wi = stickyWordIndex(_currentWordIndex.value, paragraphs.values, posMs)
                    if (wi != _currentWordIndex.value) _currentWordIndex.value = wi
                }
                delay(33)
            }
        }
    }

    fun pause() {
        if (_state.value != TtsState.PLAYING) return
        if (paragraphMode) pcmPlayer.pause() else chunkPlayer.pause()
        _state.value = TtsState.PAUSED
        refreshNowPlaying()
    }

    fun resume() {
        if (_state.value != TtsState.PAUSED) return
        if (paragraphMode) pcmPlayer.resume() else chunkPlayer.resume()
        _state.value = TtsState.PLAYING
        refreshNowPlaying()
    }

    fun togglePlayPause() {
        when (_state.value) {
            TtsState.PLAYING -> pause()
            TtsState.PAUSED -> resume()
            else -> {}
        }
    }

    /** Cut the current sentence/paragraph short. */
    fun skipForward() {
        // Sentence mode: cancel just the in-flight playChunk so the stream loop
        // moves on to the next sentence. Paragraph mode has no good per-paragraph
        // analogue without server cooperation; ignore for now.
        currentChunkJob?.cancel()
    }

    fun clearError() {
        _error.value = null
    }

    fun stop() {
        synthesizeJob?.cancel()
        synthesizeJob = null
        wordTickerJob?.cancel()
        wordTickerJob = null
        chunkPlayer.stop()
        if (paragraphMode) pcmPlayer.stop()
        paragraphMode = false
        paragraphs = emptyMap()
        resetSegmentBases()
        _state.value = TtsState.IDLE
        _currentSentence.value = null
        _currentSentenceIndex.value = -1
        _currentWordIndex.value = -1
        endBackgroundSession()
    }

    private fun beginBackgroundSession() {
        if (backgroundActive) {
            refreshNowPlaying()
            return
        }
        backgroundActive = true
        PlaybackLifecycle.startBackgroundPlayback()
        PlaybackLifecycle.requestAudioFocus()
        PlaybackLifecycle.setControls(
            onPlayPause = { togglePlayPause() },
            onSkipForward = { skipForward() },
            onSkipBack = null,
            onStop = { stop() },
        )
        refreshNowPlaying()
    }

    private fun endBackgroundSession() {
        if (!backgroundActive) return
        backgroundActive = false
        PlaybackLifecycle.setControls(null, null, null, null)
        PlaybackLifecycle.abandonAudioFocus()
        PlaybackLifecycle.stopBackgroundPlayback()
    }

    private fun refreshNowPlaying() {
        if (!backgroundActive) return
        PlaybackLifecycle.updateNowPlaying(
            title = nowPlayingTitle,
            subtitle = nowPlayingSubtitle,
            coverUrl = nowPlayingCoverUrl,
            isPlaying = _state.value == TtsState.PLAYING,
            currentMs = 0,
            durationMs = 0,
            isAudioOnly = true,
        )
    }
}

/** Per-paragraph alignment + audio offset, keyed by paragraph_index. */
internal data class ParagraphAlignment(
    val wordOffset: Int,
    val audioOffsetMs: Int,
    val words: List<TtsWordTiming> = emptyList(),
    /**
     * Coarse timings interpolated from the engine's chunk anchors, covering the
     * whole paragraph rather than the prefix [words] has reached. Used only
     * beyond that prefix — see [resolveWordIndex].
     */
    val anchorWords: List<TtsWordTiming> = emptyList(),
)

internal fun resolveWordIndex(alignments: Collection<ParagraphAlignment>, posMs: Long): Int {
    // Walk paragraphs in offset order; pick the one whose [offset, offset+last word t1] contains posMs.
    // Snapshot to avoid mutation during iteration.
    val snapshot = alignments.sortedBy { it.audioOffsetMs }
    var active: ParagraphAlignment? = null
    for (p in snapshot) {
        if (posMs >= p.audioOffsetMs) active = p else break
    }
    val p = active ?: return -1
    val rel = (posMs - p.audioOffsetMs).coerceAtLeast(0)
    // Real timings while the voice is INSIDE what the aligner has resolved;
    // the chunk anchors beyond it. Alignment lands a chunk at a time and can be
    // well behind the voice (or off), and the scan below deliberately sticks to
    // the last word it knows — which out there would peg the highlight to the
    // aligner's frontier and leave the reader on a page the listener left
    // minutes ago. With no anchors to fall back on, sticking is still right:
    // that is the end of the read.
    val alignedThroughMs = p.words.lastOrNull()?.t1Ms ?: -1
    val timings = if (rel < alignedThroughMs || p.anchorWords.isEmpty()) p.words else p.anchorWords
    // Linear scan is fine — typical paragraph has < 200 words.
    var hit: TtsWordTiming? = null
    for (w in timings) {
        if (rel >= w.t0Ms && rel < w.t1Ms) { hit = w; break }
        if (rel < w.t0Ms) break
        hit = w // past the end of this word but maybe before next — keep last
    }
    return if (hit == null) -1 else p.wordOffset + hit.wordIndex
}

/**
 * The highlight stays on the most recently spoken word through inter-word silences and
 * paragraph boundaries. Publishing -1 there flipped the renderer's active block between
 * the spoken word and a fuzzy paragraph match, which bounced the page. Only [TtsController.stop]
 * resets to -1.
 */
internal fun stickyWordIndex(current: Int, alignments: Collection<ParagraphAlignment>, posMs: Long): Int =
    resolveWordIndex(alignments, posMs).let { if (it >= 0) it else current }
