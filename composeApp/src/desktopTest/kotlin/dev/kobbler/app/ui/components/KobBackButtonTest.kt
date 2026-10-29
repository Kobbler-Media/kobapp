package dev.kobbler.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class KobBackButtonTest {

    private val entryPoints: List<Pair<String, @Composable (FormFactor) -> Unit>> = listOf(
        "pinned" to { ff -> KobBackButtonPinned(onBack = {}, formFactor = ff) },
        "header" to { ff -> KobBackHeader(onBack = {}, formFactor = ff, pageGutter = 18.dp) { Text("Title") } },
    )

    @Test
    fun `the arrow keeps its size inside a thumb-sized target`() {
        for ((_, content) in entryPoints) runUiTest {
            setContent { KobappTheme(formFactor = FormFactor.Phone) { content(FormFactor.Phone) } }
            onNodeWithContentDescription("Back", useUnmergedTree = true)
                .assertWidthIsEqualTo(22.dp)
                .assertHeightIsEqualTo(22.dp)
            onNodeWithContentDescription("Back")
                .assertWidthIsAtLeast(BackButtonSize)
                .assertHeightIsAtLeast(BackButtonSize)
        }
    }

    @Test
    fun `tv shows no back button`() {
        for ((name, content) in entryPoints) runUiTest {
            setContent { KobappTheme(formFactor = FormFactor.TV) { content(FormFactor.TV) } }
            assertEquals(0, onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size, name)
        }
    }

    @Test
    fun `the header title centres on the back arrow with or without a trailing control`() {
        val variants: List<Pair<String, (@Composable () -> Unit)?>> = listOf(
            "bare" to null,
            "with filter" to { SegmentedFilter(options = listOf("All", "Library"), selectedIndex = 0, accent = Color.Red, onSelected = {}) },
        )
        for ((name, trailing) in variants) runUiTest {
            setContent {
                KobappTheme(formFactor = FormFactor.Phone) {
                    KobBackHeader(onBack = {}, formFactor = FormFactor.Phone, pageGutter = 18.dp) {
                        BrowseHeader(title = "Title", accent = Color.Red, compact = true, trailing = trailing)
                    }
                }
            }
            val back = onNodeWithContentDescription("Back").getBoundsInRoot()
            val title = onNodeWithText("Title").getBoundsInRoot()
            val offset = ((title.top + title.bottom) / 2 - (back.top + back.bottom) / 2).value
            assertTrue(kotlin.math.abs(offset) <= 1f, "$name: title centre is ${offset}dp off the arrow's")
        }
    }
}
