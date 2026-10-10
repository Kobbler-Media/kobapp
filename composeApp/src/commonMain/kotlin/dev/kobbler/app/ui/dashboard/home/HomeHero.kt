@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import coil3.request.CachePolicy
import coil3.SingletonImageLoader
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.compose.useExistingImageAsPlaceholder
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.RecentlyAddedItem
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.SoftenedArtPx
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

/** Whatever the page is pointing at: an Up Next entry, or any card in the rails below. */
@Immutable
data class Spotlight(
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val upNextIndex: Int? = null,
)

fun UpNextItem.spotlight(index: Int) = Spotlight(
    backdropUrl = backdropUrl,
    posterUrl = mediaArtUrl,
    upNextIndex = index,
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

/**
 * The open stretch of backdrop above Up Next, carrying Resume and Details where there is a
 * pointer. The title is on the focused card already. Its height is whatever the rows below
 * leave of the screen, so Up Next and the first rail are on screen without scrolling.
 */
@Composable
fun HeroCopy(
    spotlight: Spotlight,
    upNext: List<UpNextItem>,
    height: Dp,
    onResume: ((Int) -> Unit)?,
    onDetails: ((Int) -> Unit)?,
    onActionsFocused: () -> Unit,
) {
    val colors = KobappTheme.colors
    val tv = LocalFormFactor.current == FormFactor.TV
    val gutter = if (tv) KobappTheme.dimens.pageGutterTv else KobappTheme.dimens.pageGutter
    val index = spotlight.upNextIndex
    val item = index?.let { upNext.getOrNull(it) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (onResume != null && index != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        // The buttons inside are the real stops; a focusable backdrop
                        // would add an invisible step to every traversal.
                        .focusProperties { canFocus = false }
                        .clickable { onResume(index) }
                } else Modifier,
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (onResume != null && onDetails != null) {
                // Kept in place for a rail card, which has no Resume, so the rows below do
                // not jump as the pointer moves between them.
                val active = item != null
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .graphicsLayer { alpha = if (active) 1f else 0f }
                        .focusKeyGroup("hero")
                        .onFocusChanged { if (it.hasFocus) onActionsFocused() },
                ) {
                    val accent = item?.let { colors.accentForMediaType(it.mediaType) } ?: KobappTheme.accent
                    KobButton(
                        text = item?.ctaLabel ?: "Resume",
                        onClick = { if (index != null) onResume(index) },
                        enabled = active,
                        style = KobButtonStyle.Accent,
                        accent = accent,
                        leading = { Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.focusKey("resume"),
                    )
                    KobButton(
                        text = "Details",
                        onClick = { if (index != null) onDetails(index) },
                        enabled = active,
                        // The search pill sits up and to the right; it is reachable with Up.
                        modifier = Modifier
                            .focusKey("details")
                            .focusProperties { right = FocusRequester.Cancel },
                    )
                }
            }
        }
    }
}
