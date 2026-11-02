package dev.kobbler.app.ui.components

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TvClickableTest {

    private fun clicksFor(key: Key): Int {
        var clicks = 0
        val target = FocusRequester()
        runUiTest {
            setContent {
                val state = rememberTvFocusState()
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(target)
                        .tvClickable(state) { clicks++ },
                )
            }
            waitForIdle()
            runOnIdle { target.requestFocus() }
            waitForIdle()
            onRoot().performKeyInput { pressKey(key) }
            waitForIdle()
        }
        return clicks
    }

    @Test
    fun dpadCenterFiresOnce() = assertEquals(1, clicksFor(Key.DirectionCenter))

    @Test
    fun enterFiresOnce() = assertEquals(1, clicksFor(Key.Enter))

    private class Presses(var clicks: Int = 0, var longs: Int = 0)

    private fun pressed(input: androidx.compose.ui.test.DesktopComposeUiTest.() -> Unit): Presses {
        val presses = Presses()
        val target = FocusRequester()
        runUiTest {
            setContent {
                val state = rememberTvFocusState()
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(target)
                        .tvClickable(state, onLongClick = { presses.longs++ }) { presses.clicks++ },
                )
            }
            waitForIdle()
            runOnIdle { target.requestFocus() }
            waitForIdle()
            input()
            waitForIdle()
        }
        return presses
    }

    @Test
    fun heldOkIsALongPressNotAClick() {
        for (key in listOf(Key.DirectionCenter, Key.Enter)) {
            val presses = pressed {
                onRoot().performKeyInput { keyDown(key) }
                mainClock.advanceTimeBy(1_000)
                onRoot().performKeyInput { keyUp(key) }
            }
            assertEquals(0, presses.clicks, "$key")
            assertEquals(1, presses.longs, "$key")
        }
    }

    @Test
    fun tappedOkIsStillAClick() {
        val presses = pressed { onRoot().performKeyInput { pressKey(Key.DirectionCenter) } }
        assertEquals(1, presses.clicks)
        assertEquals(0, presses.longs)
    }

    @Test
    fun touchLongPressIsALongPress() {
        assertEquals(1, pressed { onRoot().performTouchInput { longClick() } }.longs)
        assertEquals(1, pressed { onRoot().performTouchInput { click() } }.clicks)
    }
}
