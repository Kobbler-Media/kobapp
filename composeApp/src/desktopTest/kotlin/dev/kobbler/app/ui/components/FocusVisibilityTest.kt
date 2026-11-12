package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusVisibility
import dev.kobbler.kobfocus.LocalFocusVisibility
import dev.kobbler.kobfocus.trackFocusVisibility
import dev.kobbler.kobplayer.ui.HoverLabelDelayMs
import kotlin.test.Test
import kotlin.test.assertEquals

/** A click or tap still moves focus, but only steering by keys draws it. */
@OptIn(ExperimentalTestApi::class)
class FocusVisibilityTest {

    private class Rig {
        var progress: State<Float>? = null
        val labelled = FocusRequester()
        val card = FocusRequester()
        val ring get() = progress?.value ?: error("progress never composed")
    }

    private fun screen(test: DesktopComposeUiTest.(Rig) -> Unit) = runUiTest {
        val rig = Rig()
        setContent {
            KobappTheme(formFactor = FormFactor.Desktop) {
                val visibility = remember { FocusVisibility(visible = false) }
                CompositionLocalProvider(LocalFocusVisibility provides visibility) {
                    Row(Modifier.trackFocusVisibility(visibility)) {
                        val state = rememberTvFocusState()
                        rig.progress = tvFocusProgress(state.isFocused)
                        Box(Modifier.size(40.dp).testTag("card").focusRequester(rig.card).tvClickable(state) {})
                        LabelledIconButton(Icons.Outlined.Settings, "Settings", {}, Modifier.focusRequester(rig.labelled))
                    }
                }
            }
        }
        waitForIdle()
        test(rig)
    }

    private fun DesktopComposeUiTest.settle() {
        mainClock.advanceTimeBy(HoverLabelDelayMs + 500)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.labelShowing() = onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()

    private val pointers = listOf<DesktopComposeUiTest.() -> Unit>(
        { onNodeWithTag("card").performMouseInput { click() } },
        { onNodeWithTag("card").performTouchInput { click() } },
    )

    @Test
    fun `focus handed out after a click or tap draws nothing`() {
        for (press in pointers) screen { rig ->
            press()
            runOnIdle { rig.card.requestFocus() }
            settle()
            onNodeWithTag("card").assertIsFocused()
            assertEquals(0f, rig.ring, "ring")
            runOnIdle { rig.labelled.requestFocus() }
            settle()
            assertEquals(false, labelShowing(), "label")
        }
    }

    @Test
    fun `steering with keys lights focus back up`() = screen { rig ->
        onNodeWithTag("card").performTouchInput { click() }
        runOnIdle { rig.card.requestFocus() }
        settle()
        onNodeWithTag("card").performKeyInput { pressKey(Key.DirectionUp) }
        settle()
        assertEquals(1f, rig.ring, "ring")
        runOnIdle { rig.labelled.requestFocus() }
        settle()
        assertEquals(true, labelShowing(), "label")
    }

    @Test
    fun `without a tracker focus always draws`() = runUiTest {
        val card = FocusRequester()
        var progress: State<Float>? = null
        setContent {
            val state = rememberTvFocusState()
            progress = tvFocusProgress(state.isFocused)
            Box(Modifier.size(40.dp).focusRequester(card).tvClickable(state) {})
        }
        runOnIdle { card.requestFocus() }
        settle()
        assertEquals(1f, progress?.value)
    }
}
