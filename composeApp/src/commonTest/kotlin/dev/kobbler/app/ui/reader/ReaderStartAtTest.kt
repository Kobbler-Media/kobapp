package dev.kobbler.app.ui.reader

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
        val idx = startReadingAtBlock(view, blockIndex = 2, offset = 0, startSynth = { sent += it })
        assertEquals(2, idx)
        assertEquals(listOf("Three four."), sent)
    }

    @Test
    fun blockThatEmitsNoWordsFallsForwardToTheNextSpokenBlock() {
        // Long-pressing the image at block 1 must still start reading, not silently no-op.
        val sent = mutableListOf<String>()
        val idx = startReadingAtBlock(view, blockIndex = 1, offset = 0, startSynth = { sent += it })
        assertEquals(2, idx)
        assertEquals(listOf("Three four."), sent)
    }

    @Test
    fun startingAtTheTopSendsTheWholeChapter() {
        val sent = mutableListOf<String>()
        val idx = startReadingAtBlock(view, blockIndex = 0, offset = 0, startSynth = { sent += it })
        assertEquals(0, idx)
        assertEquals(listOf("One two.\n\nThree four."), sent)
    }

    @Test
    fun returnsNullAndSynthesizesNothingWhenNoWordFollows() {
        val sent = mutableListOf<String>()
        assertNull(startReadingAtBlock(view, blockIndex = 3, offset = 0, startSynth = { sent += it }))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun returnsNullForAnEmptyChapter() {
        val sent = mutableListOf<String>()
        val empty = ChapterTtsView("", emptyList())
        assertNull(startReadingAtBlock(empty, blockIndex = 0, offset = 0, startSynth = { sent += it }))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aPressInsideABlockStartsAtTheWordUnderIt() {
        data class Case(val offset: Int, val word: Int, val sent: String)
        for (case in listOf(
            Case(0, 2, "Three four."),
            Case(3, 2, "Three four."),
            Case(5, 3, "four."),
            Case(8, 3, "four."),
        )) {
            val sent = mutableListOf<String>()
            assertEquals(case.word, startReadingAtBlock(view, blockIndex = 2, offset = case.offset, startSynth = { sent += it }), "$case")
            assertEquals(listOf(case.sent), sent, "$case")
        }
    }

    @Test
    fun aPressPastTheLastWordOfABlockFallsToTheNextBlock() {
        val sent = mutableListOf<String>()
        assertEquals(2, startReadingAtBlock(view, blockIndex = 0, offset = 99, startSynth = { sent += it }))
        assertEquals(listOf("Three four."), sent)
    }
}
