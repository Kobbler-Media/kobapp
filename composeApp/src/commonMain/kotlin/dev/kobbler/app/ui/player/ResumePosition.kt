package dev.kobbler.app.ui.player

import dev.kobbler.app.data.Movie
import dev.kobbler.kobplayer.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Below this, a saved position is a mis-tap rather than something to resume from. */
const val RESUME_MIN_SECONDS = 5

/**
 * How far off the asked-for start a source may open and still count as having honoured it.
 *
 * A demuxer seeks to the nearest keyframe it can reach, and a transcoded playlist is
 * rebased to a segment boundary, so an exact landing is not on offer. This only has to be
 * wide enough to tell "seeked, roughly" from "ignored it and opened at the head".
 */
const val RESUME_TOLERANCE_MS = 30_000L

/**
 * How long to wait for a duration before repairing a resume without one.
 *
 * A duration means the parse is finished and a seek will be honoured, so it is worth
 * waiting for — but a first open over a network can take a while, and a container VLC
 * cannot measure still plays. Its absence is not a reason to leave the viewer at the top.
 */
const val RESUME_PARSE_TIMEOUT_MS = 8_000L

/**
 * How long to wait for the decoder's first clock report before judging the open-time start.
 *
 * Until it comes, the position is only the one the source was asked to open at. On a TV it
 * lands 2.5–4 s after PLAYING; a stream still silent after this is seeked regardless.
 */
const val RESUME_CLOCK_TIMEOUT_MS = 10_000L

/**
 * How long to leave a seek before reading the clock back.
 *
 * Every player answers a seek optimistically — it reports the position it was asked for
 * straight away, and the real one only arrives with the next tick from the demuxer. So a
 * seek that was dropped reads exactly like one that landed until this has passed.
 */
const val RESUME_CONFIRM_MS = 1_200L

/** How many times to put the playhead where it belongs before accepting where it is. */
const val RESUME_REPAIR_ATTEMPTS = 3

/**
 * Put the playhead at [targetMs], and make sure it got there.
 *
 * A stream is opened at its resume point rather than seeked to it, which is both faster and
 * more reliable — but it is the demuxer's to honour, and an HTTP source it has not yet
 * decided is seekable opens at the top instead. This is the repair for that, and it has to
 * confirm rather than attempt: every player answers a seek optimistically, so a dropped one
 * reads exactly like a good one until the clock has come back from the demuxer.
 */
suspend fun Player.settleAt(targetMs: Long) {
    if (targetMs <= 0) return
    withTimeoutOrNull(RESUME_PARSE_TIMEOUT_MS) { durationMs.first { it > 0 } }
    val reported = withTimeoutOrNull(RESUME_CLOCK_TIMEOUT_MS) { positionReported.first { it } } != null
    repeat(RESUME_REPAIR_ATTEMPTS) { attempt ->
        val unconfirmed = attempt == 0 && !reported
        if (!unconfirmed && currentMs.value >= targetMs - RESUME_TOLERANCE_MS) return
        seekTo(targetMs)
        delay(RESUME_CONFIRM_MS)
    }
}

/** The resume position to ask a debrid resolve to start at, or null for the start. */
fun resumeRequestSeconds(saved: Int?): Int? = saved?.takeIf { it > RESUME_MIN_SECONDS }

/** Null when there is nothing to resume: never started, or finished — its credits are not a place to pick up from. */
val Movie.resumeSeconds: Int? get() = if (isWatched) null else resumeRequestSeconds(watchPositionSeconds)

/**
 * How far the client itself has to seek once the session exists.
 *
 * A transcoded session created with `start_time` serves a playlist that already begins
 * at the resume point, so only the residual is left to the client — the server snaps
 * its rebase back to a segment boundary, which is rarely the exact request. Direct
 * play — what a debrid stream gets from this client, since VLC accepts every container
 * — reports no rebase, and the client is the only thing that can move the playhead.
 */
fun resumeSeekSeconds(saved: Int?, serverStartSeconds: Int?): Int {
    val target = resumeRequestSeconds(saved) ?: return 0
    return (PlayerTimeline.of(serverStartSeconds).toPlayerMs(target * 1000L) / 1000).toInt()
}
