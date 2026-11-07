package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun SyncPlaybackFullscreen(playing: Boolean, windowFullscreen: Boolean, toggle: () -> Unit) {
    var tookFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(playing) {
        if (playing && !windowFullscreen) {
            toggle()
            tookFullscreen = true
        } else if (!playing && tookFullscreen) {
            if (windowFullscreen) toggle()
            tookFullscreen = false
        }
    }
}
