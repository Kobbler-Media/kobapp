package dev.kobbler.app.ui.reader.layout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import coil3.compose.AsyncImage
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.Link
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import kotlin.math.roundToInt

@Immutable
data class TextHighlight(val block: Int, val range: IntRange, val color: Color)

@Composable
fun PageView(
    chapter: ChapterLayout,
    pageIndex: Int,
    blocks: List<Block>,
    styles: List<PackTextStyle>,
    typography: ReaderTypography,
    imageModel: (String) -> Any?,
    modifier: Modifier = Modifier,
    highlights: List<TextHighlight> = emptyList(),
    cursorBlock: Int? = null,
    onTap: (BlockOffset?, Offset) -> Unit = { _, _ -> },
    onLink: (Link) -> Unit = {},
    onNote: (String) -> Unit = {},
    onLongPress: (BlockOffset) -> Unit = {},
) {
    val page = chapter.pages.getOrNull(pageIndex) ?: return
    val theme = typography.theme
    val tap by rememberUpdatedState(onTap)
    val link by rememberUpdatedState(onLink)
    val note by rememberUpdatedState(onNote)
    val longPress by rememberUpdatedState(onLongPress)
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(chapter, pageIndex, blocks) {
                detectTapGestures(
                    onTap = { at ->
                        val span = chapter.charAt(pageIndex, at.x, at.y)
                            ?.let { blocks.getOrNull(it.block)?.spanAt(it.offset) }
                        when {
                            span?.note != null -> note(span.note)
                            span?.link != null -> link(span.link)
                            else -> tap(chapter.hitTest(pageIndex, at.x, at.y), at)
                        }
                    },
                    onLongPress = { at -> chapter.hitTest(pageIndex, at.x, at.y)?.let(longPress) },
                )
            },
    ) {
        // Drawn text is invisible to screen readers; the page's semantics carry what it shows.
        val pageText = remember(page, blocks) { visibleText(page, blocks) }
        Canvas(Modifier.matchParentSize().semantics { text = AnnotatedString(pageText) }) {
            drawFrames(page.frames, theme)
            if (cursorBlock != null) {
                drawCursorBand(page.slices.filter { it.blockIndex == cursorBlock }, theme.highlightColor.copy(alpha = 0.18f))
            }
            for (slice in page.slices) when (slice) {
                is TextSlice -> drawTextSlice(slice, highlights)
                is RuleSlice -> drawRule(slice, theme)
                is TableSlice -> drawTableSlice(slice, theme)
                is ImageSlice, is FixedSlice -> {}
            }
        }
        for (slice in page.slices) when (slice) {
            is ImageSlice -> PlacedImage(slice.image, slice.left, slice.top, slice.width, slice.height, imageModel)
            is FixedSlice -> FixedPageArt(
                slice.fixed, styles, typography, imageModel,
                Modifier.placedAt(slice.left, slice.top, slice.width, slice.height),
            )
            is TableSlice -> for (r in slice.rows) {
                val row = slice.table.rows[r]
                val top = slice.top - slice.rowsTop + row.top
                for (cell in row.cells) for (item in cell.items) if (item is CellItem.Image) {
                    PlacedImage(item.image, slice.left + cell.left + item.dx, top + item.dy, item.width, item.height, imageModel)
                }
            }
            is TextSlice, is RuleSlice -> {}
        }
    }
}

private fun visibleText(page: PageLayout, blocks: List<Block>): String =
    page.slices.filterIsInstance<TextSlice>().joinToString("\n") { slice ->
        val text = blocks.getOrNull(slice.blockIndex)?.text.orEmpty()
        text.substring(slice.startOffset.coerceIn(0, text.length), slice.endOffset.coerceIn(0, text.length)).trim()
    }

@Composable
private fun PlacedImage(image: ImageRef, left: Float, top: Float, width: Float, height: Float, imageModel: (String) -> Any?) {
    AsyncImage(
        model = imageModel(image.src),
        contentDescription = image.alt,
        contentScale = ContentScale.Fit,
        modifier = Modifier.placedAt(left, top, width, height),
    )
}

@Composable
private fun Modifier.placedAt(left: Float, top: Float, width: Float, height: Float): Modifier {
    val density = LocalDensity.current
    return offset { IntOffset(left.roundToInt(), top.roundToInt()) }
        .size(with(density) { width.toDp() }, with(density) { height.toDp() })
}
