package dev.kobbler.app.ui.offline

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.Book
import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.downloadAudiobook
import dev.kobbler.app.offline.downloadBook
import dev.kobbler.app.offline.offlineBookFormat
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.RowAction

/** A book's text and its audio download separately: one is read on a train, the other heard in a car. */
internal class BookDownloads(
    val text: OfflineItem?,
    val audio: OfflineItem?,
    val canText: Boolean,
    val canAudio: Boolean,
    val textAction: RowAction,
    val audioAction: RowAction,
)

@Composable
internal fun rememberBookDownloads(book: Book, accent: Color): BookDownloads {
    val engine = LocalOfflineEngine.current
    val readable = offlineBookFormat(book) != null
    val text = rememberOfflineItem(OfflineKind.Book, book.id)
    val audio = rememberOfflineItem(OfflineKind.Audiobook, book.id)
    return BookDownloads(
        text = text,
        audio = audio,
        canText = engine != null && book.isInLibrary && readable,
        canAudio = engine != null && book.isInLibrary && book.hasAudiobook,
        textAction = offlineRowAction(
            text, accent, { engine?.downloadBook(book) },
            label = if (book.hasAudiobook) "Download book" else "Download",
            key = "download.book",
        ),
        audioAction = offlineRowAction(
            audio, KobappTheme.colors.accAudio, { engine?.downloadAudiobook(book) },
            label = if (readable) "Download audio" else "Download",
            key = "download.audio",
        ),
    )
}
