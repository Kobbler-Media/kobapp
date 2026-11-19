package dev.kobbler.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import dev.kobbler.app.runUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A focused Material control is tinted only while the viewer steers by keys. */
@OptIn(ExperimentalTestApi::class)
class FocusRippleTest {

    private fun focusedButtonEdgePixel(keyed: Boolean): Color {
        var pixel = Color.Unspecified
        runUiTest(width = 100, height = 100) {
            setContent {
                val focus = remember { FocusRequester() }
                CompositionLocalProvider(*focusRippleLocals(keyed)) {
                    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                        IconButton(onClick = {}, modifier = Modifier.focusRequester(focus)) {}
                    }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            val pixels = onRoot().captureToImage().toPixelMap()
            pixel = pixels[pixels.width / 2 + 12, pixels.height / 2]
        }
        return pixel
    }

    @Test
    fun `focus is tinted when steering by keys`() {
        assertNotEquals(Color.White, focusedButtonEdgePixel(keyed = true))
    }

    @Test
    fun `focus left on a tapped control is not tinted`() {
        assertEquals(Color.White, focusedButtonEdgePixel(keyed = false))
    }
}
