package dev.kobbler.app.platform

import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLAudioElement

/**
 * WASM ChunkPlayer using HTML5 Audio element with data: URI.
 * Converts MP3 bytes to base64 data URL and plays via Audio element.
 */
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
actual class ChunkPlayer actual constructor() {
    private var audio: HTMLAudioElement? = null
    private var paused = false
    private var stopped = false

    actual suspend fun playChunk(mp3Bytes: ByteArray) {
        stopped = false
        paused = false

        // Convert MP3 bytes to base64 data URL
        val base64 = bytesToBase64(mp3Bytes)
        val dataUrl = "data:audio/mpeg;base64,$base64"

        val audioElement = document.createElement("audio") as HTMLAudioElement
        audioElement.src = dataUrl
        audio = audioElement
        audioElement.play()

        // Wait for playback to finish
        while (!stopped && !audioElement.ended) {
            delay(50)
            while (paused && !stopped) { delay(50) }
        }

        audio = null
    }

    actual fun pause() {
        paused = true
        audio?.pause()
    }

    actual fun resume() {
        paused = false
        audio?.play()
    }

    actual fun stop() {
        stopped = true
        paused = false
        audio?.pause()
        audio = null
    }
}

private fun bytesToBase64(bytes: ByteArray): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder((bytes.size * 4 / 3) + 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
        val remaining = bytes.size - i

        sb.append(chars[(b0 shr 2) and 0x3F])
        sb.append(chars[((b0 shl 4) or (b1 shr 4)) and 0x3F])
        if (remaining > 1) sb.append(chars[((b1 shl 2) or (b2 shr 6)) and 0x3F]) else sb.append('=')
        if (remaining > 2) sb.append(chars[b2 and 0x3F]) else sb.append('=')

        i += 3
    }
    return sb.toString()
}
