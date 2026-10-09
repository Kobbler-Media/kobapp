package dev.kobbler.app.platform

import kotlinx.coroutines.delay
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

actual class PcmStreamPlayer actual constructor() {
    private var line: SourceDataLine? = null
    private var sampleRateHz: Int = 0
    @Volatile private var paused: Boolean = false
    @Volatile private var stopped: Boolean = false
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var writer: Thread? = null

    // Sentinel placed on the queue to signal "no more frames after this".
    private val drainMarker = ByteArray(0)

    actual fun start(sampleRate: Int) {
        if (line != null && sampleRateHz == sampleRate) return
        stop()
        stopped = false
        paused = false
        sampleRateHz = sampleRate
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false) // signed, little-endian
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val l = AudioSystem.getLine(info) as SourceDataLine
        // Ample buffer (~500ms) so brief jitter doesn't cause underruns mid-paragraph.
        l.open(format, sampleRate * 2 /*bytes per sec*/ / 2)
        l.start()
        line = l
        writer = Thread({ pumpLoop(l) }, "tts-pcm-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun pumpLoop(l: SourceDataLine) {
        while (!stopped) {
            val chunk = try { queue.take() } catch (_: InterruptedException) { break }
            if (chunk === drainMarker) continue
            if (chunk.isEmpty()) continue
            // SourceDataLine.write blocks until the line accepts the bytes —
            // backpressure flows naturally into the queue.
            try {
                l.write(chunk, 0, chunk.size)
            } catch (_: Exception) {
                break
            }
        }
    }

    actual fun appendPcm(samples: ByteArray) {
        if (stopped || samples.isEmpty()) return
        queue.put(samples)
    }

    actual fun currentPositionMs(): Long {
        val l = line ?: return -1
        if (sampleRateHz <= 0) return -1
        // microsecondPosition is from line.start, not just bytes written.
        // Falls back to framePosition if microsecondPosition is unsupported (returns -1 on some drivers).
        val micros = l.microsecondPosition
        return if (micros >= 0) micros / 1000
        else l.framePosition.toLong() * 1000 / sampleRateHz
    }

    actual fun pause() {
        if (stopped || paused) return
        paused = true
        line?.stop()
    }

    actual fun resume() {
        if (stopped || !paused) return
        paused = false
        line?.start()
    }

    actual suspend fun awaitDrain() {
        // Wait for queue to empty AND for line buffer to drain.
        while (!stopped && queue.isNotEmpty()) {
            delay(20)
        }
        val l = line ?: return
        // line.drain() blocks; offload via a poll loop to keep this suspending.
        while (!stopped && l.available() < l.bufferSize) {
            delay(20)
        }
    }

    actual fun stop() {
        if (stopped) return
        stopped = true
        queue.clear()
        queue.offer(drainMarker)
        try { writer?.interrupt() } catch (_: Exception) {}
        try { line?.flush(); line?.stop(); line?.close() } catch (_: Exception) {}
        line = null
        writer = null
        sampleRateHz = 0
    }
}
