package dev.kobbler.kobfocus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class FocusContainmentTest {

    /**
     * Drives D-pad traversal from an overlay into a lower layer and reports whether it
     * got in. Identical either way apart from [inert], so it isolates focusInert.
     */
    private fun ComposeUiTest.focusEscapesInto(inert: Boolean): Boolean {
        var buriedFocused = false
        var overlayFocused = false
        val overlay = FocusRequester()
        lateinit var focusManager: FocusManager

        setContent {
            focusManager = LocalFocusManager.current
            Column {
                Box(
                    Modifier
                        .size(20.dp)
                        .focusRequester(overlay)
                        .onFocusChanged { if (it.isFocused) overlayFocused = true }
                        .focusable(),
                )
                // The layer underneath: composed, laid out, painted over in the real app.
                Column(Modifier.focusInert(inert)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .onFocusChanged { if (it.isFocused) buriedFocused = true }
                                .focusable(),
                        )
                    }
                }
            }
        }

        // Focus search is geometric, so it needs a laid-out tree — drive it from here
        // rather than a LaunchedEffect that runs before placement.
        waitForIdle()
        runOnIdle { overlay.requestFocus() }
        waitForIdle()
        assertTrue(overlayFocused, "overlay should have taken focus first")

        listOf(FocusDirection.Down, FocusDirection.Next, FocusDirection.Down).forEach { dir ->
            runOnIdle { focusManager.moveFocus(dir) }
            waitForIdle()
        }
        return buriedFocused
    }

    /**
     * The stacked-layer bug: a screen painted over another leaves the one underneath in
     * the focus graph, so D-pad traversal walks out of the overlay into invisible
     * content. Marking the lower layer inert must stop that.
     */
    @Test
    fun inertLayerDoesNotReceiveFocus() = runComposeUiTest {
        assertFalse(focusEscapesInto(inert = true), "focus escaped into the inert layer")
    }

    @Test
    fun activeLayerStillReceivesFocus() = runComposeUiTest {
        assertTrue(focusEscapesInto(inert = false), "a non-inert layer must stay reachable")
    }

    /** Keys compose from the modifier tree, so call sites never spell out ancestors. */
    @Test
    fun focusKeysAreHierarchical() = runComposeUiTest {
        lateinit var scope: FocusScope
        val store = FocusScopeStore()
        val inner = FocusRequester()

        setContent {
            FocusScopeHost(id = "home", store = store) {
                scope = LocalFocusScope.current
                Box(Modifier.focusKeyGroup("rail")) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .focusKey("item3", inner)
                            .focusable(),
                    )
                }
            }
            LaunchedEffect(Unit) { inner.requestFocus() }
        }
        waitForIdle()

        assertEquals("rail.item3", scope.save())
    }

    /** Focus memory has to survive the screen being torn down and rebuilt. */
    @Test
    fun focusIsRestoredAcrossRecomposition() = runComposeUiTest {
        val store = FocusScopeStore()
        val second = FocusRequester()
        val showDetail = mutableStateOf(false)
        var restoredToSecond = false

        setContent {
            if (!showDetail.value) {
                FocusScopeHost(id = "tab", store = store) {
                    Column {
                        Box(Modifier.size(10.dp).focusKey("a").focusable())
                        Box(
                            Modifier
                                .size(10.dp)
                                .focusKey("b", second)
                                .onFocusChanged { if (it.isFocused) restoredToSecond = true }
                                .focusable(),
                        )
                    }
                }
            }
        }

        second.requestFocus()
        waitForIdle()
        assertEquals("b", store.let { showDetail.value = true; waitForIdle(); it.recall("tab") })

        restoredToSecond = false
        showDetail.value = false
        waitForIdle()
        assertTrue(restoredToSecond, "focus should return to the element that had it")
    }

    /**
     * requestFocus() neither returns a result nor throws when the target cannot take
     * focus — a disabled clickable being the everyday case. Counting that as success
     * strands entry focus on an element that never gets it, leaving the D-pad dead.
     */
    @Test
    fun focusFirstSkipsTargetsThatCannotTakeFocus() = runComposeUiTest {
        val store = FocusScopeStore()
        var reachedEnabled = false

        setContent {
            FocusScopeHost(id = "screen", store = store) {
                Column {
                    Box(
                        Modifier
                            .size(20.dp)
                            .focusKey("disabled")
                            .clickable(enabled = false) {},
                    )
                    Box(
                        Modifier
                            .size(20.dp)
                            .focusKey("enabled")
                            .onFocusChanged { if (it.isFocused) reachedEnabled = true }
                            .focusable(),
                    )
                }
            }
        }
        waitForIdle()

        assertTrue(reachedEnabled, "focusFirst settled on a target that never took focus")
    }
}
