package dev.kobbler.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
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
    if (LocalCoveredByPlayer.current) remember { mutableFloatStateOf(0f) }
    else rememberInfiniteTransition(label = "shimmer").animateFloat(
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
internal fun ShimmerHost(content: @Composable () -> Unit) {
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
