package dev.kobbler.app.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.SingletonImageLoader
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every page backdrop fades between pictures, including ones already in memory. */
@OptIn(ExperimentalTestApi::class)
class SpotlightCrossfadeTest {

    private val a = Spotlight(backdropUrl = "http://kobbler.test/a.jpg", title = "A")
    private val b = Spotlight(backdropUrl = "http://kobbler.test/b.jpg", title = "B")
    private val poster = Spotlight(posterUrl = "http://kobbler.test/p.jpg", title = "P")

    private fun backdrop(test: DesktopComposeUiTest.(spot: MutableState<Spotlight?>) -> Unit) =
        runDesktopComposeUiTest(width = 800, height = 450) {
            SingletonImageLoader.setUnsafe(cachedArtLoader())
            try {
                val spot = mutableStateOf<Spotlight?>(a)
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        SpotlightBackdrop(spotlight = { spot.value }, accent = Color.Red, dim = { 0f })
                    }
                }
                waitForIdle()
                mainClock.autoAdvance = false
                test(spot)
            } finally {
                SingletonImageLoader.reset()
            }
        }

    private fun DesktopComposeUiTest.layers() = onAllNodesWithTag(BackdropArtTag).fetchSemanticsNodes().size

    @Test
    fun `a new picture fades in over the old one and the old one is then dropped`() =
        listOf(b, poster).forEach { next ->
            backdrop { spot ->
                assertEquals(1, layers())
                spot.value = next
                mainClock.advanceTimeBy(150)
                assertEquals(2, layers(), "mid-fade to ${next.title}")
                mainClock.advanceTimeBy(1_000)
                assertEquals(1, layers(), "after the fade to ${next.title}")
            }
        }

    @Test
    fun `going back mid-fade settles on the picture asked for last`() = backdrop { spot ->
        spot.value = b
        mainClock.advanceTimeBy(100)
        spot.value = a
        mainClock.advanceTimeBy(100)
        spot.value = b
        mainClock.advanceTimeBy(1_000)
        assertEquals(1, layers())
    }
}
