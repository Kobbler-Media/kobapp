package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.epub.ListItem
import dev.kobbler.app.ui.reader.epub.XhtmlBlock
import dev.kobbler.app.ui.reader.epub.XhtmlSpan
import dev.kobbler.app.ui.reader.epub.buildChapterTtsView
import dev.kobbler.app.ui.reader.epub.parseXhtmlToBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubTtsViewTest {

    private fun para(text: String) = XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text(text)))
    private fun heading(text: String) = XhtmlBlock.Heading(1, listOf(XhtmlSpan.Text(text)))
    private fun list(vararg items: String) =
        XhtmlBlock.ListBlock(items.map { ListItem(listOf(XhtmlSpan.Text(it))) }, ordered = false)

    /**
     * A fixed-layout book puts every VISUAL LINE in its own block, so one sentence
     * arrives as several and a pronoun arrives alone. The server rejoins those soft
     * wraps before synthesis — but only while they still read as wraps. Ending each
     * fragment with a fabricated full stop makes every one of them a sentence, and the
     * engine performs them one at a time, which is what makes the narration crawl.
     */
    @Test
    fun wrappedVisualLinesAreNotTurnedIntoSentences() {
        val blocks = listOf(
            para("I"),
            para("watch as Scrimshaw backs farther"),
            para("away, waving the pool cue."),
        )
        val view = buildChapterTtsView(blocks)
        assertEquals(
            "I\n\nwatch as Scrimshaw backs farther\n\naway, waving the pool cue.",
            view.plainText,
        )
        assertEquals(11, view.words.size)
    }

    /**
     * A no-break space is a glyph to Kotlin's tokenizer and a word separator to the
     * server's, which splits the token it sits in and shifts every word index after it —
     * so the highlight lands one word behind for the rest of the chapter.
     */
    @Test
    fun hardSpacesSeparateWordsTheSameWayTheServerSeesThem() {
        val view = buildChapterTtsView(listOf(para("M.\u00A0Dupont said\u202Fso.")))
        assertEquals("M. Dupont said so.", view.plainText)
        assertEquals(listOf(0, 3, 10, 15), view.words.map { it.chapterCharOffset })
        assertEquals(listOf(0..1, 3..8, 10..13, 15..17), view.words.map { it.highlightCharRange })
    }

    @Test
    fun headingsKeepTheirOwnPunctuation() {
        val view = buildChapterTtsView(listOf(heading("Chapter One"), para("First words here.")))
        assertEquals("Chapter One\n\nFirst words here.", view.plainText)
    }

    @Test
    fun listWordsShiftGlobalIndexForFollowingParagraph() {
        val blocks = listOf(para("One two three."), list("alpha beta", "gamma"), para("four five."))
        val view = buildChapterTtsView(blocks)
        assertEquals("One two three.\n\nalpha beta\n\ngamma\n\nfour five.", view.plainText)
        // Word 6 is "four" — the list contributed words 3..5 to the backend payload.
        val word = view.words.first { it.globalIndex == 6 }
        assertEquals(2, word.blockIndex)
        assertEquals(0..3, word.highlightCharRange)
        assertEquals(8, view.words.size)
    }

    @Test
    fun blockQuoteWordsShiftGlobalIndexForFollowingParagraph() {
        val blocks = listOf(
            para("Lead in."),
            XhtmlBlock.BlockQuote(listOf(para("Quoted one"), para("Quoted two."))),
            para("After."),
        )
        val view = buildChapterTtsView(blocks)
        assertEquals("Lead in.\n\nQuoted one\n\nQuoted two.\n\nAfter.", view.plainText)
        val word = view.words.first { it.globalIndex == 6 }
        assertEquals(2, word.blockIndex)
        assertEquals(0..5, word.highlightCharRange)
    }

    @Test
    fun onlyParagraphAndHeadingWordsAreHighlightable() {
        val blocks = listOf(
            heading("Title here"),
            para("Body text."),
            list("alpha", "beta"),
            XhtmlBlock.BlockQuote(listOf(para("quoted words"))),
        )
        val view = buildChapterTtsView(blocks)
        for (word in view.words) {
            val highlightable = word.blockIndex <= 1
            if (highlightable) {
                assertTrue(word.highlightCharRange != null, "block ${word.blockIndex} word ${word.globalIndex}")
            } else {
                assertNull(word.highlightCharRange, "block ${word.blockIndex} word ${word.globalIndex}")
            }
        }
    }

    @Test
    fun charOffsetsAndRangesHandleWhitespaceBoundaries() {
        data class Case(
            val name: String,
            val blocks: List<XhtmlBlock>,
            val plainText: String,
            val offsets: List<Int>,
            val ranges: List<IntRange?>,
        )
        val cases = listOf(
            Case(
                "a single word keeps the punctuation it has, which is none",
                listOf(para("Solo")),
                "Solo",
                listOf(0),
                listOf(0..3),
            ),
            Case(
                "leading and trailing whitespace is trimmed out of the payload",
                listOf(para("  hello world  ")),
                "hello world",
                listOf(0, 6),
                listOf(2..6, 8..12),
            ),
            Case(
                "all-whitespace block emits nothing",
                listOf(para("Before."), para("   \n  "), para("After.")),
                "Before.\n\nAfter.",
                listOf(0, 9),
                listOf(0..6, 0..5),
            ),
            Case(
                "offsets accumulate across the block separator",
                listOf(para("Alpha beta."), heading("Gamma")),
                "Alpha beta.\n\nGamma",
                listOf(0, 6, 13),
                listOf(0..4, 6..10, 0..4),
            ),
            Case(
                "runs of inner whitespace collapse to single tokens",
                listOf(para("one   two\tthree.")),
                "one   two\tthree.",
                listOf(0, 6, 10),
                listOf(0..2, 6..8, 10..15),
            ),
        )
        for (case in cases) {
            val view = buildChapterTtsView(case.blocks)
            assertEquals(case.plainText, view.plainText, case.name)
            assertEquals(case.offsets, view.words.map { it.chapterCharOffset }, case.name)
            assertEquals(case.ranges, view.words.map { it.highlightCharRange }, case.name)
        }
    }

    @Test
    fun paragraphAndHeadingOnlyChaptersKeepContiguousIndices() {
        val blocks = listOf(heading("Chapter One"), para("First words here."), para("Second line."))
        val view = buildChapterTtsView(blocks)
        assertEquals(
            listOf(0 to 0, 1 to 0, 2 to 1, 3 to 1, 4 to 1, 5 to 2, 6 to 2),
            view.words.map { it.globalIndex to it.blockIndex },
        )
        assertEquals(
            listOf(0..6, 8..10, 0..4, 6..10, 12..16, 0..5, 7..11),
            view.words.map { it.highlightCharRange },
        )
    }

    @Test
    fun payloadShapeForARepresentativeChapter() {
        val cases = listOf(
            """
                <html><body>
                  <h1>Chapter One</h1>
                  <p>First sentence here.</p>
                  <h2>A sub heading with no terminator</h2>
                  <p>Second paragraph, no terminator</p>
                </body></html>
            """ to "Chapter One\n\nFirst sentence here.\n\n" +
                "A sub heading with no terminator\n\nSecond paragraph, no terminator",
            """
                <html><body>
                  <p>Before the list.</p>
                  <ul><li>alpha beta</li><li>gamma.</li></ul>
                  <ol><li>first</li><li>second!</li></ol>
                  <p>After the list.</p>
                </body></html>
            """ to "Before the list.\n\nalpha beta\n\ngamma.\n\nfirst\n\nsecond!\n\nAfter the list.",
            """
                <html><body>
                  <p>Around code.</p>
                  <pre><code>fun main() { println(1) }</code></pre>
                  <hr/>
                  <br/>
                  <img src="a.png" alt="alt text"/>
                  <p>End.</p>
                </body></html>
            """ to "Around code.\n\nEnd.",
            """
                <html><body>
                  <p></p>
                  <p>   </p>
                  <h3>  </h3>
                  <ul></ul>
                  <blockquote></blockquote>
                  <p>  padded  </p>
                </body></html>
            """ to "padded",
            """
                <html><body>
                  <p>Ends with a quote"</p>
                  <p>Ends with semicolon;</p>
                  <p>Ends with comma,</p>
                </body></html>
            """ to "Ends with a quote\"\n\nEnds with semicolon;\n\nEnds with comma,",
            "<html><body></body></html>" to "",
        )
        for ((xhtml, expected) in cases) {
            assertEquals(expected, buildChapterTtsView(parseXhtmlToBlocks(xhtml.trimIndent())).plainText, xhtml)
        }
    }

    /**
     * A `<br/>` used to vanish from the payload entirely, gluing the words either
     * side of it into one token — the engine read "OneThe" aloud and the aligner
     * counted one word where the page shows two.
     */
    @Test
    fun aLineBreakSeparatesTheWordsAroundIt() {
        val blocks = listOf(
            XhtmlBlock.Paragraph(
                listOf(
                    XhtmlSpan.Text("Chapter One"),
                    XhtmlSpan.LineBreak(),
                    XhtmlSpan.Text("The Beginning"),
                ),
            ),
        )
        val view = buildChapterTtsView(blocks)
        assertEquals("Chapter One\nThe Beginning", view.plainText)
        assertEquals(4, view.words.size)
    }

    /**
     * The highlight regression: ranges index the string this builds, and the
     * renderer applies them to its own, which appends "\n" for every `<br/>`.
     * While the break was dropped here, every word after it sat one character
     * left per break — far enough to straddle the gap and light up two words at
     * once. Verse and title pages are where `<br/>` lives, so the front of a book
     * was the worst of it.
     */
    @Test
    fun highlightRangesIndexTheRenderedStringAcrossLineBreaks() {
        val spans = listOf(
            XhtmlSpan.Text("Roses are red"),
            XhtmlSpan.LineBreak(),
            XhtmlSpan.Text("violets are blue"),
        )
        val view = buildChapterTtsView(listOf(XhtmlBlock.Paragraph(spans)))
        val rendered = buildString {
            for (span in spans) when (span) {
                is XhtmlSpan.Text -> append(span.text)
                is XhtmlSpan.LineBreak -> append("\n")
            }
        }
        val spoken = view.plainText.split(Regex("\\s+"))
        view.words.forEachIndexed { i, w ->
            val range = assertNotNull(w.highlightCharRange, "word $i has no highlight range")
            assertEquals(spoken[i], rendered.substring(range.first, range.last + 1), "word $i")
        }
    }
}
