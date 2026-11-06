package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Paginates the whole chapter. With [early], [onEarly] gets the pages up to and one past
 * the page holding it as soon as they are known, while the rest is still being measured.
 */
suspend fun layoutChapter(
    blocks: List<Block>,
    styles: List<PackTextStyle>,
    typography: ReaderTypography,
    pageSize: IntSize,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    early: BlockOffset? = null,
    onEarly: (ChapterLayout) -> Unit = {},
): ChapterLayout = withContext(Dispatchers.Default) {
    var reached = -1
    var sent = early == null
    val onPage = { pages: List<PageLayout> ->
        if (!sent) {
            if (reached < 0 && early != null && pages.last().endsAfter(early)) reached = pages.lastIndex
            if (reached >= 0 && pages.lastIndex > reached) {
                sent = true
                onEarly(ChapterLayout(pages.toList(), pageSize, complete = false))
            }
        }
    }
    val pages = paginator(blocks, styles, typography, pageSize, density, fontFamilyResolver, layoutDirection, onPage = onPage).run()
    ChapterLayout(pages, pageSize)
}

/**
 * Lays out only [window] of a chapter already paginated into [breaks]; the other pages are
 * left empty. Null when the pages come out differently from [breaks], which then no longer
 * describe this chapter.
 */
suspend fun layoutWindow(
    blocks: List<Block>,
    styles: List<PackTextStyle>,
    typography: ReaderTypography,
    pageSize: IntSize,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    layoutDirection: LayoutDirection,
    breaks: PageBreaks,
    window: IntRange,
): ChapterLayout? = withContext(Dispatchers.Default) {
    val first = window.first.coerceIn(0, breaks.size - 1)
    val last = window.last.coerceIn(first, breaks.size - 1)
    if (breaks.block(first) !in blocks.indices) return@withContext null
    val laidOut = paginator(
        blocks, styles, typography, pageSize, density, fontFamilyResolver, layoutDirection,
        start = breaks.resumeAt(first),
        pageLimit = last - first + 1,
    ).run()
    val agrees = laidOut.size == last - first + 1 && laidOut.withIndex().all { (i, page) ->
        val starts = PageBreaks.of(listOf(page))
        starts.block(0) == breaks.block(first + i) && starts.offset(0) == breaks.offset(first + i) && starts.part(0) == breaks.part(first + i)
    }
    if (!agrees) return@withContext null
    val pages = List(breaks.size) { i -> laidOut.getOrNull(i - first) ?: PageLayout(i, emptyList(), emptyList()) }
    ChapterLayout(pages, pageSize, breaks, complete = false, paginated = true)
}

private suspend fun paginator(
    blocks: List<Block>,
    styles: List<PackTextStyle>,
    typography: ReaderTypography,
    pageSize: IntSize,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    layoutDirection: LayoutDirection,
    start: PageStart = PageStart(),
    pageLimit: Int = Int.MAX_VALUE,
    onPage: (List<PageLayout>) -> Unit = {},
): Paginator {
    val context = currentCoroutineContext()
    // A shared TextMeasurer's cache is not thread-safe; this one is private to the call and uncached.
    val textMeasurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 0)
    val measurer = BlockMeasurer(styles, typography, pageSize, density, textMeasurer)
    return Paginator(blocks, measurer, pageSize.height.toFloat(), { context.ensureActive() }, start, pageLimit, onPage)
}

private fun PageLayout.endsAfter(position: BlockOffset): Boolean {
    val last = slices.lastOrNull() ?: return false
    if (last.blockIndex != position.block) return last.blockIndex > position.block
    return last !is TextSlice || position.offset < last.endOffset || last.lastLine == last.layout.lineCount - 1
}
