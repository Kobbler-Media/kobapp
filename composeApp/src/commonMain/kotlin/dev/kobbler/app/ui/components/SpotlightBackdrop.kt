package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import dev.kobbler.app.theme.KobappTheme

/**
 * A page's background, fixed behind every row rather than scrolling with them, so the
 * picture is cropped to the screen's shape — not to a short band showing only the top of
 * the art. Without a backdrop the poster stands in, softened.
 *
 * [spotlight] is read here and nowhere above, so a change of focus recomposes this layer
 * and not every row on the page. The scrims are one draw pass: each full-screen layer
 * is a full-screen fill, and fill rate is what a TV stick's GPU runs out of first.
 */
@Composable
fun SpotlightBackdrop(spotlight: () -> Spotlight?, accent: Color, dim: () -> Float, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    val spot = spotlight()
    val backdrop = spot?.backdropUrl?.takeIf { it.isNotBlank() }
    val poster = spot?.posterUrl?.takeIf { it.isNotBlank() }
    val bottomScrim = remember(colors.bg) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.30f to Color.Transparent,
            0.60f to colors.bg.copy(alpha = 0.72f),
            1f to colors.bg.copy(alpha = 0.94f),
        )
    }
    val sideScrim = remember(colors.bg) {
        Brush.horizontalGradient(0f to colors.bg.copy(alpha = 0.88f), 0.60f to Color.Transparent)
    }
    Box(
        modifier
            // Picture and scrims change only with the spotlight, so they are drawn into a
            // cached layer and composited as one texture. The scroll dim is that layer's
            // alpha over the page's own background: a full-screen dim rect on top would be
            // one more full-screen fill a frame, and scrolling does not re-rasterise it.
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = 1f - dim()
            }
            .drawWithContent {
                drawContent()
                drawRect(bottomScrim)
                drawRect(sideScrim)
            },
    ) {
        val art = backdrop?.let { Art(it, softened = false) } ?: poster?.let { Art(it, softened = true) }
        if (art == null) {
            StripedBackdrop(accent = accent, opacity = 0.55f, withRadialOverlay = false, modifier = Modifier.fillMaxSize())
        } else {
            ArtCrossfade(art, Modifier.fillMaxSize())
        }
    }
}
