package dev.kobbler.kobplayer

import org.w3c.dom.HTMLVideoElement

/**
 * Typed access to the HTML5 [HTMLVideoElement].
 *
 * ```
 * player.native.asHtmlVideo()?.videoElement?.playbackRate = 2.0
 * ```
 */
val NativePlayer.HtmlVideo.videoElement: HTMLVideoElement
    get() = element as HTMLVideoElement
