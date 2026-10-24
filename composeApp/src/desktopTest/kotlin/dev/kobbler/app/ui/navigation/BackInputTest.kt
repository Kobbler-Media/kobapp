package dev.kobbler.app.ui.navigation

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class BackInputTest {

    private fun backsFrom(input: DesktopComposeUiTest.() -> Unit): Int {
        var backs = 0
        runUiTest {
            val focus = FocusRequester()
            setContent {
                Box(Modifier.fillMaxSize().backInput { backs++; true }.focusRequester(focus).focusable())
            }
            runOnIdle { focus.requestFocus() }
            input()
            waitForIdle()
        }
        return backs
    }

    // The test API has no back mouse button, so the event goes to the scene directly.
    private fun DesktopComposeUiTest.mouseBack() {
        val at = Offset(50f, 50f)
        for (type in listOf(PointerEventType.Press, PointerEventType.Release)) {
            scene.sendPointerEvent(
                eventType = type,
                position = at,
                buttons = if (type == PointerEventType.Press) PointerButtons(isBackPressed = true) else PointerButtons(),
                button = PointerButton.Back,
            )
        }
    }

    @Test
    fun `desktop back inputs each go back once`() {
        val cases = listOf<Pair<String, DesktopComposeUiTest.() -> Unit>>(
            "Escape" to { onRoot().performKeyInput { pressKey(Key.Escape) } },
            "Alt+Left" to { onRoot().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) } } },
            "mouse back button" to { mouseBack() },
        )
        for ((name, input) in cases) assertEquals(1, backsFrom(input), name)
    }

    @Test
    fun `a plain left arrow is navigation, not back`() {
        assertEquals(0, backsFrom { onRoot().performKeyInput { pressKey(Key.DirectionLeft) } })
    }
}
