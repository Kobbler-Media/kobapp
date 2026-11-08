package dev.kobbler.kobplayer

actual fun videoDecoderChoices(): List<VideoDecoderChoice> = emptyList()

actual fun deviceDecodesInterlaced(): Boolean = true

// Never asked: the web build's capabilities come from browserCapabilities().
actual fun deviceHdrFormats(): List<String> = emptyList()
