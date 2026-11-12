package dev.kobbler.app.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import dev.kobbler.app.platform.Settings
import dev.kobbler.kobplayer.Loudness
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.loudnessGain

// Device-level, not per profile, like the web player's own switch.
private const val KEY_NORMALIZE_VOLUME = "pref_normalize_volume"

private val normalizeVolumeState by lazy { mutableStateOf(Settings.getString(KEY_NORMALIZE_VOLUME) != "false") }

var normalizeVolume: Boolean
    get() = normalizeVolumeState.value
    set(value) {
        normalizeVolumeState.value = value
        Settings.putString(KEY_NORMALIZE_VOLUME, value.toString())
    }

suspend fun GlobalPlayerState.levelLoudness(player: Player) = levelPlayer(player) { loudness }

/** Follows [loudness] until cancelled, then hands the player back flat for whoever plays next. */
suspend fun levelPlayer(player: Player, loudness: () -> Loudness?) {
    try {
        snapshotFlow { loudness()?.takeIf { normalizeVolume } }
            .collect { player.setNormalizationGain(loudnessGain(it)) }
    } finally {
        player.setNormalizationGain(1f)
    }
}
