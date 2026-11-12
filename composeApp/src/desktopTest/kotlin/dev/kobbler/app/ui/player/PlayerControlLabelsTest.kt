package dev.kobbler.app.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performMouseInput
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.ui.components.LabelledIconButton
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobplayer.ui.HoverLabelDelayMs
import dev.kobbler.kobplayer.ui.OverlayChapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Icon-only player controls name themselves on hover without moving anything around them. */
@OptIn(ExperimentalTestApi::class)
class PlayerControlLabelsTest {

    private fun player(formFactor: FormFactor = FormFactor.Desktop, test: DesktopComposeUiTest.() -> Unit) = runUiTest(width = 1280, height = 720) {
        setContent {
            KobappTheme(formFactor = formFactor) {
                PlayerOverlay(
                    title = "Film",
                    isPlaying = true,
                    position = MutableStateFlow(0L),
                    durationMs = 600_000L,
                    onPlayPause = {},
                    onSeek = {},
                    onBack = {},
                    onToggleFullscreen = {},
                    chapters = listOf(OverlayChapter("Opening", 0L), OverlayChapter("Middle", 300_000L)),
                    extraControls = { LabelledIconButton(Icons.Outlined.SwapHoriz, "Switch stream", onClick = {}) },
                )
            }
        }
        waitForIdle()
        revealControls(formFactor)
        test()
    }

    private fun DesktopComposeUiTest.labelsShowing(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun DesktopComposeUiTest.hover(node: SemanticsNodeInteraction) {
        node.performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(HoverLabelDelayMs + 100)
        waitForIdle()
    }

    @Test
    fun `hovering a control names it, and the row stays put`() = player {
        for (name in listOf("Chapters", "Switch stream", "Fullscreen")) {
            val control = onNodeWithContentDescription(name)
            val before = onNodeWithContentDescription("Fullscreen").getBoundsInRoot()
            assertEquals(0, labelsShowing(name), "$name labelled before hover")
            hover(control)
            assertEquals(1, labelsShowing(name), "$name not labelled on hover")
            assertEquals(before, onNodeWithContentDescription("Fullscreen").getBoundsInRoot(), "$name's label moved the row")
            onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(640f, 200f)) }
            mainClock.advanceTimeBy(HoverLabelDelayMs + 100)
            waitForIdle()
            assertEquals(0, labelsShowing(name), "$name label outlived the hover")
        }
    }

    @Test
    fun `on a TV the focused control is named`() = player(FormFactor.TV) {
        onNodeWithContentDescription("Switch stream").requestFocus()
        mainClock.advanceTimeBy(HoverLabelDelayMs + 100)
        waitForIdle()
        assertEquals(1, labelsShowing("Switch stream"))
        onNodeWithContentDescription("Chapters").requestFocus()
        mainClock.advanceTimeBy(HoverLabelDelayMs + 100)
        waitForIdle()
        assertEquals(0, labelsShowing("Switch stream"), "label stayed on a control focus left")
        assertEquals(1, labelsShowing("Chapters"))
    }
}
