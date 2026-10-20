package dev.kobbler.app.ui.detail

import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.platform.FormFactor
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

// A phone sets every line of text across the full width of the picture, with no empty
// stretch beside a poster for the image to show through, so it needs the heavier dim.
private const val PhoneDimTop = 0.6f
private const val PhoneDimScrolled = 0.75f
private val DetailDimFade = 320.dp

@Composable
fun DetailBackdrop(spotlight: Spotlight, accent: Color, scroll: ScrollState) {
    val fadePx = with(LocalDensity.current) { DetailDimFade.toPx() }
    val phone = LocalFormFactor.current == FormFactor.Phone
    val top = if (phone) PhoneDimTop else DetailDimTop
    val scrolledDim = if (phone) PhoneDimScrolled else DetailDimScrolled
    SpotlightBackdrop(
        spotlight = { spotlight },
        accent = accent,
        dim = {
            val scrolled = (scroll.value / fadePx).coerceIn(0f, 1f)
            top + scrolled * (scrolledDim - top)
        },
        modifier = Modifier.fillMaxSize(),
        // A detail page's text runs most of the way across, not just down the left as
        // Home's does: the shade has to reach under the meta line and the synopsis.
        sideScrimReach = 0.95f,
    )
}
