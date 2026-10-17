package dev.kobbler.app.ui.dashboard.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.ui.components.SoftenedArtPx

/**
 * Up Next is where focus lands, so its backdrops are the next ones asked for. Fetched to
 * disk only: holding a dozen decoded full-screen bitmaps would crowd the posters out of a
 * low-memory device's cache.
 */
@Composable
fun PrefetchBackdrops(items: List<UpNextItem>) {
    val context = LocalPlatformContext.current
    LaunchedEffect(items) {
        val loader = SingletonImageLoader.get(context)
        items.drop(1).mapNotNull { it.backdropUrl?.takeIf(String::isNotBlank) }.forEach { url ->
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
