package dev.kobbler.app.ui.components

import androidx.compose.runtime.Composable
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

const val SoftenedArtPx = 48

/**
 * Art stood in for a backdrop, softened by decoding it at thumbnail size and letting the
 * upscale do the blurring. Modifier.blur is a RenderEffect re-run on every frame the area
 * draws — full width, on a TV stick's GPU — and a silent no-op below API 31 besides.
 */
@Composable
fun softenedArt(url: String?): ImageRequest =
    ImageRequest.Builder(LocalPlatformContext.current).data(url).size(SoftenedArtPx).build()
