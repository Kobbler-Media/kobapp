package dev.kobbler.app.platform

import androidx.compose.runtime.Composable

// iOS SFSpeechRecognizer integration is intentionally deferred — needs
// Info.plist NSSpeechRecognitionUsageDescription + microphone permission flow
// + AVAudioSession setup that's outside the scope of this redesign pass.
// Returns null so the mic button is hidden until that work lands.
@Composable
actual fun rememberVoiceInputLauncher(onResult: (String) -> Unit): VoiceInputLauncher? = null
