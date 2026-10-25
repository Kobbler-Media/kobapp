package dev.kobbler.app.ui.detail

import androidx.compose.foundation.ScrollState
import dev.kobbler.app.ui.components.spotlight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.CastRow
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.kobTopInset
import dev.kobbler.app.ui.safeTopPadding

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MovieDetailLayout(
    movie: Movie,
    formFactor: FormFactor,
    accent: Color,
    onBack: () -> Unit,
    onPersonClick: ((String) -> Unit)?,
    actions: @Composable (wide: Boolean, anchor: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    topBarStatus: @Composable () -> Unit = {},
    aside: (@Composable (wide: Boolean) -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val colors = KobappTheme.colors
    val gutter = gutterFor(formFactor)
    val isPhone = formFactor == FormFactor.Phone

    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = detailStacked(formFactor, maxWidth)
        val pageScroll = rememberScrollState()
        DetailBackdrop(movie.spotlight(), accent, pageScroll.asPageScroll())
        val pageTopHold = rememberPageTopHold(pageScroll.asPageScroll())
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageTopHold.spec) {
        Column(Modifier.fillMaxSize().scrollWhenFocusIsStuck(pageScroll.asPageScroll()).verticalScroll(pageScroll)) {
            if (stacked) {
                Spacer(Modifier.height(kobTopInset + 72.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        DetailPoster(movie.posterUrl, accent, Modifier.width(120.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            DetailTitle(movie.title, movie.year)
                            if (movie.tagline != null) {
                                Spacer(Modifier.height(6.dp))
                                Tagline(movie.tagline)
                            }
                            Spacer(Modifier.height(10.dp))
                            MovieMeta(movie)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    actions(false, Modifier)
                    if (aside != null) {
                        Spacer(Modifier.height(18.dp))
                        aside(false)
                    }
                    if (movie.synopsis != null) {
                        Spacer(Modifier.height(18.dp))
                        ExpandableText(movie.synopsis)
                    }
                    Spacer(Modifier.height(16.dp))
                    CreditLines(movie, formFactor)
                }
            } else {
                Spacer(Modifier.height(kobTopInset + 72.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DetailPoster(
                        movie.posterUrl,
                        accent,
                        Modifier.width(
                            when (formFactor) {
                                FormFactor.TV -> 200.dp
                                FormFactor.Phone -> 160.dp
                                FormFactor.Desktop -> 280.dp
                            },
                        ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        DetailTitle(movie.title, movie.year)
                        if (movie.tagline != null) {
                            Spacer(Modifier.height(10.dp))
                            Tagline(movie.tagline)
                        }
                        Spacer(Modifier.height(16.dp))
                        MovieMeta(movie)
                        if (movie.synopsis != null) {
                            Spacer(Modifier.height(26.dp))
                            // Capped where the page is not: a synopsis set across a 1600dp
                            // window is one long line the eye loses its place in.
                            Text(
                                movie.synopsis,
                                style = KobappTheme.type.body,
                                color = colors.textMd,
                                modifier = Modifier.widthIn(max = 900.dp),
                            )
                        }
                        Spacer(Modifier.height(30.dp))
                        actions(true, pageTopHold.anchor)
                        Spacer(Modifier.height(24.dp))
                        CreditLines(movie, formFactor)
                    }
                    if (aside != null) {
                        Column(
                            modifier = Modifier.widthIn(min = 260.dp, max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) { aside(true) }
                    }
                }
            }

            if (movie.cast.isNotEmpty()) {
                Spacer(Modifier.height(if (isPhone) 24.dp else 48.dp))
                CastSection(
                    movie = movie,
                    accent = accent,
                    gutter = gutter,
                    onPersonClick = onPersonClick,
                )
            }

            Spacer(Modifier.height(64.dp))
        }
        }

        TopBar(
            scroll = pageScroll,
            movie = movie,
            formFactor = formFactor,
            gutter = gutter,
            accent = accent,
            status = topBarStatus,
            onBack = onBack,
        )

        overlay()
    }
}

@Composable
private fun Tagline(text: String) {
    Text(
        text,
        style = KobappTheme.type.caption.copy(fontStyle = FontStyle.Italic),
        color = KobappTheme.colors.textMd,
    )
}

@Composable
private fun MovieMeta(movie: Movie) {
    DetailMeta(
        metaText(movie.duration),
        metaText(movie.contentRating),
        metaRating(movie.rating),
        metaText(movie.genres.take(3).joinToString(", ").ifEmpty { null }),
    )
}

@Composable
private fun CreditLines(movie: Movie, formFactor: FormFactor) {
    if (movie.directors.isEmpty() && movie.writers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (movie.directors.isNotEmpty()) {
            InfoLine(
                if (movie.directors.size > 1) "Directors" else "Director",
                movie.directors.joinToString(", "),
                formFactor,
            )
        }
        if (movie.writers.isNotEmpty()) {
            InfoLine(
                if (movie.writers.size > 1) "Writers" else "Writer",
                movie.writers.joinToString(", "),
                formFactor,
            )
        }
    }
}

@Composable
private fun CastSection(
    movie: Movie,
    accent: Color,
    gutter: Dp,
    onPersonClick: ((String) -> Unit)?,
) {
    CastRow(
        cast = movie.cast,
        onPersonClick = onPersonClick,
        accent = accent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
    )
}

@Composable
private fun TopBar(
    movie: Movie,
    scroll: ScrollState,
    formFactor: FormFactor,
    gutter: Dp,
    accent: Color,
    status: @Composable () -> Unit,
    onBack: () -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .detailTopScrim(scroll.asPageScroll())
            .safeTopPadding()
            .padding(start = gutter, end = gutter, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Not on TV: nothing in the D-pad graph reaches this pinned bar, and the
        // remote's BACK already unwinds the detail destination.
        if (formFactor != FormFactor.TV) {
            CircleBackButton(onBack, accent)
        }

        if (formFactor != FormFactor.Phone) {
            val crumb = buildString {
                append("Movies")
                if (movie.genres.isNotEmpty()) append(" · ${movie.genres.first()}")
                if (movie.year != 0) append(" · ${movie.year}")
            }
            Text(
                crumb.uppercase(),
                style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            status()
        }
    }
}

