package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.app.theme.KobappTheme

enum class MiniArtAspect { Square, Poster, Wide }

@Composable
fun MiniArt(
    accent: Color? = null,
    aspect: MiniArtAspect = MiniArtAspect.Poster,
    imageUrl: String? = null,
    size: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val acc = accent ?: KobappTheme.accent
    val shape = RoundedCornerShape(5.dp)
    val ratio = when (aspect) {
        MiniArtAspect.Square -> 1f
        MiniArtAspect.Poster -> 2f / 3f
        MiniArtAspect.Wide -> 16f / 9f
    }
    Box(
        modifier
            .let { if (size != null) it.size(size) else it.aspectRatio(ratio) }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(lerp(colors.elev3, acc, 0.40f), colors.elev3),
                ),
            )
            .border(1.dp, colors.lineSoft, shape),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(imageUrl, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
