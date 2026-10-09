package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Box(Modifier.fillMaxSize().background(stripes))
        if (withRadialOverlay) {
            val radial = remember(accent, colors.bg) {
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.18f), Color.Transparent, colors.bg.copy(alpha = 0.6f)),
                    center = Offset.Unspecified,
                    radius = Float.POSITIVE_INFINITY,
                )
            }
            Box(Modifier.fillMaxSize().background(radial))
        }
        content()
    }
}
