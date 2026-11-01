package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.PlaybackClient
import dev.kobbler.app.data.WatchProgress
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey
import kotlin.math.abs

/*
 * Cross-device handoff. Paused here, carried on elsewhere — often episodes further —
 * and come back: the player offers to follow the other device rather than let its own
 * stale playhead be written back over the newer one. The server relays every save that
 * names its device as a `watch_progress` event (api/src/watch_handoff.rs); everything
 * about whether to offer it is decided here.
 */

/** Seconds the offer stays up once play is pressed past it, before "stay" is assumed. */
const val HANDOFF_STAY_SECONDS = 15

/** A save this close to where we already are is the same place, not news. */
private const val HANDOFF_MIN_DELTA_SECONDS = 30

private fun GlobalPlayerState.markSeen(p: WatchProgress) {
    val at = p.savedAtInstant ?: return
    val since = handoffSince
    if (since == null || at > since) handoffSince = at
}

/**
 * Offer [p] if it is another device's save of this title, newer than anything already
 * accounted for, and the viewer is not watching here right now — playing here means
 * that is someone else's session, and the other device's next save offers again once
 * this one pauses.
 */
fun GlobalPlayerState.considerHandoff(p: WatchProgress, isPlaying: Boolean, currentSeconds: Int) {
    val scope = handoffScopeId ?: return
    if (p.scopeId != scope || p.clientId == PlaybackClient.id) return
    val at = p.savedAtInstant ?: return
    handoffSince?.let { if (at <= it) return }
    if (isPlaying) return
    if (p.mediaId == mediaId && abs(p.positionSeconds - currentSeconds) < HANDOFF_MIN_DELTA_SECONDS) {
        markSeen(p)
        return
    }
    // Nowhere to go from here: only the show screen knows how to open another episode.
    if (p.mediaId != mediaId && onPlayEpisode == null) return
    handoff = p
}

/**
 * Where this title stands across devices. With [baseline] (the player opened, or the
 * episode changed) whatever is already saved is what this player was opened against,
 * so it is only noted. Otherwise — the event stream came back after a gap — a newer
 * save is an offer.
 */
suspend fun GlobalPlayerState.syncHandoff(
    api: KobblerApi,
    baseline: Boolean,
    isPlaying: () -> Boolean,
    currentSeconds: () -> Int,
) {
    val type = mediaType?.takeIf { it == "movie" || it == "episode" } ?: return
    val id = mediaId ?: return
    val r = api.getPlaybackHandoff(type, id) ?: return
    if (id != mediaId) return
    handoffScopeId = r.scopeId
    val latest = r.latest ?: return
    if (baseline) markSeen(latest) else considerHandoff(latest, isPlaying(), currentSeconds())
}

fun GlobalPlayerState.stayHere() {
    handoff?.let { markSeen(it) }
    handoff = null
}

/**
 * Follow the other device. The same title is a seek; another episode is a switch, which
 * resumes from the server's position for it — the other device's, by construction. The
 * offer stays set through that switch so nothing reports the stale playhead on the way
 * out; loading the new episode clears it.
 */
fun GlobalPlayerState.followHandoff(seekToMs: (Long) -> Unit, play: () -> Unit) {
    val p = handoff ?: return
    markSeen(p)
    if (p.mediaId == mediaId) {
        handoff = null
        seekToMs(p.positionSeconds * 1000L)
        play()
        return
    }
    val open = onPlayEpisode
    if (open == null) {
        handoff = null
        return
    }
    open(p.mediaId)
}

internal fun playbackClock(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

/** The offer, drawn where the skip button sits. */
@Composable
fun HandoffCard(
    progress: WatchProgress,
    otherEpisode: Boolean,
    stayCountdown: Int?,
    accent: Color,
    onJump: () -> Unit,
    onStay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val dimens = KobappTheme.dimens
    val device = progress.clientLabel ?: "another device"
    val ep = progress.episode
    val code = ep?.let { "S${it.seasonIdentifier}E${it.episodeIdentifier}" }
    val title = when {
        ep != null -> ep.title ?: "Episode ${ep.episodeIdentifier}"
        else -> progress.movieTitle ?: "This movie"
    }
    val where = if (progress.watched) "Finished" else "Stopped at ${playbackClock(progress.positionSeconds)}"
    val jumpLabel = if (otherEpisode && code != null) "Go to $code" else "Jump to ${playbackClock(progress.positionSeconds)}"
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .widthIn(max = 440.dp)
            .background(Color.Black.copy(alpha = 0.85f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(dimens.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.s3),
    ) {
        Icon(Icons.Outlined.Devices, contentDescription = null, tint = colors.textMd)
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "WATCHED ON ${device.uppercase()}",
                style = type.caption,
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (code != null) "$code · $title" else title,
                style = type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(where, style = type.caption, color = colors.textMd)
        }
        Column(verticalArrangement = Arrangement.spacedBy(dimens.s1)) {
            KobButton(
                text = jumpLabel,
                onClick = onJump,
                style = KobButtonStyle.Accent,
                accent = accent,
                modifier = Modifier.focusKey("ctrl.handoff.jump"),
            )
            KobButton(
                text = if (stayCountdown != null) "Stay here ($stayCountdown)" else "Stay here",
                onClick = onStay,
                style = KobButtonStyle.Ghost,
                modifier = Modifier.focusKey("ctrl.handoff.stay"),
            )
        }
    }
}
