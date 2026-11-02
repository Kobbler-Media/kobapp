package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.Season
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.gutterFor
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SeasonTabsEdgeTest {

    private val seasons = (1..12).map { Season(number = it, episodes = emptyList()) }

    private fun strip(formFactor: FormFactor, width: Int, selected: Int, test: DesktopComposeUiTest.() -> Unit) =
        runUiTest(width = width, height = 300) {
            setContent {
                KobappTheme(formFactor = formFactor) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = gutterFor(formFactor))) {
                        SeasonTabs(
                            seasons, selected, KobappTheme.colors.accTv, formFactor, {},
                            menu = { Text("Mark season watched") },
                        )
                    }
                }
            }
            waitForIdle()
            test()
        }

    private fun DesktopComposeUiTest.bounds(matcher: androidx.compose.ui.test.SemanticsMatcher): Rect =
        onAllNodes(matcher).fetchSemanticsNodes().first().boundsInRoot

    @Test
    fun `the strip scrolls out to both edges of the screen`() {
        for ((formFactor, width) in listOf(FormFactor.TV to 960, FormFactor.Phone to 400, FormFactor.Desktop to 1280)) {
            strip(formFactor, width, selected = 0) {
                val row = bounds(hasScrollAction() and hasAnyDescendant(hasText("Season 1")))
                assertTrue(row.left <= 0.5f, "$formFactor: the strip starts at ${row.left}")
                assertTrue(row.right >= width - 0.5f, "$formFactor: the strip stops at ${row.right} of $width")
            }
        }
    }

    @Test
    fun `the selected season is clear of the season menu`() {
        val cases = listOf(FormFactor.Phone to 400, FormFactor.Desktop to 900).flatMap { (f, w) -> listOf(3, 6, 11).map { Triple(f, w, it) } }
        for ((formFactor, width, selected) in cases) {
            strip(formFactor, width, selected) {
                val name = "Season ${selected + 1}"
                waitUntil(timeoutMillis = UiTimeoutMs) {
                    val label = bounds(hasText(name))
                    val menu = bounds(hasContentDescription("$name options", substring = true) or hasText("$name options"))
                    label.left >= 0f && label.right <= menu.left
                }
            }
        }
    }

    @Test
    fun `the season menu sits level with the labels and the tabs sit on the rule`() {
        for ((formFactor, width) in listOf(FormFactor.Phone to 400, FormFactor.Desktop to 1280)) {
            strip(formFactor, width, selected = 0) {
                val label = bounds(hasText("Season 1"))
                val menu = bounds(hasContentDescription("Season 1 options", substring = true) or hasText("Season 1 options"))
                val strip = bounds(hasScrollAction() and hasAnyDescendant(hasText("Season 1")))
                val whole = onRoot().fetchSemanticsNode().children.first().boundsInRoot
                val offCentre = kotlin.math.abs(menu.center.y - label.center.y)
                val gap = whole.bottom - strip.bottom
                assertTrue(
                    offCentre <= 2f && gap <= 1f,
                    "$formFactor: the menu's centre is $offCentre px off the label's, $gap px between the tabs and the rule under them",
                )
            }
        }
    }
}
