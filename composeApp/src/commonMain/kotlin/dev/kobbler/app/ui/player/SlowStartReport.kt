package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobplayer.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

// Past this the viewer is looking at the branding and wondering; a healthy start, prewarmed
// or cold, is well inside it.
internal const val SLOW_START_REPORT_MS = 6_000L

// How long to keep watching once it is slow, so the report can say how it ended.
internal const val SLOW_START_SETTLE_MS = 30_000L

private const val SLOW_START_POLL_MS = 250L

/**
 * Report a start that keeps the viewer waiting, whether or not it gets there.
 *
 * A failure already files its own report; this is for the starts that never fail — a stale
 * prewarm reconnecting under the branding, a buffer that takes half a minute — and so left
 * nothing to read afterwards. Returns what was sent, or null for a start that was prompt or
 * ended in a failure that reported itself.
 */
internal suspend fun GlobalPlayerState.watchSlowStart(
    player: Player,
    api: KobblerApi,
    now: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } },
): String? {
    val begun = now()
    val media = mediaId
    val type = mediaType
    startEvents = emptyList()
    startClock = { now() - begun }
    try {
        val prompt = withTimeoutOrNull(SLOW_START_REPORT_MS) {
            player.state.first { it == PlayerState.PLAYING }
        } != null
        if (prompt) return null

        val stagesWhenSlow = PlaybackTrace.inProgress()
        val playerWhenSlow = playerLine(player)
        var outcome: String? = null
        while (outcome == null) {
            val elapsed = now() - begun
            outcome = when {
                player.state.value == PlayerState.PLAYING -> "started after ${formatSeconds(elapsed)}"
                loadingError != null -> return null
                mediaId != media -> "abandoned after ${formatSeconds(elapsed)}"
                elapsed >= SLOW_START_REPORT_MS + SLOW_START_SETTLE_MS ->
                    "still not playing after ${formatSeconds(elapsed)}"
                else -> null
            }
            if (outcome == null) delay(SLOW_START_POLL_MS)
        }

        val detail = buildString {
            appendLine("Slow start: $outcome")
            appendLine()
            diagnosticContext().forEach { appendLine(it) }
            sessionMethod?.let { appendLine("Method: $it") }
            stagesWhenSlow?.let { appendLine("Trace at ${formatSeconds(SLOW_START_REPORT_MS)}: $it") }
            appendLine("At ${formatSeconds(SLOW_START_REPORT_MS)}: $playerWhenSlow")
            append("At end: ${playerLine(player)}")
        }
        println("[PlaybackStart] ${detail.lines().first()}")
        try {
            api.reportPlaybackFailure(
                detail = detail,
                mediaType = type,
                mediaId = media,
                streamUrl = lastStreamUrl,
                sessionId = liveSessionId ?: sessionId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A diagnostic that cannot be delivered is not worth a second failure.
        }
        return detail
    } finally {
        startClock = null
    }
}

private fun playerLine(player: Player): String =
    "Player: ${player.state.value}, buffering=${player.isBuffering.value} " +
        "(${player.bufferPercent.value.toInt()}%), at ${player.currentMs.value}ms of ${player.durationMs.value}ms, " +
        "surface=${player.videoOutputReady.value}"

internal fun formatSeconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}s"
