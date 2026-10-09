@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.kobbler.app.platform

import kotlinx.coroutines.delay

actual class PcmStreamPlayer actual constructor() {
    private var ctxHandle: JsAny? = null
    private var sampleRateHz: Int = 0
    // Single-threaded — no @Volatile needed on wasm.
    private var paused: Boolean = false
    private var stopped: Boolean = false
    private var nextStartSec: Double = 0.0
    private var startedAtSec: Double = 0.0

    actual fun start(sampleRate: Int) {
        if (ctxHandle != null && sampleRateHz == sampleRate) return
        stop()
        stopped = false
        paused = false
        sampleRateHz = sampleRate
        ctxHandle = createAudioContext(sampleRate.toDouble())
        startedAtSec = audioContextNow(ctxHandle!!)
        nextStartSec = startedAtSec
    }

    actual fun appendPcm(samples: ByteArray) {
        if (stopped) return
        val ctx = ctxHandle ?: return
        val frameCount = samples.size / 2
        if (frameCount == 0) return

        // Convert s16le → Float32 in JS via a base64 wire (cheaper than crossing the bridge per-sample).
        // We pack as JsArray of doubles for simplicity; volumes are paragraph-sized so the per-call
        // cost is fine.
        val floats = DoubleArray(frameCount)
        for (i in 0 until frameCount) {
            val lo = samples[i * 2].toInt() and 0xFF
            val hi = samples[i * 2 + 1].toInt() // sign-extended
            val s = (hi shl 8) or lo
            floats[i] = s.toDouble() / 32768.0
        }
        val jsArr = floatsToJsArray(floats)
        val now = audioContextNow(ctx)
        if (nextStartSec < now) nextStartSec = now
        val durSec = frameCount.toDouble() / sampleRateHz.toDouble()
        scheduleBuffer(ctx, jsArr, sampleRateHz, nextStartSec)
        nextStartSec += durSec
    }

    actual fun currentPositionMs(): Long {
        val ctx = ctxHandle ?: return -1
        if (sampleRateHz <= 0) return -1
        val now = audioContextNow(ctx)
        val elapsed = (now - startedAtSec).coerceAtLeast(0.0)
        return (elapsed * 1000.0).toLong()
    }

    actual fun pause() {
        if (stopped || paused) return
        paused = true
        ctxHandle?.let { suspendContext(it) }
    }

    actual fun resume() {
        if (stopped || !paused) return
        paused = false
        ctxHandle?.let { resumeContext(it) }
    }

    actual suspend fun awaitDrain() {
        val ctx = ctxHandle ?: return
        while (!stopped) {
            val now = audioContextNow(ctx)
            if (now >= nextStartSec) return
            delay(40)
        }
    }

    actual fun stop() {
        if (stopped) return
        stopped = true
        ctxHandle?.let { closeContext(it) }
        ctxHandle = null
        sampleRateHz = 0
        nextStartSec = 0.0
        startedAtSec = 0.0
    }
}

// ---- JS interop ------------------------------------------------------------
// Kotlin/Wasm js("...") bodies must be a single JS expression — wrap any
// statement bodies in IIFEs so we can keep this all inline.

private fun createAudioContext(sampleRate: Double): JsAny =
    js("new (window.AudioContext || window.webkitAudioContext)({ sampleRate: sampleRate })")

private fun audioContextNow(ctx: JsAny): Double = js("ctx.currentTime")

private fun suspendContext(ctx: JsAny) {
    js("(function(){ if (ctx.suspend) ctx.suspend(); })()")
}

private fun resumeContext(ctx: JsAny) {
    js("(function(){ if (ctx.resume) ctx.resume(); })()")
}

private fun closeContext(ctx: JsAny) {
    js("(function(){ try { ctx.close(); } catch (e) {} })()")
}

private fun floatsToJsArray(arr: DoubleArray): JsAny {
    val len = arr.size
    val out = jsEmptyArray(len)
    for (i in 0 until len) jsSetIndex(out, i, arr[i])
    return out
}

private fun jsEmptyArray(len: Int): JsAny = js("new Array(len)")
private fun jsSetIndex(arr: JsAny, i: Int, v: Double) { js("(arr[i] = v)") }

private fun scheduleBuffer(ctx: JsAny, samples: JsAny, sampleRate: Int, startTime: Double) {
    js(
        "(function(){" +
            "var buf = ctx.createBuffer(1, samples.length, sampleRate);" +
            "var ch = buf.getChannelData(0);" +
            "for (var i = 0; i < samples.length; i++) ch[i] = samples[i];" +
            "var src = ctx.createBufferSource();" +
            "src.buffer = buf;" +
            "src.connect(ctx.destination);" +
            "src.start(startTime);" +
        "})()"
    )
}
