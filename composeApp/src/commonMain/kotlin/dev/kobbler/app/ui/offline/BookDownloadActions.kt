package dev.kobbler.app.ui.offline

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.Book
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.downloadAudiobook
import dev.kobbler.app.offline.downloadBook
import dev.kobbler.app.offline.offlineBookFormat
import dev.kobbler.app.theme.KobappTheme

/** A book's text and its audio download separately: one is read on a train, the other heard in a car. */
@Composable
internal fun BookDownloadActions(book: Book, accent: Color) {
    val engine = LocalOfflineEngine.current ?: return
    if (!book.isInLibrary) return
    val readable = offlineBookFormat(book) != null
    if (readable) {
        OfflineAction(
            item = rememberOfflineItem(OfflineKind.Book, book.id),
            accent = accent,
            onDownload = { engine.downloadBook(book) },
            label = if (book.hasAudiobook) "Download book" else "Download",
            focusKeyName = "download.book",
        )
    }
    if (book.hasAudiobook) {
        OfflineAction(
            item = rememberOfflineItem(OfflineKind.Audiobook, book.id),
            accent = KobappTheme.colors.accAudio,
            onDownload = { engine.downloadAudiobook(book) },
            label = if (readable) "Download audio" else "Download",
            focusKeyName = "download.audio",
        )
    }
}
