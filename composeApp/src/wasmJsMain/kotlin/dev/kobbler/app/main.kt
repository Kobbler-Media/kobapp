package dev.kobbler.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val container = document.getElementById("compose") ?: return
    ComposeViewport(container) {
        App()
    }
}
