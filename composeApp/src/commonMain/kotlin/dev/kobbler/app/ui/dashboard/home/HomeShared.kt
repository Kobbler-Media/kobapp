package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.MediaType
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.data.UpNextReason
import dev.kobbler.app.theme.KobappColors
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.MiniArt
import dev.kobbler.app.ui.components.MiniArtAspect
import dev.kobbler.app.ui.components.ReasonChip
import dev.kobbler.app.ui.components.kobFocusRing
import dev.kobbler.app.ui.components.tvFocusProgress

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

fun KobappColors.reasonAccent(reason: UpNextReason): Color = when (reason) {
    UpNextReason.Resume -> accTv
    UpNextReason.NewEpisode -> accNotif
    UpNextReason.NewSeason -> accWanted
    UpNextReason.NextInSeries -> accBooks
    UpNextReason.Schedule, UpNextReason.Live -> accLive
    UpNextReason.Companion -> accAudio
    UpNextReason.Recommended, UpNextReason.FollowUp -> accWanted
}

private fun UpNextItem.artAspect(): MiniArtAspect = when (mediaType) {
    MediaType.Book, MediaType.Audiobook -> MiniArtAspect.Square
    else -> MiniArtAspect.Poster
}

/**
 * Vertical thumbnail tile used by the TV/Desktop Up Next mixed-queue strip.
 * 48dp art + name + reason + progress, full-width within a horizontal grid cell.
 */
@Composable
fun UpNextStripTile(
    item: UpNextItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val accent = colors.accentForMediaType(item.mediaType)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Ring before clip — a clip above it crops the stroke away, which is why this
            // tile used to fake selection with a tinted background instead.
            .kobFocusRing(tvFocusProgress(focused), 12f, accent)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MiniArt(
            accent = accent,
            aspect = item.artAspect(),
            imageUrl = item.mediaArtUrl,
            modifier = Modifier.width(48.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = colors.textHi,
                style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Text(
                    item.tag.uppercase(),
                    color = colors.textLo,
                    style = KobappTheme.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.progress != null) {
                Spacer(Modifier.height(6.dp))
                ProgressLine(item.progress, accent)
            }
        }
    }
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
