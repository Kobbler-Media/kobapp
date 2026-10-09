package dev.kobbler.app.platform

/**
 * Plays MP3 audio chunks sequentially. Used for TTS sentence-by-sentence playback.
 * Each chunk is a complete MP3 — call [playChunk] and suspend until it finishes.
 */
expect class ChunkPlayer() {
    /** Play an MP3 byte array. Suspends until playback completes. */
    suspend fun playChunk(mp3Bytes: ByteArray)

    /** Pause current playback. */
    fun pause()

    /** Resume paused playback. */
    fun resume()

    /** Stop and release. */
    fun stop()
}
