package dev.kobbler.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ThumbnailCue(
    val startMs: Long,
    val endMs: Long,
    val url: String,
    val cropX: Int = 0,
    val cropY: Int = 0,
    val cropW: Int = 0,
    val cropH: Int = 0,
)

suspend fun fetchThumbnailVttText(vttUrl: String, client: HttpClient, authToken: String): String? = try {
    client.get(vttUrl) { header("Authorization", "Bearer $authToken") }.bodyAsText()
} catch (_: Exception) {
    null
}

// kobbler serves the VTT at `.../thumbnails.vtt` and the sprite sheets it
// references (`sprite_NNN.jpg`) at `.../thumbnails/sprite_NNN.jpg`. Naive
// directory resolution (`.../`) misses the `thumbnails/` segment and 404s.
// Mirror the web client: strip `.vtt` and treat the stem as a directory.
fun thumbnailVttBaseUrl(vttUrl: String): String =
    if (vttUrl.endsWith(".vtt")) vttUrl.removeSuffix(".vtt") + "/"
    else vttUrl.substringBeforeLast('/') + "/"

suspend fun parseThumbnailVtt(vttUrl: String, client: HttpClient, authToken: String): List<ThumbnailCue> {
    val text = fetchThumbnailVttText(vttUrl, client, authToken) ?: return emptyList()
    return parseThumbnailVttText(text, thumbnailVttBaseUrl(vttUrl))
}

fun parseThumbnailVttText(text: String, baseUrl: String): List<ThumbnailCue> {
    val cues = mutableListOf<ThumbnailCue>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.contains("-->")) {
            val parts = line.split("-->").map { it.trim() }
            if (parts.size == 2) {
                val startMs = parseVttTime(parts[0])
                val endMs = parseVttTime(parts[1])
                i++
                while (i < lines.size && lines[i].isBlank()) i++
                if (i < lines.size) {
                    val rawEntry = lines[i].trim()
                    if (rawEntry.isNotEmpty()) {
                        val fragmentIdx = rawEntry.indexOf('#')
                        val rawUrl: String
                        var cx = 0; var cy = 0; var cw = 0; var ch = 0
                        if (fragmentIdx >= 0) {
                            rawUrl = rawEntry.substring(0, fragmentIdx)
                            val fragment = rawEntry.substring(fragmentIdx + 1)
                            if (fragment.startsWith("xywh=")) {
                                val coords = fragment.removePrefix("xywh=").split(",")
                                if (coords.size == 4) {
                                    cx = coords[0].toIntOrNull() ?: 0
                                    cy = coords[1].toIntOrNull() ?: 0
                                    cw = coords[2].toIntOrNull() ?: 0
                                    ch = coords[3].toIntOrNull() ?: 0
                                }
                            }
                        } else {
                            rawUrl = rawEntry
                        }

                        val resolvedUrl = when {
                            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                            rawUrl.startsWith("/") -> {
                                val origin = baseUrl.substringBefore("//") + "//" +
                                    baseUrl.substringAfter("//").substringBefore("/")
                                "$origin$rawUrl"
                            }
                            else -> "$baseUrl$rawUrl"
                        }
                        cues.add(ThumbnailCue(startMs, endMs, resolvedUrl, cx, cy, cw, ch))
                    }
                }
            }
        }
        i++
    }
    return cues
}

private fun parseVttTime(time: String): Long {
    val parts = time.split(":")
    return when (parts.size) {
        3 -> {
            val h = parts[0].toLongOrNull() ?: 0
            val m = parts[1].toLongOrNull() ?: 0
            val secParts = parts[2].split(".")
            val s = secParts[0].toLongOrNull() ?: 0
            val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0
            h * 3600000 + m * 60000 + s * 1000 + ms
        }
        2 -> {
            val m = parts[0].toLongOrNull() ?: 0
            val secParts = parts[1].split(".")
            val s = secParts[0].toLongOrNull() ?: 0
            val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0
            m * 60000 + s * 1000 + ms
        }
        else -> 0
    }
}

@Composable
fun ThumbnailPreview(
    cues: List<ThumbnailCue>,
    durationMs: Long,
    seekFraction: Float,
    modifier: Modifier = Modifier,
) {
    if (cues.isEmpty()) return

    val seekMs = (seekFraction * durationMs).toLong()
    val cue = cues.firstOrNull { seekMs in it.startMs until it.endMs }
        ?: cues.lastOrNull { seekMs >= it.startMs }
        ?: return

    val bias = ((seekFraction * 2f) - 1f).coerceIn(-0.85f, 0.85f)
    val shape = RoundedCornerShape(6.dp)
    val previewWidth = 240.dp
    val previewWidthPx = with(LocalDensity.current) { previewWidth.roundToPx() }
    val hasCrop = cue.cropW > 0 && cue.cropH > 0
    val aspectRatio = if (hasCrop) cue.cropW.toFloat() / cue.cropH else 16f / 9f

    // The VTT never states the sheet's pixel size; the union of its cues' crop rects is it.
    val sheetSize = remember(cues, cue.url) {
        var w = 0
        var h = 0
        for (c in cues) {
            if (c.url != cue.url) continue
            if (c.cropX + c.cropW > w) w = c.cropX + c.cropW
            if (c.cropY + c.cropH > h) h = c.cropY + c.cropH
        }
        w to h
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        contentAlignment = BiasAlignment(horizontalBias = bias, verticalBias = -1f),
    ) {
        val tileModifier = Modifier
            .width(previewWidth)
            .aspectRatio(aspectRatio)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.5f), shape)

        if (hasCrop) {
            SpriteCrop(
                url = cue.url,
                cropX = cue.cropX,
                cropY = cue.cropY,
                cropW = cue.cropW,
                cropH = cue.cropH,
                sheetW = sheetSize.first,
                sheetH = sheetSize.second,
                displayWidthPx = previewWidthPx,
                modifier = tileModifier,
            )
        } else {
            AsyncImage(
                model = cue.url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = tileModifier,
            )
        }
    }
}

private const val SPRITE_CACHE_BYTES = 12L * 1024 * 1024

private var spriteImageLoader: ImageLoader? = null

// A sprite sheet is worth tens of megabytes decoded and is thrown away as soon as the
// user stops scrubbing. Through the singleton loader two of them evict every poster in
// the app, so they get their own small cache. Everything else — the auth-injecting Ktor
// fetcher, the disk cache — is inherited from the singleton.
private fun spriteLoader(context: PlatformContext): ImageLoader =
    spriteImageLoader ?: SingletonImageLoader.get(context)
        .newBuilder()
        .memoryCache { MemoryCache.Builder().maxSizeBytes(SPRITE_CACHE_BYTES).build() }
        .build()
        .also { spriteImageLoader = it }

// Manual ImageLoader.execute() + asPainter() instead of rememberAsyncImagePainter:
// the rememberAsyncImagePainter path uses ConstraintsSizeResolver which never
// observes a size when the painter is rendered through a Canvas DrawScope,
// so the load silently never starts and the tile stays on its black background.
// Driving the load explicitly removes that dependency entirely.
@Composable
private fun SpriteCrop(
    url: String,
    cropX: Int,
    cropY: Int,
    cropW: Int,
    cropH: Int,
    sheetW: Int,
    sheetH: Int,
    displayWidthPx: Int,
    modifier: Modifier,
) {
    val context = LocalPlatformContext.current
    var painter by remember(url) { mutableStateOf<Painter?>(null) }

    LaunchedEffect(url, sheetW, sheetH, displayWidthPx) {
        painter = withContext(Dispatchers.Default) {
            val builder = ImageRequest.Builder(context).data(url)
            if (sheetW > 0 && sheetH > 0 && cropW > 0) {
                // One tile fills displayWidthPx, so the whole sheet only ever needs to
                // decode at that same scale. INEXACT keeps it a pure downsample.
                val scale = displayWidthPx.toFloat() / cropW
                builder
                    .size((sheetW * scale).toInt().coerceAtLeast(1), (sheetH * scale).toInt().coerceAtLeast(1))
                    .precision(Precision.INEXACT)
            }
            (spriteLoader(context).execute(builder.build()) as? SuccessResult)
                ?.image?.asPainter(context)
        }
    }

    Canvas(modifier = modifier) {
        val p = painter ?: return@Canvas
        if (cropW <= 0 || cropH <= 0 || sheetW <= 0 || sheetH <= 0) return@Canvas

        val intrinsic = p.intrinsicSize
        if (!intrinsic.width.isFinite() || intrinsic.width <= 0f) return@Canvas

        // Sized off the sheet's declared geometry, not the painter's: the decode is
        // downsampled by an arbitrary factor, but the crop rects stay in source pixels.
        val scale = size.width / cropW.toFloat()
        clipRect {
            translate(left = -cropX * scale, top = -cropY * scale) {
                with(p) {
                    draw(size = Size(sheetW * scale, sheetH * scale))
                }
            }
        }
    }
}
