package dev.kobbler.app.platform

import androidx.compose.runtime.Composable

/** Offers [completions] in the on-screen keyboard's own suggestion row while [content]'s text field is being typed in. */
@Composable
expect fun KeyboardCompletions(completions: List<String>, onPick: (String) -> Unit, content: @Composable () -> Unit)
