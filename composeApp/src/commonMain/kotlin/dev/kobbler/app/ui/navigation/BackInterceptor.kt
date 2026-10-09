package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import dev.kobbler.app.platform.BackHandler

/**
 * Lets a screen claim BACK before the root navigation handler acts on it.
 *
 * The root runs as an `onPreviewKeyEvent`, which dispatches root-first, so a screen that
 * owns dismissable state (an open search, an expanded panel) can never see Escape on its
 * own — the root has already gone to the sidebar or popped the stack. Screens register
 * here instead of the root growing a special case per widget.
 *
 * This covers the keyboard path only. Android's hardware BACK does not arrive as a key
 * event once a text field holds focus — it goes through OnBackPressedDispatcher, where an
 * unhandled press finishes the Activity — so [InterceptBack] installs a platform
 * BackHandler for that path instead of routing it through here.
 */
@Stable
class BackInterceptors {
    private val handlers = mutableListOf<() -> Boolean>()

    fun add(handler: () -> Boolean) {
        handlers += handler
    }

    fun remove(handler: () -> Boolean) {
        handlers -= handler
    }

    /** Innermost first: the most recently registered screen is the one on top. */
    fun consume(): Boolean = handlers.asReversed().any { it() }
}

val LocalBackInterceptors = staticCompositionLocalOf { BackInterceptors() }

@Composable
fun InterceptBack(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)

    val registry = LocalBackInterceptors.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(registry) {
        val handler = {
            if (currentEnabled) {
                currentOnBack()
                true
            } else {
                false
            }
        }
        registry.add(handler)
        onDispose { registry.remove(handler) }
    }
}
