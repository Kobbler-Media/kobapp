package dev.kobbler.app.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay
import java.util.concurrent.LinkedBlockingQueue

actual class PcmStreamPlayer actual constructor() {
    private var track: AudioTrack? = null
    private var sampleRateHz: Int = 0
    @Volatile private var paused: Boolean = false
    @Volatile private var stopped: Boolean = false
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var writer: Thread? = null
    private val drainMarker = ByteArray(0)

    actual fun start(sampleRate: Int) {
        if (track != null && sampleRateHz == sampleRate) return
        stop()
        stopped = false
        paused = false
        sampleRateHz = sampleRate

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * 2 / 2) // ~500 ms

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t

        writer = Thread({ pumpLoop(t) }, "tts-pcm-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun pumpLoop(t: AudioTrack) {
        while (!stopped) {
            val chunk = try { queue.take() } catch (_: InterruptedException) { break }
            if (chunk === drainMarker) continue
            if (chunk.isEmpty()) continue
            var off = 0
            while (off < chunk.size && !stopped) {
                val n = try {
                    t.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING)
                } catch (_: Exception) { -1 }
                if (n <= 0) break
                off += n
            }
        }
    }

    actual fun appendPcm(samples: ByteArray) {
        if (stopped || samples.isEmpty()) return
        queue.put(samples)
    }

    actual fun currentPositionMs(): Long {
        val t = track ?: return -1
        if (sampleRateHz <= 0) return -1
        val frames = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return frames * 1000 / sampleRateHz
    }

    actual fun pause() {
        if (stopped || paused) return
        paused = true
        try { track?.pause() } catch (_: Exception) {}
    }

    actual fun resume() {
        if (stopped || !paused) return
        paused = false
        try { track?.play() } catch (_: Exception) {}
    }

    actual suspend fun awaitDrain() {
        while (!stopped && queue.isNotEmpty()) delay(20)
        // No reliable "is buffer drained" signal on AudioTrack; poll position changes.
        var lastPos = currentPositionMs()
        var stable = 0
        while (!stopped && stable < 4) {
            delay(40)
            val p = currentPositionMs()
            if (p == lastPos) stable++ else { stable = 0; lastPos = p }
        }
    }

    actual fun stop() {
        if (stopped) return
        stopped = true
        queue.clear()
        queue.offer(drainMarker)
        try { writer?.interrupt() } catch (_: Exception) {}
        try {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        } catch (_: Exception) {}
        track = null
        writer = null
        sampleRateHz = 0
    }
}
