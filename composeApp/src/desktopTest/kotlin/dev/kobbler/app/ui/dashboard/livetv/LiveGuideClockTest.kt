package dev.kobbler.app.ui.dashboard.livetv

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * On air is a fact about the time, not a field on a row.
 *
 * The server stamps each programme when the window is fetched, and a guide outlives that
 * stamp: sit on it for half an hour and the accent is on something that has finished,
 * while OK on what is actually airing does nothing at all — the one gesture the page is
 * for, lost to a clock nobody was reading.
 */
@OptIn(ExperimentalTestApi::class)
class LiveGuideClockTest {

    private val t0 = Instant.parse("2026-09-09T20:00:00Z")

    private fun entry(id: String, title: String, from: Int, to: Int) = LiveGuideEntry(
        id = id, channelId = "c1",
        startTime = (t0 + from.minutes).toString(),
        endTime = (t0 + to.minutes).toString(),
        title = title, episodeNumber = null, episodeTitle = null, synopsis = null,
        genres = emptyList(), imageUrl = null, posterUrl = null, isFirst = false,
        episodeKey = null, durationSeconds = (to - from) * 60L,
        progressPct = null, recordingId = null, recordingStatus = null, repeatCount = 0,
    )

    @Test
    fun `a programme is on air from its start until its end`() {
        val programme = entry("e1", "Something", from = 0, to = 30)

        assertEquals(true, programme.isAiringAt(t0))
        assertEquals(true, programme.isAiringAt(t0 + 29.minutes))
        assertEquals(false, programme.isAiringAt(t0 - 1.minutes))
        // The end is the next programme's start; both cannot be on at once.
        assertEquals(false, programme.isAiringAt(t0 + 30.minutes))
    }

    @Test
    fun `a programme the feed gave no times is never on air`() {
        val undated = entry("e1", "Something", 0, 30).copy(startTime = "", endTime = "")
        assertEquals(false, undated.isAiringAt(t0))
    }

    /**
     * The guide is open, and the programme the viewer is sitting on comes on air while
     * they watch the row. Nothing is refetched; the clock is the only thing that moved.
     */
    @Test
    fun `a programme that comes on air while the guide is open becomes watchable`() = runUiTest {
        val guide = listOf(
            LiveGuideChannel(
                channel = LiveChannel(
                    id = "c1", name = "Channel One", lcn = null, callsign = null, logoUrl = null,
                    network = null, region = null, kind = null, hd = false, enabled = true,
                    favourite = false, muxLabel = null, typeLabel = null, hasGuide = true,
                    guideEntryCount = 1, videoCodec = null, audioCodec = null, signalQuality = null,
                ),
                entries = listOf(entry("e1", "Starts Soon", from = 5, to = 35)),
            ),
        )
        val clock = GuideClock(t0)
        var watched: LiveChannel? = null

        setContent {
            FocusScopeHost(id = "guide", store = FocusScopeStore(), restoreEnabled = false) {
                KobappTheme(formFactor = FormFactor.TV) {
                    LiveGuideList(
                        guide = guide,
                        at = null,
                        clock = clock,
                        loading = false,
                        wide = true,
                        accent = KobappTheme.colors.textHi,
                        gutter = 24.dp,
                        entryKey = null,
                        forYou = emptyList(),
                        recorded = emptySet(),
                        chips = {},
                        onWatch = { watched = it },
                        onOpenChannel = {},
                        onRecord = {},
                        modifier = Modifier.size(1280.dp, 720.dp),
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Starts Soon").performClick()
        waitForIdle()
        assertNull(watched, "a programme that has not started yet was played")

        clock.now = t0 + 10.minutes
        waitForIdle()
        onNodeWithText("Starts Soon").performClick()
        waitForIdle()
        assertEquals("c1", watched?.id, "the programme came on air and OK still did nothing")
    }
}
