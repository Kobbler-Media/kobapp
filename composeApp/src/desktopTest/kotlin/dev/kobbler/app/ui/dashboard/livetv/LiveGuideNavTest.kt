package dev.kobbler.app.ui.dashboard.livetv

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveGuideEntry
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScope
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * UP and DOWN move between channels at the time the viewer is reading, not between
 * rectangles.
 *
 * Compose weighs a candidate from the centre of the focused one, so a two-hour programme
 * reaches an hour into the row below and steps straight over the half-hour that starts at
 * the same time as it: press DOWN on the film you are watching and you land on what the
 * next channel is showing after the one it is showing now.
 */
@OptIn(ExperimentalTestApi::class)
class LiveGuideNavTest {

    private val windowStart = Instant.parse("2026-09-07T19:00:00Z")

    private fun channel(id: String) = LiveChannel(
        id = id, name = "Channel $id", lcn = "9", callsign = null, logoUrl = null,
        network = null, region = null, kind = null, hd = false, enabled = true,
        favourite = false, muxLabel = null, typeLabel = null, hasGuide = true,
        guideEntryCount = 4, videoCodec = null, audioCodec = null, signalQuality = null,
    )

    private fun entry(id: String, title: String, from: Int, to: Int) = LiveGuideEntry(
        id = id, channelId = "c1",
        startTime = (windowStart + from.minutes).toString(),
        endTime = (windowStart + to.minutes).toString(),
        title = title, episodeNumber = null, episodeTitle = null, synopsis = null,
        genres = emptyList(), imageUrl = null, posterUrl = null, isFirst = false,
        episodeKey = null, durationSeconds = (to - from) * 60L,
        progressPct = null, recordingId = null, recordingStatus = null, repeatCount = 0,
    )

    private val guide = listOf(
        LiveGuideChannel(
            channel = channel("c1"),
            entries = listOf(entry("a1", "The Long Film", from = 0, to = 120)),
        ),
        LiveGuideChannel(
            channel = channel("c2"),
            entries = listOf(
                entry("b1", "First Half", from = 0, to = 30),
                entry("b2", "Second Half", from = 30, to = 60),
                entry("b3", "Third Half", from = 60, to = 90),
                entry("b4", "Fourth Half", from = 90, to = 120),
            ),
        ),
    )

    private class Grid(val focused: () -> String?, val move: (FocusDirection) -> Unit)

    private fun ComposeUiTest.showGuide(
        guide: List<LiveGuideChannel> = this@LiveGuideNavTest.guide,
        clock: GuideClock = GuideClock(windowStart),
    ): Grid {
        var scope: FocusScope? = null
        var manager: FocusManager? = null
        setContent {
            FocusScopeHost(id = "guide", store = FocusScopeStore(), restoreEnabled = false) {
                scope = LocalFocusScope.current
                manager = LocalFocusManager.current
                KobappTheme(formFactor = FormFactor.TV) {
                    LiveGuideGrid(
                        guide = guide,
                        windowStart = windowStart,
                        windowMinutes = 240,
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
        return Grid(
            focused = { scope?.save() },
            // The D-pad rather than a raw key press: the desktop test harness does not
            // turn arrow keys into traversal, and the mapping from one to the other is
            // the platform's job either way.
            move = { direction ->
                runOnIdle { manager!!.moveFocus(direction) }
                settle()
            },
        )
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    @Test
    fun `down from a long programme lands on what the next channel is showing at the same time`() =
        runComposeUiTest {
            val grid = showGuide()

            onNodeWithText("The Long Film").requestFocus()
            settle()
            grid.move(FocusDirection.Down)

            assertTrue(grid.focused()?.endsWith("gch.c2.e.b1") == true, "DOWN landed on ${grid.focused()}")
        }

    @Test
    fun `up out of a short programme comes back to the one covering it`() = runComposeUiTest {
        val grid = showGuide()

        onNodeWithText("Third Half").requestFocus()
        settle()
        grid.move(FocusDirection.Up)

        assertTrue(grid.focused()?.endsWith("gch.c1.e.a1") == true, "UP landed on ${grid.focused()}")
    }

    /**
     * The programme being read finishes, and the cell holding the cursor leaves the grid
     * with it. Focus goes with the cell, and a TV whose D-pad does nothing — or jumps to
     * the top of the page — is worse than the finished programme was.
     */
    @Test
    fun `focus moves on with the schedule when the programme it is on ends`() = runComposeUiTest {
        val clock = GuideClock(windowStart)
        val lineup = listOf(
            LiveGuideChannel(
                channel = channel("c1"),
                entries = listOf(
                    entry("z1", "Ending Soon", from = 0, to = 10),
                    entry("z2", "Up Next", from = 10, to = 40),
                ),
            ),
        )
        val grid = showGuide(lineup, clock)

        onNodeWithText("Ending Soon").requestFocus()
        settle()

        clock.now = windowStart + 15.minutes
        settle()

        assertTrue(grid.focused()?.endsWith("gch.c1.e.z2") == true, "focus ended up on ${grid.focused()}")
    }

    @Test
    fun `the target is whatever the next channel is showing at the anchor`() {
        assertEquals(
            "c2" to "b3",
            guideVerticalTarget(guide, fromIndex = 0, delta = 1, anchor = windowStart + 70.minutes),
        )
    }

    /** A gap in the lineup is not somewhere to put the cursor; the press falls through. */
    @Test
    fun `a channel showing nothing at the anchor is no target`() {
        assertNull(guideVerticalTarget(guide, fromIndex = 0, delta = 1, anchor = windowStart + 200.minutes))
        assertNull(guideVerticalTarget(guide, fromIndex = 1, delta = 1, anchor = windowStart))
    }

    /**
     * The film started an hour ago, so reading across from its own start would land on a
     * programme the channel below finished an hour ago.
     */
    @Test
    fun `a programme already under way anchors on the live edge`() {
        val liveEdge = windowStart + 60.minutes
        assertEquals(liveEdge, guideAnchor(guide[0].entries[0], liveEdge))
    }

    @Test
    fun `a programme yet to start anchors on its own start`() {
        assertEquals(windowStart + 90.minutes, guideAnchor(guide[1].entries[3], windowStart))
    }

    /**
     * Walking down a lineup holds the time it started on. Re-anchoring on each landing
     * would drift towards the start of whatever it last hit, so four channels down the
     * cursor is reading a different part of the evening than the viewer chose.
     */
    @Test
    fun `moving onto a programme that covers the anchor leaves it where it was`() {
        val nav = GuideNav()
        nav.onFocused(guide[1].entries[1], liveEdge = windowStart)
        assertEquals(windowStart + 30.minutes, nav.anchor)

        nav.onFocused(guide[0].entries[0], liveEdge = windowStart)
        assertEquals(windowStart + 30.minutes, nav.anchor)
    }
}
