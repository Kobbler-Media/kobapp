package dev.kobbler.app.platform

import androidx.compose.runtime.Composable

@Composable
actual fun KeyboardCompletions(completions: List<String>, onPick: (String) -> Unit, content: @Composable () -> Unit) = content()
