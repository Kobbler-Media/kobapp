package dev.kobbler.app.ui.components

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
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TvClickableTest {

    private fun clicksFor(key: Key): Int {
        var clicks = 0
        val target = FocusRequester()
        runComposeUiTest {
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
}
