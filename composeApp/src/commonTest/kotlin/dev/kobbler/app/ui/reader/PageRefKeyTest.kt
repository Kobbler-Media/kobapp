package dev.kobbler.app.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PageRefKeyTest {

    @Test
    fun `a page's pager key is a primitive Android can save, unique per chapter and page`() {
        val refs = (0..3).flatMap { chapter -> (0..400 step 97).map { PageRef(chapter, it) } }
        refs.forEach { assertIs<Long>(it.pagerKey) }
        assertEquals(refs.size, refs.map { it.pagerKey }.toSet().size)
    }
}
