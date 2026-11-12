package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kobbler.app.data.MediaType
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.NewsreaderFamily
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.remainingLabel
import dev.kobbler.app.ui.detail.PrimaryAction
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

const val HomeBillboardPlayTag = "home-billboard-play"
const val HomeBillboardDetailsTag = "home-billboard-details"
const val HomeBillboardProgressTag = "home-billboard-progress"

private val Legible = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), blurRadius = 12f)
private val ButtonRadius = 24.dp
private val PlayWidth = 200.dp

@Composable
internal fun HomeBillboard(
    item: UpNextItem,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    // Every slot is laid out even when empty, and both buttons are a fixed width whatever
    // their label, so stepping between items never resizes the block and nothing shifts.
    Column(modifier.widthIn(max = 720.dp)) {
        Text(
            item.title,
            color = colors.textHi,
            style = KobappTheme.type.h1.copy(
                fontFamily = NewsreaderFamily,
                fontSize = 46.sp,
                lineHeight = 50.sp,
                fontWeight = FontWeight.Medium,
                shadow = Legible,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle.orEmpty(),
            color = colors.textHi.copy(alpha = 0.9f),
            style = KobappTheme.type.body.copy(shadow = Legible),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        val play = remember { FocusRequester() }
        // Coming up from Up Next the nearest button is often Details; the row is always
        // entered on Play.
        Row(
            Modifier
                .focusKeyGroup("billboard")
                .focusProperties { onEnter = { play.requestFocus() } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryAction(
                icon = Icons.Outlined.PlayArrow,
                label = item.ctaLabel,
                onClick = onPlay,
                progress = item.progress,
                remaining = item.progress?.let { fraction -> item.remainingSeconds?.let(::remainingLabel) ?: "${(fraction * 100).toInt()}%" },
                reserveProgress = true,
                progressModifier = Modifier.testTag(HomeBillboardProgressTag),
                modifier = Modifier
                    .width(PlayWidth)
                    .testTag(HomeBillboardPlayTag)
                    .focusKey("play", focusRequester = play, entryEligible = false)
                    .onFocusChanged { if (it.isFocused) onFocused() },
            )
            KobButton(
                onClick = onDetails,
                style = KobButtonStyle.Ghost,
                cornerRadiusDp = ButtonRadius.value,
                modifier = Modifier
                    .width(170.dp)
                    // The backdrop's scrims fade out toward the right; a bright picture there
                    // would swallow a bare outline.
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(ButtonRadius))
                    .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(ButtonRadius))
                    .testTag(HomeBillboardDetailsTag)
                    .focusKey("details", entryEligible = false)
                    .onFocusChanged { if (it.isFocused) onFocused() },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(detailsLabel(item.mediaType), style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

internal fun detailsLabel(mediaType: MediaType) = when (mediaType) {
    MediaType.TvShow -> "Episodes"
    MediaType.SportsEvent -> "Event"
    MediaType.Book, MediaType.Audiobook -> "Book details"
    MediaType.Movie -> "Details"
}
