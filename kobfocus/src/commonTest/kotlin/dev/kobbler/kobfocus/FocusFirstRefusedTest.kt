package dev.kobbler.kobfocus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A touchscreen refuses focus to every clickable, so entry focus fails on each target a
 * page registers. A frame spent per refusal turned a page of episode rows into minutes of
 * the UI thread waking every vsync to draw nothing.
 */
@OptIn(ExperimentalTestApi::class)
class FocusFirstRefusedTest {

    @Test
    fun aPageThatRefusesFocusGivesUpWithinAFewFrames() = runComposeUiTest {
        var finished = false
        // Driven by hand, or the clock runs as many frames as it takes to go idle.
        mainClock.autoAdvance = false
        setContent {
            FocusScopeHost(id = "page", store = FocusScopeStore(), restoreEnabled = false) {
                val scope = LocalFocusScope.current
                Column {
                    repeat(200) { i ->
                        Box(Modifier.size(1.dp).focusKey("row.$i").focusProperties { canFocus = false }.focusable())
                    }
                }
                LaunchedEffect(Unit) {
                    scope.focusFirst()
                    finished = true
                }
            }
        }
        mainClock.advanceTimeBy(2_000)
        assertTrue(finished, "entry focus was still trying after 2s of frames")
    }
}
