package dev.kobbler.app.ui.components

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.MORPH_SIZE_MS
import dev.kobbler.app.ui.player.rememberPlayerCoversScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A screen nobody can see must not repaint the window: on a variable-refresh panel every
 * repaint is a vote for 60Hz, and 24fps film shown at 60 judders.
 */
@OptIn(ExperimentalTestApi::class)
class CoveredByPlayerTest {

    private fun shimmerDraws(covered: Boolean): Int {
        var draws = 0
        runUiTest {
            mainClock.autoAdvance = false
            setContent {
                KobappTheme {
                    CompositionLocalProvider(LocalCoveredByPlayer provides covered) {
                        Box(Modifier.drawWithContent { draws++; drawContent() }) {
                            ShimmerHost { ShimmerBox(Modifier.size(40.dp)) }
                        }
                    }
                }
            }
            mainClock.advanceTimeByFrame()
            val settled = draws
            mainClock.advanceTimeBy(2_000)
            draws -= settled
        }
        return draws
    }

    @Test
    fun `a skeleton shimmers while it can be seen`() {
        assertTrue(shimmerDraws(covered = false) > 10)
    }

    @Test
    fun `a skeleton under the player holds still`() {
        assertEquals(0, shimmerDraws(covered = true))
    }

    @Test
    fun `covered content is not drawn, and is drawn again the moment it is uncovered`() = runUiTest {
        var covered by mutableStateOf(false)
        var tick by mutableStateOf(0)
        var draws = 0
        setContent {
            Box(Modifier.size(40.dp).hiddenWhenCovered(covered)) {
                Box(Modifier.size(40.dp).drawBehind { tick; draws++ })
            }
        }
        waitForIdle()
        assertTrue(draws > 0)
        covered = true
        waitForIdle()
        val whileCovered = draws
        repeat(5) {
            tick++
            waitForIdle()
        }
        assertEquals(whileCovered, draws, "covered content was still being painted")
        covered = false
        waitForIdle()
        assertTrue(draws > whileCovered, "uncovered content was not redrawn")
    }

    @Test
    fun `the player covers the screen only once it has finished arriving`() = runUiTest {
        mainClock.autoAdvance = false
        var fills by mutableStateOf(false)
        var covers = false
        setContent { covers = rememberPlayerCoversScreen(fills) }
        mainClock.advanceTimeByFrame()
        assertFalse(covers)
        fills = true
        mainClock.advanceTimeBy(MORPH_SIZE_MS / 2L)
        assertFalse(covers, "the screen behind stopped drawing while the player was still growing over it")
        mainClock.advanceTimeBy(MORPH_SIZE_MS.toLong())
        assertTrue(covers)
        fills = false
        mainClock.advanceTimeByFrame()
        assertFalse(covers, "the screen behind stayed blank as the player shrank away from it")
    }
}
