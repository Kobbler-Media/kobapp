package dev.kobbler.app.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import dev.kobbler.kobpalette.ColorPalette
import dev.kobbler.kobpalette.extractPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SAMPLE_SIZE = 40
private const val CACHE_MAX = 64

private val paletteCache = LinkedHashMap<String, ColorPalette>()

/**
 * Extract a [ColorPalette] from an image URL.
 *
 * Decodes at [SAMPLE_SIZE] rather than reusing a display-sized load: the pixels are only
 * ever quantized, never shown, so a full-resolution poster decode buys nothing.
 */
@Composable
fun rememberPalette(imageUrl: String?): ColorPalette {
    val context = LocalPlatformContext.current
    var palette by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { paletteCache[it] } ?: ColorPalette())
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrEmpty()) return@LaunchedEffect
        touchCache(imageUrl)?.let {
            palette = it
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(SAMPLE_SIZE, SAMPLE_SIZE)
            .precision(Precision.INEXACT)
            .build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
            ?.image ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) {
            // The painter is built here, off a SuccessResult, so it is a plain bitmap
            // wrapper — never the live AsyncImagePainter the main thread may be drawing.
            runCatching { extractPaletteFromPainter(image.asPainter(context)) }.getOrNull()
        } ?: return@LaunchedEffect
        putCache(imageUrl, extracted)
        palette = extracted
    }

    return palette
}

private fun touchCache(url: String): ColorPalette? =
    paletteCache.remove(url)?.also { paletteCache[url] = it }

private fun putCache(url: String, palette: ColorPalette) {
    paletteCache[url] = palette
    while (paletteCache.size > CACHE_MAX) {
        paletteCache.remove(paletteCache.keys.first())
    }
}

private fun extractPaletteFromPainter(painter: Painter): ColorPalette {
    val bitmap = ImageBitmap(SAMPLE_SIZE, SAMPLE_SIZE)
    val canvas = Canvas(bitmap)
    val drawScope = CanvasDrawScope()
    val drawSize = Size(SAMPLE_SIZE.toFloat(), SAMPLE_SIZE.toFloat())

    drawScope.draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = drawSize,
    ) {
        with(painter) {
            draw(size = drawSize)
        }
    }

    val pixelMap = bitmap.toPixelMap()
    val pixels = IntArray(pixelMap.width * pixelMap.height)
    for (y in 0 until pixelMap.height) {
        for (x in 0 until pixelMap.width) {
            val c = pixelMap[x, y]
            pixels[y * pixelMap.width + x] =
                ((c.alpha * 255).toInt() shl 24) or
                ((c.red * 255).toInt() shl 16) or
                ((c.green * 255).toInt() shl 8) or
                (c.blue * 255).toInt()
        }
    }
    return extractPalette(pixels)
}
