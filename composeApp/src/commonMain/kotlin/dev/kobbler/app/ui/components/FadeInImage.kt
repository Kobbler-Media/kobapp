package dev.kobbler.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import kotlinx.coroutines.launch

private const val FadeInMillis = 250

/**
 * Art that fades in as its own bytes arrive, so a screenful does not pop in at once; a
 * memory-cache hit shows straight away. Coil's crossfade redraws itself from inside draw,
 * and on a focused card the title marquee's every-frame redraw swallows that — the art
 * stalled half faded. A frame-clock animation on the layer's alpha cannot be lost that way.
 */
@Composable
fun FadeInImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    onShown: () -> Unit = {},
) {
    val fade = remember(model) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        onSuccess = { state ->
            scope.launch {
                if (state.result.dataSource == DataSource.MEMORY_CACHE) fade.snapTo(1f)
                else fade.animateTo(1f, tween(FadeInMillis))
                onShown()
            }
        },
        modifier = modifier.graphicsLayer { this.alpha = fade.value * alpha },
    )
}

