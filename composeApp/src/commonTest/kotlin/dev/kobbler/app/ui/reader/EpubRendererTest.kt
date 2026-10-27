package dev.kobbler.app.ui.reader

import androidx.compose.ui.graphics.Color
import dev.kobbler.app.ui.reader.epub.InlineStyle
import dev.kobbler.app.ui.reader.epub.ListItem
import dev.kobbler.app.ui.reader.epub.XhtmlBlock
import dev.kobbler.app.ui.reader.epub.XhtmlSpan
import dev.kobbler.app.platform.ZipArchive
import dev.kobbler.app.ui.reader.epub.findActiveBlockIndex
import dev.kobbler.app.ui.reader.epub.findActiveListItemIndex
import dev.kobbler.app.ui.reader.epub.findHighlightRange
import dev.kobbler.app.ui.reader.epub.buildChapterTtsView
import dev.kobbler.app.ui.reader.epub.parseXhtmlToBlocks
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EpubRendererTest {

    @Test
    fun svgWrappedImageProducesImageBlock() {
        val xhtml = """
            <html><body>
              <div>
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
                     width="100%" height="100%" viewBox="0 0 394 600" preserveAspectRatio="none">
                  <image width="394" height="600" xlink:href="images/calibre_cover.jpg"/>
                </svg>
              </div>
            </body></html>
        """.trimIndent()
        val blocks = parseXhtmlToBlocks(xhtml)
        val image = blocks.filterIsInstance<XhtmlBlock.Image>().firstOrNull()
        assertNotNull(image, "SVG-wrapped <image> should produce an XhtmlBlock.Image")
        assertEquals("images/calibre_cover.jpg", image.src)
    }

    @Test
    fun svgWithoutImageEmitsNothing() {
        val xhtml = """
            <html><body>
              <p>before</p>
              <svg viewBox="0 0 10 10"><circle cx="5" cy="5" r="3"/></svg>
              <p>after</p>
            </body></html>
        """.trimIndent()
        val blocks = parseXhtmlToBlocks(xhtml)
        // SVG with no <image> contributes no blocks; surrounding paragraphs still render.
        assertEquals(2, blocks.filterIsInstance<XhtmlBlock.Paragraph>().size)
        assertTrue(blocks.none { it is XhtmlBlock.Image })
    }

    @Test
    fun hiddenAttributeIsNotRendered() {
        val xhtml = """
            <html><body>
              <p>visible</p>
              <div hidden><p>overlay text</p></div>
            </body></html>
        """.trimIndent()
        val rendered = buildChapterTtsView(parseXhtmlToBlocks(xhtml)).plainText
        assertEquals("visible", rendered)
        val withHidden = buildChapterTtsView(parseXhtmlToBlocks(xhtml, includeHidden = true)).plainText
        assertTrue("overlay text" in withHidden)
    }

    @Test
    fun inlineDisplayNoneIsNotRendered() {
        val xhtml = """
            <html><body>
              <p>visible</p>
              <div style="display:none"><p>hidden by style</p></div>
            </body></html>
        """.trimIndent()
        val rendered = buildChapterTtsView(parseXhtmlToBlocks(xhtml)).plainText
        assertEquals("visible", rendered)
    }

    @Test
    fun inlineVisibilityHiddenIsNotRendered() {
        val xhtml = """
            <html><body>
              <p>visible</p>
              <div style="visibility:hidden"><p>hidden by visibility</p></div>
            </body></html>
        """.trimIndent()
        val rendered = buildChapterTtsView(parseXhtmlToBlocks(xhtml)).plainText
        assertEquals("visible", rendered)
    }

    @Test
    fun stylesheetDisplayNoneIsNotRendered() {
        val xhtml = """
            <html><body>
              <style type="text/css">.annotation { display: none; }</style>
              <p>visible</p>
              <div class="annotation"><p>note</p></div>
            </body></html>
        """.trimIndent()
        val rendered = buildChapterTtsView(parseXhtmlToBlocks(xhtml)).plainText
        assertEquals("visible", rendered)
    }

    @Test
    fun stylesheetVisibilityHiddenIsNotRendered() {
        val xhtml = """
            <html><body>
              <style type="text/css">#overlay { visibility: hidden; }</style>
              <p>visible</p>
              <div id="overlay"><p>overlay</p></div>
            </body></html>
        """.trimIndent()
        val rendered = buildChapterTtsView(parseXhtmlToBlocks(xhtml)).plainText
        assertEquals("visible", rendered)
    }

    @Test
    fun hiddenInlineSpanIsSkippedButSurroundingTextKept() {
        val xhtml = """
            <html><body>
              <p>before <span hidden>secret</span> after</p>
            </body></html>
        """.trimIndent()
        val rendered = buildChapterTtsView(parseXhtmlToBlocks(xhtml)).plainText
        assertTrue("secret" !in rendered)
        assertTrue("before" in rendered)
        assertTrue("after" in rendered)
    }

    @Test
    fun fixedLayoutPageWithoutPositionedTextDegradesToArtworkOnly() {
        // A kobo-fullpage + kobo-page-text pair whose text layer carries NO positioned
        // fragments (no stylesheet to place them) cannot be overlaid — the page degrades
        // to artwork only. The text stays available to search and TTS.
        val xhtml = """
            <html><body>
              <div class="kobo-fullpage">
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
                     viewBox="0 0 600 914" preserveAspectRatio="xMidYMid meet">
                  <image width="600" height="914" xlink:href="images/page001.jpg"/>
                </svg>
              </div>
              <div class="kobo-page-text" hidden="hidden"><p>And that's when I see him.</p></div>
            </body></html>
        """.trimIndent()
        val blocks = parseXhtmlToBlocks(xhtml)
        val images = blocks.filterIsInstance<XhtmlBlock.Image>()
        assertEquals(1, images.size)
        assertEquals("images/page001.jpg", images.first().src)
        val rendered = buildChapterTtsView(blocks).plainText
        assertTrue("see him" !in rendered, "unpositioned overlay text has nowhere to render")
        val withHidden = buildChapterTtsView(parseXhtmlToBlocks(xhtml, includeHidden = true)).plainText
        assertTrue("see him" in withHidden, "search and TTS must still find the text")
    }

    @Test
    fun displayBlockIsStillRendered() {
        // Guard against the inline-style check being too eager.
        val xhtml = """
            <html><body>
              <div style="display:block; margin:0"><p>still visible</p></div>
            </body></html>
        """.trimIndent()
        val rendered = buildChapterTtsView(parseXhtmlToBlocks(xhtml)).plainText
        assertTrue("still visible" in rendered)
    }

    @Test
    fun findsActiveBlockBySentenceMatch() {
        val blocks = listOf(
            XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text("First sentence here."))),
            XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text("Second paragraph follows. With another sentence."))),
            XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text("Third one ends."))),
        )
        assertEquals(1, findActiveBlockIndex(blocks, "Second paragraph follows"))
        assertEquals(1, findActiveBlockIndex(blocks, "With another sentence."))
    }

    @Test
    fun findActiveBlockNormalisesWhitespaceAcrossLineBreaks() {
        val blocks = listOf(
            XhtmlBlock.Paragraph(listOf(
                XhtmlSpan.Text("Across\n   multiple   lines\twe match."),
            )),
        )
        assertEquals(0, findActiveBlockIndex(blocks, "Across multiple lines we match"))
    }

    @Test
    fun findActiveBlockReturnsNegativeForMissingHighlight() {
        val blocks = listOf(XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text("Hello."))))
        assertEquals(-1, findActiveBlockIndex(blocks, null))
        assertEquals(-1, findActiveBlockIndex(blocks, ""))
        assertEquals(-1, findActiveBlockIndex(blocks, "   "))
        assertEquals(-1, findActiveBlockIndex(blocks, "not present"))
    }

    @Test
    fun findActiveBlockSearchesNestedListsAndQuotes() {
        val blocks = listOf(
            XhtmlBlock.ListBlock(
                items = listOf(ListItem(listOf(XhtmlSpan.Text("Buy milk")))),
                ordered = false,
            ),
            XhtmlBlock.BlockQuote(listOf(
                XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text("Quoted wisdom.")))
            )),
        )
        assertEquals(0, findActiveBlockIndex(blocks, "Buy milk"))
        assertEquals(1, findActiveBlockIndex(blocks, "Quoted wisdom"))
    }

    @Test
    fun findActiveBlockNormalisesSmartQuotesAndDashes() {
        // EPUB has typographic quotes/em-dash; TTS commonly returns straight quotes/hyphen.
        val blocks = listOf(
            XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text("He said “hello” — quietly."))),
        )
        assertEquals(0, findActiveBlockIndex(blocks, "He said \"hello\" - quietly"))
    }

    @Test
    fun findActiveBlockIgnoresTrailingPunctuationOnNeedle() {
        // buildChapterTtsView appends a period to make the splitter recognise sentence boundaries,
        // so the chunk text from the server may end with `.` even when the block text does not.
        val blocks = listOf(XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text("Hello world"))))
        assertEquals(0, findActiveBlockIndex(blocks, "Hello world."))
    }

    @Test
    fun findHighlightRangeMatchesAcrossSpans() {
        // Sentence crosses a span boundary — common with italics/links inside paragraphs.
        val spans = listOf(
            XhtmlSpan.Text("She said, "),
            XhtmlSpan.Text("hello", InlineStyle(italic = true)),
            XhtmlSpan.Text(", before leaving."),
        )
        val range = findHighlightRange(spans, "She said, hello, before leaving.")!!
        assertEquals(0, range.first)
        assertEquals("She said, hello, before leaving.".length - 1, range.last)
    }

    @Test
    fun findHighlightRangeReturnsNullWhenAbsent() {
        val spans = listOf(XhtmlSpan.Text("nothing here"))
        assertEquals(null, findHighlightRange(spans, "missing text"))
    }

    @Test
    fun findsActiveListItem() {
        val items = listOf(
            ListItem(listOf(XhtmlSpan.Text("Buy milk"))),
            ListItem(listOf(XhtmlSpan.Text("Walk the dog."))),
            ListItem(listOf(XhtmlSpan.Text("Email Bob"))),
        )
        assertEquals(1, findActiveListItemIndex(items, "Walk the dog"))
        assertEquals(2, findActiveListItemIndex(items, "Email Bob."))
        assertEquals(-1, findActiveListItemIndex(items, "missing"))
        assertEquals(-1, findActiveListItemIndex(items, null))
    }
    // --- Regression: gen-3 KEPUB fixed-layout page (kob-ebook "rewrite KF8 CSS-background
    // pages into scaling SVGs") ---
    //
    // "Timmy Failure: The Cat Stole My Pants" symptom: kobplayer showed the overlay text
    // but no page artwork. The artwork is an <svg><image xlink:href> inside .kobo-fullpage;
    // the bytes must survive parse -> block model -> ZIP resolution. A miss at any stage
    // degrades silently: a missing images[] entry makes ImageBlock fall back to the raw
    // relative src string and AsyncImage draws nothing.

    @Test
    fun gen3FixedLayoutPageLoadsArtworkFromZip() = runTest {
        val page = """
            <?xml version='1.0' encoding='UTF-8'?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head>
            <title>Timmy Failure: The Cat Stole My Pants</title>
            <meta name="viewport" content="width=600, height=914"/>
            <style type="text/css" class="kobostylehacks">div#book-inner { margin-top: 0; margin-bottom: 0;}html, body { height: 100%; margin: 0; padding: 0; }div#book-columns, div#book-inner, div.kobo-fullpage { height: 100%; }div.kobo-fullpage svg { display: block; width: 100%; height: 100%; }</style>
             <link rel="stylesheet" type="text/css" href="styles/style0000.css"/>
            </head>
            <body>
            <div id="book-columns"><div id="book-inner">
            <div class="kobo-fullpage"><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" viewBox="0 0 600 914" preserveAspectRatio="xMidYMid meet"><image width="600" height="914" xlink:href="images/image_0013.jpg"/></svg></div>
            <div class="kobo-page-text" hidden="hidden">
            <div class="fs"><div><div style="position: relative;overflow: hidden;width:600;height:914;">
            <div class="page_6_1"><span class="justify style2"><span class="koboSpan" id="kobo.0.1">It happened when we were touring the </span></span></div>
            <div class="page_6_2"><span class="justify style2"><span class="koboSpan" id="kobo.0.2">house of a famous author. </span></span></div>
            </div></div></div>
            </div>
            </div></div>
            </body></html>
        """.trimIndent()
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>The Cat Stole My Pants</dc:title>
            <dc:creator>Stephan Pastis</dc:creator>
            <dc:identifier id="BookId">9780763697976</dc:identifier>
            <meta property="dcterms:modified">2024-01-01T00:00:00Z</meta>
            <meta property="rendition:layout">pre-paginated</meta>
            </metadata>
            <manifest>
            <item id="chapter_6" href="part0006.html" media-type="application/xhtml+xml"/>
            <item id="img_0013" href="images/image_0013.jpg" media-type="image/jpeg"/>
            </manifest>
            <spine>
            <itemref idref="chapter_6"/>
            </spine>
            </package>
         """.trimIndent()
        // The real book's stylesheet: fragments are absolutely positioned inside the
        // 600x914 page box, fonts sized as % of the 16px CSS base.
        val css = """
            div.fs { height: 914px; width:600px; position: relative; }
            div.page_6_1 { top:35.61%; left:22.66%; position:absolute; width:378.19px;text-align:justify; }
            div.page_6_2 { top:38.91%; left:16.66%; position:absolute; width:232.75px;text-align:justify; }
            .style2 { font-family:ebheofmnimrod;font-size:108.12%;color:#242323; }
        """.trimIndent()
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:tc:opendocument:xmlns:container">
            <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles>
            </container>
        """.trimIndent()
        // Minimal JFIF JPEG: SOI, APP0 header, EOI.
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01,
            0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte(),
        )

        val epub = parseEpub(
            ZipArchive(
                storedZip(
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/styles/style0000.css" to css.encodeToByteArray(),
                    "OEBPS/part0006.html" to page.encodeToByteArray(),
                    "OEBPS/images/image_0013.jpg" to jpeg,
                ),
            ),
        )
        assertEquals(1, epub.chapters.size)
        assertEquals("OEBPS/part0006.html", epub.chapters[0].href)

        val view = buildChapterView(epub, 0)
        assertNotNull(view)

        // A fixed-layout page renders as a single FixedPage: artwork plus the story text
        // positioned over it — the hidden kobo-page-text layer IS the visible text.
        val fixedPages = view.blocks.filterIsInstance<XhtmlBlock.FixedPage>()
        assertEquals(1, fixedPages.size)
        val fixedPage = fixedPages[0]
        assertEquals("images/image_0013.jpg", fixedPage.image.src)
        assertEquals(600, fixedPage.image.width)
        assertEquals(914, fixedPage.image.height)
        assertEquals(2, fixedPage.fragments.size)

        // Fragment geometry comes from the stylesheet: page_6_1 sits at top 35.61% /
        // left 22.66%, 378.19px wide, in the .style2 font — 108.12% of the 16px base.
        val f1 = fixedPage.fragments[0]
        assertEquals(0.3561f, f1.topPct, 0.001f)
        assertEquals(0.2266f, f1.leftPct, 0.001f)
        assertEquals(378.19f, f1.widthPagePx, 0.1f)
        assertEquals(16f * 1.0812f, f1.fontSizePagePx, 0.1f)
        // The stylesheet's color is the page's design — dark text on light artwork —
        // and must not follow the reader's theme (white theme text would vanish).
        assertEquals(Color(0xFF242323), f1.color)
        val f1Text = f1.spans.filterIsInstance<XhtmlSpan.Text>().joinToString("") { it.text }
        assertEquals("It happened when we were touring the ", f1Text)

        // The bytes are actually resolved from the ZIP, keyed by the document-relative src.
        assertEquals(jpeg.toList(), view.images["images/image_0013.jpg"]?.toList())

        // Contract: the render pass KEEPS the fixed-page text (it is the page's visible
        // content) and TTS reads the same fragments.
        val rendered = buildChapterTtsView(view.blocks).plainText
        assertTrue("touring" in rendered, "fixed-page text must render")
        assertTrue("touring" in view.tts.plainText, "TTS must read the fixed-page text")
    }
}
