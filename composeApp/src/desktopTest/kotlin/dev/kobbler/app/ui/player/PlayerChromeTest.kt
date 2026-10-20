package dev.kobbler.app.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlayerChromeTest {

    private class Rig {
        var backs = 0
        var playPauses = 0
    }

    private fun player(formFactor: FormFactor, loading: Boolean = false, test: DesktopComposeUiTest.(Rig) -> Unit) =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val rig = Rig()
            setContent {
                KobappTheme(formFactor = formFactor) {
                    PlayerOverlay(
                        title = "Film",
                        isPlaying = false,
                        currentMs = { 0L },
                        durationMs = 60_000L,
                        onPlayPause = { rig.playPauses++ },
                        onSeek = {},
                        onBack = { rig.backs++ },
                        loading = loading,
                    )
                }
            }
            waitForIdle()
            test(rig)
        }

    @Test
    fun `only a TV leaves the exit to its remote`() {
        mapOf(FormFactor.Phone to 1, FormFactor.Desktop to 1, FormFactor.TV to 0).forEach { (ff, expected) ->
            player(ff) {
                assertEquals(expected, onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size, "back buttons on $ff")
            }
        }
    }

    @Test
    fun `a tap on a stream that is still opening offers the way out`() {
        listOf(FormFactor.Phone, FormFactor.Desktop).forEach { ff ->
            player(ff, loading = true) { rig ->
                assertTrue(onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty(), "back over the logo before a tap on $ff")
                onRoot().performMouseInput { click(Offset(640f, 360f)) }
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                onNodeWithContentDescription("Back").performClick()
                assertEquals(1, rig.backs, "back from the loading screen on $ff")
                assertEquals(0, rig.playPauses, "the tap that asked for a way out played or paused on $ff")
            }
        }
    }

    @Test
    fun `the way out steps aside again`() = player(FormFactor.Phone, loading = true) {
        onRoot().performMouseInput { click(Offset(640f, 360f)) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithContentDescription("Back").assertExists()
        mainClock.advanceTimeBy(6_000)
        waitForIdle()
        assertTrue(onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty(), "back stayed over the logo")
    }

    @Test
    fun `desktop back button leaves the player`() = player(FormFactor.Desktop) { rig ->
        onNodeWithContentDescription("Back").performClick()
        assertEquals(1, rig.backs)
    }

    @Test
    fun `desktop play pause sits with the seek bar, not over the picture`() = player(FormFactor.Desktop) {
        val button = onNodeWithContentDescription("Play/Pause").getUnclippedBoundsInRoot()
        val bar = onNodeWithContentDescription("Seek").getUnclippedBoundsInRoot()
        assertTrue(button.top >= bar.top, "play/pause $button is above the seek bar $bar")
        assertTrue(onAllNodesWithContentDescription("Rewind 10s").fetchSemanticsNodes().isEmpty(), "the centre transport is still up")
    }

    @Test
    fun `clicking the picture plays and pauses on desktop only`() {
        mapOf(FormFactor.Desktop to 1, FormFactor.Phone to 0).forEach { (ff, expected) ->
            player(ff) { rig ->
                onRoot().performMouseInput { click(Offset(1000f, 200f)) }
                mainClock.advanceTimeBy(1_000)
                waitForIdle()
                assertEquals(expected, rig.playPauses, "play/pause from a click on the picture on $ff")
            }
        }
    }
}
