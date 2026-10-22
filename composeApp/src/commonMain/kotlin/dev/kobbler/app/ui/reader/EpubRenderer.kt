package dev.kobbler.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.kobbler.app.ui.reader.epub.InlineStyle
import dev.kobbler.app.ui.reader.epub.TtsWordHighlight
import dev.kobbler.app.ui.reader.epub.XhtmlBlock
import dev.kobbler.app.ui.reader.epub.XhtmlSpan
import dev.kobbler.app.ui.reader.epub.findActiveBlockIndex
import dev.kobbler.app.ui.reader.epub.findActiveListItemIndex
import dev.kobbler.app.ui.reader.epub.findHighlightRange

@Composable
fun EpubContent(
    blocks: List<XhtmlBlock>,
    theme: ReaderTheme,
    highlightedText: String? = null,
    wordHighlight: () -> TtsWordHighlight? = { null },
    imageResolver: ((String) -> Any?)? = null,
    pageHeight: Int = 0,
    onActiveBlockY: ((Int) -> Unit)? = null,
    onBlockPositioned: ((blockIndex: Int, topY: Int, height: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val word = wordHighlight()
    val sentenceIndex = remember(blocks, highlightedText) { findActiveBlockIndex(blocks, highlightedText) }
    val activeIndex = word?.blockIndex ?: sentenceIndex
    val contentModifier = modifier.padding(horizontal = theme.horizontalPadding.dp)

    var placedYs by remember { mutableStateOf(IntArray(0)) }
    var highlightLocalY by remember { mutableStateOf(IndexedY(-1, 0)) }
    LaunchedEffect(placedYs, highlightLocalY, activeIndex) {
        val blockY = placedYs.getOrNull(activeIndex) ?: return@LaunchedEffect
        val h = highlightLocalY
        val localY = if (h.index == activeIndex) h.y else 0
        onActiveBlockY?.invoke(blockY + localY)
    }

    val trackActive = onActiveBlockY != null && activeIndex >= 0
    val onActiveHighlightY = remember(activeIndex) {
        { y: Int -> highlightLocalY = IndexedY(activeIndex, y) }
    }
    val currentOnBlockPositioned by rememberUpdatedState(onBlockPositioned)
    val onBlocksPlaced = remember {
        { ys: IntArray, heights: IntArray ->
            placedYs = ys
            val report = currentOnBlockPositioned
            if (report != null) for (i in ys.indices) report(i, ys[i], heights[i])
        }
    }

    BlockColumn(pageHeight = pageHeight, onPlaced = onBlocksPlaced, modifier = contentModifier) {
        for ((i, block) in blocks.withIndex()) {
            val isActive = trackActive && i == activeIndex
            val blockWordRange = if (i == word?.blockIndex) word.charRange else null
            Box(Modifier.fillMaxWidth()) {
                RenderBlock(
                    block, theme, if (i == activeIndex) highlightedText else null, blockWordRange, imageResolver,
                    onHighlightLocalY = if (isActive) onActiveHighlightY else null,
                )
            }
        }
    }
}

// Bounds come from placement rather than per-block onGloballyPositioned: that callback
// fires for every block on every scroll frame, and the reader turns pages by scrolling.
@Composable
private fun BlockColumn(
    pageHeight: Int,
    onPlaced: (ys: IntArray, heights: IntArray) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val last = remember { PlacedBlocks() }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val childConstraints = Constraints(maxWidth = constraints.maxWidth)
        val placeables = measurables.map { it.measure(childConstraints) }
        val heights = IntArray(placeables.size) { placeables[it].height }
        val ys: IntArray
        val totalHeight: Int
        if (pageHeight > 0) {
            val result = computePaginatedLayout(heights, pageHeight)
            ys = result.blockYs
            totalHeight = result.totalHeight
        } else {
            ys = IntArray(heights.size)
            var y = 0
            for (i in heights.indices) { ys[i] = y; y += heights[i] }
            totalHeight = y
        }
        layout(constraints.maxWidth, totalHeight) {
            for (i in placeables.indices) placeables[i].placeRelative(0, ys[i])
            if (!ys.contentEquals(last.ys) || !heights.contentEquals(last.heights)) {
                last.ys = ys
                last.heights = heights
                onPlaced(ys, heights)
            }
        }
    }
}

private class PlacedBlocks(var ys: IntArray = IntArray(0), var heights: IntArray = IntArray(0))

@Composable
private fun RenderBlock(
    block: XhtmlBlock,
    theme: ReaderTheme,
    highlightedText: String?,
    wordCharRange: IntRange?,
    imageResolver: ((String) -> Any?)?,
    onHighlightLocalY: ((Int) -> Unit)? = null,
) {
    val reportsOwnY = block is XhtmlBlock.Paragraph ||
        block is XhtmlBlock.Heading ||
        block is XhtmlBlock.ListBlock ||
        block is XhtmlBlock.BlockQuote
    if (onHighlightLocalY != null && !reportsOwnY) {
        LaunchedEffect(Unit) { onHighlightLocalY(0) }
    }
    when (block) {
        is XhtmlBlock.Paragraph -> ParagraphBlock(block, theme, highlightedText, wordCharRange, onHighlightLocalY)
        is XhtmlBlock.Heading -> HeadingBlock(block, theme, highlightedText, wordCharRange, onHighlightLocalY)
        is XhtmlBlock.Image -> ImageBlock(block, imageResolver)
        is XhtmlBlock.FixedPage -> FixedPageBlock(block, theme, imageResolver)
        is XhtmlBlock.BlockQuote -> BlockQuoteBlock(block, theme, highlightedText, imageResolver, onHighlightLocalY)
        is XhtmlBlock.ListBlock -> ListBlockRenderer(block, theme, highlightedText, onHighlightLocalY)
        is XhtmlBlock.CodeBlock -> CodeBlockRenderer(block, theme)
        is XhtmlBlock.Divider -> HorizontalDivider(
            Modifier.padding(vertical = 12.dp),
            color = theme.textColor.copy(alpha = 0.2f),
        )
        is XhtmlBlock.Spacer -> Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ParagraphBlock(
    block: XhtmlBlock.Paragraph,
    theme: ReaderTheme,
    highlightedText: String?,
    wordCharRange: IntRange?,
    onHighlightLocalY: ((Int) -> Unit)?,
) {
    val styled = rememberStyledText(block.spans, theme, highlightedText, wordCharRange)
    Text(
        text = styled.annotated,
        style = bodyTextStyle(theme, textAlign = block.align ?: TextAlign.Start),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = theme.paragraphSpacing.dp),
        onTextLayout = highlightYReporter(onHighlightLocalY, styled.highlightRange),
    )
}

@Composable
private fun HeadingBlock(
    block: XhtmlBlock.Heading,
    theme: ReaderTheme,
    highlightedText: String?,
    wordCharRange: IntRange?,
    onHighlightLocalY: ((Int) -> Unit)?,
) {
    val styled = rememberStyledText(block.spans, theme, highlightedText, wordCharRange)
    val scale = when (block.level) {
        1 -> 1.8f
        2 -> 1.5f
        3 -> 1.25f
        4 -> 1.1f
        else -> 1.0f
    }
    Text(
        text = styled.annotated,
        style = bodyTextStyle(theme).copy(
            fontSize = (theme.fontSize.value * scale).sp,
            lineHeight = (theme.fontSize.value * scale * 1.25f).sp,
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        onTextLayout = highlightYReporter(onHighlightLocalY, styled.highlightRange),
    )
}

@Composable
private fun ImageBlock(block: XhtmlBlock.Image, imageResolver: ((String) -> Any?)?) {
    val model = imageResolver?.invoke(block.src) ?: block.src
    val imgModifier = if (block.width != null && block.height != null && block.height > 0) {
        Modifier
            .widthIn(max = block.width.dp)
            .aspectRatio(block.width.toFloat() / block.height.toFloat())
    } else Modifier
    val alignmentModifier = if (block.align == TextAlign.Center) Modifier.fillMaxWidth() else Modifier
    Box(
        modifier = alignmentModifier.padding(vertical = 8.dp),
        contentAlignment = if (block.align == TextAlign.Center) Alignment.Center else Alignment.TopStart,
    ) {
        AsyncImage(
            model = model,
            contentDescription = block.alt,
            contentScale = ContentScale.Fit,
            modifier = imgModifier.clip(RoundedCornerShape(4.dp)),
        )
    }
}

/** Fixed-layout page: artwork fills the page box; story-text fragments are positioned
 *  over it in page coordinates, scaled by the rendered box width. Font sizes follow the
 *  book's stylesheet (page px), not the user's theme — the text must stay registered
 *  with the artwork. */
@Composable
private fun FixedPageBlock(
    block: XhtmlBlock.FixedPage,
    theme: ReaderTheme,
    imageResolver: ((String) -> Any?)?,
) {
    val pageW = block.image.width?.takeIf { it > 0 } ?: 600
    val pageH = block.image.height?.takeIf { it > 0 } ?: 914
    val model = imageResolver?.invoke(block.image.src) ?: block.image.src
    var boxWidthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(pageW.toFloat() / pageH.toFloat())
            .onSizeChanged { boxWidthPx = it.width }
            .clip(RoundedCornerShape(4.dp)),
    ) {
        AsyncImage(
            model = model,
            contentDescription = block.image.alt,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        if (boxWidthPx > 0) {
            val boxWidthDp = with(LocalDensity.current) { boxWidthPx.toDp() }
            val scale = boxWidthDp.value / pageW
            val boxHeightDp = boxWidthDp * pageH / pageW
            Box(modifier = Modifier.matchParentSize()) {
                for (fragment in block.fragments) {
                    val styled = rememberStyledText(fragment.spans, theme, null, null)
                    Text(
                        text = styled.annotated,
                        style = androidx.compose.ui.text.TextStyle(
                            color = fragment.color ?: theme.textColor,
                            fontSize = (fragment.fontSizePagePx * scale).sp,
                            lineHeight = (fragment.fontSizePagePx * scale * 1.2f).sp,
                            textAlign = fragment.align,
                            fontFamily = theme.fontFamily,
                        ),
                        modifier = Modifier
                            .offset(
                                x = (fragment.leftPct * boxWidthDp.value).dp,
                                y = (fragment.topPct * boxHeightDp.value).dp,
                            )
                            .width((fragment.widthPagePx * scale).dp),
                    )
                }
            }
        }
    }
}
@Composable
private fun BlockQuoteBlock(
    block: XhtmlBlock.BlockQuote,
    theme: ReaderTheme,
    highlightedText: String?,
    imageResolver: ((String) -> Any?)?,
    onHighlightLocalY: ((Int) -> Unit)?,
) {
    val activeInnerIdx = if (onHighlightLocalY != null) findActiveBlockIndex(block.blocks, highlightedText) else -1
    val topPaddingPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    var innerBlockY by remember { mutableIntStateOf(0) }
    var innerHighlightY by remember { mutableIntStateOf(0) }
    LaunchedEffect(innerBlockY, innerHighlightY, activeInnerIdx) {
        if (activeInnerIdx >= 0) onHighlightLocalY?.invoke(topPaddingPx + innerBlockY + innerHighlightY)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .background(theme.textColor.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)
    ) {
        Column {
            for ((idx, inner) in block.blocks.withIndex()) {
                val isActiveInner = idx == activeInnerIdx
                val innerMod = if (isActiveInner) {
                    Modifier.onGloballyPositioned { coords -> innerBlockY = coords.positionInParent().y.toInt() }
                } else Modifier
                Box(innerMod) {
                    RenderBlock(
                        inner, theme, highlightedText, null, imageResolver,
                        onHighlightLocalY = if (isActiveInner) { y -> innerHighlightY = y } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListBlockRenderer(
    block: XhtmlBlock.ListBlock,
    theme: ReaderTheme,
    highlightedText: String?,
    onHighlightLocalY: ((Int) -> Unit)?,
) {
    val activeItemIdx = if (onHighlightLocalY != null) findActiveListItemIndex(block.items, highlightedText) else -1
    var itemY by remember { mutableIntStateOf(0) }
    var itemHighlightY by remember { mutableIntStateOf(0) }
    LaunchedEffect(itemY, itemHighlightY, activeItemIdx) {
        if (activeItemIdx >= 0) onHighlightLocalY?.invoke(itemY + itemHighlightY)
    }
    Column(Modifier.padding(start = 16.dp, bottom = 8.dp)) {
        block.items.forEachIndexed { idx, item ->
            val isActiveItem = idx == activeItemIdx
            val rowMod = Modifier.fillMaxWidth().padding(bottom = 4.dp).let {
                if (isActiveItem) it.onGloballyPositioned { coords -> itemY = coords.positionInParent().y.toInt() } else it
            }
            Row(rowMod) {
                val bullet = if (block.ordered) "${idx + 1}. " else "• "
                Text(bullet, style = bodyTextStyle(theme))
                val styled = rememberStyledText(item.spans, theme, highlightedText, null)
                Text(
                    text = styled.annotated,
                    style = bodyTextStyle(theme),
                    modifier = Modifier.weight(1f),
                    onTextLayout = if (isActiveItem) {
                        highlightYReporter({ y -> itemHighlightY = y }, styled.highlightRange)
                    } else NoOpTextLayout,
                )
            }
        }
    }
}

@Composable
private fun CodeBlockRenderer(block: XhtmlBlock.CodeBlock, theme: ReaderTheme) {
    Text(
        text = block.text,
        style = bodyTextStyle(theme).copy(
            fontSize = (theme.fontSize.value * 0.9f).sp,
            fontFamily = FontFamily.Monospace,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(theme.textColor.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .padding(8.dp),
    )
}

private fun bodyTextStyle(theme: ReaderTheme, textAlign: TextAlign = TextAlign.Start) =
    androidx.compose.ui.text.TextStyle(
        color = theme.textColor,
        fontSize = theme.fontSize,
        lineHeight = theme.lineHeight,
        textAlign = textAlign,
        fontFamily = theme.fontFamily,
    )

private data class IndexedY(val index: Int, val y: Int)

private data class StyledText(val annotated: AnnotatedString, val highlightRange: IntRange?)

@Composable
private fun rememberStyledText(
    spans: List<XhtmlSpan>,
    theme: ReaderTheme,
    highlightedText: String?,
    wordRange: IntRange?,
): StyledText = remember(spans, theme, highlightedText, wordRange) {
    buildStyledText(spans, theme, highlightedText, wordRange)
}

private fun buildStyledText(
    spans: List<XhtmlSpan>,
    theme: ReaderTheme,
    highlightedText: String?,
    wordRange: IntRange?,
): StyledText {
    var range: IntRange? = null
    val annotated = buildAnnotatedString {
        for (span in spans) when (span) {
            is XhtmlSpan.Text -> withStyle(spanStyle(span.style, theme)) { append(span.text) }
            is XhtmlSpan.LineBreak -> append("\n")
        }
        val rendered = toAnnotatedString().text
        if (wordRange != null && rendered.isNotEmpty()) {
            val start = wordRange.first.coerceAtLeast(0)
            val end = (wordRange.last + 1).coerceAtMost(rendered.length)
            if (end > start) {
                addStyle(SpanStyle(background = theme.highlightColor), start, end)
                range = start..(end - 1)
            }
        } else if (!highlightedText.isNullOrEmpty()) {
            val matched = findHighlightRange(spans, highlightedText)
            if (matched != null) {
                addStyle(SpanStyle(background = theme.highlightColor), matched.first, matched.last + 1)
                range = matched
            }
        }
    }
    return StyledText(annotated, range)
}

private val NoOpTextLayout: (TextLayoutResult) -> Unit = {}

private fun highlightYReporter(
    report: ((Int) -> Unit)?,
    range: IntRange?,
): (TextLayoutResult) -> Unit {
    if (report == null) return NoOpTextLayout
    return { result ->
        val y = if (range == null) 0 else {
            val maxIdx = (result.layoutInput.text.length - 1).coerceAtLeast(0)
            val charIdx = range.first.coerceIn(0, maxIdx)
            result.getBoundingBox(charIdx).top.toInt()
        }
        report(y)
    }
}

private fun spanStyle(style: InlineStyle, theme: ReaderTheme): SpanStyle = SpanStyle(
    fontWeight = if (style.bold) FontWeight.Bold else null,
    fontStyle = if (style.italic) FontStyle.Italic else null,
    textDecoration = when {
        style.underline && style.strikethrough -> TextDecoration.combine(
            listOf(TextDecoration.Underline, TextDecoration.LineThrough)
        )
        style.underline -> TextDecoration.Underline
        style.strikethrough -> TextDecoration.LineThrough
        else -> null
    },
    fontFamily = if (style.monospace) FontFamily.Monospace else null,
    baselineShift = when {
        style.superscript -> BaselineShift.Superscript
        style.subscript -> BaselineShift.Subscript
        else -> null
    },
    color = style.color ?: if (style.link != null) theme.linkColor else Color.Unspecified,
    fontSize = style.fontSize?.let { (theme.fontSize.value * it).sp } ?: TextUnit.Unspecified,
)
