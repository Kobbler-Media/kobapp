package dev.kobbler.kobfocus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android answers a cleared focus by having the root view ask for it straight back, which
 * Compose serves with an enter search from the root — synchronously, inside the clear.
 * Desktop does not, so the focus manager here does it by hand. Eviction that runs before the layer
 * is actually sealed lets that search walk back in, and the two recurse until the stack
 * gives out: the crash pressing play on an episode row, as the player covered the scaffold.
 */
@OptIn(ExperimentalTestApi::class)
class FocusInertRegrabTest {

    private class Rig {
        lateinit var scope: FocusScope
        var regrabs = 0
        var player by mutableStateOf(false)
        val play = FocusRequester()
    }

    @Composable
    private fun Rig.AndroidLikeRoot(content: @Composable () -> Unit) {
        val owner = LocalFocusManager.current
        val regrabbing = remember(owner) {
            object : FocusManager by owner {
                override fun clearFocus(force: Boolean) {
                    owner.clearFocus(force)
                    if (regrabs++ < MAX_REGRABS) owner.moveFocus(FocusDirection.Next)
                }
            }
        }
        CompositionLocalProvider(LocalFocusManager provides regrabbing) { content() }
    }

    @Composable
    private fun Rig.EpisodeRow() {
        Column(Modifier.focusKeyGroup("episode.1")) {
            Box(Modifier.size(20.dp).focusKey("play", play).focusable())
            Box(Modifier.size(20.dp).focusKey("streamopts").focusable())
        }
    }

    private fun ComposeUiTest.coverAndUncover(rig: Rig) {
        waitForIdle()
        runOnIdle { rig.play.requestFocus() }
        waitForIdle()
        assertEquals("episode.1.play", rig.scope.focused)
        rig.regrabs = 0

        rig.player = true
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertTrue(rig.regrabs < MAX_REGRABS, "eviction and the root's re-grab recursed ${rig.regrabs} times")
        assertEquals(null, rig.scope.focused, "focus stayed on the covered page")

        rig.player = false
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertEquals("episode.1.play", rig.scope.focused)
    }

    @Test
    fun coveringThePageWithThePlayerDoesNotRecurse() = runComposeUiTest {
        val rig = Rig()
        setContent {
            rig.AndroidLikeRoot {
                FocusScopeHost(id = "page", store = FocusScopeStore(), restoreEnabled = false) {
                    rig.scope = LocalFocusScope.current
                    Box(Modifier.focusInert(rig.player)) { rig.EpisodeRow() }
                }
            }
        }
        coverAndUncover(rig)
    }

    @Test
    fun nestedInertLayersDoNotRecurse() = runComposeUiTest {
        val rig = Rig()
        setContent {
            rig.AndroidLikeRoot {
                FocusScopeHost(id = "page", store = FocusScopeStore(), restoreEnabled = false) {
                    rig.scope = LocalFocusScope.current
                    Box(Modifier.focusInert(rig.player)) {
                        Box(Modifier.focusInert(rig.player)) { rig.EpisodeRow() }
                    }
                }
            }
        }
        coverAndUncover(rig)
    }

    private companion object {
        const val MAX_REGRABS = 50
    }
}
