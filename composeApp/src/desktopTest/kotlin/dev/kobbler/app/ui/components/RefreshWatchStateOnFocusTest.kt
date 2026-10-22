package dev.kobbler.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalTestApi::class)
class RefreshWatchStateOnFocusTest {

    // The refetch after a real absence is covered by WatchHistorySyncTest.
    private fun refetchesAfter(awaySeconds: Int): Boolean {
        val clock = TestTimeSource()
        var focused by mutableStateOf(true)
        var refetched = false
        runComposeUiTest {
            setContent { RefreshWatchStateOnFocus(focused, timeSource = clock) }
            waitForIdle()
            val before = DataCache.versionOf("home")
            focused = false
            waitForIdle()
            clock += awaySeconds.seconds
            focused = true
            waitForIdle()
            refetched = DataCache.versionOf("home") != before
        }
        return refetched
    }

    @Test
    fun `a glance away does not refetch watch state`() {
        assertEquals(false, refetchesAfter(awaySeconds = 2), "an alt-tab refetched every cached page")
    }
}
