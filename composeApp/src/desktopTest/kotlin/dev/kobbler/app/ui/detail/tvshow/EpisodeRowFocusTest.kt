package dev.kobbler.app.ui.detail.tvshow

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two action buttons on an episode row have to be reachable with a remote.
 * Being `clickable` — and so nominally focusable — is not enough on its own: a
 * D-pad press has to actually land on them.
 */
@OptIn(ExperimentalTestApi::class)
class EpisodeRowFocusTest {

    private fun focusAfterRights(
        rights: Int,
        content: @Composable (FocusRequester, (String) -> Unit) -> Unit,
    ): String {
        var focused = "none"
        val entry = FocusRequester()
        var manager: androidx.compose.ui.focus.FocusManager? = null
        runUiTest {
            setContent {
                manager = LocalFocusManager.current
                content(entry) { focused = it }
            }
            waitForIdle()
            runOnIdle { entry.requestFocus() }
            waitForIdle()
            repeat(rights) {
                runOnIdle { manager!!.moveFocus(FocusDirection.Right) }
                waitForIdle()
            }
        }
        return focused
    }

    /** The shape EpisodeRow uses today: one focusable row with focusable icons nested inside. */
    @Composable
    private fun NestedRow(entry: FocusRequester, onFocus: (String) -> Unit) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(entry)
                    .onFocusChanged { if (it.isFocused) onFocus("row") }
                    .clickable { },
            ) {
                Box(Modifier.width(300.dp).size(60.dp))
                Box(
                    Modifier
                        .size(36.dp)
                        .onFocusChanged { if (it.isFocused) onFocus("tune") }
                        .clickable { },
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .onFocusChanged { if (it.isFocused) onFocus("watched") }
                        .clickable { },
                )
            }
        }
    }

    /**
     * The fix: the container is a non-focusable focus group and the row body is its
     * own leaf, so the three targets are siblings the beam search can step between.
     */
    @Composable
    private fun SplitRow(entry: FocusRequester, onFocus: (String) -> Unit) {
        Column {
            Row(Modifier.fillMaxWidth().focusGroup()) {
                Box(
                    Modifier
                        .width(300.dp)
                        .size(60.dp)
                        .focusRequester(entry)
                        .onFocusChanged { if (it.isFocused) onFocus("row") }
                        .clickable { },
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .onFocusChanged { if (it.isFocused) onFocus("tune") }
                        .clickable { },
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .onFocusChanged { if (it.isFocused) onFocus("watched") }
                        .clickable { },
                )
            }
        }
    }

    @Test
    fun nestingTheActionsInsideTheFocusableRowStrandsThem() {
        // Documents why the split below is necessary: a focusable ancestor swallows
        // the press, so `clickable` alone leaves the icons unreachable by remote.
        assertEquals("row", focusAfterRights(1, ::NestedRow))
    }

    @Test
    fun rightFromTheRowReachesTheFirstAction() {
        assertEquals("tune", focusAfterRights(1, ::SplitRow))
    }

    @Test
    fun rightAgainReachesTheSecondAction() {
        assertEquals("watched", focusAfterRights(2, ::SplitRow))
    }
}
