package dev.kobbler.kobfocus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Chrome composed straight away and content that registers a few frames later — a lazy
 * rail registers during measure — must still hand entry focus to the content.
 */
@OptIn(ExperimentalTestApi::class)
class FocusFirstLateContentTest {

    @Test
    fun chromeThatRegistersFirstDoesNotTakeEntryFocusFromLateContent() {
        for (preferred in listOf(true, false)) runComposeUiTest {
            lateinit var scope: FocusScope
            setContent {
                FocusScopeHost(id = "page", store = FocusScopeStore()) {
                    scope = LocalFocusScope.current
                    var contentShown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        repeat(3) { withFrameNanos { } }
                        contentShown = true
                    }
                    Column {
                        Box(Modifier.size(10.dp).focusKey("chrome", entryEligible = false).focusable())
                        if (contentShown) {
                            Box(Modifier.size(10.dp).focusKey("content", preferredEntry = preferred).focusable())
                        }
                    }
                }
            }
            waitForIdle()
            assertEquals("content", scope.save(), "preferredEntry=$preferred: entry focus went to chrome")
        }
    }
}
