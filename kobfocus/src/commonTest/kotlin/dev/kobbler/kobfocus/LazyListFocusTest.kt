package dev.kobbler.kobfocus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A lazy list composes only what is on screen, and recycles what scrolls away: focus memory has to cope with both. */
@OptIn(ExperimentalTestApi::class)
class LazyListFocusTest {

    @Test
    fun restoringARowALazyListHasNotComposedScrollsItIntoBeing() = runComposeUiTest {
        val store = FocusScopeStore().apply { store("page", "list.row.80") }
        lateinit var scope: FocusScope
        setContent {
            FocusScopeHost(id = "page", store = store) {
                scope = LocalFocusScope.current
                val state = rememberLazyListState()
                LazyColumn(
                    Modifier
                        .size(100.dp)
                        .focusKeyGroup("list")
                        .focusReveal { key ->
                            val row = key.removePrefix("row.").toIntOrNull() ?: return@focusReveal false
                            state.scrollToItem(row)
                            true
                        },
                    state = state,
                ) {
                    items((0 until 100).toList(), key = { it }) { row ->
                        Box(Modifier.size(20.dp).focusKey("row.$row").focusable())
                    }
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals("list.row.80", scope.focused)
    }

    @Test
    fun aKeyOutsideTheRevealersGroupIsLeftAlone() = runComposeUiTest {
        val store = FocusScopeStore().apply { store("page", "other.row.80") }
        var asked = 0
        setContent {
            FocusScopeHost(id = "page", store = store) {
                LazyColumn(Modifier.size(100.dp).focusKeyGroup("list").focusReveal { asked++; false }) {
                    items((0 until 100).toList()) { row ->
                        Box(Modifier.size(20.dp).focusKey("row.$row").focusable())
                    }
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals(0, asked)
    }

    @Test
    fun uncoveringALazyListScrolledPastItsFirstItemHandsFocusBackToTheRowThatHadIt() = runComposeUiTest {
        lateinit var scope: FocusScope
        var covered by mutableStateOf(false)
        val target = FocusRequester()
        setContent {
            FocusScopeHost(id = "page", store = FocusScopeStore(), restoreEnabled = !covered) {
                scope = LocalFocusScope.current
                val state = rememberLazyListState(initialFirstVisibleItemIndex = 80)
                Box(Modifier.focusInert(covered)) {
                    LazyColumn(Modifier.size(100.dp).focusKey("page", entryEligible = false).focusable(), state = state) {
                        items((0 until 100).toList(), key = { it }) { row ->
                            val requester = if (row == 80) target else remember { FocusRequester() }
                            Box(Modifier.size(20.dp).focusKey("row.$row", requester).focusable())
                        }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { target.requestFocus() }
        waitForIdle()
        assertEquals("page.row.80", scope.focused)
        covered = true
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        covered = false
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals("page.row.80", scope.focused)
    }

    @Test
    fun aRowRecycledByTheLazyListRegistersTheRequesterItNowCarries() = runComposeUiTest {
        lateinit var scope: FocusScope
        var restored = false
        setContent {
            FocusScopeHost(id = "page", store = FocusScopeStore(), restoreEnabled = false) {
                scope = LocalFocusScope.current
                val state = rememberLazyListState()
                LazyColumn(Modifier.size(100.dp), state = state) {
                    items((0 until 100).toList(), key = { it }) { row ->
                        Box(Modifier.size(20.dp).focusKeyGroup("row.$row")) {
                            Box(Modifier.size(20.dp).focusKey("play").focusable())
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    while (state.firstVisibleItemIndex < 80) {
                        state.scrollBy(10f)
                        withFrameNanos { }
                    }
                    restored = scope.restore("row.82.play")
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertTrue(restored, "the recycled row's own requester was never registered")
        assertEquals("row.82.play", scope.focused)
    }
}
