package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.kobbler.app.platform.bookFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The book's own `@font-face` families, loaded once per book while publisher styles are on.
 *  A face that fails to load is skipped; its text falls back to the reader font. */
@Composable
internal fun rememberPublisherFonts(book: ReaderBook?, enabled: Boolean): Map<String, FontFamily> {
    var fonts by remember(book) { mutableStateOf<Map<String, FontFamily>>(emptyMap()) }
    LaunchedEffect(book, enabled) {
        if (book == null || !enabled || fonts.isNotEmpty()) return@LaunchedEffect
        val faces = book.manifest.fonts
        if (faces.isEmpty()) return@LaunchedEffect
        fonts = withContext(Dispatchers.Default) {
            faces.groupBy { it.family }.mapNotNull { (family, members) ->
                val loaded = members.mapNotNull { face ->
                    val bytes = orNull { book.assetBytes(face.src) } ?: return@mapNotNull null
                    orNull {
                        bookFont(
                            key = "${book.bookId}_${face.src.hashCode()}",
                            bytes = bytes,
                            weight = FontWeight(face.weight.coerceIn(1, 1000)),
                            style = if (face.italic) FontStyle.Italic else FontStyle.Normal,
                        )
                    }
                }
                if (loaded.isEmpty()) null else family to FontFamily(loaded)
            }.toMap()
        }
    }
    return if (enabled) fonts else emptyMap()
}
