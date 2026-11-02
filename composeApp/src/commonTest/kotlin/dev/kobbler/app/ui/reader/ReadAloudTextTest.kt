package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.FixedPageDto
import dev.kobbler.app.ui.reader.pack.Fragment
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.Span
import dev.kobbler.app.ui.reader.pack.Table
import dev.kobbler.app.ui.reader.pack.TableCell
import dev.kobbler.app.ui.reader.pack.TableRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadAloudTextTest {

    private fun para(vararg spans: String) = Block(kind = BlockKind.Text, spans = spans.map { Span(it) })
    private fun heading(text: String) = Block(kind = BlockKind.Heading, level = 1, spans = listOf(Span(text)))
    private fun table(vararg cells: String) =
        Block(kind = BlockKind.Table, table = Table(listOf(TableRow(cells = cells.map { TableCell(listOf(para(it))) }))))
    private fun fixedPage(vararg fragments: String) = Block(
        kind = BlockKind.FixedPage,
        fixed = FixedPageDto(ImageRef("art.jpg"), 600f, 900f, fragments.map { Fragment(spans = listOf(Span(it))) }),
    )

    /**
     * A fixed-layout book puts every VISUAL LINE in its own block, so one sentence
     * arrives as several and a pronoun arrives alone. The server rejoins those soft
     * wraps before synthesis — but only while they still read as wraps. Ending each
     * fragment with a fabricated full stop makes every one of them a sentence, and the
     * engine performs them one at a time, which is what makes the narration crawl.
     */
    @Test
    fun wrappedVisualLinesAreNotTurnedIntoSentences() {
        val view = buildChapterTtsView(listOf(para("I"), para("watch as Scrimshaw backs farther"), para("away, waving the pool cue.")))
        assertEquals("I\n\nwatch as Scrimshaw backs farther\n\naway, waving the pool cue.", view.plainText)
        assertEquals(11, view.words.size)
    }

    /**
     * A no-break space is a glyph to Kotlin's tokenizer and a word separator to the
     * server's, which splits the token it sits in and shifts every word index after it —
     * so the highlight lands one word behind for the rest of the chapter.
     */
    @Test
    fun hardSpacesSeparateWordsTheSameWayTheServerSeesThem() {
        val view = buildChapterTtsView(listOf(para("M. Dupont said so.")))
        assertEquals("M. Dupont said so.", view.plainText)
        assertEquals(listOf(0, 3, 10, 15), view.words.map { it.chapterCharOffset })
        assertEquals(listOf(0..1, 3..8, 10..13, 15..17), view.words.map { it.highlightCharRange })
    }

    @Test
    fun tableAndFixedPageWordsCountButCarryNoHighlight() {
        val blocks = listOf(para("One two three."), table("alpha beta", "gamma"), fixedPage("delta", "epsilon"), para("four five."))
        val view = buildChapterTtsView(blocks)
        assertEquals("One two three.\n\nalpha beta\n\ngamma\n\ndelta\n\nepsilon\n\nfour five.", view.plainText)
        // Word 8 is "four": the table and the page contributed words 3..7 to the payload.
        val word = view.words.first { it.globalIndex == 8 }
        assertEquals(3, word.blockIndex)
        assertEquals(0..3, word.highlightCharRange)
        assertTrue(view.words.filter { it.blockIndex in 1..2 }.all { it.highlightCharRange == null })
    }

    @Test
    fun codeImagesAndRulesAreNotSpoken() {
        val blocks = listOf(
            para("Around code."),
            Block(kind = BlockKind.Code, spans = listOf(Span("fun main() { println(1) }"))),
            Block(kind = BlockKind.Rule),
            Block(kind = BlockKind.Image, image = ImageRef("a.png", "alt text")),
            para("End."),
        )
        val view = buildChapterTtsView(blocks)
        assertEquals("Around code.\n\nEnd.", view.plainText)
        assertEquals(listOf(0, 0, 4), view.words.map { it.blockIndex })
    }

    @Test
    fun charOffsetsAndRangesHandleWhitespaceBoundaries() {
        data class Case(val name: String, val blocks: List<Block>, val plainText: String, val offsets: List<Int>, val ranges: List<IntRange?>)
        val cases = listOf(
            Case("a single word keeps the punctuation it has, which is none", listOf(para("Solo")), "Solo", listOf(0), listOf(0..3)),
            Case("leading and trailing whitespace is trimmed out of the payload", listOf(para("  hello world  ")), "hello world", listOf(0, 6), listOf(2..6, 8..12)),
            Case("all-whitespace block emits nothing", listOf(para("Before."), para("   \n  "), para("After.")), "Before.\n\nAfter.", listOf(0, 9), listOf(0..6, 0..5)),
            Case("offsets accumulate across the block separator", listOf(para("Alpha beta."), heading("Gamma")), "Alpha beta.\n\nGamma", listOf(0, 6, 13), listOf(0..4, 6..10, 0..4)),
            Case("runs of inner whitespace collapse to single tokens", listOf(para("one   two\tthree.")), "one   two\tthree.", listOf(0, 6, 10), listOf(0..2, 6..8, 10..15)),
        )
        for (case in cases) {
            val view = buildChapterTtsView(case.blocks)
            assertEquals(case.plainText, view.plainText, case.name)
            assertEquals(case.offsets, view.words.map { it.chapterCharOffset }, case.name)
            assertEquals(case.ranges, view.words.map { it.highlightCharRange }, case.name)
        }
    }

    @Test
    fun textAndHeadingChaptersKeepContiguousIndices() {
        val view = buildChapterTtsView(listOf(heading("Chapter One"), para("First words here."), para("Second line.")))
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 1, 3 to 1, 4 to 1, 5 to 2, 6 to 2), view.words.map { it.globalIndex to it.blockIndex })
        assertEquals(listOf(0..6, 8..10, 0..4, 6..10, 12..16, 0..5, 7..11), view.words.map { it.highlightCharRange })
    }

    /**
     * A `<br/>` arrives as "\n" inside the span text. Dropping it would glue the words
     * either side into one token — the engine reads "OneThe" aloud and the aligner
     * counts one word where the page shows two — and slide every later highlight one
     * character left, far enough to straddle the gap and light up two words at once.
     */
    @Test
    fun highlightRangesIndexTheRenderedStringAcrossLineBreaks() {
        val block = para("Roses are red", "\n", "violets are blue")
        val view = buildChapterTtsView(listOf(block))
        assertEquals("Roses are red\nviolets are blue", view.plainText)
        val spoken = view.plainText.split(Regex("\\s+"))
        view.words.forEachIndexed { i, w ->
            val range = assertNotNull(w.highlightCharRange, "word $i has no highlight range")
            assertEquals(spoken[i], block.text.substring(range.first, range.last + 1), "word $i")
        }
    }

    @Test
    fun activeBlockIsFoundBySentence() {
        val blocks = listOf(
            para("First sentence here."),
            para("Second paragraph follows. With another sentence."),
            table("Buy milk"),
            para("He said “hello” — quietly."),
            para("Across\n   multiple   lines\twe match"),
        )
        data class Case(val needle: String?, val expected: Int)
        for (case in listOf(
            Case("Second paragraph follows", 1),
            Case("With another sentence.", 1),
            Case("Buy milk", 2),
            Case("He said \"hello\" - quietly", 3),
            Case("Across multiple lines we match.", 4),
            Case(null, -1),
            Case("   ", -1),
            Case("not present", -1),
        )) {
            assertEquals(case.expected, findActiveBlockIndex(blocks, case.needle), "${case.needle}")
        }
    }

    /** The spoken sentence lights the first paragraph that holds it, not a repeat further down. */
    @Test
    fun aRepeatedSentenceHighlightsOnlyItsFirstParagraph() {
        val sentence = "The door was open."
        assertEquals(0, findActiveBlockIndex(listOf(para(sentence), para("Nobody came."), para(sentence)), sentence))
    }

    @Test
    fun highlightRangeSpansStyledRunsAndIncludesTheFullStop() {
        val block = para("She said, ", "hello", ", before leaving.")
        val range = assertNotNull(fuzzyFind(block.text, "She said, hello, before leaving."))
        assertEquals(0..block.text.lastIndex, range)
        assertNull(fuzzyFind(block.text, "missing text"))
    }
}
