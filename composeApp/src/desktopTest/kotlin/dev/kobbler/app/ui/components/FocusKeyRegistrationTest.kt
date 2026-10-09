package dev.kobbler.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.kobbler.kobfocus.FocusScope
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScope
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FocusKeyRegistrationTest {

    private fun ComposeUiTest.keysAfterTabbing(steps: Int, content: @Composable () -> Unit): List<String?> {
        lateinit var scope: FocusScope
        lateinit var focusManager: FocusManager
        setContent {
            FocusScopeHost(id = "test", store = FocusScopeStore()) {
                scope = LocalFocusScope.current
                focusManager = LocalFocusManager.current
                content()
            }
        }
        waitForIdle()
        return List(steps) {
            runOnIdle { focusManager.moveFocus(FocusDirection.Next) }
            waitForIdle()
            scope.save()
        }
    }

    @Test
    fun segmentedFilterKeysEachOption() = runComposeUiTest {
        val keys = keysAfterTabbing(2) {
            SegmentedFilter(
                options = listOf("All", "In Progress"),
                selectedIndex = 0,
                accent = Color.White,
                onSelected = {},
            )
        }
        assertEquals(setOf("seg.all", "seg.in-progress"), keys.toSet())
    }

    @Test
    fun sectionHeadingKeysSeeAll() = runComposeUiTest {
        val keys = keysAfterTabbing(1) {
            SectionHeading(
                title = "Continue",
                accent = Color.White,
                modifier = Modifier,
                seeAllLabel = "see all",
                onSeeAll = {},
            )
        }
        assertEquals(listOf("see-all"), keys)
    }
}
