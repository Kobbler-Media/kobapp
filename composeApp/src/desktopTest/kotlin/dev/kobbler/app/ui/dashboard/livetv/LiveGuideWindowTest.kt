package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The guide starts at now, not at the half hour before it.
 *
 * A window rounded down to the slot reaches back over programmes that have already
 * finished, and those are what the grid puts at the head of every row — the first thing
 * the cursor lands on, and the one cell in the page that cannot be watched.
 */
@OptIn(ExperimentalTestApi::class)
class LiveGuideWindowTest {

    private val at2045 = Instant.parse("2026-09-09T20:45:00Z")

    @Test
    fun `the steppers' way home is now, to the minute`() {
        assertEquals(at2045, shiftGuideWindow(at2045 - 360.minutes, 0, 240, now = at2045))
    }

    @Test
    fun `stepping walks by the delta from wherever the window is`() {
        assertEquals(
            at2045 + 240.minutes,
            shiftGuideWindow(at2045, 240, 240, now = at2045),
        )
    }

    @Test
    fun `the ruler labels the half hours, not the window's own offset`() {
        assertEquals(
            listOf("21:00", "21:30", "22:00", "22:30"),
            guideRulerTicks(at2045, 120).map { it.toString().substring(11, 16) },
        )
    }

    @Test
    fun `a window that opens on a half hour is labelled from its own start`() {
        val at2100 = Instant.parse("2026-09-09T21:00:00Z")
        assertEquals(
            listOf("21:00", "21:30"),
            guideRulerTicks(at2100, 60).map { it.toString().substring(11, 16) },
        )
    }

    private fun channel(id: String) = LiveChannel(
        id = id, name = "Channel $id", lcn = null, callsign = null, logoUrl = null,
        network = null, region = null, kind = null, hd = false, enabled = true,
        favourite = false, muxLabel = null, typeLabel = null, hasGuide = true,
        guideEntryCount = 2, videoCodec = null, audioCodec = null, signalQuality = null,
    )

    private fun entry(id: String, title: String, from: Int, to: Int) =
        LiveGuideEntry(
            id = id, channelId = "c1",
            startTime = (nowInstant() + from.minutes).toString(),
            endTime = (nowInstant() + to.minutes).toString(),
            title = title, episodeNumber = null, episodeTitle = null, synopsis = null,
            genres = emptyList(), imageUrl = null, posterUrl = null, isFirst = false,
            episodeKey = null, durationSeconds = (to - from) * 60L,
            progressPct = null, recordingId = null, recordingStatus = null, repeatCount = 0,
        )

    /** The bug as the viewer meets it: a finished programme sitting at the head of the row. */
    @Test
    fun `a programme that has already finished is not in the grid`() = runComposeUiTest {
        val guide = listOf(
            LiveGuideChannel(
                channel = channel("c1"),
                entries = listOf(
                    entry("e0", "Already Finished", from = -45, to = -15),
                    entry("e1", "On Air Now", from = -15, to = 15),
                ),
            ),
        )
        setContent {
            FocusScopeHost(id = "guide", store = FocusScopeStore(), restoreEnabled = false) {
                KobappTheme(formFactor = FormFactor.TV) {
                    LiveGuideGrid(
                        guide = guide,
                        windowStart = nowInstant(),
                        windowMinutes = 240,
                        clock = GuideClock(),
                        selection = LiveGuideSelectionHolder(),
                        activeRecordings = emptyList(),
                        loading = false,
                        accent = KobappTheme.colors.textHi,
                        canWant = false,
                        gutter = 24.dp,
                        modifier = Modifier.size(1280.dp, 720.dp),
                        facetStrip = {},
                        onSelect = {},
                        onWatch = {},
                        onRecord = {},
                        onAddToWanted = {},
                        onShiftWindow = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("On Air Now").assertIsDisplayed()
        onNodeWithText("Already Finished").assertDoesNotExist()
    }
}
