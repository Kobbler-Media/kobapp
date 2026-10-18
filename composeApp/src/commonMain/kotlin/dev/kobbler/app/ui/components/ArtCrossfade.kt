package dev.kobbler.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.decode.DataSource
import coil3.request.ImageRequest
import kotlinx.coroutines.launch

const val BackdropArtTag = "backdrop-art"

private const val ArtFadeMs = 400

data class Art(val url: String, val softened: Boolean)

/**
 * Coil's own crossfade is skipped for a memory-cache hit, so any picture seen before would
 * cut in hard. Two layers instead, the newer drawn over the older with the painter's alpha
 * rather than a layer's: no saveLayer, and nothing animates once the fade is done.
 */
@Composable
fun ArtCrossfade(art: Art, modifier: Modifier = Modifier) {
    var front by remember { mutableStateOf(art) }
    var back by remember { mutableStateOf<Art?>(null) }
    val fade = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(art) {
        if (art == front) return@LaunchedEffect
        val outgoing = if (back == null || fade.value >= 0.5f) front else back
        back = outgoing?.takeIf { it != art }
        front = art
        fade.snapTo(if (back == null) 1f else 0f)
    }

    Box(modifier) {
        listOfNotNull(back, front).forEach { layer ->
            key(layer) {
                ArtLayer(
                    art = layer,
                    alpha = { if (layer == front) fade.value else 1f },
                    onReady = { fromMemory ->
                        if (layer != front) return@ArtLayer
                        scope.launch {
                            if (back == null && fromMemory) fade.snapTo(1f) else fade.animateTo(1f, tween(ArtFadeMs))
                            back = null
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtLayer(art: Art, alpha: () -> Float, onReady: (fromMemory: Boolean) -> Unit) {
    val context = LocalPlatformContext.current
    val request = remember(art) {
        ImageRequest.Builder(context)
            .data(art.url)
            .apply { if (art.softened) size(SoftenedArtPx) }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = alpha() * if (art.softened) 0.6f else 1f,
        onSuccess = { onReady(it.result.dataSource == DataSource.MEMORY_CACHE) },
        onError = { onReady(false) },
        modifier = Modifier.fillMaxSize().testTag(BackdropArtTag),
    )
}
