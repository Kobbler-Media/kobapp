package dev.kobbler.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobpalette.ColorPalette

internal const val ArtToneStrength = 0.45f

internal fun artTone(palette: ColorPalette, bg: Color, accent: Color): Color {
    val swatch = palette.darkAccent.takeIf { it != Color.Unspecified } ?: accent
    return lerp(bg, swatch, ArtToneStrength)
}

/** A flat stand-in for art that is loading or missing, matched to [artUrl]'s colours. */
@Composable
fun rememberArtTone(artUrl: String?, accent: Color): Color {
    val palette = rememberPalette(artUrl)
    val bg = KobappTheme.colors.bg
    return remember(palette, bg, accent) { artTone(palette, bg, accent) }
}
