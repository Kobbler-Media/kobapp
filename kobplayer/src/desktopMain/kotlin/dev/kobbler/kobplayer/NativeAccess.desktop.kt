package dev.kobbler.kobplayer

import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

/**
 * Typed access to the vlcj [EmbeddedMediaPlayer].
 *
 * ```
 * player.native.vlcj.chapters().setChapter(3)
 * player.native.vlcj.audio().equalizer()
 * ```
 */
val NativePlayer.Vlcj.vlcj: EmbeddedMediaPlayer
    get() = player as EmbeddedMediaPlayer
