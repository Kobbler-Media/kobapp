package dev.kobbler.app.ui.reader

import dev.kobbler.app.ui.reader.PageSpreadSide.Auto
import dev.kobbler.app.ui.reader.PageSpreadSide.Center
import dev.kobbler.app.ui.reader.PageSpreadSide.Left
import dev.kobbler.app.ui.reader.PageSpreadSide.Right
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageSpreadTest {

    private fun fixed(side: PageSpreadSide = Auto, spreads: Boolean = true) = FixedLayout(side, spreads)

    private val cases: List<Triple<String, Pair<List<FixedLayout?>, Boolean>, List<IntRange>>> = listOf(
        Triple(
            "the cover stands alone, then pages pair up",
            List(6) { fixed() } to false,
            listOf(0..0, 1..2, 3..4, 5..5),
        ),
        Triple(
            "reflowable chapters never pair",
            listOf(null, fixed(), fixed(), null, fixed(), fixed()) to false,
            listOf(0..0, 1..2, 3..3, 4..5),
        ),
        Triple(
            "a page that forbids spreads stands alone",
            listOf(fixed(), fixed(), fixed(spreads = false), fixed(), fixed()) to false,
            listOf(0..0, 1..1, 2..2, 3..4),
        ),
        Triple(
            "a centred page stands alone and pairing restarts after it",
            listOf(fixed(), fixed(), fixed(Center), fixed(), fixed()) to false,
            listOf(0..0, 1..1, 2..2, 3..4),
        ),
        Triple(
            "explicit left and right sides decide the pairs left to right",
            listOf(fixed(Right), fixed(Left), fixed(Right), fixed(Right), fixed(Left), fixed(Right)) to false,
            listOf(0..0, 1..2, 3..3, 4..5),
        ),
        Triple(
            "right to left, a page starts a spread on the right",
            listOf(fixed(Left), fixed(Right), fixed(Left), fixed(Left)) to true,
            listOf(0..0, 1..2, 3..3),
        ),
        Triple(
            "a cover the book puts on the leading side pairs with the next page",
            listOf(fixed(Left), fixed(Right), fixed()) to false,
            listOf(0..1, 2..2),
        ),
    )

    @Test
    fun pairsSpreadsFromRenditionHints() {
        for ((name, input, expected) in cases) {
            assertEquals(expected, pairSpreads(input.first, rtl = input.second), name)
        }
    }

    @Test
    fun partnerIsTheOtherPageOfTheSpread() {
        val spreads = listOf(0..0, 1..2, 3..3)
        assertEquals(2, spreads.partnerOf(1))
        assertEquals(1, spreads.partnerOf(2))
        assertNull(spreads.partnerOf(0))
        assertNull(spreads.partnerOf(3))
        assertNull(spreads.partnerOf(9))
    }
}
