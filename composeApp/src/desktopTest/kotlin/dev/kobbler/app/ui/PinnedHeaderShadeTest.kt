package dev.kobbler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pinned header shades in once the page runs under it — whether the viewer scrolled it
 * there or the page opened part way down, as the calendar does on today.
 */
@OptIn(ExperimentalTestApi::class)
class PinnedHeaderShadeTest {

    // No top padding: the page's picture sits under the header at rest, the case the shade
    // must stay out of.
    private fun shaded(startAt: Int, gesture: DesktopComposeUiTest.() -> Unit = {}): Boolean {
        var redUnderHeader = 0f
        runUiTest(width = 300, height = 400) {
            lateinit var state: LazyListState
            setContent {
                KobappTheme(formFactor = FormFactor.Phone) {
                    CompositionLocalProvider(LocalTopChromeOverlap provides 1.dp) {
                        PinnedHeader(header = { Box(Modifier.fillMaxWidth().height(60.dp)) }) {
                            state = rememberLazyListState()
                            LaunchedEffect(Unit) { if (startAt > 0) state.scrollToItem(startAt) }
                            LazyColumn(
                                state = state,
                                modifier = Modifier.scrollsUnderTopChrome(state).fillMaxSize().testTag("list"),
                            ) {
                                items(100) { Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Red)) }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            gesture()
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            redUnderHeader = onRoot().captureToImage().toPixelMap()[150, 30].red
        }
        return redUnderHeader < 0.5f
    }

    @Test
    fun `the shade follows whether anything is under the header`() {
        val cases = mapOf(
            "at the top" to shaded(startAt = 0),
            "opened part way down" to shaded(startAt = 20),
            "scrolled back from where it opened" to shaded(startAt = 20) {
                onNodeWithTag("list").performTouchInput { swipeDown(startY = top + 100f, endY = top + 300f) }
            },
        )
        assertEquals(
            mapOf("at the top" to false, "opened part way down" to true, "scrolled back from where it opened" to true),
            cases,
        )
    }
}
