package dev.kobbler.app.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpubParserTest {

    @Test
    fun parsesNcxNavLabelsAsHrefToTitleMap() {
        val ncx = """
            <ncx><navMap>
              <navPoint><navLabel><text>Chapter One</text></navLabel><content src="ch1.xhtml"/></navPoint>
              <navPoint><navLabel><text>Chapter Two</text></navLabel><content src="ch2.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent()
        val titles = parseNcxTitlesFromXml(ncx, basePath = "")
        assertEquals("Chapter One", titles["ch1.xhtml"])
        assertEquals("Chapter Two", titles["ch2.xhtml"])
    }

    @Test
    fun ncxLastEntryWinsForSameHref() {
        // Calibre emits two navPoints per chapter (e.g., "PART 1" + "Watershed Moment")
        // both pointing to the same XHTML. Prefer the more descriptive one (the last).
        val ncx = """
            <ncx><navMap>
              <navPoint><navLabel><text>PART 1</text></navLabel><content src="ch1.xhtml"/></navPoint>
              <navPoint><navLabel><text>Watershed Moment</text></navLabel><content src="ch1.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent()
        val titles = parseNcxTitlesFromXml(ncx, basePath = "")
        assertEquals("Watershed Moment", titles["ch1.xhtml"])
    }

    @Test
    fun ncxStripsFragments() {
        val ncx = """
            <ncx><navMap>
              <navPoint><navLabel><text>Section A</text></navLabel><content src="ch1.xhtml#sec1"/></navPoint>
            </navMap></ncx>
        """.trimIndent()
        val titles = parseNcxTitlesFromXml(ncx, basePath = "OEBPS/")
        assertEquals("Section A", titles["OEBPS/ch1.xhtml"])
    }

    @Test
    fun applyChapterTitlesUsesNcxLabel() {
        val chapters = listOf(
            EpubChapter(title = "11/22/63", href = "ch1.xhtml", xhtml = ""),
        )
        val result = applyChapterTitles(chapters, mapOf("ch1.xhtml" to "Chapter One"), "11/22/63")
        assertEquals("Chapter One", result[0].title)
    }

    @Test
    fun applyChapterTitlesIsStickyForUnnamedSpineSplits() {
        // Calibre often splits one logical chapter across multiple spine files.
        // The continuation files have no NCX entry but should keep the chapter label.
        val chapters = listOf(
            EpubChapter(title = "11/22/63", href = "ch1.xhtml", xhtml = ""),
            EpubChapter(title = "11/22/63", href = "ch1-cont.xhtml", xhtml = ""),
            EpubChapter(title = "11/22/63", href = "ch2.xhtml", xhtml = ""),
        )
        val ncxTitles = mapOf(
            "ch1.xhtml" to "Chapter One",
            "ch2.xhtml" to "Chapter Two",
        )
        val result = applyChapterTitles(chapters, ncxTitles, "11/22/63")
        assertEquals("Chapter One", result[0].title)
        assertEquals("Chapter One", result[1].title) // sticky
        assertEquals("Chapter Two", result[2].title)
    }

    @Test
    fun applyChapterTitlesDropsRedundantBookTitle() {
        // For pre-NCX spine items whose <title> is just the book title, return null
        // so the top bar falls back to the book title rather than echoing it as the chapter.
        val chapters = listOf(
            EpubChapter(title = "11/22/63", href = "front.xhtml", xhtml = ""),
        )
        val result = applyChapterTitles(chapters, emptyMap(), "11/22/63")
        assertNull(result[0].title)
    }

    @Test
    fun applyChapterTitlesKeepsDistinctFileTitleBeforeFirstNcx() {
        // The cover/titlepage usually has <title>Cover</title>, distinct from the book title.
        // Keep it for spine items before any NCX label is encountered.
        val chapters = listOf(
            EpubChapter(title = "Cover", href = "titlepage.xhtml", xhtml = ""),
            EpubChapter(title = "11/22/63", href = "front.xhtml", xhtml = ""),
            EpubChapter(title = "11/22/63", href = "ch1.xhtml", xhtml = ""),
        )
        val ncxTitles = mapOf("ch1.xhtml" to "Chapter One")
        val result = applyChapterTitles(chapters, ncxTitles, "11/22/63")
        assertEquals("Cover", result[0].title)
        assertNull(result[1].title)
        assertEquals("Chapter One", result[2].title)
    }
}
