package dev.kobbler.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.kobbler.app.platform.BrowserFullscreen
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val container = document.getElementById("compose") ?: return
    val fullscreen = BrowserFullscreen()
    ComposeViewport(container) {
        App(onToggleFullscreen = fullscreen::toggle, isFullscreen = fullscreen.active)
    }
}
