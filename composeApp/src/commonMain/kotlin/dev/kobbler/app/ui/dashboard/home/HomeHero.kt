@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import coil3.request.CachePolicy
import coil3.SingletonImageLoader
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.compose.useExistingImageAsPlaceholder
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.RecentlyAddedItem
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.SoftenedArtPx
import dev.kobbler.app.ui.components.StripedBackdrop

/** Whatever the page is pointing at: an Up Next entry, or any card in the rails below. */
@Immutable
data class Spotlight(
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
)

fun UpNextItem.spotlight() = Spotlight(
    backdropUrl = backdropUrl,
    posterUrl = mediaArtUrl,
)

fun RecentlyAddedItem.spotlight() = Spotlight(
    backdropUrl = backdropUrl,
    posterUrl = imageUrl,
)

fun DiscoverItem.spotlight() = Spotlight(
    backdropUrl = backdropUrl,
    posterUrl = posterUrl,
)

/**
 * The page's background, fixed behind every row rather than scrolling with the hero, so
 * the picture is cropped to the screen's shape — not to a short band showing only the
 * top of the art. Without a backdrop the poster stands in, softened.
 *
 * [spotlight] is read here and nowhere above, so a change of focus recomposes this layer
 * and not every rail on the page. The scrims are one draw pass: each full-screen layer
 * is a full-screen fill, and fill rate is what a TV stick's GPU runs out of first.
 */
@Composable
fun HomeBackdrop(spotlight: () -> Spotlight?, accent: Color, dim: () -> Float, modifier: Modifier = Modifier) {
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

/**
 * Up Next is where focus lands, so its backdrops are the next ones asked for. Fetched to
 * disk only: holding a dozen decoded full-screen bitmaps would crowd the posters out of a
 * low-memory device's cache.
 */
@Composable
fun PrefetchBackdrops(items: List<UpNextItem>) {
    val context = LocalPlatformContext.current
    LaunchedEffect(items) {
        val loader = SingletonImageLoader.get(context)
        items.drop(1).mapNotNull { it.backdropUrl?.takeIf(String::isNotBlank) }.forEach { url ->
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    // The bytes land in the disk cache whatever the decode size; a
                    // thumbnail keeps the decode itself from costing a core.
                    .size(SoftenedArtPx)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build(),
            )
        }
    }
}
