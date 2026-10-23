package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.dashboard.livetv.localHhMm
import dev.kobbler.app.ui.dashboard.livetv.parseLiveInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

/**
 * Whether playback is at the live edge, and the way back to it.
 *
 * The seek bar spans the ring buffer, so stepping back is the scrubber's job. All that is
 * left is saying where the playhead sits relative to the edge, and offering a way home.
 */
@Composable
fun LiveEdgeIndicator(
    state: GlobalPlayerState,
    accent: Color,
    playerMs: StateFlow<Long>,
    durationMs: Long,
    onJumpToLive: () -> Unit,
) {
    if (!state.liveTimeshift) {
        LivePill(accent)
        return
    }
    val behindSeconds by playerMs.collectAsPlayheadState(durationMs) { ((durationMs - it) / 1000).coerceAtLeast(0L) }
    if (behindSeconds < AtLiveEdgeSeconds) {
        LivePill(accent)
        return
    }
    KobButton(
        text = "Jump to live · ${behindLabel(behindSeconds)} behind",
        onClick = onJumpToLive,
        style = KobButtonStyle.Accent,
        accent = accent,
        cornerRadiusDp = 999f,
        leading = { Icon(Icons.Outlined.SkipNext, null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.focusKey("ctrl.jumplive", entryEligible = false),
    )
}

@Composable
private fun LivePill(accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("LIVE", style = KobappTheme.type.labelSm, color = KobappTheme.colors.bg)
    }
}

// HLS hands out whole segments, so a couple of seconds behind the edge still is the edge.
const val AtLiveEdgeSeconds = 12L
private const val AtLiveEdgeMs = AtLiveEdgeSeconds * 1000

/**
 * How long to wait before re-reading the session's now/next.
 *
 * They are read once when the channel is tuned and were never refreshed, so an hour into
 * a watch the player still named the programme that was on when it opened, the seek bar's
 * programme region pointed at a slot that had passed, and Record sent that programme's id.
 * Wake just after the current one is due to end. The floor keeps an end time that has
 * already passed — a guide that is behind, or a programme that just finished — from
 * spinning; the ceiling covers a channel carrying no guide at all.
 */
fun nextNowRefreshDelay(nowEndIso: String?, at: Instant): Duration {
    val end = parseLiveInstant(nowEndIso) ?: return NowRefreshCeiling
    return ((end - at) + EndOfProgrammeSlack).coerceIn(NowRefreshFloor, NowRefreshCeiling)
}

private val NowRefreshFloor = 20.seconds
private val NowRefreshCeiling = 10.minutes
private val EndOfProgrammeSlack = 5.seconds

/**
 * Whether stepping back into the buffer has to swap what the player is playing.
 *
 * A tune the server transcodes is already playing the buffer playlist — its stream URL
 * *is* the playlist — so a reload swaps a source for itself, tearing the video surface
 * down and re-parsing for nothing. That teardown is what a rewind showed as "it jumped to
 * another track". Only a direct tune genuinely has to swap: it is on the raw transport
 * stream, which carries no timeline to seek in.
 */
fun timeshiftNeedsSourceSwap(
    alreadyTimeshifting: Boolean,
    currentStreamUrl: String?,
    playlistUrl: String,
): Boolean = !alreadyTimeshifting && currentStreamUrl != playlistUrl

/**
 * Where "live" is on a playlist that is still being written.
 *
 * The last segment of it is the one the server is filling, so the duration the player
 * reports is a moment that does not exist yet: seeking to it starves the decoder, and the
 * picture stops on a frame and stays there. Land a segment short of it instead — well
 * inside [AtLiveEdgeSeconds], so this still reads as being at the edge.
 */
fun liveEdgeSeekMs(durationMs: Long): Long = (durationMs - LiveEdgeSlackMs).coerceAtLeast(0)

/**
 * Any seek on a live source, held off that last segment.
 *
 * Jump to live is only the press that finds the end first: the ±10 s controls, the media
 * keys and a drag to the right of the bar all clamp to the reported duration, and all of
 * them starve the decoder in the same way. A source with no duration yet — the raw
 * transport stream — has nothing to clamp against and is left alone.
 */
fun liveSafeSeekMs(targetMs: Long, durationMs: Long): Long =
    if (durationMs > 0) minOf(targetMs, liveEdgeSeekMs(durationMs)) else targetMs

private const val LiveEdgeSlackMs = 4_000L

/**
 * Buffer offset a seek on the live bar lands on, or null when it is the live edge and the
 * raw stream should simply be left alone.
 *
 * The playhead is pinned to the right on a live tune, so a seek that barely moves is the
 * bar reporting where it already is — a tap on its right-hand end. Anything further back
 * is a viewer asking to step back, and is answered: the bar steps 10 s per press and
 * [AtLiveEdgeSeconds] is 12, so measuring the *request* against the slack meant every
 * single press, and the 10 s rewind button on every live tune, did nothing whatsoever.
 * The slack still applies — to where the step lands, never to whether it happens.
 */
fun timeshiftTargetSeconds(seekMs: Long, bufferMs: Long): Int? {
    if (bufferMs - seekMs < MinStepBackMs) return null
    val target = minOf(seekMs, bufferMs - AtLiveEdgeMs)
    return (target / 1000).coerceAtLeast(0).toInt()
}

// Below this a seek is the pinned playhead reporting itself, not a step away from it.
private const val MinStepBackMs = 5_000L

private fun behindLabel(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val mins = seconds / 60
    if (mins < 60) return "$mins min"
    val h = mins / 60
    val m = mins % 60
    return if (m == 0L) "${h}h" else "${h}h ${m}m"
}

/**
 * Where the programme now airing sits inside the buffer, as a seek-bar region — the
 * scrubber spans everything retained since tune-in, which is usually more than one
 * programme, so without this there is nothing to aim at.
 */
fun liveProgrammeRegion(
    state: GlobalPlayerState,
    durationMs: Long,
    tint: Color,
): dev.kobbler.kobplayer.ui.SeekBarRegion? {
    if (durationMs <= 0) return null
    val bufferStart = parseLiveInstant(state.liveBuffer?.startTime) ?: return null
    val start = parseLiveInstant(state.liveNowStart) ?: return null
    val end = parseLiveInstant(state.liveNowEnd) ?: return null
    val startFraction = ((start - bufferStart).inWholeMilliseconds.toFloat() / durationMs).coerceIn(0f, 1f)
    val endFraction = ((end - bufferStart).inWholeMilliseconds.toFloat() / durationMs).coerceIn(0f, 1f)
    if (endFraction <= startFraction) return null
    return dev.kobbler.kobplayer.ui.SeekBarRegion(
        startFraction = startFraction,
        endFraction = endFraction,
        color = tint.copy(alpha = 0.35f),
    )
}
