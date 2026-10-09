package dev.kobbler.app.ui.detail

import dev.kobbler.app.ui.kobTopInset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.BookAuthor
import dev.kobbler.app.data.Series
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.app.ui.components.tvAutoGlow

@Composable
fun SeriesDetailScreen(
    series: Series,
    books: List<Book>,
    formFactor: FormFactor,
    onBack: () -> Unit,
    onBookClick: (Book) -> Unit,
    onAuthorClick: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    val accent = colors.accBooks
    val gutter = gutterFor(formFactor)
    val isPhone = formFactor == FormFactor.Phone
    val sortedBooks = books.sortedBy { it.seriesIndex ?: Double.MAX_VALUE }
    val authorRefs = books.flatMap { it.authors }.distinctBy { it.id }
    val heroCovers = sortedBooks.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.take(4)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SeriesHero(
                    series = series,
                    authorRefs = authorRefs,
                    heroCovers = heroCovers,
                    accent = accent,
                    formFactor = formFactor,
                    isPhone = isPhone,
                    gutter = gutter,
                    onAuthorClick = onAuthorClick,
                )
            }

            item {
                Spacer(Modifier.height(if (isPhone) 18.dp else 28.dp))
                SectionHeader(
                    accent = accent,
                    countLabel = "${sortedBooks.size} book${if (sortedBooks.size != 1) "s" else ""}",
                    gutter = gutter,
                )
                Spacer(Modifier.height(14.dp))
            }

            items(sortedBooks, key = { it.id }) { book ->
                Box(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
                    Box(Modifier.widthIn(max = 1200.dp).fillMaxWidth().align(Alignment.TopCenter)) {
                        BookRow(
                            book = book,
                            accent = accent,
                            formFactor = formFactor,
                            onClick = { onBookClick(book) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            item { Spacer(Modifier.height(48.dp)) }
        }

        DetailBackButton(onBack, formFactor, accent = accent)
    }
}

@Composable
private fun SeriesHero(
    series: Series,
    authorRefs: List<BookAuthor>,
    heroCovers: List<String>,
    accent: Color,
    formFactor: FormFactor,
    isPhone: Boolean,
    gutter: Dp,
    onAuthorClick: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    val heroHeight = when (formFactor) {
        FormFactor.TV -> 280.dp
        FormFactor.Desktop -> 340.dp
        FormFactor.Phone -> 280.dp
    }
    val coverWidth = when (formFactor) {
        FormFactor.TV -> 100.dp
        FormFactor.Desktop -> 120.dp
        FormFactor.Phone -> 78.dp
    }

    // heightIn, not height: at TV/phone type sizes the crumb + display title + meta +
    // 3-line description no longer fit the old fixed hero box.
    Box(Modifier.fillMaxWidth().heightIn(min = heroHeight)) {
        StripedBackdrop(
            accent = accent,
            opacity = if (isPhone) 0.32f else 0.42f,
            withRadialOverlay = true,
            modifier = Modifier.matchParentSize(),
            cached = true,
        )
        Box(
            Modifier.matchParentSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.6f to colors.bg.copy(alpha = 0.4f),
                    1.0f to colors.bg,
                )
            )
        )

        if (isPhone) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = gutter, end = gutter, top = kobTopInset + 72.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (heroCovers.isNotEmpty()) {
                    BookMosaic(heroCovers, coverWidth = coverWidth, accent = accent)
                    Spacer(Modifier.height(18.dp))
                }
                SeriesTitleBlock(
                    series = series,
                    authorRefs = authorRefs,
                    onAuthorClick = onAuthorClick,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = gutter, end = gutter, top = 64.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (heroCovers.isNotEmpty()) {
                    BookMosaic(heroCovers, coverWidth = coverWidth, accent = accent)
                }
                Column(modifier = Modifier.widthIn(max = 1000.dp).padding(bottom = 4.dp)) {
                    SeriesTitleBlock(
                        series = series,
                        authorRefs = authorRefs,
                        onAuthorClick = onAuthorClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesTitleBlock(
    series: Series,
    authorRefs: List<BookAuthor>,
    onAuthorClick: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    val crumb = buildString {
        append("Series")
        if (series.authorNames.isNotBlank()) append(" · ${series.authorNames}")
    }
    Text(
        crumb.uppercase(),
        style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
        color = colors.textLo,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        series.name,
        style = KobappTheme.displayTitle.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.03).em,
        ),
        color = colors.textHi,
    )
    Spacer(Modifier.height(12.dp))
    // Flow, not Row: a long author list squeezed the count beside it into a broken column.
    FlowRow(
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${series.bookCount} book${if (series.bookCount != 1L) "s" else ""}",
            style = KobappTheme.type.bodySm,
            color = colors.textMd,
        )
        if (authorRefs.isNotEmpty()) {
            MetaDot()
            Row(
                modifier = Modifier.focusKeyGroup("authors"),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                authorRefs.forEachIndexed { i, ref ->
                    if (i > 0) Text(", ", style = KobappTheme.type.bodySm, color = colors.textLo)
                    Text(
                        ref.name,
                        style = KobappTheme.type.bodySm,
                        color = KobappTheme.colors.accBooks,
                        modifier = Modifier
                            .focusKey(ref.id)
                            .clickable { onAuthorClick(ref.id) },
                    )
                }
            }
        }
    }
    if (!series.description.isNullOrBlank()) {
        Spacer(Modifier.height(14.dp))
        ExpandableText(series.description, collapsedMaxLines = 3)
    }
}

@Composable
private fun BookMosaic(coverUrls: List<String>, coverWidth: Dp, accent: Color) {
    val colors = KobappTheme.colors
    if (coverUrls.isEmpty()) {
        Box(
            modifier = Modifier
                .width(coverWidth)
                .aspectRatio(2f / 3f)
                .clip(KobappTheme.shapes.poster)
                .background(accent.copy(alpha = 0.2f)),
        )
        return
    }
    val rotations = listOf(-3f, -1f, 1f, 3f)
    val overlap = coverWidth * 0.28f
    Row {
        coverUrls.forEachIndexed { i, url ->
            val rot = rotations.getOrElse(i) { 0f }
            Box(
                modifier = Modifier
                    .then(if (i == 0) Modifier else Modifier.offset(x = -overlap * i))
                    .graphicsLayer { rotationZ = rot }
                    .width(coverWidth)
                    .aspectRatio(2f / 3f)
                    .clip(KobappTheme.shapes.poster)
                    .background(colors.elev2)
                    .border(1.dp, colors.line, KobappTheme.shapes.poster),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(accent: Color, countLabel: String, gutter: Dp) {
    val colors = KobappTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        Box(Modifier.widthIn(max = 1200.dp).fillMaxWidth().align(Alignment.TopCenter)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(3.dp).height(22.dp).background(accent, RoundedCornerShape(2.dp)))
                    Text("In order", style = KobappTheme.type.h2, color = colors.textHi)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    countLabel,
                    style = KobappTheme.type.bodySm.copy(letterSpacing = 0.1.em),
                    color = colors.textLo,
                )
            }
        }
    }
}

@Composable
private fun BookRow(
    book: Book,
    accent: Color,
    formFactor: FormFactor,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val isPhone = formFactor == FormFactor.Phone
    val coverWidth = when (formFactor) {
        FormFactor.TV -> 96.dp
        FormFactor.Desktop -> 120.dp
        FormFactor.Phone -> 96.dp
    }
    val container = Modifier
        .fillMaxWidth()
        .focusKey("book.${book.id}")
        .tvAutoGlow(cornerRadiusDp = 12f, scale = 1.01f)
        .clip(KobappTheme.shapes.card)
        .background(colors.elev1)
        .border(1.dp, colors.lineSoft, KobappTheme.shapes.card)
        .clickable(onClick = onClick)
        .padding(if (isPhone) 12.dp else 16.dp)

    if (isPhone) {
        Column(container) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                BookRowCover(book, coverWidth, accent)
                Column(Modifier.weight(1f)) {
                    BookRowTitleLine(book)
                    Spacer(Modifier.height(6.dp))
                    BookStatusPill(book, accent)
                }
            }
            if (!book.description.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    book.description,
                    style = KobappTheme.type.bodySm,
                    color = colors.textMd,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            BookRowCta(book, accent, onClick, Modifier.wrapContentWidth(Alignment.End))
        }
    } else {
        Row(container, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            BookRowCover(book, coverWidth, accent)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BookRowTitleLine(book, Modifier.weight(1f))
                    BookStatusPill(book, accent)
                }
                if (!book.description.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        book.description,
                        style = KobappTheme.type.bodySm,
                        color = colors.textMd,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 720.dp),
                    )
                }
            }
            BookRowCta(book, accent, onClick)
        }
    }
}

@Composable
private fun BookRowCover(book: Book, coverWidth: Dp, accent: Color) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .width(coverWidth)
            .aspectRatio(2f / 3f)
            .clip(KobappTheme.shapes.poster)
            .background(colors.elev2)
            .border(1.dp, colors.line, KobappTheme.shapes.poster),
    ) {
        if (book.coverUrl.isNotBlank()) {
            AsyncImage(
                model = book.coverUrl,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.18f)))
        }
    }
}

@Composable
private fun BookRowTitleLine(book: Book, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    val idxLabel = book.seriesIndex?.let { idx ->
        if (idx == idx.toLong().toDouble()) {
            val n = idx.toLong()
            if (n < 10) "0$n" else n.toString()
        } else idx.toString()
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (idxLabel != null) {
            Text(
                idxLabel,
                style = KobappTheme.type.bodySm,
                color = colors.textXlo,
            )
        }
        Text(
            book.title,
            style = KobappTheme.type.readerTitle,
            color = colors.textHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class BookReadStatus { Wanted, InLibrary, NotInLibrary }

private fun bookStatus(book: Book): BookReadStatus = when {
    book.wantStatus == "wanted" -> BookReadStatus.Wanted
    book.isInLibrary -> BookReadStatus.InLibrary
    else -> BookReadStatus.NotInLibrary
}

@Composable
private fun BookStatusPill(book: Book, accent: Color) {
    val colors = KobappTheme.colors
    val (label, color) = when (bookStatus(book)) {
        BookReadStatus.Wanted -> "wanted" to colors.accWanted
        BookReadStatus.InLibrary -> "in library" to accent
        BookReadStatus.NotInLibrary -> "unread" to colors.textLo
    }
    Box(
        modifier = Modifier
            .clip(KobappTheme.shapes.pill)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.30f), KobappTheme.shapes.pill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            maxLines = 1,
            style = KobappTheme.type.caption.copy(letterSpacing = 0.08.em),
            color = color,
        )
    }
}

@Composable
private fun BookRowCta(book: Book, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    val (label, style) = when (bookStatus(book)) {
        BookReadStatus.Wanted -> "Wanted" to KobButtonStyle.Default
        BookReadStatus.InLibrary -> "Open" to KobButtonStyle.Accent
        BookReadStatus.NotInLibrary -> "View" to KobButtonStyle.Ghost
    }
    KobButton(onClick = onClick, style = style, accent = accent, modifier = Modifier.focusKey("cta").then(modifier)) {
        Icon(
            Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            tint = if (style == KobButtonStyle.Accent) colors.bg else colors.textHi,
            modifier = Modifier.width(18.dp),
        )
        Text(
            label,
            style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            color = if (style == KobButtonStyle.Accent) colors.bg else colors.textHi,
        )
    }
}
