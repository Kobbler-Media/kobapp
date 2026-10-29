package dev.kobbler.app.ui.detail

import dev.kobbler.app.ui.components.KobBackButton
import androidx.compose.foundation.ScrollState
import dev.kobbler.app.ui.components.spotlight
import dev.kobbler.app.ui.components.BackButtonGutter
import dev.kobbler.app.ui.components.BackButtonTop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import dev.kobbler.app.ui.components.TvFocusScale
import dev.kobbler.app.ui.components.tvAutoGlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.Section
import dev.kobbler.app.theme.LocalSection
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.kobTopInset
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
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
    val colors = KobappTheme.colors
    val accent = colors.accBooks
    val gutter = gutterFor(formFactor)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = detailStacked(formFactor, maxWidth)
        val pageScroll = rememberScrollState()
        DetailBackdrop(book.spotlight(), accent, pageScroll.asPageScroll())
        val pageTopHold = rememberPageTopHold(pageScroll.asPageScroll())
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageTopHold.spec) {
        Column(
            Modifier
                .fillMaxSize()
                .scrollWhenFocusIsStuck(pageScroll.asPageScroll())
                .verticalScroll(pageScroll),
        ) {
            if (stacked) {
                PhoneBookLayout(
                    book = book,
                    api = api,
                    accent = accent,
                    gutter = gutter,
                    onRead = onRead,
                    onListen = onListen,
                    onViewComic = onViewComic,
                    onAuthorClick = onAuthorClick,
                    onSeriesClick = onSeriesClick,
                )
            } else {
                Spacer(Modifier.height(if (formFactor == FormFactor.Phone) kobTopInset + detailWideTop(formFactor) else 56.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    LeftColumn(
                        book = book,
                        api = api,
                        accent = accent,
                        formFactor = formFactor,
                        onRead = onRead,
                        onListen = onListen,
                        onViewComic = onViewComic,
                        actionsModifier = pageTopHold.anchor,
                        modifier = Modifier.width(if (formFactor == FormFactor.Desktop) 320.dp else 220.dp),
                    )
                    MiddleColumn(
                        book = book,
                        accent = accent,
                        onAuthorClick = onAuthorClick,
                        onSeriesClick = onSeriesClick,
                        modifier = Modifier.widthIn(max = 760.dp),
                    )
                    // A phone on its side has no room for a third column; its upright page
                    // goes without the panel too.
                    if (formFactor == FormFactor.Desktop || (formFactor == FormFactor.TV && book.genres.isNotEmpty())) {
                        RightPanel(
                            book = book,
                            accent = accent,
                            formFactor = formFactor,
                            modifier = Modifier.widthIn(min = 260.dp, max = 360.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(64.dp))
        }
        }

        TopBar(
            scroll = pageScroll,
            book = book,
            formFactor = formFactor,
            gutter = gutter,
            accent = accent,
            onBack = onBack,
        )
    }
}

@Composable
private fun PhoneBookLayout(
    book: Book,
    api: KobblerApi,
    accent: Color,
    gutter: Dp,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onViewComic: () -> Unit,
    onAuthorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
) {
    Spacer(Modifier.height(detailStackedTop))
    Box(Modifier.fillMaxWidth().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        BookPoster(book.coverUrl, accent, posterWidth = 200.dp)
    }
    Spacer(Modifier.height(20.dp))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SerifTitle(book.title, KobappTheme.colors.textHi)
        Spacer(Modifier.height(8.dp))
        AuthorAndSeries(book = book, accent = accent, onAuthorClick = onAuthorClick, onSeriesClick = onSeriesClick, centered = true)
        if (book.formats.isNotEmpty() || book.hasAudiobook) {
            Spacer(Modifier.height(14.dp))
            FormatChips(book, accent = accent, centered = true)
        }
        Spacer(Modifier.height(18.dp))
        ActionRow(
            book = book,
            api = api,
            accent = accent,
            onRead = onRead,
            onListen = onListen,
            onViewComic = onViewComic,
        )
        if (!book.description.isNullOrBlank()) {
            Spacer(Modifier.height(20.dp))
            ExpandableText(book.description)
        }
        if (book.genres.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            GenreChips(book.genres, accent = accent)
        }
    }
}

@Composable
private fun LeftColumn(
    book: Book,
    api: KobblerApi,
    accent: Color,
    formFactor: FormFactor,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onViewComic: () -> Unit,
    modifier: Modifier = Modifier,
    actionsModifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BookPoster(book.coverUrl, accent, posterWidth = if (formFactor == FormFactor.Desktop) 320.dp else 220.dp)
        ActionRow(
            book = book,
            api = api,
            accent = accent,
            onRead = onRead,
            onListen = onListen,
            onViewComic = onViewComic,
            modifier = actionsModifier,
        )
        if (book.formats.isNotEmpty() || book.hasAudiobook) {
            FormatChips(book, accent = accent, centered = false)
        }
    }
}

@Composable
private fun MiddleColumn(
    book: Book,
    accent: Color,
    onAuthorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    Column(modifier = modifier) {
        SerifTitle(book.title, colors.textHi)
        Spacer(Modifier.height(14.dp))
        AuthorAndSeries(book = book, accent = accent, onAuthorClick = onAuthorClick, onSeriesClick = onSeriesClick, centered = false)
        Spacer(Modifier.height(22.dp))
        StatRow(book)
        if (!book.description.isNullOrBlank()) {
            Spacer(Modifier.height(22.dp))
            Text(
                book.description,
                style = KobappTheme.type.body,
                color = colors.textMd,
            )
        }
        if (book.genres.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            GenreChips(book.genres, accent = accent)
        }
    }
}

@Composable
private fun RightPanel(book: Book, accent: Color, formFactor: FormFactor, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    Column(
        modifier = modifier
            .clip(KobappTheme.shapes.card)
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, KobappTheme.shapes.card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Details", style = KobappTheme.type.h3, color = colors.textHi)
            Spacer(Modifier.weight(1f))
            Text(
                if (book.isInLibrary) "in library" else "discover",
                style = KobappTheme.type.bodySm,
                color = if (book.isInLibrary) colors.ok else colors.textLo,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
        InfoLine("Title", book.title, formFactor)
        if (book.authors.isNotEmpty()) {
            InfoLine("Author", book.authors.joinToString(" · ") { it.name }, formFactor)
        } else if (book.author.isNotBlank()) {
            InfoLine("Author", book.author, formFactor)
        }
        if (book.narrator != null) InfoLine("Narrator", book.narrator, formFactor)
        if (book.rating != null) InfoLine("Rating", formatRatingOutOf(book.rating, BOOK_RATING_OUT_OF), formFactor)
        if (book.formats.isNotEmpty()) InfoLine("Formats", book.formats.joinToString(" · ") { it.uppercase() }, formFactor)
        if (book.hasAudiobook) InfoLine("Audiobook", "available", formFactor)
        if (book.durationSeconds != null) InfoLine("Duration", formatDuration(book.durationSeconds), formFactor)
        val allSeries = collectSeries(book)
        if (allSeries.isNotEmpty()) {
            InfoLine(
                "Series",
                allSeries.first().let { (_, name, idx) ->
                    if (idx != null) "$name · #${formatIndex(idx)}" else name
                },
                formFactor,
            )
        }
    }
}

@Composable
private fun BookPoster(coverUrl: String?, accent: Color, posterWidth: Dp) {
    val colors = KobappTheme.colors
    if (!coverUrl.isNullOrEmpty()) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(posterWidth)
                .aspectRatio(2f / 3f)
                .clip(KobappTheme.shapes.poster)
                .border(1.dp, colors.lineSoft, KobappTheme.shapes.poster),
        )
    } else {
        Box(
            modifier = Modifier
                .width(posterWidth)
                .aspectRatio(2f / 3f)
                .clip(KobappTheme.shapes.poster)
                .background(colors.elev2)
                .border(1.dp, accent.copy(alpha = 0.4f), KobappTheme.shapes.poster),
        )
    }
}

@Composable
private fun SerifTitle(text: String, color: Color, italic: Boolean = false) {
    Text(
        text,
        style = KobappTheme.displayTitle.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            letterSpacing = (-0.02).em,
        ),
        color = color,
    )
}

@Composable
private fun AuthorAndSeries(
    book: Book,
    accent: Color,
    onAuthorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    centered: Boolean,
) {
    val colors = KobappTheme.colors
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    Column(horizontalAlignment = align) {
        if (book.authors.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                book.authors.forEachIndexed { i, ref ->
                    if (i > 0) Text(", ", style = KobappTheme.type.bodySm, color = colors.textLo)
                    Text(
                        ref.name,
                        style = KobappTheme.type.body.copy(fontWeight = FontWeight.Medium),
                        color = accent,
                        modifier = Modifier.detailLink { onAuthorClick(ref.id) },
                    )
                }
            }
        } else if (book.author.isNotBlank()) {
            Text(book.author, style = KobappTheme.type.body, color = colors.textMd)
        }
        val allSeries = collectSeries(book)
        if (allSeries.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            allSeries.forEach { (sId, sName, sIdx) ->
                val label = buildString {
                    append(sName)
                    if (sIdx != null) append(" · ").also { append("Book ${formatIndex(sIdx)}") }
                }
                Text(
                    label.uppercase(),
                    style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
                    color = colors.textMd,
                    modifier = Modifier.detailLink { onSeriesClick(sId) },
                )
            }
        }
    }
}

private fun Modifier.detailLink(onClick: () -> Unit): Modifier = this
    .tvAutoGlow(cornerRadiusDp = 6f, scale = TvFocusScale.Subtle)
    .clip(RoundedCornerShape(6.dp))
    .pointerHoverIcon(PointerIcon.Hand)
    .clickable(onClick = onClick)
    .padding(horizontal = 4.dp, vertical = 2.dp)

@Composable
private fun StatRow(book: Book) {
    val items = buildList {
        if (book.rating != null) add("Rating" to "★ ${formatRating(book.rating)}")
        if (book.narrator != null) add("Narrator" to book.narrator)
        if (book.durationSeconds != null) add("Duration" to formatDuration(book.durationSeconds))
        if (book.formats.isNotEmpty()) add("Formats" to "${book.formats.size}")
    }
    if (items.isEmpty()) return
    // Flow, not Row: five stats overflow a phone's width and a Row squeezed the last one.
    FlowRow(
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { (k, v) -> Stat(k, v) }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val colors = KobappTheme.colors
    Column {
        Text(
            label.uppercase(),
            style = KobappTheme.type.label.copy(letterSpacing = 0.12.em),
            color = colors.textLo,
        )
        Spacer(Modifier.height(4.dp))
        Text(value, style = KobappTheme.type.body, color = colors.textHi)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatChips(book: Book, accent: Color, centered: Boolean) {
    val audioAccent = KobappTheme.colors.accAudio
    FlowRow(
        horizontalArrangement = if (centered) Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        book.formats.forEach { fmt ->
            val up = fmt.uppercase()
            val isReadable = up in setOf("EPUB", "KEPUB", "MOBI", "AZW3", "FB2", "PDF", "CBZ", "CBR")
            KobChip(
                text = up,
                style = if (isReadable) KobChipStyle.AccentTint else KobChipStyle.Default,
                accent = accent,
            )
        }
        if (book.hasAudiobook) {
            KobChip(text = "AUDIO", style = KobChipStyle.AccentTint, accent = audioAccent)
        }
    }
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

@Composable
private fun TopBar(
    book: Book,
    scroll: ScrollState,
    formFactor: FormFactor,
    gutter: Dp,
    accent: Color,
    onBack: () -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .detailTopScrim(scroll.asPageScroll())
            .safeTopPadding()
            .padding(start = BackButtonGutter(formFactor, gutter), end = gutter, top = BackButtonTop, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Not on TV: nothing in the D-pad graph reaches this pinned bar, and the
        // remote's BACK already unwinds the detail destination.
        if (formFactor != FormFactor.TV) {
            KobBackButton(onBack, accent = accent)
        }

        if (formFactor != FormFactor.Phone) {
            val crumb = buildString {
                append("Library · Books")
                val series = collectSeries(book).firstOrNull()
                if (series != null) append(" · ${series.second}")
            }
            Text(
                crumb.uppercase(),
                style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun collectSeries(book: Book): List<Triple<String, String, Double?>> = buildList {
    if (book.seriesId != null && book.seriesName != null) {
        add(Triple(book.seriesId, book.seriesName, book.seriesIndex))
    }
    book.seriesMemberships
        .filter { it.seriesId != book.seriesId }
        .forEach { add(Triple(it.seriesId, it.seriesName, it.seriesIndex)) }
}

private fun formatIndex(idx: Double): String =
    if (idx == idx.toLong().toDouble()) idx.toLong().toString() else idx.toString()

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
