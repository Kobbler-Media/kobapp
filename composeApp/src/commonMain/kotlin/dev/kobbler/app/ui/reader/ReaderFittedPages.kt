package dev.kobbler.app.ui.reader

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import dev.kobbler.app.ui.reader.layout.FixedPageArt
import dev.kobbler.app.ui.reader.layout.ReaderTypography
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.FixedLayoutDto
import dev.kobbler.app.ui.reader.pack.PageSide

internal fun FixedLayoutDto.toFixedLayout() = FixedLayout(
    side = when (side) {
        PageSide.Auto -> PageSpreadSide.Auto
        PageSide.Left -> PageSpreadSide.Left
        PageSide.Right -> PageSpreadSide.Right
        PageSide.Center -> PageSpreadSide.Center
    },
    spreads = spreads,
)

/**
 * The chapters to show as whole pages fitted to [box], left to right, or null when the
 * current chapter flows as text. Empty while the spread partner is still loading, so the
 * page does not flash up alone before its neighbour joins it.
 */
@Composable
internal fun rememberFittedChapters(
    book: ReaderBook,
    current: ChapterView?,
    currentChapter: Int,
    fit: ReaderPageFit,
    box: IntSize,
): List<ChapterView>? {
    val spreads = remember(book) {
        pairSpreads(book.manifest.chapters.map { it.fixedLayout?.toFixedLayout() }, book.manifest.rtl)
    }
    val partner = if (fit == ReaderPageFit.Spread && current?.page != null) spreads.partnerOf(currentChapter) else null
    val partnerView = rememberChapterView(book, partner ?: -1)
    val page = current?.page ?: return null
    if (partner != null && partnerView == null) return emptyList()
    val order = fittedChapters(fit, box, currentChapter, page.size, partner, partnerView?.page?.size, book.manifest.rtl)
        ?: return null
    return order.mapNotNull { if (it == currentChapter) current else partnerView }
}

@Composable
internal fun FittedPages(
    views: List<ChapterView>,
    book: ReaderBook,
    typography: ReaderTypography,
    fillWidth: Boolean,
    modifier: Modifier = Modifier,
) {
    val shown = views.mapNotNull { view -> view.page?.let { view to it } }
    if (fillWidth) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            for ((_, page) in shown) {
                val aspect = page.size.width.toFloat() / page.size.height
                PageArt(page, book, typography, Modifier.fillMaxWidth().aspectRatio(aspect))
            }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val sizes = spreadLayout(IntSize(constraints.maxWidth, constraints.maxHeight), shown.map { it.second.size })
        val density = LocalDensity.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            for ((i, entry) in shown.withIndex()) {
                val size = with(density) { DpSize(sizes[i].width.toDp(), sizes[i].height.toDp()) }
                PageArt(entry.second, book, typography, Modifier.size(size))
            }
        }
    }
}

@Composable
private fun PageArt(page: FittedPage, book: ReaderBook, typography: ReaderTypography, modifier: Modifier) {
    val block = page.block
    when (block.kind) {
        BlockKind.FixedPage -> block.fixed?.let { FixedPageArt(it, book.manifest.styles, typography, book::assetUrl, modifier) }
        BlockKind.Image -> block.image?.let {
            AsyncImage(
                model = book.assetUrl(it.src),
                contentDescription = it.alt,
                contentScale = ContentScale.Fit,
                modifier = modifier,
            )
        }
        else -> {}
    }
}
