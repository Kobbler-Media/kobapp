package dev.kobbler.app.ui.reader

import dev.kobbler.app.runUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A slider drag is dozens of value changes. Each chapter jump parses a chapter and each
 * settings save writes six preferences, so neither may run per step of the drag.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderSlidersTest {

    private val slider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun `dragging the chapter slider jumps once, on release`() = runUiTest {
        val jumps = mutableListOf<Float>()
        setContent {
            ReaderBottomBar(
                pages = { 0 to 1 },
                currentChapter = 0,
                totalChapters = 20,
                onSliderChange = { jumps += it },
                theme = ReaderTheme.Dark,
                entryFocus = remember { FocusRequester() },
                onChapters = null,
            )
        }
        onNodeWithText("Ch 1/20", substring = true).performMouseInput { click() }
        onNode(slider).performMouseInput {
            moveTo(center - Offset(width / 3f, 0f))
            press()
            repeat(10) {
                advanceEventTime(16)
                moveBy(Offset(width / 16f, 0f))
            }
            release()
        }
        assertEquals(1, jumps.size, "the chapter changed on every step of the drag: $jumps")
        assertTrue(jumps.single() > 0.5f)
    }

    @Test
    fun `settings save once the value settles, not per change`() = runUiTest {
        mainClock.autoAdvance = false
        var saves = 0
        var value by mutableFloatStateOf(1f)
        setContent { SaveWhenSettled(value) { saves++ } }
        mainClock.advanceTimeBy(1_000)
        assertEquals(0, saves, "opening the reader is not a change")
        repeat(20) {
            value += 0.01f
            mainClock.advanceTimeBy(16)
        }
        assertEquals(0, saves)
        mainClock.advanceTimeBy(1_000)
        assertEquals(1, saves)
    }

    @Test
    fun `a change still pending when the reader closes is saved`() = runUiTest {
        mainClock.autoAdvance = false
        var saves = 0
        var value by mutableFloatStateOf(1f)
        var shown by androidx.compose.runtime.mutableStateOf(true)
        setContent { if (shown) SaveWhenSettled(value) { saves++ } }
        mainClock.advanceTimeBy(100)
        value = 2f
        mainClock.advanceTimeBy(16)
        shown = false
        mainClock.advanceTimeBy(16)
        assertEquals(1, saves)
    }
}
