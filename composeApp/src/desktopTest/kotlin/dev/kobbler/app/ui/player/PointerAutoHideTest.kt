package dev.kobbler.app.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import dev.kobbler.app.runUiTest
import dev.kobbler.kobplayer.ui.PlayerChrome
import dev.kobbler.kobplayer.ui.VideoPlayerOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * A resting mouse reports move events all the time — sensor noise, a hand that is not
 * quite still. Each one counted as reaching for the controls, restarting the auto-hide
 * clock, so the bar never went away while a movie played.
 */
@OptIn(ExperimentalTestApi::class)
class PointerAutoHideTest {

    @Test
    fun `a resting mouse leaves the controls to auto-hide`() = runUiTest {
        overlay()
        revealControls(PlayerChrome.Pointer)
        check(titleShown()) { "controls did not come up" }

        // Eight seconds of micro-moves around one point, well past the 4-second
        // window: the bar hides if the jitter is not counted as activity.
        repeat(16) {
            onRoot().performMouseInput { moveTo(center + Offset(1f, 0f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
        }
        check(!titleShown()) { "jitter kept the controls up" }
    }

    @Test
    fun `a real mouse move restarts the auto-hide clock`() = runUiTest {
        overlay()
        revealControls(PlayerChrome.Pointer)
        check(titleShown()) { "controls did not come up" }

        mainClock.advanceTimeBy(3_000)
        onRoot().performMouseInput { moveTo(center + Offset(300f, 0f)) }
        mainClock.advanceTimeBy(3_000)
        check(titleShown()) { "a move 3s in should restart the 4s clock" }

        mainClock.advanceTimeBy(1_500)
        check(!titleShown()) { "the restarted clock should have fired" }
    }

    private fun ComposeUiTest.overlay() {
        setContent {
            VideoPlayerOverlay(
                title = "A Movie",
                isPlaying = true,
                position = MutableStateFlow(60_000L),
                durationMs = 7_680_000L,
                onPlayPause = {},
                onSeek = {},
                onBack = {},
                chrome = PlayerChrome.Pointer,
            )
        }
        waitForIdle()
    }

    private fun ComposeUiTest.titleShown() = onAllNodesWithText("A Movie").fetchSemanticsNodes().isNotEmpty()
}