package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.supportsVideoPlayback
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.CastRow
import dev.kobbler.app.ui.components.ExpandableText
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.app.ui.components.WatchProgressBar
import dev.kobbler.app.ui.components.rememberPalette
import dev.kobbler.app.ui.player.resumeSeconds
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.launch
import dev.kobbler.app.ui.kobTopInset
import dev.kobbler.app.ui.safeTopPadding

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MovieDetailScreen(
    movie: Movie,
    api: KobblerApi,
    formFactor: FormFactor,
    onBack: () -> Unit,
    onPlay: (fromStart: Boolean) -> Unit,
    onWatchedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onPersonClick: ((String) -> Unit)? = null,
    onStream: ((fromStart: Boolean) -> Unit)? = null,
    onStreamPick: (() -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val accent = colors.accMovies
    val toast = rememberToastState()
    val palette = rememberPalette(movie.posterUrl)
    val gutter = gutterFor(formFactor)
    val isPhone = formFactor == FormFactor.Phone

    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = detailStacked(formFactor, maxWidth)
        StripedBackdrop(
            accent = accent,
            opacity = if (isPhone) 0.30f else 0.40f,
            withRadialOverlay = true,
            modifier = Modifier.fillMaxSize(),
            cached = true,
        )

        val pageScroll = rememberScrollState()
        val pageTopHold = rememberPageTopHold(pageScroll)
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageTopHold.spec) {
        Column(Modifier.fillMaxSize().scrollWhenFocusIsStuck(pageScroll).verticalScroll(pageScroll)) {
            if (stacked) {
                PhoneMovieHero(movie, accent, palette.darkAccent)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
                    if (movie.genres.isNotEmpty()) {
                        GenreChips(movie.genres, accent = accent)
                        Spacer(Modifier.height(14.dp))
                    }
                    ActionRow(
                        movie = movie, api = api, accent = accent,
                        onPlay = onPlay, onStream = onStream, onStreamPick = onStreamPick,
                        onWatchedChanged = onWatchedChanged,
                        onCastClick = onPersonClick,
                        toast = toast,
                    )
                    MovieWatchProgress(movie, accent, topSpacing = 14.dp)
                    if (movie.synopsis != null) {
                        Spacer(Modifier.height(18.dp))
                        ExpandableText(movie.synopsis)
                    }
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
                        MovieTitleBlock(movie.title, colors.textHi)
                        if (movie.tagline != null) {
                            Spacer(Modifier.height(10.dp))
                            Tagline(movie.tagline)
                        }
                        Spacer(Modifier.height(26.dp))
                        StatRow(movie)
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
                        ActionRow(
                            movie = movie, api = api, accent = accent,
                            onPlay = onPlay, onStream = onStream, onStreamPick = onStreamPick,
                            onWatchedChanged = onWatchedChanged,
                            onCastClick = onPersonClick,
                            toast = toast,
                            modifier = pageTopHold.anchor,
                        )
                        MovieWatchProgress(movie, accent, topSpacing = 16.dp)
                        if (movie.genres.isNotEmpty()) {
                            Spacer(Modifier.height(20.dp))
                            GenreChips(movie.genres, accent = accent)
                        }
                        Spacer(Modifier.height(24.dp))
                        CreditLines(movie, formFactor)
                    }
                }
            }

            if (movie.cast.isNotEmpty()) {
                Spacer(Modifier.height(if (isPhone) 24.dp else 48.dp))
                CastSection(
                    movie = movie,
                    gutter = gutter,
                    onPersonClick = onPersonClick,
                )
            }

            Spacer(Modifier.height(64.dp))
        }
        }

        TopBar(
            movie = movie,
            formFactor = formFactor,
            gutter = gutter,
            accent = accent,
            onBack = onBack,
        )

        ToastOverlay(toast, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PhoneMovieHero(movie: Movie, accent: Color, paletteAccent: Color) {
    DetailHero(
        backdropUrl = movie.backdropUrl,
        posterUrl = movie.posterUrl,
        formFactor = FormFactor.Phone,
        accentTint = paletteAccent,
        accent = accent,
    ) {
        val colors = KobappTheme.colors
        val crumb = buildString {
            append("Movie")
            if (movie.year != 0) append(" · ${movie.year}")
            if (movie.duration != null) append(" · ${movie.duration}")
            if (movie.contentRating != null) append(" · ${movie.contentRating}")
            if (movie.rating != null) append(" · ★ ${formatRating(movie.rating)}")
        }
        Text(
            crumb.uppercase(),
            style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
            color = colors.textLo,
        )
        Spacer(Modifier.height(8.dp))
        MovieTitleBlock(movie.title, colors.textHi)
        if (movie.tagline != null) {
            Spacer(Modifier.height(6.dp))
            Tagline(movie.tagline)
        }
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
private fun MovieTitleBlock(title: String, color: Color) {
    // The two-line split is a 10-foot/desktop flourish: it halves a title that already
    // wraps three times in a phone hero's 194dp column.
    val parts = if (LocalFormFactor.current == FormFactor.Phone) TitleParts(title.trim(), null) else splitTitle(title)
    Column {
        SerifTitle(parts.head, color)
        if (parts.tail != null) SerifTitle(parts.tail, color, italic = true)
    }
}

@Composable
private fun SerifTitle(
    text: String,
    color: Color,
    italic: Boolean = false,
) {
    Text(
        text,
        style = KobappTheme.displayTitle.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            letterSpacing = (-0.03).em,
        ),
        color = color,
    )
}

@Composable
private fun StatRow(movie: Movie) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (movie.year != 0) Stat("Year", movie.year.toString())
        if (movie.duration != null) Stat("Runtime", movie.duration)
        if (movie.rating != null) {
            Column {
                Text(
                    "RATING",
                    style = KobappTheme.type.label.copy(letterSpacing = 0.12.em),
                    color = KobappTheme.colors.textLo,
                )
                Spacer(Modifier.height(4.dp))
                RatingBadge(movie.rating)
            }
        }
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

@Composable
private fun ActionRow(
    movie: Movie,
    api: KobblerApi,
    accent: Color,
    onPlay: (fromStart: Boolean) -> Unit,
    onStream: ((fromStart: Boolean) -> Unit)?,
    onStreamPick: (() -> Unit)?,
    onWatchedChanged: (Boolean) -> Unit,
    onCastClick: ((String) -> Unit)?,
    toast: ToastState,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.focusKeyGroup("actions"),
    ) {
        val canPlay = supportsVideoPlayback && (movie.filePath != null || onStream != null)
        val play: (Boolean) -> Unit = if (movie.filePath != null) onPlay else (onStream ?: onPlay)
        val resumeAt = movie.resumeSeconds
        if (canPlay) {
            KobButton(
                onClick = { play(resumeAt == null) },
                style = KobButtonStyle.Accent,
                accent = accent,
                modifier = Modifier.focusKey("play", preferredEntry = true),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = colors.bg, modifier = Modifier.size(20.dp))
                Text(
                    if (resumeAt != null) "Resume" else "Play",
                    style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.bg,
                )
            }
        }

        if (canPlay && resumeAt != null) {
            KobButton(
                onClick = { play(true) },
                style = KobButtonStyle.Default,
                accent = accent,
                modifier = Modifier.focusKey("fromStart"),
            ) {
                Icon(Icons.Outlined.Replay, contentDescription = null, tint = colors.textHi, modifier = Modifier.size(18.dp))
                Text("From start", style = KobappTheme.type.body, color = colors.textHi)
            }
        }

        if (onStreamPick != null) {
            KobButton(
                onClick = onStreamPick,
                style = KobButtonStyle.Default,
                accent = accent,
                modifier = Modifier.focusKey("streams"),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = colors.textHi, modifier = Modifier.size(18.dp))
                Text("Streams", style = KobappTheme.type.body, color = colors.textHi)
            }
        }

        if (api.canWant) {
            WatchlistToggle(
                inWatchlist = movie.inWatchlist,
                accent = accent,
                toast = toast,
                onToggle = { on ->
                    if (on) api.addToWatchlist("movie", movie.id)
                    else api.removeFromWatchlist("movie", movie.id)
                },
                focusKeyName = "watchlist",
            )
        }

        if (movie.canBeWanted(api.canWant)) {
            var wanted by remember { mutableStateOf(false) }
            val wantScope = rememberCoroutineScope()
            val wantAction: () -> Unit = {
                wantScope.launch {
                    try { api.wantMovieById(movie.id); wanted = true } catch (_: Exception) {}
                }
            }
            KobButton(
                onClick = if (wanted) ({}) else wantAction,
                style = KobButtonStyle.Default,
                enabled = !wanted,
                accent = accent,
                modifier = Modifier.focusKey("want"),
            ) {
                Icon(
                    if (wanted) Icons.Outlined.Check else Icons.Outlined.BookmarkAdd,
                    contentDescription = null,
                    tint = if (wanted) colors.warn else colors.textHi,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (wanted) "Wanted" else "Want",
                    style = KobappTheme.type.body,
                    color = if (wanted) colors.warn else colors.textHi,
                )
            }
        }

        if (canPlay) {
            val watched = movie.isWatched
            val watchScope = rememberCoroutineScope()
            val watchAction: () -> Unit = {
                val newState = !watched
                onWatchedChanged(newState)
                watchScope.launch {
                    try { api.setMovieWatched(movie.id, newState) }
                    catch (_: Exception) { onWatchedChanged(!newState) }
                }
            }
            KobButton(
                onClick = watchAction,
                style = KobButtonStyle.Ghost,
                accent = accent,
                modifier = Modifier.focusKey("watched"),
            ) {
                Icon(
                    if (watched) Icons.Outlined.Check else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = if (watched) colors.ok else colors.textMd,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (watched) "Watched" else "Mark watched",
                    style = KobappTheme.type.body,
                    color = if (watched) colors.ok else colors.textMd,
                )
            }
        }
    }
}

@Composable
private fun MovieWatchProgress(movie: Movie, accent: Color, topSpacing: Dp) {
    val position = movie.watchPositionSeconds
    if (movie.isWatched || position == null || position <= 0) return
    Spacer(Modifier.height(topSpacing))
    WatchProgressBar(
        positionSeconds = position,
        totalSeconds = movie.runtimeMinutes?.times(60),
        accent = accent,
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
    gutter: Dp,
    onPersonClick: ((String) -> Unit)?,
) {
    val colors = KobappTheme.colors
    CastRow(
        cast = movie.cast,
        onPersonClick = onPersonClick,
        accent = colors.accMovies,
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
    )
}

@Composable
private fun TopBar(
    movie: Movie,
    formFactor: FormFactor,
    gutter: Dp,
    accent: Color,
    onBack: () -> Unit,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .detailTopScrim()
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
            )
        }
    }
}

private data class TitleParts(val head: String, val tail: String?)

private fun splitTitle(title: String): TitleParts {
    val trimmed = title.trim()
    if (trimmed.length < 18) return TitleParts(trimmed, null)
    val mid = trimmed.length / 2
    val splitAt = (mid until trimmed.length).firstOrNull { trimmed[it] == ' ' }
        ?: (mid downTo 0).firstOrNull { trimmed[it] == ' ' }
        ?: return TitleParts(trimmed, null)
    return TitleParts(trimmed.substring(0, splitAt), trimmed.substring(splitAt + 1))
}
