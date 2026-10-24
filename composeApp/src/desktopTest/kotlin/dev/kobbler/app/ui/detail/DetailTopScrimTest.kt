package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scrolled, a detail page's bar fades in a shade over the page. On a wide screen it hides the
 * synopsis behind the breadcrumb; a phone's bar is a lone back button, and there the poster
 * must stay visible under it until it leaves the top of the screen.
 */
@OptIn(ExperimentalTestApi::class)
class DetailTopScrimTest {

    private fun redShowingHalfwayDown(formFactor: FormFactor): Float {
        var red = 0f
        runUiTest(width = 200, height = 100) {
            setContent {
                KobappTheme(formFactor = formFactor) {
                    Box(Modifier.fillMaxSize().background(Color.Red)) {
                        Box(Modifier.fillMaxWidth().height(100.dp).detailTopScrim(ScrollState(10_000)))
                    }
                }
            }
            val pixels = onRoot().captureToImage().toPixelMap()
            red = pixels[100, 50].red
        }
        return red
    }

    @Test
    fun `the scrim hides the page on a wide screen but not on a phone`() {
        val phone = redShowingHalfwayDown(FormFactor.Phone)
        assertTrue(phone > 0.35f, "the phone scrim hides the poster: red $phone")
        val desktop = redShowingHalfwayDown(FormFactor.Desktop)
        assertTrue(desktop < 0.2f, "the wide scrim lets the synopsis through: red $desktop")
    }
}
