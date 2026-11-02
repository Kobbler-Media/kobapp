package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.ImageRef
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ReaderPageFit(val label: String) { Width("Fit width"), Page("Fit page"), Spread("Two pages") }

enum class ReaderStyleMode(val label: String) { Publisher("Book's style"), Reader("My style") }

enum class ReaderAlignment(val label: String) { Justified("Justified"), Ragged("Ragged") }

// A spread is only worth it if it barely shrinks either page against showing it alone.
private const val SpreadMinScale = 0.85f

@Immutable
internal class FittedPage(val blockIndex: Int, val block: Block, val size: IntSize)

internal fun fittedPageOf(blocks: List<Block>, fixedLayout: Boolean): FittedPage? {
    val content = blocks.withIndex().filter { it.value.kind != BlockKind.Rule }
    val (index, block) = content.singleOrNull() ?: return null
    val size = when (block.kind) {
        BlockKind.FixedPage -> block.fixed?.let { IntSize(it.pageWidth.roundToInt(), it.pageHeight.roundToInt()) }
        BlockKind.Image -> if (!fixedLayout) return null else block.image?.let(::declaredSize)
        else -> null
    } ?: return null
    return FittedPage(index, block, size)
}

private fun declaredSize(image: ImageRef): IntSize? {
    val w = image.width ?: return null
    val h = image.height ?: return null
    return if (w > 0 && h > 0) IntSize(w, h) else null
}

internal fun spreadLayout(box: IntSize, pages: List<IntSize>): List<IntSize> {
    val aspects = pages.map { it.width.toFloat() / it.height }
    val height = min(box.height.toFloat(), box.width / aspects.sum())
    return aspects.map { IntSize((height * it).roundToInt(), height.roundToInt()) }
}

internal fun spreadFits(box: IntSize, first: IntSize, second: IntSize): Boolean {
    val alone = max(spreadLayout(box, listOf(first))[0].height, spreadLayout(box, listOf(second))[0].height)
    return spreadLayout(box, listOf(first, second))[0].height >= alone * SpreadMinScale
}

/** Chapter indices to show fitted, left to right, or null when the chapter scrolls as text. */
internal fun fittedChapters(
    fit: ReaderPageFit,
    box: IntSize,
    current: Int,
    currentPage: IntSize?,
    partner: Int?,
    partnerPage: IntSize?,
    rtl: Boolean,
): List<Int>? {
    if (currentPage == null) return null
    if (fit != ReaderPageFit.Spread || partner == null || partnerPage == null) return listOf(current)
    if (!spreadFits(box, currentPage, partnerPage)) return listOf(current)
    val spread = listOf(min(current, partner), max(current, partner))
    return if (rtl) spread.reversed() else spread
}
