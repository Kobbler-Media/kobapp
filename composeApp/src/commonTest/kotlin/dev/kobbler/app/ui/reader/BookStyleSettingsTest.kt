package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.layout.BookStyle
import dev.kobbler.app.ui.reader.layout.StyleAspect
import kotlin.test.Test
import kotlin.test.assertEquals

class BookStyleSettingsTest {

    private fun load(vararg stored: Pair<String, String>) = loadBookStyle(stored.toMap()::get)

    @Test
    fun `the old two-way setting becomes all book or all mine`() {
        assertEquals(BookStyle.Book, load())
        assertEquals(BookStyle.Book, load("reader_style_mode" to "Publisher"))
        assertEquals(BookStyle.Mine, load("reader_style_mode" to "Reader"))
    }

    @Test
    fun `each aspect is saved on its own and wins over the old setting`() {
        val custom = BookStyle.Book.with(StyleAspect.Colours, book = false).with(StyleAspect.Sizes, book = false)
        val saved = mutableMapOf("reader_style_mode" to "Reader")
        saveBookStyle(custom, saved::set)
        assertEquals(custom, loadBookStyle(saved::get))
        assertEquals("mine", saved["reader_style_colours"])
        assertEquals("book", saved["reader_style_typeface"])
    }
}
