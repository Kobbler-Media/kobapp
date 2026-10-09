package dev.kobbler.app.platform

import androidx.compose.runtime.Composable

interface VoiceInputLauncher {
    val isAvailable: Boolean
    fun start()
}

/** Compose-aware speech-to-text launcher. Returns null on platforms that don't
 *  support voice input (Desktop, Wasm). The returned launcher captures
 *  [onResult] for the duration of the composition. */
@Composable
expect fun rememberVoiceInputLauncher(onResult: (String) -> Unit): VoiceInputLauncher?
