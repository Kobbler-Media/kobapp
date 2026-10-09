package dev.kobbler.app.ui.reader

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A read aloud runs until it is stopped or the book ends.
 *
 * Advancing used to be one chapter and a check: if that chapter had nothing to say —
 * a plate, a divider, a title page — the read simply stopped, silently, in the middle
 * of the book. Backgrounded, that is a reader that quietly gives up.
 */
class ReaderTtsAdvanceTest {

    private fun chapters(vararg text: String): suspend (Int) -> String = { text[it] }

    @Test
    fun `a chapter with text is read without skipping anything`() = runTest {
        val skipped = mutableListOf<Int>()
        val next = nextSpokenChapter(
            from = 1,
            lastIndex = 2,
            textAt = chapters("one", "two", "three"),
            onSkip = { skipped += it },
        )
        assertEquals(SpokenChapter(1, "two"), next)
        assertEquals(emptyList(), skipped)
    }

    /** The bug as the listener meets it: the read stopping on an image plate. */
    @Test
    fun `a blank chapter is walked past rather than ending the read`() = runTest {
        val next = nextSpokenChapter(
            from = 1,
            lastIndex = 2,
            textAt = chapters("one", "   ", "three"),
            onSkip = {},
        )
        assertEquals(SpokenChapter(2, "three"), next)
    }

    @Test
    fun `a whole plate section is walked past in one go`() = runTest {
        val skipped = mutableListOf<Int>()
        val next = nextSpokenChapter(
            from = 1,
            lastIndex = 5,
            textAt = chapters("one", "", "", "\n\n", "", "six"),
            onSkip = { skipped += it },
        )
        assertEquals(SpokenChapter(5, "six"), next)
        assertEquals(listOf(1, 2, 3, 4), skipped, "the reader has to walk the pages it passes")
    }

    /** Nothing but plates to the end of the book: the read is genuinely over. */
    @Test
    fun `blank chapters all the way to the end finish the book`() = runTest {
        val skipped = mutableListOf<Int>()
        assertNull(
            nextSpokenChapter(
                from = 1,
                lastIndex = 3,
                textAt = chapters("one", "", "", ""),
                onSkip = { skipped += it },
            ),
        )
        assertEquals(listOf(1, 2, 3), skipped)
    }

    @Test
    fun `starting past the last chapter has nothing to read`() = runTest {
        assertNull(nextSpokenChapter(from = 3, lastIndex = 2, textAt = chapters("a", "b", "c"), onSkip = {}))
    }

    /**
     * Pressing play while sitting on a plate reads from there on, rather than being a
     * button that does nothing.
     */
    @Test
    fun `a read can start on a blank chapter`() = runTest {
        val next = nextSpokenChapter(
            from = 0,
            lastIndex = 2,
            textAt = chapters("", "", "three"),
            onSkip = {},
        )
        assertEquals(SpokenChapter(2, "three"), next)
    }
}
