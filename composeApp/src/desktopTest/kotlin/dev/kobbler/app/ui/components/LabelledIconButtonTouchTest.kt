package dev.kobbler.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.IconAction
import dev.kobbler.kobplayer.ui.HoverLabelLingerMs
import kotlin.test.Test
import kotlin.test.assertEquals

/** A phone has no hover, so holding an icon button is how it says what it does. */
@OptIn(ExperimentalTestApi::class)
class LabelledIconButtonTouchTest {

    private val buttons = listOf<@Composable (onClick: () -> Unit) -> Unit>(
        { LabelledIconButton(Icons.Outlined.SwapHoriz, "Switch stream", onClick = it) },
        { IconAction(Icons.Outlined.SwapHoriz, "Switch stream", onClick = it, accent = Color.Red) },
    )

    private fun button(test: DesktopComposeUiTest.(clicks: () -> Int) -> Unit) {
        for (button in buttons) runUiTest {
            var clicks = 0
            setContent {
                KobappTheme(formFactor = FormFactor.Phone) { button { clicks++ } }
            }
            waitForIdle()
            test { clicks }
        }
    }

    private fun DesktopComposeUiTest.labelShowing() = onAllNodesWithText("Switch stream").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `holding names the button instead of pressing it`() = button { clicks ->
        onNodeWithContentDescription("Switch stream").performTouchInput { longClick() }
        waitForIdle()
        assertEquals(true, labelShowing(), "label")
        assertEquals(0, clicks(), "a hold pressed the button")
        mainClock.advanceTimeBy(HoverLabelLingerMs + 100)
        waitForIdle()
        assertEquals(false, labelShowing(), "label outstayed the hold")
    }

    @Test
    fun `a tap still presses it, unlabelled`() = button { clicks ->
        onNodeWithContentDescription("Switch stream").performTouchInput { click() }
        mainClock.advanceTimeBy(HoverLabelLingerMs)
        waitForIdle()
        assertEquals(1, clicks())
        assertEquals(false, labelShowing())
    }
}
