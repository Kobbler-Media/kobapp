package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The guide keeps up with the clock.
 *
 * A window is fetched once and the track is a fixed minutes-per-dp, so an evening spent
 * on the page walks the live edge off the right of the screen: what is left on it is
 * programmes that finished hours ago, and what is actually on is nowhere to be seen.
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

    private fun ComposeUiTest.showGuide(clock: GuideClock, from: Instant = windowStart) {
        setContent {
            FocusScopeHost(id = "guide", store = FocusScopeStore(), restoreEnabled = false) {
                KobappTheme(formFactor = FormFactor.TV) {
                    LiveGuideGrid(
                        guide = guide,
                        windowStart = from,
                        windowMinutes = 480,
                        clock = clock,
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
        settle()
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    private fun ComposeUiTest.leftOf(title: String): Dp = onNodeWithText(title).getBoundsInRoot().left

    @Test
    fun `what is on air is at the head of the row however far the clock has moved`() = runComposeUiTest {
        val clock = GuideClock(windowStart)
        showGuide(clock)
        val head = leftOf("Hour 0")

        clock.now = windowStart + 300.minutes
        settle()

        onNodeWithText("Hour 5").assertIsDisplayed()
        val moved = leftOf("Hour 5")
        assertTrue(
            abs((moved - head).value) < 2f,
            "the on-air programme sits at $moved, five hours of track away from the head at $head",
        )
    }

    @Test
    fun `a programme that finishes while the guide is open leaves the grid`() = runComposeUiTest {
        val clock = GuideClock(windowStart)
        showGuide(clock)
        onNodeWithText("Hour 0").assertIsDisplayed()

        clock.now = windowStart + 300.minutes
        settle()

        onNodeWithText("Hour 0").assertDoesNotExist()
        onNodeWithText("Hour 4").assertDoesNotExist()
    }

    /**
     * Stepping the window forward is asking to read the schedule, not to be dragged back
     * to what is on — the live edge is hours behind the whole window.
     */
    @Test
    fun `a window the viewer stepped forward to is left where they put it`() = runComposeUiTest {
        val clock = GuideClock(windowStart - 240.minutes)
        showGuide(clock)

        onNodeWithText("Hour 0").assertIsDisplayed()
        assertTrue(leftOf("Hour 0") < 400.dp, "the window opened scrolled to ${leftOf("Hour 0")}")
    }
}
