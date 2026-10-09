package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.epub.ChapterTtsView
import dev.kobbler.app.ui.reader.epub.TtsWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderStartAtTest {

    // "One two.\n\nThree four." — block 0 and block 2, block 1 emits nothing (an image).
    private val view = ChapterTtsView(
        plainText = "One two.\n\nThree four.",
        words = listOf(
            TtsWord(0, 0, 0, 0..2),
            TtsWord(1, 4, 0, 4..7),
            TtsWord(2, 10, 2, 0..4),
            TtsWord(3, 16, 2, 6..10),
        ),
    )

    @Test
    fun startsAtFirstWordOfTheRequestedBlock() {
        val sent = mutableListOf<String>()
        val idx = startReadingAtBlock(view, blockIndex = 2, startSynth = { sent += it })
        assertEquals(2, idx)
        assertEquals(listOf("Three four."), sent)
    }

    @Test
    fun blockThatEmitsNoWordsFallsForwardToTheNextSpokenBlock() {
        // Long-pressing the image at block 1 must still start reading, not silently no-op.
        val sent = mutableListOf<String>()
        val idx = startReadingAtBlock(view, blockIndex = 1, startSynth = { sent += it })
        assertEquals(2, idx)
        assertEquals(listOf("Three four."), sent)
    }

    @Test
    fun startingAtTheTopSendsTheWholeChapter() {
        val sent = mutableListOf<String>()
        val idx = startReadingAtBlock(view, blockIndex = 0, startSynth = { sent += it })
        assertEquals(0, idx)
        assertEquals(listOf("One two.\n\nThree four."), sent)
    }

    @Test
    fun returnsNullAndSynthesizesNothingWhenNoWordFollows() {
        val sent = mutableListOf<String>()
        assertNull(startReadingAtBlock(view, blockIndex = 3, startSynth = { sent += it }))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun returnsNullForAnEmptyChapter() {
        val sent = mutableListOf<String>()
        val empty = ChapterTtsView("", emptyList())
        assertNull(startReadingAtBlock(empty, blockIndex = 0, startSynth = { sent += it }))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun blockAtFindsTheBlockContainingTheContentY() {
        val bounds = mapOf(0 to 0..99, 1 to 100..299, 2 to 300..399)
        assertEquals(0, blockAt(bounds, 0))
        assertEquals(1, blockAt(bounds, 100))
        assertEquals(1, blockAt(bounds, 299))
        assertEquals(2, blockAt(bounds, 300))
    }

    @Test
    fun blockAtReturnsNullInPaginationWhitespace() {
        // computePaginatedLayout leaves a gap when a block is pushed to the next page.
        val bounds = mapOf(0 to 0..299, 1 to 600..899)
        assertNull(blockAt(bounds, 450))
        assertNull(blockAt(bounds, 1000))
    }

    @Test
    fun blockAtPicksTheLowestIndexWhenBoundsOverlap() {
        // Iteration order of a snapshot state map is not a contract; the answer must be.
        val bounds = mapOf(2 to 0..199, 1 to 100..299, 0 to 150..250)
        assertEquals(0, blockAt(bounds, 200))
    }
}
