package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import dev.kobbler.app.theme.KobappTheme

/**
 * Diagonal repeating-stripe backdrop tinted by [accent]. Mirrors the JSX
 * `.stripes` class: `repeating-linear-gradient(135deg, mix(acc,22%,elev2) 0..14px, mix(acc,12%,elev2) 14..28px)`.
 */
@Composable
fun StripedBackdrop(
    accent: Color,
    opacity: Float = 0.45f,
    modifier: Modifier = Modifier,
    withRadialOverlay: Boolean = true,
    // For a large backdrop that stays put: drawn once into a layer and composited as one
    // texture after that. Not for list items — a layer per row costs a render-target
    // switch each time one is created, which is every row a scroll brings in.
    cached: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    val colors = KobappTheme.colors
    // background() keys its shader cache on the Brush instance, so these have to survive
    // recomposition or every frame rebuilds a repeating gradient the size of the screen.
    val stripes = remember(colors.elev2, accent, opacity) {
        val c1 = lerp(colors.elev2, accent, 0.22f).copy(alpha = opacity)
        val c2 = lerp(colors.elev2, accent, 0.12f).copy(alpha = opacity)
        Brush.linearGradient(
            colorStops = arrayOf(0f to c1, 0.499f to c1, 0.501f to c2, 1f to c2),
            start = Offset.Zero,
            end = Offset(28f, 28f),
            tileMode = TileMode.Repeated,
        )
    }
    val radial = remember(accent, colors.bg, withRadialOverlay) {
        if (!withRadialOverlay) null
        else Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.18f), Color.Transparent, colors.bg.copy(alpha = 0.6f)),
            center = Offset.Unspecified,
            radius = Float.POSITIVE_INFINITY,
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        // The content stays outside the layer so its changes do not re-rasterise it.
        Box(
            Modifier
                .matchParentSize()
                .then(if (cached) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier)
                .drawBehind {
                    drawRect(colors.bg)
                    drawRect(stripes)
                    radial?.let { drawRect(it) }
                },
        )
        content()
    }
}
