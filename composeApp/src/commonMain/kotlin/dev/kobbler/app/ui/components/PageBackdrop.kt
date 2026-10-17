package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag

@Stable
class PageBackdrop {
    internal var spotlight by mutableStateOf<SpotlightState?>(null)
    internal var accent by mutableStateOf(Color.Unspecified)
    internal var dim by mutableStateOf<() -> Float>({ 0f })
}

val LocalPageBackdrop = staticCompositionLocalOf<PageBackdrop?> { null }

const val PageBackdropTag = "page-backdrop"

/**
 * Draws a page's backdrop behind everything inside it, the bars over the page included, so
 * on a phone the picture runs up under the status bar instead of starting below the chrome.
 * The page itself only says what to show, through [hostedBackdrop].
 */
@Composable
fun PageBackdropHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val host = remember { PageBackdrop() }
    Box(modifier.fillMaxSize()) {
        host.spotlight?.let { state ->
            SpotlightBackdrop(
                spotlight = { state.shown },
                accent = host.accent,
                dim = { host.dim() },
                modifier = Modifier.fillMaxSize().testTag(PageBackdropTag),
            )
        }
        CompositionLocalProvider(LocalPageBackdrop provides host) { content() }
    }
}

/**
 * Hands [state] to an enclosing [PageBackdropHost]. True when there is one, and the caller
 * then draws no backdrop of its own.
 */
@Composable
fun hostedBackdrop(state: SpotlightState, accent: Color, dim: () -> Float): Boolean {
    val host = LocalPageBackdrop.current ?: return false
    DisposableEffect(host, state) {
        host.spotlight = state
        onDispose { if (host.spotlight === state) host.spotlight = null }
    }
    SideEffect {
        host.accent = accent
        host.dim = dim
    }
    return true
}
