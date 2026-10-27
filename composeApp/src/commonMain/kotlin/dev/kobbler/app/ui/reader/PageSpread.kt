package dev.kobbler.app.ui.reader

enum class PageSpreadSide { Auto, Left, Right, Center }

data class FixedLayout(val side: PageSpreadSide = PageSpreadSide.Auto, val spreads: Boolean = true)

/**
 * Groups spine items into the spreads a book would be printed as: the cover alone, then
 * pages in pairs. Explicit `page-spread-*` hints win over parity — a page on the trailing
 * side cannot start a spread and a page on the leading side cannot end one — and anything
 * reflowable, centred or barred from spreads stands alone.
 */
internal fun pairSpreads(layouts: List<FixedLayout?>, rtl: Boolean): List<IntRange> {
    val leading = if (rtl) PageSpreadSide.Right else PageSpreadSide.Left
    val trailing = if (rtl) PageSpreadSide.Left else PageSpreadSide.Right
    fun sideOf(i: Int): PageSpreadSide? = layouts[i]?.takeIf { it.spreads }?.side?.takeIf { it != PageSpreadSide.Center }
    val out = ArrayList<IntRange>()
    var i = 0
    while (i < layouts.size) {
        val side = sideOf(i)
        val next = if (i + 1 < layouts.size) sideOf(i + 1) else null
        val opens = side != null && side != trailing && !(i == 0 && side == PageSpreadSide.Auto)
        if (opens && next != null && next != leading) {
            out += i..i + 1
            i += 2
        } else {
            out += i..i
            i++
        }
    }
    return out
}

internal fun List<IntRange>.partnerOf(index: Int): Int? {
    val spread = firstOrNull { index in it } ?: return null
    return when {
        spread.first == spread.last -> null
        index == spread.first -> spread.last
        else -> spread.first
    }
}
