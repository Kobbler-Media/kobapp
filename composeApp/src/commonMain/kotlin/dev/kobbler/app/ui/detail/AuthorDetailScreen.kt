package dev.kobbler.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.kobbler.app.data.Author
import dev.kobbler.app.data.Book
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.components.PosterCard
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

@Composable
fun AuthorDetailScreen(
    author: Author,
    books: List<Book>,
    formFactor: FormFactor,
    onBack: () -> Unit,
    onBookClick: (Book) -> Unit,
    onSeriesClick: (String) -> Unit,
) {
    val colors = KobappTheme.colors
    val accent = colors.accBooks
    val gutter = gutterFor(formFactor)
    val isPhone = formFactor == FormFactor.Phone

    val seriesGroups: List<Triple<String, String, List<Book>>> = books
        .filter { it.seriesId != null }
        .groupBy { it.seriesId!! to (it.seriesName ?: "Unknown Series") }
        .map { (key, value) ->
            Triple(key.first, key.second, value.sortedBy { it.seriesIndex ?: Double.MAX_VALUE })
        }
        .sortedBy { it.second }
    val standaloneBooks = books.filter { it.seriesId == null }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = detailStacked(formFactor, maxWidth)
        StripedBackdrop(
            accent = accent,
            opacity = if (isPhone) 0.28f else 0.36f,
            withRadialOverlay = true,
            modifier = Modifier.fillMaxSize(),
            cached = true,
        )

        val pageScroll = rememberScrollState()
        Column(Modifier.fillMaxSize().scrollWhenFocusIsStuck(pageScroll).verticalScroll(pageScroll)) {
            Spacer(Modifier.height(56.dp))

            if (stacked) {
                Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
                    PhonePortraitHeader(author, accent)
                    Spacer(Modifier.height(20.dp))
                    SeriesBlocks(
                        seriesGroups = seriesGroups,
                        standaloneBooks = standaloneBooks,
                        accent = accent,
                        gridColumns = 3,
                        posterWidth = 96.dp,
                        onBookClick = onBookClick,
                        onSeriesClick = onSeriesClick,
                    )
                    Spacer(Modifier.height(64.dp))
                }
            } else {
                val portraitWidth = if (formFactor == FormFactor.Desktop) 320.dp else 220.dp
                val posterWidth = if (formFactor == FormFactor.Desktop) 140.dp else 100.dp
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DesktopPortraitAside(
                        author = author,
                        accent = accent,
                        portraitWidth = portraitWidth,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        SeriesBlocks(
                            seriesGroups = seriesGroups,
                            standaloneBooks = standaloneBooks,
                            accent = accent,
                            gridColumns = 5,
                            posterWidth = posterWidth,
                            onBookClick = onBookClick,
                            onSeriesClick = onSeriesClick,
                        )
                    }
                }
                Spacer(Modifier.height(64.dp))
            }
        }

        DetailBackButton(onBack, formFactor, accent = accent)
    }
}

@Composable
private fun PhonePortraitHeader(author: Author, accent: Color) {
    val colors = KobappTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        AuthorPortrait(
            author = author,
            accent = accent,
            size = 96.dp,
            circle = true,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Author",
                style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
                color = colors.textLo,
            )
            Spacer(Modifier.height(4.dp))
            SerifAuthorName(author.name, colors.textHi)
            Spacer(Modifier.height(6.dp))
            Text(
                authorMeta(author),
                style = KobappTheme.type.bodySm,
                color = colors.textMd,
            )
        }
    }
    if (!author.bio.isNullOrBlank()) {
        Spacer(Modifier.height(14.dp))
        ExpandableText(author.bio, collapsedMaxLines = 5)
    }
}

@Composable
private fun DesktopPortraitAside(
    author: Author,
    accent: Color,
    portraitWidth: Dp,
) {
    val colors = KobappTheme.colors
    Column(modifier = Modifier.width(portraitWidth)) {
        AuthorPortrait(
            author = author,
            accent = accent,
            size = Dp.Unspecified,
            circle = false,
            aspect = 3f / 4f,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Author".uppercase(),
            style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
            color = colors.textLo,
        )
        Spacer(Modifier.height(8.dp))
        SerifAuthorName(author.name, colors.textHi)
        Spacer(Modifier.height(10.dp))
        Text(
            authorMeta(author),
            style = KobappTheme.type.bodySm.copy(letterSpacing = 0.08.em),
            color = colors.textLo,
        )
        if (!author.bio.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            ExpandableText(author.bio, collapsedMaxLines = 8)
        }
    }
}

@Composable
private fun AuthorPortrait(
    author: Author,
    accent: Color,
    size: Dp,
    circle: Boolean,
    aspect: Float = 1f,
) {
    val colors = KobappTheme.colors
    val shape = if (circle) CircleShape else KobappTheme.shapes.hero
    val gradient = Brush.linearGradient(
        listOf(accent, lerp(colors.elev3, accent, 0.20f), colors.elev2),
    )

    val base = Modifier
        .clip(shape)
        .background(gradient)
        .border(1.dp, colors.lineSoft, shape)

    val sized = if (size != Dp.Unspecified) {
        base.size(size)
    } else {
        base.fillMaxWidth().aspectRatio(aspect)
    }

    Box(modifier = sized, contentAlignment = Alignment.Center) {
        val image = author.imageUrl ?: author.coverUrls.firstOrNull()
        if (!image.isNullOrEmpty()) {
            AsyncImage(
                model = image,
                contentDescription = author.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            Text(
                author.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = KobappTheme.type.display.copy(
                    fontSize = if (circle) (size.value * 0.42f).sp else 96.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun SerifAuthorName(name: String, color: Color) {
    Text(
        name,
        style = KobappTheme.type.h1.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.02).em,
        ),
        color = color,
    )
}

private fun authorMeta(author: Author): String = buildString {
    append("${author.bookCount} book")
    if (author.bookCount != 1L) append("s")
    if (author.seriesCount > 0) {
        append(" · ${author.seriesCount} series")
    }
}

@Composable
private fun SeriesBlocks(
    seriesGroups: List<Triple<String, String, List<Book>>>,
    standaloneBooks: List<Book>,
    accent: Color,
    gridColumns: Int,
    posterWidth: Dp,
    onBookClick: (Book) -> Unit,
    onSeriesClick: (String) -> Unit,
) {
    seriesGroups.forEach { (seriesId, seriesName, booksInSeries) ->
        SeriesBlock(
            groupKey = "series.$seriesId",
            title = seriesName,
            accent = accent,
            seeAllLabel = "Open series →",
            onSeeAll = { onSeriesClick(seriesId) },
            books = booksInSeries,
            showSeriesIndex = true,
            gridColumns = gridColumns,
            posterWidth = posterWidth,
            onBookClick = onBookClick,
        )
    }
    if (standaloneBooks.isNotEmpty()) {
        SeriesBlock(
            groupKey = "standalone",
            title = if (seriesGroups.isEmpty()) "Books" else "Standalone",
            accent = accent,
            seeAllLabel = null,
            onSeeAll = null,
            books = standaloneBooks,
            showSeriesIndex = false,
            gridColumns = gridColumns,
            posterWidth = posterWidth,
            onBookClick = onBookClick,
        )
    }
}

@Composable
private fun SeriesBlock(
    groupKey: String,
    title: String,
    accent: Color,
    seeAllLabel: String?,
    onSeeAll: (() -> Unit)?,
    books: List<Book>,
    showSeriesIndex: Boolean,
    gridColumns: Int,
    posterWidth: Dp,
    onBookClick: (Book) -> Unit,
) {
    Column(Modifier.focusKeyGroup(groupKey).fillMaxWidth().padding(bottom = 28.dp)) {
        SectionHeading(
            title = title,
            accent = accent,
            seeAllLabel = seeAllLabel,
            onSeeAll = onSeeAll,
        )
        Spacer(Modifier.height(14.dp))
        PosterGrid(
            books = books,
            accent = accent,
            columns = gridColumns,
            posterWidth = posterWidth,
            showSeriesIndex = showSeriesIndex,
            onBookClick = onBookClick,
        )
    }
}

@Composable
private fun PosterGrid(
    books: List<Book>,
    accent: Color,
    columns: Int,
    posterWidth: Dp,
    showSeriesIndex: Boolean,
    onBookClick: (Book) -> Unit,
) {
    val rows = books.chunked(columns)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { book ->
                    Box(modifier = Modifier.width(posterWidth)) {
                        PosterCard(
                            modifier = Modifier.focusKey("book.${book.id}"),
                            title = book.title,
                            imageUrl = book.coverUrl,
                            subtitle = subtitleFor(book, showSeriesIndex),
                            accentColor = accent,
                            onClick = { onBookClick(book) },
                        )
                    }
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.width(posterWidth))
                }
            }
        }
    }
}

private fun subtitleFor(book: Book, showSeriesIndex: Boolean): String? {
    if (showSeriesIndex) {
        val idx = book.seriesIndex ?: return book.author.takeIf { it.isNotBlank() }
        val label = if (idx == idx.toLong().toDouble()) "#${idx.toLong()}" else "#$idx"
        return label
    }
    return book.author.takeIf { it.isNotBlank() }
}
