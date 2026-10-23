package dev.kobbler.kobplayer

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import uk.co.caprica.vlcj.player.base.MediaPlayer

// vlcj 5.0.0-M6's media().info().statistics() still passes libvlc 3's stats struct, and
// libvlc 4's 64-bit counters write past the end of it and corrupt the heap.
internal fun MediaPlayer.inputBytesRead(): Long {
    val ref = media().newMediaRef() ?: return 0
    return try {
        Memory(256).also { Libvlc4Stats.libvlc_media_get_stats(ref.mediaInstance().pointer, it) }.getLong(0)
    } finally {
        ref.release()
    }
}

@Suppress("FunctionName")
private object Libvlc4Stats {
    init {
        Native.register("vlc")
    }

    external fun libvlc_media_get_stats(media: Pointer, stats: Pointer): Boolean
}
