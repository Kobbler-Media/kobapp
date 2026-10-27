package dev.kobbler.app.ui.reader

import com.fleeksoft.ksoup.nodes.Document

internal class OpfRendition(val prePaginated: Boolean, val spreads: Boolean, val rtl: Boolean)

internal fun opfRendition(opf: Document): OpfRendition {
    val meta = opf.select("metadata meta[property]").associate { it.attr("property") to it.text().trim() }
    return OpfRendition(
        prePaginated = meta["rendition:layout"] == "pre-paginated",
        spreads = meta["rendition:spread"] != "none",
        rtl = opf.select("spine").first()?.attr("page-progression-direction") == "rtl",
    )
}

internal fun OpfRendition.layoutFor(itemrefProperties: String): FixedLayout? {
    val props = itemrefProperties.split(' ', '\t', '\n').filter { it.isNotBlank() }.toSet()
    val prePaginated = when {
        "rendition:layout-pre-paginated" in props -> true
        "rendition:layout-reflowable" in props -> false
        else -> prePaginated
    }
    if (!prePaginated) return null
    val spreads = when {
        "rendition:spread-none" in props -> false
        props.any { it.startsWith("rendition:spread-") } -> true
        else -> spreads
    }
    val side = when {
        "page-spread-left" in props || "rendition:page-spread-left" in props -> PageSpreadSide.Left
        "page-spread-right" in props || "rendition:page-spread-right" in props -> PageSpreadSide.Right
        "rendition:page-spread-center" in props -> PageSpreadSide.Center
        else -> PageSpreadSide.Auto
    }
    return FixedLayout(side, spreads)
}
