package dev.kobbler.app.ui.player

import dev.kobbler.app.platform.Settings
import dev.kobbler.kobplayer.AudioOutputMode

// Device-level, not per profile: what the HDMI chain can play belongs to the box.
const val KEY_AUDIO_OUTPUT = "pref_audio_output"

fun audioOutputModeSetting(): AudioOutputMode =
    Settings.getString(KEY_AUDIO_OUTPUT)
        ?.let { name -> AudioOutputMode.entries.firstOrNull { it.name == name } }
        ?: AudioOutputMode.AUTO
