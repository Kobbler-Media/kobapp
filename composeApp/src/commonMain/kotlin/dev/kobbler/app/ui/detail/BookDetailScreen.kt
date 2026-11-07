package dev.kobbler.app.ui.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.offline.OfflineState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalSection
import dev.kobbler.app.theme.Section
import dev.kobbler.app.ui.components.Async
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.components.remainingLabel
import dev.kobbler.app.ui.detail.actions.BookAction
import dev.kobbler.app.ui.detail.actions.BookActionState
import dev.kobbler.app.ui.detail.actions.BookMedium
import dev.kobbler.app.ui.detail.actions.Stage
import dev.kobbler.app.ui.detail.actions.bookActions
import dev.kobbler.app.ui.offline.rememberBookDownloads
import dev.kobbler.app.ui.offline.rememberOnline
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun BookDetailScreen(
    book: Book,
    api: KobblerApi,
    formFactor: FormFactor,
    onBack: () -> Unit,
    onRead: (fromStart: Boolean) -> Unit,
    onListen: (fromStart: Boolean) -> Unit,
    onViewComic: () -> Unit = {},
    onAuthorClick: (String) -> Unit = {},
    onSeriesClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = KobappTheme.colors.accBooks
    BookDetailLayout(
        book = book,
        formFactor = formFactor,
        accent = accent,
        onBack = onBack,
        onAuthorClick = onAuthorClick,
        onSeriesClick = onSeriesClick,
        modifier = modifier,
        actions = { anchor ->
            ActionRow(
                book = book,
                api = api,
                accent = accent,
                onRead = onRead,
                onListen = onListen,
                onViewComic = onViewComic,
                modifier = anchor,
            )
        },
    )
}

@Composable
private fun ActionRow(
    book: Book,
    api: KobblerApi,
    accent: Color,
    onRead: (fromStart: Boolean) -> Unit,
    onListen: (fromStart: Boolean) -> Unit,
    onViewComic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val readable = book.formats.any { it.uppercase() in ReadableFormats }
    val (readPercent, listenPercent) = rememberBookProgress(book, api, readable)
    val downloads = rememberBookDownloads(book, accent)
    var wanted by remember { mutableStateOf(false) }
    val wantScope = rememberCoroutineScope()
    // Opened from Audiobooks, the book is being listened to; anywhere else, read.
    val listenFirst = LocalSection.current == Section.Audio
    val plan = bookActions(
        BookActionState(
            readable = readable,
            comic = book.formats.any { it.uppercase() in ComicFormats },
            audio = book.hasAudiobook,
            listenFirst = listenFirst,
            text = BookMedium(readPercent, downloads.text?.state == OfflineState.Complete, downloads.text != null),
            listening = BookMedium(listenPercent, downloads.audio?.state == OfflineState.Complete, downloads.audio != null),
            offline = !rememberOnline(),
            canDownloadText = downloads.canText,
            canDownloadAudio = downloads.canAudio,
            wantable = !book.isInLibrary && api.canWant && book.wantStatus != "wanted",
        ),
    )
    val actions = plan.mapIndexed { i, action ->
        when (action) {
            is BookAction.Read -> RowAction(
                key = "read",
                label = when (action.stage) {
                    Stage.Unstarted -> "Read"
                    Stage.InProgress -> "Resume reading"
                    Stage.Finished -> "Read again"
                },
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                onClick = { onRead(action.stage == Stage.Finished) },
                progress = action.progress?.let { (it / 100).toFloat() },
            )
            is BookAction.Listen -> RowAction(
                key = "listen",
                label = when (action.stage) {
                    Stage.Unstarted -> "Listen"
                    Stage.InProgress -> "Resume listening"
                    Stage.Finished -> "Listen again"
                },
                icon = Icons.Outlined.Headphones,
                onClick = { onListen(action.stage == Stage.Finished) },
                progress = action.progress?.let { (it / 100).toFloat() },
                remaining = action.progress?.let { p -> book.durationSeconds?.let { remainingLabel((it * (1 - p / 100)).toInt()) } },
                tint = colors.accAudio.takeIf { i > 0 && !listenFirst },
            )
            BookAction.ReadComic -> RowAction("read", "Read", Icons.AutoMirrored.Outlined.MenuBook, onViewComic)
            BookAction.ViewComic -> RowAction("comic", "View comic", Icons.Outlined.AutoStories, onViewComic)
            BookAction.DownloadBook -> downloads.textAction
            BookAction.DownloadAudio -> downloads.audioAction
            BookAction.Want -> RowAction(
                key = "want",
                label = if (wanted) "Wanted" else "Want",
                icon = if (wanted) Icons.Outlined.Check else Icons.Outlined.Download,
                tint = if (wanted) colors.accWanted else null,
                onClick = {
                    if (!wanted) wantScope.launch { try { api.wantBookById(book.id); wanted = true } catch (_: Exception) {} }
                },
            )
        }
    }
    DetailActionRow(actions = actions, accent = accent, wide = false, modifier = modifier)
}

private val ReadableFormats = setOf("EPUB", "KEPUB", "MOBI", "AZW3", "FB2", "PDF")
private val ComicFormats = setOf("CBZ", "CBR")

@Composable
private fun rememberBookProgress(book: Book, api: KobblerApi, readable: Boolean): Pair<Double?, Double?> {
    val (state, _) = rememberCached(DataCache.bookProgressKey(book.id), readable) {
        coroutineScope {
            val read = async { if (readable) api.readerProgressOrNull(book.id)?.progressPercent else null }
            val listen = async { if (book.hasAudiobook) api.getAudioPosition(book.id).progressPercent else null }
            read.await() to listen.await()
        }
    }
    return (state as? Async.Ready)?.value ?: (null to null)
}
