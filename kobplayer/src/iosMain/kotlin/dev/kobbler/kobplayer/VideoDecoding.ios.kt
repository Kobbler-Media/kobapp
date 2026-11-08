package dev.kobbler.kobplayer

actual fun videoDecoderChoices(): List<VideoDecoderChoice> = emptyList()

actual fun deviceDecodesInterlaced(): Boolean = true

// LibVLC tone-maps HDR itself, but never applies a Dolby Vision RPU.
actual fun deviceHdrFormats(): List<String> = listOf("hdr10", "hlg")
