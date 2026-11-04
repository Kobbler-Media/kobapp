package dev.kobbler.app.ui.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalSection
import dev.kobbler.app.theme.Section
import dev.kobbler.app.ui.offline.BookDownloadActions
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.launch

@Composable
fun BookDetailScreen(
    book: Book,
    api: KobblerApi,
    formFactor: FormFactor,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onListen: () -> Unit,
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
    onRead: () -> Unit,
    onListen: () -> Unit,
    onViewComic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = bookActions(book, api, onRead, onListen, onViewComic)
    val first = actions.firstOrNull()
    DetailActionRow(
        wide = false,
        modifier = modifier,
        primary = first?.let { action ->
            { fill ->
                PrimaryAction(
                    icon = action.icon,
                    label = action.label,
                    onClick = action.onClick,
                    enabled = action.enabled,
                    modifier = Modifier.focusKey(action.key, preferredEntry = true).then(fill),
                )
            }
        },
    ) {
        actions.drop(1).forEach { action ->
            IconAction(
                icon = action.icon,
                label = action.label,
                accent = accent,
                tint = action.tint ?: KobappTheme.colors.textHi,
                onClick = action.onClick,
                enabled = action.enabled,
                modifier = Modifier.focusKey(action.key),
            )
        }
        BookDownloadActions(book, accent)
    }
}

private class BookAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val tint: Color? = null,
)

@Composable
private fun bookActions(
    book: Book,
    api: KobblerApi,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onViewComic: () -> Unit,
): List<BookAction> {
    val colors = KobappTheme.colors
    val list = mutableListOf<BookAction>()

    val readableFormats = setOf("EPUB", "KEPUB", "MOBI", "AZW3", "FB2", "PDF")
    val comicFormats = setOf("CBZ", "CBR")
    val hasReadable = book.formats.any { it.uppercase() in readableFormats }
    val hasComic = book.formats.any { it.uppercase() in comicFormats }

    if (hasReadable) {
        list += BookAction("read", "Read", Icons.AutoMirrored.Outlined.MenuBook, onRead)
    }
    if (hasComic) {
        list += if (hasReadable) {
            BookAction("comic", "View comic", Icons.Outlined.AutoStories, onViewComic)
        } else {
            BookAction("read", "Read", Icons.AutoMirrored.Outlined.MenuBook, onViewComic)
        }
    }
    if (book.hasAudiobook) {
        // Opened from Audiobooks, the book is being listened to; anywhere else, read.
        val listenFirst = LocalSection.current == Section.Audio
        val listen = BookAction("listen", "Listen", Icons.Outlined.Headphones, onListen, tint = colors.accAudio.takeIf { list.isNotEmpty() && !listenFirst })
        if (listenFirst) list.add(0, listen) else list += listen
    }
    if (!book.isInLibrary && api.canWant && book.wantStatus != "wanted") {
        var wanted by remember { mutableStateOf(false) }
        val wantScope = rememberCoroutineScope()
        list += BookAction(
            key = "want",
            label = if (wanted) "Wanted" else "Want",
            icon = if (wanted) Icons.Outlined.Check else Icons.Outlined.BookmarkAdd,
            onClick = {
                if (!wanted) wantScope.launch { try { api.wantBookById(book.id); wanted = true } catch (_: Exception) {} }
            },
            tint = if (wanted) colors.accWanted else null,
        )
    }
    return list
}
