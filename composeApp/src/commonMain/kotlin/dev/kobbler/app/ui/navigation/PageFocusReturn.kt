package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import dev.kobbler.kobfocus.FocusScope
import dev.kobbler.kobfocus.LocalFocusScope

/**
 * Hands focus from the top bar back to the page under it.
 *
 * Compose's 2D search cannot do this itself: it weighs a scroller as one candidate by its
 * own bounds, and a page that runs up under the floating bar starts above it, so it is
 * never "below" the bar and Down from there went nowhere.
 */
@Stable
internal class PageFocusReturn {
    private var scope: FocusScope? = null

    @Composable
    fun Track() {
        val current = LocalFocusScope.current
        DisposableEffect(current) {
            scope = current
            onDispose { if (scope === current) scope = null }
        }
    }

    suspend fun returnFocus() {
        val page = scope ?: return
        if (!page.restore(page.save())) page.focusFirst()
    }
}
