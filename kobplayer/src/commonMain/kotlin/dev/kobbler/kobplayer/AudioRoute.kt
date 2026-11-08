package dev.kobbler.kobplayer

// libVLC rebuilds its audio output only when the device id changes, and an AudioTrack that
// failed once stays silent until it is rebuilt: a TV switched off and on under an unchanged
// route left playback mute until it was restarted. Once invalidated, the same id is reached
// by way of another, which libVLC applies as one rebuild at the next decoded block.
class AudioRoute {
    private var applied: String? = null
    private var stale = false

    fun select(device: String): List<String> {
        val previous = applied
        val rebuild = stale && previous == device
        stale = false
        applied = device
        return when {
            rebuild -> listOf(if (device == "stereo") "pcm" else "stereo", device)
            previous == device -> emptyList()
            else -> listOf(device)
        }
    }

    fun invalidate(): Boolean {
        if (applied != null) stale = true
        return stale
    }

    fun reset() {
        applied = null
        stale = false
    }
}
