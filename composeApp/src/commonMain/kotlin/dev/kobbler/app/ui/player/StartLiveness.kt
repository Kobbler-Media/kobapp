package dev.kobbler.app.ui.player

import dev.kobbler.app.data.SessionFeed

/** How long a feed may send the player nothing before a start is called lost. */
internal const val FEED_IDLE_STALL_MS = 10_000L

/** A start this slow is worth a word on how the feed is doing. */
internal const val FEED_NOTE_AFTER_MS = 2_500L

/**
 * One look at a start that has not played yet.
 *
 * [readyForMs] is null while the server still says it is preparing: the budget only runs
 * once there is something to play, never across the server's own lead time.
 */
internal data class StartProbe(
    val elapsedMs: Long,
    val readyForMs: Long?,
    val budgetMs: Long,
    val feed: SessionFeed? = null,
    val servedGrew: Boolean = false,
    val downloadGrew: Boolean = false,
)

internal sealed interface StartVerdict {
    data object Wait : StartVerdict

    /** [sourceHealthy]: the server still has the file coming, so the player's connection is the suspect. */
    data class Stalled(val reason: String, val sourceHealthy: Boolean = false) : StartVerdict
}

internal fun judgeStart(p: StartProbe): StartVerdict {
    val feed = p.feed
    val healthy = feed != null && feed.sourceHealthy(p.downloadGrew)
    if (p.elapsedMs >= START_CEILING_MS) {
        return StartVerdict.Stalled("Nothing played within ${START_CEILING_MS / 1000}s", healthy)
    }
    val ready = p.readyForMs ?: return StartVerdict.Wait
    if (feed == null) {
        return if (ready >= p.budgetMs) {
            StartVerdict.Stalled("Nothing played within ${p.budgetMs / 1000}s of the stream being ready")
        } else {
            StartVerdict.Wait
        }
    }
    val idle = feed.idleMs
    return when {
        idle == null -> if (ready >= DIRECT_START_BUDGET_MS) {
            StartVerdict.Stalled("The player never asked for the stream", healthy)
        } else {
            StartVerdict.Wait
        }
        p.servedGrew -> StartVerdict.Wait
        idle > FEED_IDLE_STALL_MS -> StartVerdict.Stalled("The stream sent nothing for ${idle / 1000}s", healthy)
        else -> StartVerdict.Wait
    }
}

private fun SessionFeed.sourceHealthy(downloadGrew: Boolean): Boolean {
    val total = totalBytes ?: 0
    val complete = total > 0 && (downloadedBytes ?: 0) >= total
    return (downloadBps ?: 0) > 0 || downloadGrew || complete
}

/** The quiet line under a slow start, so a wait that is moving reads as moving. */
internal fun startFeedLine(feed: SessionFeed?): String? = when {
    feed == null -> null
    feed.bytesServed > 0 -> "Buffering · ${formatBytes(feed.bytesServed)}"
    (feed.downloadBps ?: 0) > 0 -> "Fetching the source · ${formatBytes(feed.downloadBps!!)}/s"
    else -> null
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${(bytes / 100_000_000) / 10.0} GB"
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    else -> "${maxOf(1, bytes / 1_000)} KB"
}
