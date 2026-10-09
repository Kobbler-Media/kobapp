package dev.kobbler.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

private const val SweepPx = 1500f
private const val BandPx = 400f

private val LocalShimmerOffset = staticCompositionLocalOf<State<Float>?> { null }

@Composable
private fun rememberShimmerOffset(): State<Float> =
    rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = SweepPx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )

/**
 * One clock for every [ShimmerBox] beneath it. A skeleton is dozens of boxes and each one
 * driving its own transition means dozens of recompositions and shader rebuilds per frame,
 * for the whole cold start of a screen. Nests harmlessly — the outermost host wins.
 */
@Composable
private fun ShimmerHost(content: @Composable () -> Unit) {
    if (LocalShimmerOffset.current != null) {
        content()
        return
    }
    CompositionLocalProvider(LocalShimmerOffset provides rememberShimmerOffset(), content = content)
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp,
) {
    val offset = LocalShimmerOffset.current ?: rememberShimmerOffset()
    val colors = KobappTheme.colors
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val stops = remember(colors.elev2, colors.elev3) {
        listOf(colors.elev2, colors.elev3, colors.elev2)
    }
    Box(
        // Sweeping the canvas rather than the gradient keeps one Brush instance alive, so
        // its shader survives; a per-frame Brush would be rebuilt on every draw.
        modifier.drawWithCache {
            val clip = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val brush = Brush.linearGradient(stops, Offset(-BandPx, -BandPx), Offset.Zero)
            onDrawBehind {
                val sweep = offset.value
                clipPath(clip) {
                    translate(sweep, sweep) {
                        drawRect(brush, topLeft = Offset(-sweep, -sweep), size = size)
                    }
                }
            }
        },
    )
}

/** Skeleton placeholder for a poster card grid */
@Composable
fun PosterCardSkeleton(modifier: Modifier = Modifier) {
    ShimmerHost {
        Column(modifier.width(140.dp)) {
            ShimmerBox(Modifier.width(KobappTheme.dimens.posterWidth).aspectRatio(2f / 3f))
            Spacer(Modifier.height(8.dp))
            ShimmerBox(Modifier.width(KobappTheme.dimens.posterWidth).height(12.dp))
            Spacer(Modifier.height(4.dp))
            ShimmerBox(Modifier.width(KobappTheme.dimens.posterWidth * 0.6f).height(10.dp))
        }
    }
}

/** Skeleton placeholder for the detail hero area */
@Composable
fun DetailHeroSkeleton(modifier: Modifier = Modifier) {
    ShimmerHost {
        Row(
            modifier = modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShimmerBox(Modifier.width(120.dp).aspectRatio(2f / 3f), cornerRadius = 6.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerBox(Modifier.fillMaxWidth().height(24.dp))
                ShimmerBox(Modifier.fillMaxWidth(0.7f).height(16.dp))
                ShimmerBox(Modifier.fillMaxWidth(0.5f).height(16.dp))
            }
        }
    }
}

/** Skeleton for a carousel row */
@Composable
fun CarouselSkeleton(modifier: Modifier = Modifier) {
    ShimmerHost {
        Column(modifier.fillMaxWidth().padding(start = 16.dp)) {
            ShimmerBox(Modifier.width(120.dp).height(18.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { PosterCardSkeleton() }
            }
        }
    }
}

/** Full dashboard skeleton with hero + carousel rows */
@Composable
fun DashboardSkeleton(modifier: Modifier = Modifier) {
    ShimmerHost {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Wide card skeleton (continue watching)
            ShimmerBox(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(160.dp), cornerRadius = 6.dp)
            CarouselSkeleton()
            CarouselSkeleton()
        }
    }
}
