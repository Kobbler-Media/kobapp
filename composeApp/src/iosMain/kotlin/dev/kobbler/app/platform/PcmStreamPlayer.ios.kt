package dev.kobbler.app.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode

@OptIn(ExperimentalForeignApi::class)
actual class PcmStreamPlayer actual constructor() {
    private var engine: AVAudioEngine? = null
    private var player: AVAudioPlayerNode? = null
    private var format: AVAudioFormat? = null
    private var sampleRateHz: Int = 0
    @Volatile private var paused: Boolean = false
    @Volatile private var stopped: Boolean = false

    // Frames scheduled (whether played yet or not). Used to compute end of stream.
    private var framesScheduled: Long = 0

    actual fun start(sampleRate: Int) {
        if (engine != null && sampleRateHz == sampleRate) return
        stop()
        stopped = false
        paused = false
        sampleRateHz = sampleRate
        framesScheduled = 0

        val eng = AVAudioEngine()
        val pl = AVAudioPlayerNode()
        // Float32 mono — we convert from s16 on the fly because AVAudioFormat
        // for non-interleaved Int16 buffers is fiddly across iOS versions.
        val fmt = AVAudioFormat(
            commonFormat = AVAudioPCMFormatFloat32,
            sampleRate = sampleRate.toDouble(),
            channels = 1u,
            interleaved = false,
        )

        eng.attachNode(pl)
        eng.connect(pl, eng.mainMixerNode, fmt)
        try {
            eng.startAndReturnError(null)
        } catch (_: Throwable) {
            return
        }
        pl.play()

        engine = eng
        player = pl
        format = fmt
    }

    actual fun appendPcm(samples: ByteArray) {
        if (stopped || samples.isEmpty()) return
        val pl = player ?: return
        val fmt = format ?: return

        // Each sample = 2 bytes (s16le). Drop trailing odd byte if any.
        val frameCount = samples.size / 2
        if (frameCount == 0) return

        val buf = AVAudioPCMBuffer(pCMFormat = fmt, frameCapacity = frameCount.toUInt())
            ?: return
        buf.frameLength = frameCount.toUInt()

        // Float32 channelData[0]
        val channelData = buf.floatChannelData ?: return
        val ptr = channelData[0] ?: return

        // Convert s16le → float32 in [-1,1]
        for (i in 0 until frameCount) {
            val lo = samples[i * 2].toInt() and 0xFF
            val hi = samples[i * 2 + 1].toInt() // sign-extended
            val s = (hi shl 8) or lo
            ptr[i] = s.toFloat() / 32768f
        }

        framesScheduled += frameCount
        pl.scheduleBuffer(buf, completionHandler = null)
    }

    actual fun currentPositionMs(): Long {
        val pl = player ?: return -1
        val sr = sampleRateHz
        if (sr <= 0) return -1
        val nodeTime = pl.lastRenderTime ?: return 0L
        val playerTime = pl.playerTimeForNodeTime(nodeTime) ?: return 0L
        return playerTime.sampleTime.toLong() * 1000 / sr
    }

    actual fun pause() {
        if (stopped || paused) return
        paused = true
        try { player?.pause() } catch (_: Exception) {}
    }

    actual fun resume() {
        if (stopped || !paused) return
        paused = false
        try { player?.play() } catch (_: Exception) {}
    }

    actual suspend fun awaitDrain() {
        val sr = sampleRateHz
        if (sr <= 0) return
        // Wait until playback time catches the last scheduled sample.
        while (!stopped) {
            val pl = player ?: return
            val nodeTime = pl.lastRenderTime
            val playerTime = nodeTime?.let { pl.playerTimeForNodeTime(it) }
            val played = playerTime?.sampleTime?.toLong() ?: 0L
            if (played >= framesScheduled) return
            delay(40)
        }
    }

    actual fun stop() {
        if (stopped) return
        stopped = true
        try { player?.stop() } catch (_: Exception) {}
        try { engine?.stop() } catch (_: Exception) {}
        player = null
        engine = null
        format = null
        sampleRateHz = 0
        framesScheduled = 0
    }
}
