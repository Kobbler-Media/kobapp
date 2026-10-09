package dev.kobbler.app.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class ChunkPlayer actual constructor() {
    private var audioPlayer: AVAudioPlayer? = null
    @Volatile
    private var paused = false

    actual suspend fun playChunk(mp3Bytes: ByteArray) {
        val nsData = mp3Bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = mp3Bytes.size.toULong())
        }

        val player = try {
            AVAudioPlayer(data = nsData, error = null)
        } catch (_: Exception) {
            // Fallback: estimate duration and delay
            val durationMs = (mp3Bytes.size.toLong() * 8 * 1000) / 128000
            delay(durationMs)
            return
        }

        audioPlayer = player
        player.prepareToPlay()

        suspendCancellableCoroutine { cont ->
            val delegate = AudioPlayerDelegate(cont)
            player.delegate = delegate
            player.play()

            cont.invokeOnCancellation {
                player.stop()
                audioPlayer = null
            }
        }

        audioPlayer = null
    }

    actual fun pause() {
        paused = true
        audioPlayer?.pause()
    }

    actual fun resume() {
        paused = false
        audioPlayer?.play()
    }

    actual fun stop() {
        paused = false
        audioPlayer?.stop()
        audioPlayer = null
    }
}

private class AudioPlayerDelegate(
    private val continuation: CancellableContinuation<Unit>,
) : NSObject(), AVAudioPlayerDelegateProtocol {
    override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
        if (continuation.isActive) continuation.resume(Unit)
    }

    override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
        if (continuation.isActive) continuation.resume(Unit)
    }
}
