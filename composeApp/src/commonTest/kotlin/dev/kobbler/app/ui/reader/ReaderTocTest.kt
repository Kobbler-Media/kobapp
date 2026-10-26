package dev.kobbler.app.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderTocTest {

    private val entries = listOf(TocEntry("Cover", 0), TocEntry("One", 2), TocEntry("Two", 5))

    @Test
    fun theChapterBeingReadIsTheEntryItFallsUnder() {
        val cases = listOf(0 to 0, 1 to 0, 2 to 1, 4 to 1, 5 to 2, 9 to 2)
        for ((chapter, expected) in cases) {
            assertEquals(expected, activeTocEntry(entries, chapter), "chapter $chapter")
        }
        assertEquals(0, activeTocEntry(listOf(TocEntry("Late", 3)), 1))
        assertEquals(0, activeTocEntry(emptyList(), 4))
    }
}
