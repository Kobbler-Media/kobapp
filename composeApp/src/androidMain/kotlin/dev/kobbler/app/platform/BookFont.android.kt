package dev.kobbler.app.platform

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File

// Android can only load a font from a file, so the bytes go through the file cache.
actual fun bookFont(key: String, bytes: ByteArray, weight: FontWeight, style: FontStyle): Font? {
    val cacheKey = "font_$key"
    if (FileCache.pathFor(cacheKey) == null) FileCache.put(cacheKey, bytes)
    val path = FileCache.pathFor(cacheKey) ?: return null
    return Font(File(path), weight, style)
}
