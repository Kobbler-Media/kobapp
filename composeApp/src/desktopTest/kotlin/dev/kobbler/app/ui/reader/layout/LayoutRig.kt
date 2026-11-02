package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.kobbler.app.ui.reader.ReaderTheme
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.Span
import dev.kobbler.app.ui.reader.pack.Table
import dev.kobbler.app.ui.reader.pack.TableCell
import dev.kobbler.app.ui.reader.pack.TableRow
import kotlinx.coroutines.runBlocking
import kotlin.math.floor
import kotlin.test.assertTrue

internal object LayoutRig {
    val resolver = createFontFamilyResolver()
    val density = Density(1f)
    val typography = ReaderTypography(ReaderTheme.Light.copy(fontSize = 16.sp, lineHeight = 24.sp, horizontalPadding = 0))
    const val Width = 400

    val lineHeight: Float by lazy { layout(listOf(lines(1)), height = 1000).pages[0].slices[0].height }

    fun pageHeightFor(lines: Int): Int = floor(lines * lineHeight).toInt() + 1

    fun layout(
        blocks: List<Block>,
        width: Int = Width,
        height: Int = pageHeightFor(10),
        styles: List<PackTextStyle> = listOf(PackTextStyle()),
        typography: ReaderTypography = this.typography,
    ): ChapterLayout = runBlocking {
        layoutChapter(blocks, styles, typography, IntSize(width, height), density, resolver)
    }

    fun lines(n: Int, style: Int = 0, kind: BlockKind = BlockKind.Text): Block =
        Block(kind = kind, style = style, spans = listOf(Span((1..n).joinToString("\n") { "line $it" })))

    fun prose(words: Int, seed: Int = 0): Block =
        Block(spans = listOf(Span((0 until words).joinToString(" ") { Words[(it * 7 + seed) % Words.size] })))

    fun filler(lines: Int, pageLines: Int = 10): Block {
        val room = lines * lineHeight + lineHeight / 2
        return image(10, floor(pageHeightFor(pageLines) - room).toInt())
    }

    fun image(width: Int, height: Int) = Block(kind = BlockKind.Image, image = ImageRef("img.png", width = width, height = height))

    fun table(rows: Int, columns: Int = 2) = Block(
        kind = BlockKind.Table,
        table = Table(List(rows) { r -> TableRow(r == 0, List(columns) { c -> TableCell(listOf(Block(spans = listOf(Span("r$r c$c"))))) }) }),
    )

    fun linesOn(layout: ChapterLayout, page: Int, block: Int): Int =
        layout.pages[page].slices.filterIsInstance<TextSlice>().filter { it.blockIndex == block }
            .sumOf { it.lastLine - it.firstLine + 1 }

    fun PageLayout.contains(position: BlockOffset): Boolean = slices.any {
        it.blockIndex == position.block && when (it) {
            is TextSlice -> position.offset in it.startOffset..it.endOffset
            else -> true
        }
    }

    fun assertWellFormed(layout: ChapterLayout) {
        for (page in layout.pages) {
            assertTrue(page.slices.isNotEmpty(), "page ${page.index} is empty")
            for (slice in page.slices) {
                assertTrue(slice.bottom <= layout.pageSize.height + 0.5f || page.slices.size == 1, "slice overflows page ${page.index}")
            }
        }
    }

    private val Words = (
        "the quick brown fox jumps over a lazy dog while seven extraordinary wizards quietly " +
            "contemplate typographic hyphenation across unreasonably narrow columns of justified prose"
        ).split(" ")
}
