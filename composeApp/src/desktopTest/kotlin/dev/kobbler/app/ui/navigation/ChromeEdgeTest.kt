package dev.kobbler.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Chrome that runs to the screen's edges divides itself from the page on the one side that
 * faces it. A full outline draws hairlines against the bezel too, and on a TV the rail reads
 * as a box floating on the screen.
 */
@OptIn(ExperimentalTestApi::class)
class ChromeEdgeTest {

    private fun capture(width: Int, height: Int, content: @Composable () -> Unit): PixelMap {
        lateinit var pixels: PixelMap
        runUiTest(width = width, height = height) {
            setContent {
                KobappTheme(formFactor = FormFactor.TV) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
                }
            }
            pixels = onRoot().captureToImage().toPixelMap()
        }
        return pixels
    }

    @Test
    fun `the phone bottom bar has a top hairline and nothing on its sides`() {
        val pixels = capture(300, 200) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                KobPhoneBottomBar(items = emptyList(), active = Section.entries.first())
            }
        }
        val bottom = pixels.height - 4
        assertEquals(pixels[150, bottom], pixels[0, bottom], "left edge")
        assertEquals(pixels[150, bottom], pixels[pixels.width - 1, bottom], "right edge")
        assertEquals(pixels[150, bottom], pixels[150, pixels.height - 1], "bottom edge")
        val top = (0 until pixels.height).first { pixels[150, it] != Color.Black }
        assertNotEquals(pixels[150, bottom], pixels[150, top], "top hairline")
    }

    @Test
    fun `the side rail has an inner hairline and nothing against the bezel`() {
        val pixels = capture(400, 300) {
            KobSidebar(items = emptyList(), active = Section.entries.first(), expanded = false, tvMode = true)
        }
        val inside = 10
        val mid = pixels.height / 2
        assertEquals(pixels[inside, mid], pixels[0, mid], "left edge")
        assertEquals(pixels[inside, mid], pixels[inside, 0], "top edge")
        assertEquals(pixels[inside, mid], pixels[inside, pixels.height - 1], "bottom edge")
        val railEnd = (0 until pixels.width).last { pixels[it, mid] != Color.Black }
        assertNotEquals(pixels[inside, mid], pixels[railEnd, mid], "inner hairline")
    }
}
