package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.kobbler.app.ui.components.Spotlight
import dev.kobbler.app.ui.components.SpotlightBackdrop
import dev.kobbler.app.ui.components.SpotlightOnScroll
import dev.kobbler.app.ui.components.SpotlightState
import dev.kobbler.app.ui.components.bottomRowKeys
import dev.kobbler.app.ui.components.hostedBackdrop

// Posters packed edge to edge are busier than Home's rails, so the picture fades sooner.
private const val ScrolledBrowseDim = 0.65f
private val BrowseDimFade = 240.dp

/**
 * [spotlightsFor] maps a grid item's key to what it can show: one card, or every card in
 * a rail spanning the row.
 */
@Composable
internal fun BrowseBackdrop(
    spotlight: SpotlightState,
    accent: Color,
    gridState: LazyGridState,
    touch: Boolean,
    spotlightsFor: (key: Any) -> List<Spotlight>,
) {
    val hasSpotlight by remember(spotlight) { derivedStateOf { spotlight.shown != null } }
    if (touch) {
        SpotlightOnScroll(
            state = spotlight,
            position = { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset },
            atTop = { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 },
            bottomRow = { gridState.bottomRowKeys().flatMap(spotlightsFor) },
        )
    }
    val fadePx = with(LocalDensity.current) { BrowseDimFade.toPx() }
    val dim = remember(gridState, fadePx) {
        {
            val scrolled = if (gridState.firstVisibleItemIndex > 0) 1f
            else (gridState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
            scrolled * ScrolledBrowseDim
        }
    }
    if (hostedBackdrop(spotlight, accent, dim) || !hasSpotlight) return
    SpotlightBackdrop(
        spotlight = { spotlight.shown },
        accent = accent,
        dim = dim,
        modifier = Modifier.fillMaxSize(),
    )
}
