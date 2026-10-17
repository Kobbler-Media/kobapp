package dev.kobbler.app.ui.detail

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.kobbler.app.ui.components.Spotlight
import dev.kobbler.app.ui.components.SpotlightBackdrop

// The title and synopsis sit straight on the picture from the first frame, unlike Home
// whose opening stretch is bare backdrop, so some dim is there before any scroll.
private const val DetailDimTop = 0.25f
private const val DetailDimScrolled = 0.6f
private val DetailDimFade = 320.dp

@Composable
fun DetailBackdrop(spotlight: Spotlight, accent: Color, scroll: ScrollState) {
    val fadePx = with(LocalDensity.current) { DetailDimFade.toPx() }
    SpotlightBackdrop(
        spotlight = { spotlight },
        accent = accent,
        dim = {
            val scrolled = (scroll.value / fadePx).coerceIn(0f, 1f)
            DetailDimTop + scrolled * (DetailDimScrolled - DetailDimTop)
        },
        modifier = Modifier.fillMaxSize(),
    )
}
