package dev.kobbler.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/**
 * Fetches images a later screen will show into the disk cache, not memory: holding decoded
 * full-size bitmaps would crowd what is on screen out of a low-memory device's cache.
 */
@Composable
fun PrefetchToDisk(urls: List<String>) {
    val context = LocalPlatformContext.current
    LaunchedEffect(urls) {
        val loader = SingletonImageLoader.get(context)
        urls.filter(String::isNotBlank).forEach { url ->
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
