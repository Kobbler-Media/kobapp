package dev.kobbler.app.ui.dashboard.livetv

import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A TV comes up on the channel it was left on. The guide opened on whatever the lineup
 * happened to sort first instead, so the preview pane described a channel nobody had
 * asked for and the remote's first press was always a walk back to their own.
 */
class LiveLastChannelTest {

    private fun channel(id: String) = LiveChannel(
        id = id, name = "Channel $id", lcn = null, callsign = null, logoUrl = null,
        network = null, region = null, kind = null, hd = false, enabled = true,
        favourite = false, muxLabel = null, typeLabel = null, hasGuide = true,
        guideEntryCount = 2, videoCodec = null, audioCodec = null, signalQuality = null,
    )

    private val now = Instant.parse("2026-09-09T19:30:00Z")

    private fun entry(id: String, onAir: Boolean) = LiveGuideEntry(
        id = id, channelId = "c",
        startTime = if (onAir) "2026-09-09T19:00:00Z" else "2026-09-09T18:00:00Z",
        endTime = if (onAir) "2026-09-09T20:00:00Z" else "2026-09-09T19:00:00Z",
        title = "Programme $id",
        episodeNumber = null, episodeTitle = null, synopsis = null, genres = emptyList(),
        imageUrl = null, posterUrl = null, isFirst = false, episodeKey = null,
        durationSeconds = 3600, progressPct = null, recordingId = null,
        recordingStatus = null, repeatCount = 0,
    )

    private val guide = listOf(
        LiveGuideChannel(channel("a"), listOf(entry("a-earlier", false), entry("a-now", true))),
        LiveGuideChannel(channel("b"), listOf(entry("b-earlier", false), entry("b-now", true))),
    )

    @Test
    fun `the guide opens on the channel last watched`() {
        val selection = initialGuideSelection(guide, lastChannelId = "b", now = now)
        assertEquals("b", selection?.channel?.id)
        assertEquals("b-now", selection?.entry?.id)
    }

    @Test
    fun `a channel that is no longer in the lineup falls back to the first`() {
        val selection = initialGuideSelection(guide, lastChannelId = "gone", now = now)
        assertEquals("a", selection?.channel?.id)
    }

    @Test
    fun `nothing watched yet opens on the first channel`() {
        assertEquals("a", initialGuideSelection(guide, lastChannelId = null, now = now)?.channel?.id)
    }

    /**
     * A channel with nothing scheduled in this window is not somewhere to open: the pane
     * described nothing, the Watch button was not drawn, and the page opened with no
     * focus target at all.
     */
    @Test
    fun `a channel with no listings in this window falls through to one that has some`() {
        val sparse = listOf(
            LiveGuideChannel(channel("a"), emptyList()),
            LiveGuideChannel(channel("b"), emptyList()),
            LiveGuideChannel(channel("c"), listOf(entry("c-now", true))),
        )
        val selection = initialGuideSelection(sparse, lastChannelId = "b", now = now)
        assertEquals("c", selection?.channel?.id)
        assertEquals("c-now", selection?.entry?.id)
    }

    @Test
    fun `an empty guide has nothing to open on`() {
        assertNull(initialGuideSelection(emptyList(), lastChannelId = "b", now = now))
    }
}
