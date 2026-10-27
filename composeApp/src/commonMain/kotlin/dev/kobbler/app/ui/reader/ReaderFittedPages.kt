package dev.kobbler.app.ui.reader

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize

/**
 * The chapters to show as whole pages fitted to [box], left to right, or null when the
 * current chapter scrolls as text. Empty while the spread partner is still being parsed,
 * so the page does not flash up alone before its neighbour joins it.
 */
@Composable
internal fun rememberFittedChapters(
    epub: EpubBook,
    current: ChapterView?,
    currentChapter: Int,
    fit: ReaderPageFit,
    box: IntSize,
): List<ChapterView>? {
    val spreads = remember(epub) { pairSpreads(epub.chapters.map { it.fixedLayout }, epub.rtl) }
    val partner = if (fit == ReaderPageFit.Spread && current?.page != null) spreads.partnerOf(currentChapter) else null
    val partnerView = rememberChapterView(epub, partner ?: -1)
    val page = current?.page ?: return null
    if (partner != null && partnerView == null) return emptyList()
    val order = fittedChapters(fit, box, currentChapter, page.size, partner, partnerView?.page?.size, epub.rtl)
        ?: return null
    return order.mapNotNull { if (it == currentChapter) current else partnerView }
}

@Composable
internal fun FittedPages(
    views: List<ChapterView>,
    theme: ReaderTheme,
    fillWidth: Boolean,
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val shown = views.mapNotNull { view -> view.page?.let { view to it } }
    if (fillWidth) {
        Column(modifier.fillMaxWidth()) {
            for ((view, page) in shown) {
                val aspect = page.size.width.toFloat() / page.size.height
                PageArt(page.block, theme, { view.images[it] }, Modifier.fillMaxWidth().aspectRatio(aspect))
            }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(viewportHeight), contentAlignment = Alignment.Center) {
        val sizes = spreadLayout(IntSize(constraints.maxWidth, constraints.maxHeight), shown.map { it.second.size })
        val density = LocalDensity.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            for ((i, entry) in shown.withIndex()) {
                val (view, page) = entry
                val size = with(density) { DpSize(sizes[i].width.toDp(), sizes[i].height.toDp()) }
                PageArt(page.block, theme, { view.images[it] }, Modifier.size(size))
            }
        }
    }
}
