package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.MediaType
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
