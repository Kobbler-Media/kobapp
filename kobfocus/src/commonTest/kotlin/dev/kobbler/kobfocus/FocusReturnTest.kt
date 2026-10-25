package dev.kobbler.kobfocus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Focus taken away from the viewer by the app, not by the D-pad, has to come back. */
@OptIn(ExperimentalTestApi::class)
class FocusReturnTest {

    @Composable
    private fun Target(key: String, requester: FocusRequester = FocusRequester()) {
        Box(Modifier.size(20.dp).focusKey(key, requester).focusable())
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }

    @Test
    fun closingAnOverlayReturnsFocusToTheControlThatOpenedIt() = runComposeUiTest {
        lateinit var scope: FocusScope
        val opener = FocusRequester()
        var open by mutableStateOf(false)
        setContent {
            FocusScopeHost(id = "page", store = FocusScopeStore(), restoreEnabled = false) {
                scope = LocalFocusScope.current
                Column {
                    Column(Modifier.focusInert(open)) {
                        Target("first")
                        Target("opener", opener)
                        Target("last")
                    }
                    if (open) {
                        Column(Modifier.focusKeyGroup("overlay")) {
                            Target("a")
                            Target("b")
                        }
                        LaunchedEffect(Unit) { scope.focusFirst() }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { opener.requestFocus() }
        settle()

        open = true
        settle()
        assertEquals("overlay.a", scope.focused)

        open = false
        settle()
        assertEquals("opener", scope.focused)
    }

    @Test
    fun aFocusedControlThatRemovesItselfHandsFocusToTheNextInItsGroup() = runComposeUiTest {
        lateinit var scope: FocusScope
        val want = FocusRequester()
        var wanted by mutableStateOf(false)
        setContent {
            FocusScopeHost(id = "page", store = FocusScopeStore(), restoreEnabled = false) {
                scope = LocalFocusScope.current
                Column {
                    Target("above")
                    Row(Modifier.focusKeyGroup("row")) {
                        Target("play")
                        if (!wanted) Target("want", want)
                        Target("options")
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { want.requestFocus() }
        settle()
        assertEquals("row.want", scope.focused)

        wanted = true
        settle()
        assertEquals("row.options", scope.focused)
    }

    @Test
    fun theLastOfItsGroupFallsBackToTheOneBefore() = runComposeUiTest {
        lateinit var scope: FocusScope
        val want = FocusRequester()
        var wanted by mutableStateOf(false)
        setContent {
            FocusScopeHost(id = "page", store = FocusScopeStore(), restoreEnabled = false) {
                scope = LocalFocusScope.current
                Row(Modifier.focusKeyGroup("row")) {
                    Target("play")
                    if (!wanted) Target("want", want)
                }
            }
        }
        waitForIdle()
        runOnIdle { want.requestFocus() }
        settle()

        wanted = true
        settle()
        assertEquals("row.play", scope.focused)
    }
}
