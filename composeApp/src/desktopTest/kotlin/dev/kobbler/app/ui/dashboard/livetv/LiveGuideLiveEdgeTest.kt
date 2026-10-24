package dev.kobbler.app.ui.dashboard.livetv

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The guide keeps up with the clock. A window is fetched once, so an evening spent on the
 * page must not leave each row describing programmes that finished hours ago.
 */
@OptIn(ExperimentalTestApi::class)
class LiveGuideLiveEdgeTest {

    private val windowStart = Instant.parse("2026-09-07T19:00:00Z")

    private fun channel(id: String) = LiveChannel(
        id = id, name = "Channel $id", lcn = "9", callsign = null, logoUrl = null,
        network = null, region = null, kind = null, hd = false, enabled = true,
        favourite = false, muxLabel = null, typeLabel = null, hasGuide = true,
        guideEntryCount = 8, videoCodec = null, audioCodec = null, signalQuality = null,
    )

    private fun entry(hour: Int) = LiveGuideEntry(
        id = "e$hour", channelId = "c1",
        startTime = (windowStart + (hour * 60).minutes).toString(),
        endTime = (windowStart + ((hour + 1) * 60).minutes).toString(),
        title = "Hour $hour", episodeNumber = null, episodeTitle = null, synopsis = null,
        genres = emptyList(), imageUrl = null, posterUrl = null, isFirst = false,
        episodeKey = null, durationSeconds = 3600L,
        progressPct = null, recordingId = null, recordingStatus = null, repeatCount = 0,
    )

    private val guide = listOf(
        LiveGuideChannel(channel = channel("c1"), entries = (0..7).map { entry(it) }),
    )

    private fun ComposeUiTest.showGuide(clock: GuideClock, at: Instant? = null) {
        setContent {
            FocusScopeHost(id = "guide", store = FocusScopeStore(), restoreEnabled = false) {
                KobappTheme(formFactor = FormFactor.TV) {
                    LiveGuideList(
                        guide = guide,
                        at = at,
                        clock = clock,
                        loading = false,
                        wide = true,
                        accent = KobappTheme.colors.textHi,
                        gutter = 24.dp,
                        entryKey = null,
                        forYou = emptyList(),
                        recorded = emptySet(),
                        chips = {},
                        onWatch = {},
                        onOpenChannel = {},
                        onRecord = {},
                        modifier = Modifier.size(1280.dp, 720.dp),
                    )
                }
            }
        }
        settle()
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    @Test
    fun `what is on air leads the row however far the clock has moved`() = runUiTest {
        val clock = GuideClock(windowStart + 10.minutes)
        showGuide(clock)
        onNodeWithText("Hour 0").assertIsDisplayed()
        onNodeWithText("Hour 1").assertIsDisplayed()

        clock.now = windowStart + 310.minutes
        settle()

        onNodeWithText("Hour 5").assertIsDisplayed()
        onNodeWithText("Hour 6").assertIsDisplayed()
        onNodeWithText("Hour 0").assertDoesNotExist()
        onNodeWithText("Hour 4").assertDoesNotExist()
    }

    /** A picked time is asking to read the schedule, not to be dragged back to what is on. */
    @Test
    fun `a picked time is left where the viewer put it`() = runUiTest {
        val clock = GuideClock(windowStart + 10.minutes)
        showGuide(clock, at = windowStart + 250.minutes)

        onNodeWithText("Hour 4").assertIsDisplayed()
        onNodeWithText("Hour 0").assertDoesNotExist()
    }
}
