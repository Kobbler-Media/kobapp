package dev.kobbler.app.platform

import dev.kobbler.kobplayer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Desktop ChunkPlayer using kobplayer's audio-only player for MP3 playback.
 * Reuses a single VLC player instance across chunks to avoid per-chunk overhead.
 */
actual class ChunkPlayer actual constructor() {
    @Volatile
    private var paused = false
    private var player: Player? = null
    private var tempFile: File? = null

    private fun ensurePlayer(): Player {
        return player ?: createPlayer(PlayerConfig(audioOnly = true)).also { player = it }
    }

    actual suspend fun playChunk(mp3Bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("tts_chunk_", ".mp3").also { tempFile = it }
            tmp.writeBytes(mp3Bytes)
            tmp.deleteOnExit()

            val p = ensurePlayer()
            p.load(MediaSource.Url(url = tmp.toURI().toString()))
            p.play()

            // Wait for playback to finish
            try {
                p.state.first { it == PlayerState.FINISHED || it == PlayerState.ERROR }
            } catch (_: CancellationException) {
                // cancelled — cleanup below
            }

            tmp.delete()
            tempFile = null
        }
    }

    actual fun pause() {
        paused = true
        player?.pause()
    }

    actual fun resume() {
        paused = false
        player?.play()
    }

    actual fun stop() {
        paused = false
        player?.release()
        player = null
        tempFile?.delete()
        tempFile = null
    }
}
