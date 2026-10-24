package dev.kobbler.kobplayer

// Read ahead in bursts and leave the source alone in between, as ExoPlayer does: topped up
// as it drained, the stream arrived every second and the radio never slept. Refilled with
// half the buffer still unread: a connection that died while idle takes kobvlc up to ten
// seconds to notice and replace, and at a 4K bitrate a quarter of the buffer is less than
// that.
data class ReadAhead(val bufferKb: Int = 64 * 1024, val refillPercent: Int = 50) {
    internal val vlcOptions: List<String>
        get() = listOf("--prefetch-buffer-size=$bufferKb", "--prefetch-refill-level=$refillPercent")

    internal fun forLowRam() = copy(bufferKb = minOf(bufferKb, 16 * 1024))
}
