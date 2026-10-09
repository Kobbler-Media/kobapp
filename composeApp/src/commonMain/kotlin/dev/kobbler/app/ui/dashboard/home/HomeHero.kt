package dev.kobbler.app.ui.dashboard.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import coil3.request.CachePolicy
import coil3.SingletonImageLoader
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.RecentlyAddedItem
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.ReasonChip
import dev.kobbler.app.ui.components.SoftenedArtPx
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.app.ui.components.softenedArt
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

/** Whatever the page is pointing at: an Up Next entry, or any card in the rails below. */
@Immutable
data class Spotlight(
    val title: String,
    val caption: String,
    val subtitle: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val upNextIndex: Int? = null,
)

fun UpNextItem.spotlight(index: Int) = Spotlight(
    title = title,
    caption = "Up next",
    subtitle = subtitle,
    backdropUrl = backdropUrl,
    posterUrl = mediaArtUrl,
    upNextIndex = index,
)

fun RecentlyAddedItem.spotlight() = Spotlight(
    title = title,
    caption = "Recently added",
    subtitle = subtitle,
    backdropUrl = backdropUrl,
    posterUrl = imageUrl,
)

fun DiscoverItem.spotlight(section: String) = Spotlight(
    title = title,
    caption = section,
    subtitle = year?.toString(),
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
            .drawWithContent {
                // Fully covered, the picture would still be a full-screen texture a frame.
                if (dim() < 1f) drawContent()
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                // Picture and scrims change only with the spotlight, so they are drawn
                // into a cached layer and composited as one texture; the scroll dim sits
                // outside it so scrolling does not re-rasterise the picture.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(bottomScrim)
                    drawRect(sideScrim)
                },
        ) {
        if (backdrop == null && poster == null) {
            StripedBackdrop(accent = accent, opacity = 0.55f, withRadialOverlay = false, modifier = Modifier.fillMaxSize())
        }
        // Each frame of a fade is a full-screen saveLayer for the alpha and a re-render of
        // the cached layer, and moving along a row starts one on nearly every press: more
        // than a TV stick's GPU has. A TV swaps the picture outright.
        val fade = if (LocalFormFactor.current == FormFactor.TV) snap<Float>() else tween<Float>(450)
        Crossfade(targetState = backdrop to poster, animationSpec = fade, label = "home-backdrop") { (b, p) ->
            when {
                b != null -> AsyncImage(
                    model = b,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                p != null -> AsyncImage(
                    model = softenedArt(p),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.6f,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Box(Modifier.fillMaxSize())
            }
        }
        }
        Box(
            Modifier.matchParentSize().drawBehind {
                val d = dim()
                if (d > 0f) drawRect(colors.bg, alpha = d)
            },
        )
    }
}

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
 * The words over the backdrop. Its height is whatever the rows below leave of the screen,
 * so Up Next and the first rail are on screen without scrolling.
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
    val captionStyle = KobappTheme.type.caption.copy(letterSpacing = TextUnit(0.12f, TextUnitType.Em))

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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (item != null) "Up next · ${index + 1} of ${upNext.size}".uppercase() else spotlight.caption.uppercase(),
                    color = colors.textMd,
                    style = captionStyle,
                )
                if (item != null) ReasonChip(reason = item.reason, label = item.tag)
                if (!spotlight.subtitle.isNullOrBlank()) {
                    Text(
                        spotlight.subtitle,
                        color = colors.textMd,
                        style = KobappTheme.type.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (!item?.tail.isNullOrBlank()) {
                    Text("· ${item.tail}", color = colors.textLo, style = KobappTheme.type.caption, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                spotlight.title,
                color = colors.textHi,
                style = KobappTheme.type.h1.copy(fontWeight = FontWeight.SemiBold),
                maxLines = if (tv) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (onResume != null && onDetails != null) {
                // Kept in place for a rail card, which has no Resume, so the rows below do
                // not jump as the pointer moves between them.
                val active = item != null
                Spacer(Modifier.height(14.dp))
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
