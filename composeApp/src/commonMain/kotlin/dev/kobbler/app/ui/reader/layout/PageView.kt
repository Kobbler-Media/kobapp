package dev.kobbler.app.ui.reader.layout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    onImageTap: (image: ImageRef, at: Offset, floating: Boolean) -> Unit = { _, _, _ -> },
) {
    val page = chapter.pages.getOrNull(pageIndex) ?: return
    val density = LocalDensity.current.density
    val solo = page.soloImage(density)
    val theme = typography.theme
    val tap by rememberUpdatedState(onTap)
    val link by rememberUpdatedState(onLink)
    val note by rememberUpdatedState(onNote)
    val longPress by rememberUpdatedState(onLongPress)
    val imageTap by rememberUpdatedState(onImageTap)
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(chapter, pageIndex, blocks) {
                detectTapGestures(
                    onTap = { at ->
                        val span = chapter.charAt(pageIndex, at.x, at.y)
                            ?.let { blocks.getOrNull(it.block)?.spanAt(it.offset) }
                        val image = page.imageAt(at, size, density)
                        when {
                            span?.note != null -> note(span.note)
                            span?.link != null -> link(span.link)
                            image != null -> imageTap(image.image, at, image.floating)
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
            drawBoxes(page.boxes, theme, typography.book.colours)
            drawFrames(page.frames, theme)
            if (cursorBlock != null) {
                drawCursorBand(page.slices.filter { it.blockIndex == cursorBlock }, theme.highlightColor.copy(alpha = 0.18f))
            }
            for (slice in page.slices) when (slice) {
                is TextSlice -> {
                    drawTextSlice(slice, highlights)
                    blocks.getOrNull(slice.blockIndex)?.let { drawSpanBorders(slice, it, styles, typography) }
                }
                is RuleSlice -> drawRule(slice, theme, typography.book.colours)
                is TableSlice -> drawTableSlice(slice, theme, typography.book)
                is ImageSlice, is FixedSlice -> {}
            }
        }
        for (slice in page.slices) when (slice) {
            is ImageSlice -> if (slice == solo) FilledImage(slice, density, imageModel)
                else PlacedImage(slice.image, slice.left, slice.top, slice.width, slice.height, imageModel)
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
            is RuleSlice -> RuleOrnament(slice, imageModel)
            is TextSlice -> {}
        }
    }
}

private fun visibleText(page: PageLayout, blocks: List<Block>): String =
    page.slices.filterIsInstance<TextSlice>().joinToString("\n") { slice ->
        val text = blocks.getOrNull(slice.blockIndex)?.text.orEmpty()
        text.substring(slice.startOffset.coerceIn(0, text.length), slice.endOffset.coerceIn(0, text.length)).trim()
    }

/** Shown below its own size: the layout shrank it to fit the text column or the page. */
private fun ImageSlice.shrunk(density: Float): Boolean = image.width?.let { it * density > width + 0.5f } ?: true

/** A picture the column shrank, with the page to itself, may use the whole page instead. */
private fun PageLayout.soloImage(density: Float): ImageSlice? = (slices.singleOrNull() as? ImageSlice)?.takeIf { it.shrunk(density) }

private fun ImageSlice.filling(box: IntSize, density: Float): Rect {
    val natural = image.width?.let { it * density / width } ?: Float.MAX_VALUE
    val scale = minOf(box.width / width, box.height / height, natural)
    val w = width * scale
    val h = height * scale
    val left = (box.width - w) / 2
    val top = (box.height - h) / 2
    return Rect(left, top, left + w, top + h)
}

private fun PageLayout.imageAt(at: Offset, box: IntSize, density: Float): ImageSlice? {
    soloImage(density)?.let { return it.takeIf { _ -> it.filling(box, density).contains(at) } }
    return slices.firstNotNullOfOrNull { slice ->
        (slice as? ImageSlice)?.takeIf { Rect(it.left, it.top, it.left + it.width, it.top + it.height).contains(at) }
    }
}

@Composable
private fun FilledImage(slice: ImageSlice, density: Float, imageModel: (String) -> Any?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val area = slice.filling(IntSize(constraints.maxWidth, constraints.maxHeight), density)
        PlacedImage(slice.image, area.left, area.top, area.width, area.height, imageModel)
    }
}

@Composable
private fun RuleOrnament(slice: RuleSlice, imageModel: (String) -> Any?) {
    val image = slice.style?.image ?: return
    val tiles = slice.imageTiles()
    if (tiles.isEmpty()) return
    val ref = ImageRef(image.src, width = image.width, height = image.height)
    Box(Modifier.placedAt(slice.left, slice.top, slice.width, slice.height).clipToBounds()) {
        for (tile in tiles) PlacedImage(ref, tile.left - slice.left, 0f, tile.width, tile.height, imageModel)
    }
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
