package dev.kobbler.app.platform

/**
 * Gapless PCM streaming player. Used for the streaming TTS paragraph path
 * where the backend pushes raw 16-bit-LE mono PCM frames as they're generated.
 *
 * Lifecycle: start(sampleRate) → appendPcm(...) × N → drainAndStop().
 * [currentPositionMs] is playback time (not bytes-fed) so word-level highlight
 * can sync against the audio sink, not the network.
 */
expect class PcmStreamPlayer() {
    /** Open the audio sink at [sampleRate] Hz, 16-bit-LE mono. Idempotent within a single sample-rate session. */
    fun start(sampleRate: Int)

    /** Append PCM samples (16-bit-LE mono) to the playing stream. Non-blocking. */
    fun appendPcm(samples: ByteArray)

    /** Milliseconds of audio actually rendered to the output, not the buffered amount. -1 if not started. */
    fun currentPositionMs(): Long

    /** Pause playback. Buffered audio is preserved. */
    fun pause()

    /** Resume after pause. */
    fun resume()

    /** Suspend until all currently buffered audio has been rendered. */
    suspend fun awaitDrain()

    /** Stop playback and release the sink. Subsequent appendPcm calls are silently dropped. */
    fun stop()
}
