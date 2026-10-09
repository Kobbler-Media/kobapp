package dev.kobbler.app.platform

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

actual class ChunkPlayer actual constructor() {
    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null

    actual suspend fun playChunk(mp3Bytes: ByteArray) {
        // Write temp file on IO thread
        val tmp = withContext(Dispatchers.IO) {
            File.createTempFile("tts_chunk_", ".mp3").also { it.writeBytes(mp3Bytes) }
        }
        tempFile = tmp

        // MediaPlayer callbacks require a Looper — must run on Main
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val mp = MediaPlayer()
                mediaPlayer = mp

                // Speech routed through the music stream so it survives screen-off
                // alongside the foreground service (default attrs use NOTIFICATION/RING
                // routing on some devices and don't get audio focus).
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                mp.setDataSource(tmp.absolutePath)
                mp.setOnCompletionListener {
                    mp.release()
                    mediaPlayer = null
                    tmp.delete()
                    tempFile = null
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnErrorListener { _, _, _ ->
                    mp.release()
                    mediaPlayer = null
                    tmp.delete()
                    tempFile = null
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                mp.prepare()
                mp.start()

                cont.invokeOnCancellation {
                    mp.release()
                    mediaPlayer = null
                    tmp.delete()
                    tempFile = null
                }
            }
        }
    }

    actual fun pause() {
        mediaPlayer?.pause()
    }

    actual fun resume() {
        mediaPlayer?.start()
    }

    actual fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        tempFile?.delete()
        tempFile = null
    }
}
