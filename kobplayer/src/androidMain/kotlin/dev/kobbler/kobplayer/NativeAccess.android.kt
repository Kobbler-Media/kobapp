package dev.kobbler.kobplayer

import org.videolan.libvlc.LibVLC

/**
 * Typed access to the libvlc-android [org.videolan.libvlc.MediaPlayer].
 *
 * ```
 * player.native.asLibVlc()?.vlcPlayer?.setEqualizer(...)
 * ```
 */
val NativePlayer.LibVlc.vlcPlayer: org.videolan.libvlc.MediaPlayer
    get() = player as org.videolan.libvlc.MediaPlayer

/**
 * Typed access to the [LibVLC] instance.
 */
val NativePlayer.LibVlc.vlcLib: LibVLC
    get() = libVLC as LibVLC
