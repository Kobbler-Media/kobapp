package dev.kobbler.kobplayer

actual fun videoDecoderChoices(): List<VideoDecoderChoice> = emptyList()

actual fun deviceDecodesInterlaced(): Boolean = DeviceFieldDecode.current != FieldDecodeVerdict.HardwareEmitsNothing

actual fun deviceHdrFormats(): List<String> = DisplayHdr.formats()
