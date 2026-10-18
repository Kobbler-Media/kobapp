package dev.kobbler.app.ui.components

import coil3.ColorImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult

// Answers every picture as though it were already decoded in memory, the case Coil's own
// crossfade skips.
internal fun cachedArtLoader(): ImageLoader =
    ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            add(Interceptor { chain ->
                SuccessResult(
                    image = ColorImage(color = 0xFF336699.toInt(), width = 64, height = 36),
                    request = chain.request,
                    dataSource = DataSource.MEMORY_CACHE,
                )
            })
        }
        .build()
