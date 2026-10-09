package dev.kobbler.app.ui.player

import dev.kobbler.app.platform.Settings
import dev.kobbler.kobplayer.VideoDecoding

// Device-level, not per profile: which GPUs exist belongs to the machine.
const val KEY_VIDEO_DECODING = "pref_video_decoding"

fun videoDecodingSetting(): VideoDecoding = VideoDecoding.fromKey(Settings.getString(KEY_VIDEO_DECODING))
