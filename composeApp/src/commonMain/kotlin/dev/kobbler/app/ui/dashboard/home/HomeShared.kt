package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.ForYouItem
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.MediaType
import dev.kobbler.app.data.StremioContinueItem
import dev.kobbler.app.ui.components.PosterCard
import dev.kobbler.app.theme.KobappColors
import dev.kobbler.app.theme.KobappTheme

fun KobappColors.accentForMediaType(mediaType: MediaType): Color = when (mediaType) {
    MediaType.Movie -> accMovies
    MediaType.TvShow -> accTv
    MediaType.SportsEvent -> accSports
    MediaType.Book -> accBooks
    MediaType.Audiobook -> accAudio
}

fun KobappColors.accentForRecentlyAdded(itemType: String): Color = when (itemType) {
    "movie" -> accMovies
    "episode", "event", "tv" -> accTv
    "book" -> accBooks
    "audiobook" -> accAudio
    else -> accHome
}

fun KobappColors.accentForSection(sectionKey: String): Color = when {
    sectionKey.contains("movie", true) -> accMovies
    sectionKey.contains("tv", true) -> accTv
    sectionKey.contains("book", true) -> accBooks
    else -> accWanted
}

@Composable
fun ProgressLine(progress: Float, accent: Color, height: Dp = 3.dp) {
    val colors = KobappTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.elev3),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(accent),
        )
    }
}

const val StremioContinueTitle = "Continue Watching on Stremio"

@Composable
fun StremioContinueCard(
    entry: StremioContinueItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    val item = entry.item
    PosterCard(
        title = item.title,
        imageUrl = item.posterUrl,
        subtitle = entry.subtitle ?: item.year?.toString().orEmpty(),
        accentColor = KobappTheme.colors.accentForRecentlyAdded(item.mediaType),
        statusBadge = item.libraryStatus,
        progress = entry.progress,
        onClick = onClick,
        width = width,
        modifier = modifier,
    )
}

const val ForYouTitle = "For You"

fun becauseLabel(because: List<String>): String = when (because.size) {
    0 -> ""
    1 -> "Because you watched ${because[0]}"
    else -> "Because you watched ${because[0]} +${because.size - 1}"
}

@Composable
fun ForYouCard(
    entry: ForYouItem,
    api: KobblerApi,
    onClick: (DiscoverItem) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    val item = entry.item
    val wantScope = rememberCoroutineScope()
    val menu = remember(item, api) { discoverContextMenu(item, api, wantScope, onClick) }
    PosterCard(
        title = item.title,
        imageUrl = item.posterUrl,
        subtitle = becauseLabel(entry.because),
        accentColor = KobappTheme.colors.accentForRecentlyAdded(item.mediaType),
        statusBadge = item.libraryStatus,
        contextMenuItems = menu,
        onClick = { onClick(item) },
        width = width,
        modifier = modifier,
    )
}
