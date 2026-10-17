@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

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
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.useExistingImageAsPlaceholder
import coil3.request.ImageRequest
import coil3.request.crossfade
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
        if (backdrop == null && poster == null) {
            StripedBackdrop(accent = accent, opacity = 0.55f, withRadialOverlay = false, modifier = Modifier.fillMaxSize())
        } else {
            val context = LocalPlatformContext.current
            // One image whose request changes, fading from the picture already on screen:
            // a Crossfade of two composables is a full-screen saveLayer a frame, which a TV
            // stick cannot afford, and without any fade the new picture jumps in.
            val request = remember(backdrop, poster) {
                ImageRequest.Builder(context)
                    .data(backdrop ?: poster)
                    .apply { if (backdrop == null) size(SoftenedArtPx) }
                    .crossfade(BackdropFadeMs)
                    .useExistingImageAsPlaceholder(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (backdrop != null) 1f else 0.6f,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val BackdropFadeMs = 400
