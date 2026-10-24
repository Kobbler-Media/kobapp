package dev.kobbler.app.ui.components

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TvFocusDecorTest {

    private fun progressWhenFocused(decorate: Boolean): Float {
        val target = FocusRequester()
        var progress: State<Float>? = null
        runUiTest {
            setContent {
                CompositionLocalProvider(LocalTvFocusDecor provides decorate) {
                    val state = rememberTvFocusState()
                    progress = tvFocusProgress(state.isFocused)
                    Box(
                        Modifier
                            .size(40.dp)
                            .focusRequester(target)
                            .tvClickable(state) {},
                    )
                }
            }
            waitForIdle()
            runOnIdle { target.requestFocus() }
            // The ring eases in over FocusInMillis; let it finish before reading.
            mainClock.advanceTimeBy(500)
            waitForIdle()
        }
        return progress?.value ?: error("progress never composed")
    }

    @Test
    fun `a focused card draws its ring by default`() {
        assertEquals(1f, progressWhenFocused(decorate = true))
    }

    @Test
    fun `a focused card behind the open rail draws nothing`() {
        assertEquals(0f, progressWhenFocused(decorate = false))
    }
}
