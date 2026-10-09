package dev.kobbler.kobfocus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overlay case: the control that opens a panel is the last thing focused, and going
 * inert behind that panel loses focus without the panel being handed it. Entry focus for
 * the panel must not settle back on the control that is now unreachable.
 */
@OptIn(ExperimentalTestApi::class)
class FocusFirstAfterInertTest {

    @Test
    fun entryFocusSkipsTheControlThatWentInert() = runComposeUiTest {
        lateinit var scope: FocusScope
        val opener = FocusRequester()
        var panelOpen by mutableStateOf(false)
        var panelFocused = false

        setContent {
            FocusScopeHost(id = "player", store = FocusScopeStore(), restoreEnabled = false) {
                scope = LocalFocusScope.current
                Column {
                    Column(Modifier.focusInert(panelOpen)) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .focusKey("ctrl.channels", opener)
                                .focusable(),
                        )
                    }
                    if (panelOpen) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .focusKey("ch.abc")
                                .onFocusChanged { if (it.isFocused) panelFocused = true }
                                .focusable(),
                        )
                    }
                }
                // What the fullscreen player does the moment the rail goes up.
                LaunchedEffect(panelOpen) { if (panelOpen) scope.focusFirst() }
            }
        }
        waitForIdle()
        runOnIdle { opener.requestFocus() }
        waitForIdle()
        assertEquals("ctrl.channels", scope.save())

        panelOpen = true
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        assertTrue(panelFocused, "entry focus never reached the panel; it settled on ${scope.save()}")
    }
}
