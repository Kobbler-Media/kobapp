package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.PositionResponse
import dev.kobbler.app.data.SkipSegment
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey

/*
 * The server counts a title watched at 90% even when the ending has not started yet, so a
 * viewer who stopped there and comes back is opened at the top. Offer the place they left,
 * and take it unless they say otherwise.
 */

const val RESUME_OFFER_SECONDS = 5

/** Only a play that opened at the top was denied a resume. */
const val RESUME_OFFER_START_WINDOW_MS = 30_000L

/** With no credits to go by, a position this near the end is the end. */
private const val END_MARGIN_SECONDS = 15

fun earlyFinishSeconds(state: PositionResponse, segments: List<SkipSegment>, durationSeconds: Double): Int? {
    if (!state.watched) return null
    val position = state.positionSeconds?.takeIf { it > RESUME_MIN_SECONDS } ?: return null
    val ending = segments.filter { it.segmentType == "credits" }.minOfOrNull { it.startSeconds }
        ?: (durationSeconds - END_MARGIN_SECONDS)
    return position.takeIf { it < ending }
}

suspend fun GlobalPlayerState.considerResumeOffer(api: KobblerApi, durationMs: Long) {
    val type = mediaType?.takeIf { it == "movie" || it == "episode" } ?: return
    val id = mediaId ?: return
    val state = api.getWatchState(type, id) ?: return
    if (id != mediaId) return
    resumeOffer = earlyFinishSeconds(state, skipSegments, durationMs / 1000.0)
}

fun GlobalPlayerState.acceptResumeOffer(seekToMs: (Long) -> Unit) {
    val at = resumeOffer ?: return
    resumeOffer = null
    seekToMs(at * 1000L)
}

fun GlobalPlayerState.dismissResumeOffer() {
    resumeOffer = null
}

@Composable
fun ResumeOfferCard(
    atSeconds: Int,
    countdown: Int?,
    accent: Color,
    thumbnail: (@Composable () -> Unit)?,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    resumeModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val dimens = KobappTheme.dimens
    val shape = RoundedCornerShape(10.dp)
    val at = playbackClock(atSeconds)

    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .background(Color.Black.copy(alpha = 0.85f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(dimens.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.s3),
    ) {
        thumbnail?.invoke()
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("PICK UP WHERE YOU LEFT OFF", style = type.caption, color = colors.textLo, maxLines = 1)
            Text(
                "Stopped at $at",
                style = type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(dimens.s1)) {
            KobButton(
                text = if (countdown != null) "Resume from $at ($countdown)" else "Resume from $at",
                onClick = onResume,
                style = KobButtonStyle.Accent,
                accent = accent,
                modifier = resumeModifier.focusKey("ctrl.resumeoffer.resume"),
            )
            KobButton(
                text = "Cancel",
                onClick = onCancel,
                style = KobButtonStyle.Ghost,
                modifier = Modifier.focusKey("ctrl.resumeoffer.cancel"),
            )
        }
    }
}
