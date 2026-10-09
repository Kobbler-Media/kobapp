package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import kotlin.time.Instant

/**
 * Where UP and DOWN go in the grid.
 *
 * Compose's own 2D search weighs a candidate from the centre of the focused rectangle, so
 * a two-hour programme reaches an hour down into the row below and steps over the
 * half-hour that starts at the same time as it. A schedule is read across, not measured:
 * the channel below is showing something at the time being looked at, and that is the
 * cell the press is for.
 *
 * Not snapshot state on purpose — the anchor is read during a focus search and never
 * drawn, so making it observable would recompose the grid on every D-pad press.
 */
@Stable
internal class GuideNav {
    private val cells = mutableMapOf<String, FocusRequester>()

    var anchor: Instant = Instant.DISTANT_PAST
        private set

    /**
     * A vertical move lands on a programme that already covers the anchor, so it leaves
     * the anchor alone; anything else is the viewer choosing a new time to read across.
     * Without that, walking down a lineup would drift towards the start of whichever
     * programme it last landed on.
     */
    fun onFocused(entry: LiveGuideEntry, liveEdge: Instant) {
        if (!entry.isAiringAt(anchor)) anchor = guideAnchor(entry, liveEdge)
    }

    fun register(channelId: String, entryId: String, requester: FocusRequester) {
        cells[cellKey(channelId, entryId)] = requester
    }

    fun unregister(channelId: String, entryId: String, requester: FocusRequester) {
        val key = cellKey(channelId, entryId)
        if (cells[key] === requester) cells -= key
    }

    /**
     * [FocusRequester.Default] rather than null for a row off the end of the lineup, or
     * one with a gap at this time: the press then falls back to Compose's own search,
     * which is what carries focus out of the grid and into the page around it.
     */
    fun target(guide: List<LiveGuideChannel>, fromIndex: Int, delta: Int): FocusRequester {
        val (channelId, entryId) = guideVerticalTarget(guide, fromIndex, delta, anchor)
            ?: return FocusRequester.Default
        return cells[cellKey(channelId, entryId)] ?: FocusRequester.Default
    }
}

/**
 * The time a vertical move keeps hold of. A programme already under way anchors on the
 * live edge rather than its own start: the channel below finished whatever it was showing
 * back then, and landing on that is landing on something that cannot be watched.
 */
internal fun guideAnchor(entry: LiveGuideEntry, liveEdge: Instant): Instant =
    maxOf(parseLiveInstant(entry.startTime) ?: liveEdge, liveEdge)

internal fun guideVerticalTarget(
    guide: List<LiveGuideChannel>,
    fromIndex: Int,
    delta: Int,
    anchor: Instant,
): Pair<String, String>? {
    val row = guide.getOrNull(fromIndex + delta) ?: return null
    val entry = row.entries.firstOrNull { it.isAiringAt(anchor) } ?: return null
    return row.channel.id to entry.id
}

private fun cellKey(channelId: String, entryId: String) = "$channelId $entryId"
