package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The grid scrolls at a fixed minutes-per-dp. Sizing cells to the track instead made the
 * loaded window the cell scale, so asking the server for more guide silently made every
 * programme smaller — four hours turned a half-hour show into a clipped box.
 */
@OptIn(ExperimentalTestApi::class)
class LiveGuideScaleTest {

    private val windowStart = Instant.parse("2026-09-07T19:00:00Z")

    private fun channel(id: String) = LiveChannel(
        id = id, name = "Channel $id", lcn = "9", callsign = null, logoUrl = null,
        network = null, region = null, kind = null, hd = false, enabled = true,
        favourite = false, muxLabel = null, typeLabel = null, hasGuide = true,
        guideEntryCount = 1, videoCodec = null, audioCodec = null, signalQuality = null,
    )

    private fun entry(id: String, title: String, offsetMinutes: Int, lengthMinutes: Int) =
        LiveGuideEntry(
            id = id, channelId = "c1",
            startTime = (windowStart + offsetMinutes.minutes).toString(),
            endTime = (windowStart + (offsetMinutes + lengthMinutes).minutes).toString(),
            title = title, episodeNumber = null, episodeTitle = null, synopsis = null,
            genres = emptyList(), imageUrl = null, posterUrl = null, isFirst = false,
            episodeKey = null, durationSeconds = lengthMinutes * 60L,
            progressPct = null, recordingId = null, recordingStatus = null, repeatCount = 0,
        )

    /** What a real lineup looks like: half-hours, with one channel dropping shorts in. */
    private val mixedGuide = listOf(
        LiveGuideChannel(
            channel = channel("c1"),
            entries = listOf(
                entry("m1", "Evening Feature", offsetMinutes = 0, lengthMinutes = 30),
                entry("m2", "Late Feature", offsetMinutes = 30, lengthMinutes = 30),
            ),
        ),
        LiveGuideChannel(
            channel = channel("c2"),
            entries = listOf(
                entry("s1", "Filler", offsetMinutes = 0, lengthMinutes = 5),
                entry("s2", "Also Filler", offsetMinutes = 5, lengthMinutes = 5),
                entry("s3", "The Main Event", offsetMinutes = 10, lengthMinutes = 50),
            ),
        ),
    )

    private val guide = listOf(
        LiveGuideChannel(
            channel = channel("c1"),
            entries = listOf(
                entry("e1", "Half Hour Show", offsetMinutes = 0, lengthMinutes = 30),
                entry("e2", "Feature Length", offsetMinutes = 30, lengthMinutes = 90),
                entry("e3", "Late Tonight", offsetMinutes = 180, lengthMinutes = 60),
            ),
        ),
    )

    private fun ComposeUiTest.showGuide(
        windowMinutes: Int,
        guide: List<LiveGuideChannel> = this@LiveGuideScaleTest.guide,
    ) {
        setContent {
            FocusScopeHost(id = "guide", store = FocusScopeStore(), restoreEnabled = false) {
                KobappTheme(formFactor = FormFactor.TV) {
                    LiveGuideGrid(
                        guide = guide,
                        windowStart = windowStart,
                        windowMinutes = windowMinutes,
                        clock = GuideClock(windowStart),
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
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    private fun widthOfHalfHourShow(windowMinutes: Int): Dp {
        var width = 0.dp
        runComposeUiTest {
            showGuide(windowMinutes)
            val bounds = onNodeWithText("Half Hour Show").getBoundsInRoot()
            width = bounds.right - bounds.left
        }
        return width
    }

    private fun ComposeUiTest.widthOf(title: String): Dp {
        val bounds = onNodeWithText(title).getBoundsInRoot()
        return bounds.right - bounds.left
    }

    /**
     * A channel that fills its gaps with five-minute shorts draws them 35dp wide, with no
     * room for a title. Stretching the axis until they fit would spread every other
     * channel's half-hours over three screens, so the cursor carries the room instead:
     * the programme being looked at grows over its neighbours, and the axis stays put.
     */
    @Test
    fun `the short programme under the cursor grows enough to read`() = runComposeUiTest {
        showGuide(windowMinutes = 240, guide = mixedGuide)
        val idle = widthOf("Filler")
        assertTrue(idle < 60.dp, "a five-minute programme should be a sliver, not $idle")

        onNodeWithText("Filler").requestFocus()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        assertTrue(widthOf("Filler") > 120.dp, "the focused programme came out at ${widthOf("Filler")}")
    }

    /** Only the focused one grows: the axis a viewer is reading must not move under them. */
    @Test
    fun `the rest of the axis does not move when a short programme is focused`() = runComposeUiTest {
        showGuide(windowMinutes = 240, guide = mixedGuide)
        val before = onNodeWithText("Evening Feature").getBoundsInRoot()

        onNodeWithText("Filler").requestFocus()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        assertEquals(before, onNodeWithText("Evening Feature").getBoundsInRoot())
    }

    @Test
    fun `a programme is the same width however much guide is loaded`() {
        assertEquals(widthOfHalfHourShow(windowMinutes = 120), widthOfHalfHourShow(windowMinutes = 480))
    }

    @Test
    fun `a half hour is wide enough to read on a ten foot screen`() {
        val width = widthOfHalfHourShow(windowMinutes = 240)
        assertTrue(width > 150.dp, "a half-hour programme came out at $width")
    }

    /**
     * The point of the scroller: a programme hours ahead is off-screen until the remote
     * walks to it, and then it is on-screen. Cells placed outside the scroll container
     * would still render and still take focus, and simply never come into view.
     */
    @Test
    fun `a programme hours ahead is off screen until the axis is scrolled to it`() = runComposeUiTest {
        showGuide(windowMinutes = 240)
        val bounds = onNodeWithText("Late Tonight").getBoundsInRoot()
        assertEquals(
            0.dp, bounds.right - bounds.left,
            "a programme three hours out should be off the end of the axis, not on screen",
        )
    }

    /**
     * The axis is a scroller with more on it than fits, which is what makes the rest of
     * the evening reachable at all. Cells laid out to the track instead would leave every
     * row exactly as wide as the viewport and nothing to scroll to.
     */
    @Test
    fun `the time axis has more loaded than it shows`() = runComposeUiTest {
        showGuide(windowMinutes = 240)
        val scrollers = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
        ).fetchSemanticsNodes()
        val ranges = scrollers.mapNotNull {
            it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        }
        assertTrue(ranges.isNotEmpty(), "the guide rows are not in a horizontal scroller")
        assertTrue(
            ranges.any { it.maxValue() > 0f },
            "nothing to scroll to: the axis is no wider than the viewport",
        )
    }
}
