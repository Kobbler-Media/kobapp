package dev.kobbler.app.ui.detail

import dev.kobbler.app.ui.components.KobBackButtonPinned
import dev.kobbler.app.ui.components.Spotlight

import dev.kobbler.app.ui.kobTopInset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.kobbler.app.data.FilmographyCredit
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.data.Person
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.PosterCard
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

private enum class FilmographySort(val label: String) {
    Year("By year"),
    Rating("By rating"),
    Role("By role"),
}

@Composable
fun PersonDetailScreen(
    person: Person,
    filmography: List<FilmographyCredit>,
    api: KobblerApi,
    onBack: () -> Unit,
    onCreditClick: (FilmographyCredit) -> Unit = {},
    formFactor: FormFactor = FormFactor.Desktop,
) {
    val colors = KobappTheme.colors
    val accent = colors.accMovies
    val gutter = gutterFor(formFactor)

    var sort by remember { mutableStateOf(FilmographySort.Year) }
    val sortedFilmography = remember(filmography, sort) { filmography.sortedFor(sort) }
    val stats = remember(filmography) { computeStats(filmography) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = detailStacked(formFactor, maxWidth)
        val pageScroll = rememberScrollState()
        DetailBackdrop(Spotlight(posterUrl = person.imageUrl ?: sortedFilmography.firstNotNullOfOrNull { it.posterUrl }), accent, pageScroll.asPageScroll())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .scrollWhenFocusIsStuck(pageScroll.asPageScroll())
                .verticalScroll(pageScroll)
                .padding(
                    top = if (stacked) detailStackedTop else kobTopInset + detailWideTop(formFactor),
                    bottom = 64.dp,
                ),
        ) {
            if (stacked) {
                PhoneHeader(person = person, stats = stats, accent = accent, gutter = gutter)
                Spacer(Modifier.height(24.dp))
                Box(Modifier.padding(horizontal = gutter)) {
                    FilmographyHeader(
                        accent = accent,
                        sort = sort,
                        onSortChange = { sort = it },
                        count = sortedFilmography.size,
                    )
                }
                Spacer(Modifier.height(14.dp))
                FilmographyGrid(
                    items = sortedFilmography,
                    modifier = Modifier.padding(horizontal = gutter).fillMaxWidth(),
                    onCreditClick = onCreditClick,
                )
            } else {
                val asideWidth = if (formFactor == FormFactor.Desktop) 320.dp else 220.dp

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AsideColumn(
                        person = person,
                        stats = stats,
                        accent = accent,
                        width = asideWidth,
                        modifier = Modifier.width(asideWidth),
                    )
                    Column(Modifier.weight(1f)) {
                        FilmographyHeader(
                            accent = accent,
                            sort = sort,
                            onSortChange = { sort = it },
                            count = sortedFilmography.size,
                        )
                        Spacer(Modifier.height(18.dp))
                        FilmographyGrid(
                            items = sortedFilmography,
                            modifier = Modifier.fillMaxWidth(),
                            onCreditClick = onCreditClick,
                        )
                    }
                }
            }
        }

        KobBackButtonPinned(onBack, formFactor, accent = accent)
    }
}

private data class PersonStats(val films: Int, val inLibrary: Int, val wanted: Int)

private fun computeStats(filmography: List<FilmographyCredit>) = PersonStats(
    films = filmography.size,
    inLibrary = filmography.count { it.inLibrary },
    wanted = filmography.count { !it.inLibrary && !it.wantStatus.isNullOrBlank() },
)

private fun List<FilmographyCredit>.sortedFor(sort: FilmographySort): List<FilmographyCredit> = when (sort) {
    FilmographySort.Year -> sortedByDescending { it.year ?: Int.MIN_VALUE }
    FilmographySort.Rating -> sortedWith(
        compareByDescending<FilmographyCredit> { it.inLibrary }
            .thenByDescending { it.year ?: Int.MIN_VALUE }
    )
    FilmographySort.Role -> sortedWith(
        compareBy<FilmographyCredit> { it.creditType }
            .thenByDescending { it.year ?: Int.MIN_VALUE }
    )
}

@Composable
private fun PhoneHeader(person: Person, stats: PersonStats, accent: Color, gutter: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Portrait(
            imageUrl = person.imageUrl,
            fallbackSeed = person.name,
            accent = accent,
            width = 110.dp,
        )
        Column(Modifier.weight(1f)) {
            SerifName(person.name)
            Spacer(Modifier.height(4.dp))
            MetaLine(person)
            Spacer(Modifier.height(10.dp))
            StatChips(stats, accent)
        }
    }
    if (person.bio != null) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.padding(horizontal = gutter)) {
            ExpandableText(person.bio, collapsedMaxLines = 4)
        }
    }
}

@Composable
private fun AsideColumn(
    person: Person,
    stats: PersonStats,
    accent: Color,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Portrait(
            imageUrl = person.imageUrl,
            fallbackSeed = person.name,
            accent = accent,
            width = width,
        )
        Spacer(Modifier.height(18.dp))
        SerifName(person.name)
        Spacer(Modifier.height(6.dp))
        MetaLine(person)
        if (person.bio != null) {
            Spacer(Modifier.height(14.dp))
            ExpandableText(person.bio, collapsedMaxLines = 6)
        }
        Spacer(Modifier.height(14.dp))
        StatChips(stats, accent)
    }
}

@Composable
private fun Portrait(
    imageUrl: String?,
    fallbackSeed: String,
    accent: Color,
    width: Dp,
) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(3f / 4f)
            .clip(KobappTheme.shapes.card)
            .border(1.dp, colors.line, KobappTheme.shapes.card)
            .background(
                Brush.linearGradient(
                    listOf(
                        lerp(colors.elev3, accent, 0.35f),
                        colors.elev3,
                    ),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrEmpty()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = fallbackSeed,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(KobappTheme.shapes.card),
            )
        } else {
            Text(
                fallbackSeed.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = KobappTheme.type.display.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = (width.value * 0.42f).sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.textHi.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun SerifName(name: String) {
    Text(
        name,
        style = KobappTheme.type.h1.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.02).em,
        ),
        color = KobappTheme.colors.textHi,
    )
}

@Composable
private fun MetaLine(person: Person) {
    val colors = KobappTheme.colors
    val parts = buildList {
        person.knownForDepartment?.let { add(it.lowercase()) }
        yearOf(person.birthDate)?.let { add(it) }
        person.placeOfBirth?.let { add(it) }
        yearOf(person.deathDate)?.let { add("d. $it") }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = KobappTheme.type.bodySm.copy(letterSpacing = 0.05.em),
        color = colors.textLo,
    )
}

private fun yearOf(date: String?): String? =
    date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatChips(stats: PersonStats, accent: Color) {
    val filmsLabel = if (stats.films == 1) "1 credit" else "${stats.films} credits"
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KobChip(text = filmsLabel, style = KobChipStyle.Default, accent = accent)
        if (stats.inLibrary > 0) {
            KobChip(text = "${stats.inLibrary} in library", style = KobChipStyle.AccentTint, accent = accent)
        }
        if (stats.wanted > 0) {
            KobChip(text = "${stats.wanted} wanted", style = KobChipStyle.AccentTint, accent = KobappTheme.colors.accWanted)
        }
    }
}

@Composable
private fun FilmographyHeader(
    accent: Color,
    sort: FilmographySort,
    onSortChange: (FilmographySort) -> Unit,
    count: Int,
) {
    Column {
        SectionHeading(
            title = "Filmography",
            accent = accent,
            trailing = {
                Text(
                    "$count",
                    style = KobappTheme.type.bodySm,
                    color = KobappTheme.colors.textLo,
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        SortTabs(sort = sort, accent = accent, onChange = onSortChange)
    }
}

@Composable
private fun SortTabs(sort: FilmographySort, accent: Color, onChange: (FilmographySort) -> Unit) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .focusKeyGroup("sort")
            .clip(KobappTheme.shapes.pill)
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, KobappTheme.shapes.pill)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FilmographySort.entries.forEach { option ->
            val selected = option == sort
            val focusState = rememberTvFocusState()
            Box(
                modifier = Modifier
                    .focusKey(option.name.lowercase())
                    .tvFocusGlow(focusState, cornerRadiusDp = 999f, scale = 1.03f)
                    .clip(KobappTheme.shapes.pill)
                    .background(if (selected) colors.elev3 else Color.Transparent)
                    .tvClickable(focusState) { onChange(option) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    option.label,
                    style = KobappTheme.type.caption.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (selected) accent else colors.textMd,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilmographyGrid(
    items: List<FilmographyCredit>,
    modifier: Modifier = Modifier,
    onCreditClick: (FilmographyCredit) -> Unit,
) {
    FlowRow(
        modifier = modifier.focusKeyGroup("filmography"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items.forEach { credit ->
            CreditGridItem(credit = credit, onCreditClick = onCreditClick)
        }
    }
}

@Composable
private fun CreditGridItem(
    credit: FilmographyCredit,
    onCreditClick: (FilmographyCredit) -> Unit,
) {
    val colors = KobappTheme.colors
    val accent = when (credit.mediaType) {
        "tv" -> colors.accTv
        else -> colors.accMovies
    }
    val status: LibraryStatus? = when {
        credit.inLibrary -> LibraryStatus.InLibrary
        !credit.wantStatus.isNullOrBlank() -> LibraryStatus.Wanted
        else -> null
    }
    val subtitle = buildList {
        credit.year?.let { add(it.toString()) }
        credit.character?.let { add(it) }
        credit.job?.let { add(it) }
    }.joinToString(" · ").takeIf { it.isNotEmpty() }

    PosterCard(
        modifier = Modifier.focusKey("credit.${credit.mediaType}.${credit.providerId}"),
        title = credit.title,
        imageUrl = credit.posterUrl ?: "",
        subtitle = subtitle,
        accentColor = accent,
        statusBadge = status,
        onClick = { if (credit.libraryId != null) onCreditClick(credit) },
    )
}
