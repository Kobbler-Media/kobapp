package dev.kobbler.app.ui.reader.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class BlockKind {
    @SerialName("text") Text,
    @SerialName("heading") Heading,
    @SerialName("code") Code,
    @SerialName("image") Image,
    @SerialName("rule") Rule,
    @SerialName("table") Table,
    @SerialName("fixed_page") FixedPage,
}

@Serializable
enum class Frame {
    @SerialName("quote") Quote,
    @SerialName("aside") Aside,
    @SerialName("figure") Figure,
}

@Serializable
data class Block(
    val kind: BlockKind = BlockKind.Text,
    val level: Int? = null,
    val style: Int = 0,
    val spans: List<Span> = emptyList(),
    @SerialName("space_before") val spaceBefore: Float = 0f,
    @SerialName("inset_start") val insetStart: Float = 0f,
    @SerialName("inset_end") val insetEnd: Float = 0f,
    val frame: Frame? = null,
    val marker: String? = null,
    @SerialName("break_before") val breakBefore: Boolean = false,
    @SerialName("keep_with_next") val keepWithNext: Boolean = false,
    @SerialName("avoid_break_inside") val avoidBreakInside: Boolean = false,
    val image: ImageRef? = null,
    val table: Table? = null,
    val fixed: FixedPageDto? = null,
) {
    @Transient
    val text: String = spans.joinToString("") { it.text }

    @Transient
    private val spanStarts: IntArray = IntArray(spans.size).also { starts ->
        var at = 0
        spans.forEachIndexed { i, span -> starts[i] = at; at += span.text.length }
    }

    fun spanAt(offset: Int): Span? {
        if (offset < 0 || offset >= text.length) return null
        var lo = 0
        var hi = spanStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (spanStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return spans[lo]
    }

    fun spanStart(index: Int): Int = spanStarts[index]
}

@Serializable
data class Span(
    val text: String = "",
    val style: Int = 0,
    val link: Link? = null,
    val note: String? = null,
)

@Serializable
data class Link(val position: Position? = null, val href: String? = null)

@Serializable
data class ImageRef(
    val src: String,
    val alt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class Table(val rows: List<TableRow> = emptyList())

@Serializable
data class TableRow(val header: Boolean = false, val cells: List<TableCell> = emptyList())

@Serializable
data class TableCell(
    val blocks: List<Block> = emptyList(),
    val colspan: Int = 1,
    val rowspan: Int = 1,
)

@Serializable
data class FixedPageDto(
    val image: ImageRef,
    @SerialName("page_width") val pageWidth: Float,
    @SerialName("page_height") val pageHeight: Float,
    val fragments: List<Fragment> = emptyList(),
)

@Serializable
data class Fragment(
    val top: Float = 0f,
    val left: Float = 0f,
    val width: Float = 0f,
    @SerialName("font_size") val fontSize: Float = 0f,
    val color: String? = null,
    val align: TextAlignDto? = null,
    val spans: List<Span> = emptyList(),
)
