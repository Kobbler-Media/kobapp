package dev.kobbler.app.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.kobbler.kobplayer.ui.PlayerChrome
import dev.kobbler.kobplayer.ui.VideoPlayerOverlay
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The player only reports its new position some time after a seek, so a burst that read the
 * playhead fresh on every tap would land each one on the same spot. The position is fixed at
 * one minute here to stand in for that lag.
 */
@OptIn(ExperimentalTestApi::class)
class DoubleTapSkipTest {

    private fun ComposeUiTest.overlay(seeks: MutableList<Long>) {
        setContent {
            VideoPlayerOverlay(
                title = "A Movie",
                isPlaying = true,
                currentMs = { 60_000L },
                durationMs = 7_680_000L,
                onPlayPause = {},
                onSeek = { seeks += it },
                onBack = {},
                chrome = PlayerChrome.Touch,
            )
        }
        waitForIdle()
    }

    private fun ComposeUiTest.doubleTap(right: Boolean) =
        onRoot().performTouchInput { doubleClick(side(right)) }

    private fun ComposeUiTest.tap(right: Boolean) {
        onRoot().performTouchInput { click(side(right)) }
        mainClock.advanceTimeBy(400)
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.side(right: Boolean) =
        if (right) centerRight - Offset(40f, 0f) else centerLeft + Offset(40f, 0f)

    @Test
    fun `every tap after the first double tap adds ten seconds`() = runComposeUiTest {
        val seeks = mutableListOf<Long>()
        overlay(seeks)

        doubleTap(right = true)
        doubleTap(right = true)
        tap(right = true)
        waitForIdle()

        assertEquals(listOf(70_000L, 90_000L, 100_000L), seeks)
        onNodeWithText("40s").assertExists()
    }

    @Test
    fun `rewind bursts stack the same way`() = runComposeUiTest {
        val seeks = mutableListOf<Long>()
        overlay(seeks)

        doubleTap(right = false)
        tap(right = false)
        waitForIdle()

        assertEquals(listOf(50_000L, 40_000L), seeks)
        onNodeWithText("20s").assertExists()
    }

    @Test
    fun `a burst ends once the taps stop`() = runComposeUiTest {
        val seeks = mutableListOf<Long>()
        overlay(seeks)

        doubleTap(right = true)
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        doubleTap(right = true)
        waitForIdle()

        assertEquals(listOf(70_000L, 70_000L), seeks)
        onNodeWithText("10s").assertExists()
    }
}
