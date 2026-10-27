package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * Cropped art over a placeholder — striped, or a flat [tone] when one is given — that stops
 * being drawn once the art covers it: hidden or not, every layer is a fill the GPU pays for
 * on each frame the item moves.
 */
@Composable
fun StripedArt(
    url: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    opacity: Float = 0.55f,
    contentDescription: String? = null,
    tone: Color = Color.Unspecified,
) {
    var covered by remember(url) { mutableStateOf(false) }
    Box(modifier) {
        if (!covered) {
            if (tone != Color.Unspecified) {
                Box(Modifier.fillMaxSize().background(tone))
            } else {
                StripedBackdrop(accent = accent, opacity = opacity, withRadialOverlay = false, modifier = Modifier.fillMaxSize())
            }
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onSuccess = { covered = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
