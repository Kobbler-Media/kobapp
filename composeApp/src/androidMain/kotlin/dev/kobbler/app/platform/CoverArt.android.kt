package dev.kobbler.app.platform

import android.content.Context
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.ByteArrayOutputStream

private const val CoverArtSizePx = 512

/**
 * Through the app's image loader because covers need its bearer token, and as bytes
 * because System UI will not fetch an http artwork URI itself.
 */
suspend fun loadCoverArt(context: Context, url: String): Pair<Bitmap, ByteArray>? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(CoverArtSizePx)
        .allowHardware(false)
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val bitmap = result.image.toBitmap()
    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    return bitmap to bytes
}
