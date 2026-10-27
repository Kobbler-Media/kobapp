package dev.kobbler.app.ui.reader.epub

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

sealed interface XhtmlBlock {
    data class Paragraph(val spans: List<XhtmlSpan>, val align: TextAlign? = null) : XhtmlBlock
    data class Heading(val level: Int, val spans: List<XhtmlSpan>) : XhtmlBlock
    data class Image(
        val src: String,
        val alt: String?,
        val align: TextAlign? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : XhtmlBlock
    data class BlockQuote(val blocks: List<XhtmlBlock>) : XhtmlBlock
    /** Fixed-layout page (gen-3 KEPUB): full-page artwork with the story text positioned
     *  absolutely over it in page coordinates. The text comes from the book's hidden
     *  `kobo-page-text` layer — Kobo ships it hidden so devices only read it for TTS and
     *  search, but it IS the visible story text, and the stylesheet places each fragment
     *  over the artwork. Both the render and TTS passes emit this so block indices
     *  line up between them. */
    data class FixedPage(
        val image: Image,
        val fragments: List<OverlayFragment>,
    ) : XhtmlBlock {
        val pageWidth: Int get() = image.width?.takeIf { it > 0 } ?: 600
        val pageHeight: Int get() = image.height?.takeIf { it > 0 } ?: 914
    }
    data class ListBlock(val items: List<ListItem>, val ordered: Boolean) : XhtmlBlock
    data class CodeBlock(val text: String) : XhtmlBlock
    data object Divider : XhtmlBlock
    data object Spacer : XhtmlBlock
}

data class ListItem(val spans: List<XhtmlSpan>)

/** One absolutely-positioned text fragment on a [XhtmlBlock.FixedPage]. Coordinates are
 *  fractions of the page box ([XhtmlBlock.Image.width] × [XhtmlBlock.Image.height] page px);
 *  sizes are page px, scaled to the rendered box by the renderer. */
data class OverlayFragment(
    val topPct: Float,
    val leftPct: Float,
    val widthPagePx: Float,
    val fontSizePagePx: Float,
    val color: Color?,
    val align: TextAlign,
    val spans: List<XhtmlSpan>,
)

sealed interface XhtmlSpan {
    data class Text(val text: String, val style: InlineStyle = InlineStyle()) : XhtmlSpan
    data class LineBreak(val dummy: Unit = Unit) : XhtmlSpan
}

data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val monospace: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val link: String? = null,
    val fontSize: Float? = null,
    val color: Color? = null,
)

data class TtsWordHighlight(val blockIndex: Int, val charRange: IntRange)

/** One backend-visible word of a chapter. [globalIndex] matches the forced aligner's
 *  index, so it must count every word the backend receives, lists and quotes included.
 *  [chapterCharOffset] is where the word starts in [ChapterTtsView.plainText].
 *  [highlightCharRange] is the range in the *block's* rendered text, null when the
 *  block has no single Text composable to hang a SpanStyle on (lists, blockquotes). */
data class TtsWord(
    val globalIndex: Int,
    val chapterCharOffset: Int,
    val blockIndex: Int,
    val highlightCharRange: IntRange?,
)

/** The exact plain text sent to the TTS backend, paired with a word table whose
 *  indices line up with the backend's forced-aligner output. */
data class ChapterTtsView(val plainText: String, val words: List<TtsWord>)
