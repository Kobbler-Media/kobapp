package dev.kobbler.app.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.ui.reader.layout.ChapterLayout
import dev.kobbler.app.ui.reader.layout.PageView
import dev.kobbler.app.ui.reader.layout.ReaderTypography
import dev.kobbler.app.ui.reader.layout.TextHighlight
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.Link
import kotlinx.coroutines.launch

// Two pages side by side only once each would still be wider than a phone held upright.
private const val SpreadAspect = 1.3f

/**
 * The book as pages: the current chapter's pages, flanked by the last page of the chapter
 * before and the first of the chapter after, so a swipe across a chapter boundary is the
 * same gesture as any other. Settling on a flanking page makes that chapter current.
 */
@Composable
internal fun ReaderPagedBody(
    book: ReaderBook,
    paging: ReaderPaging,
    typography: ReaderTypography,
    pageFit: ReaderPageFit,
    highlights: List<TextHighlight>,
    cursorBlock: Int?,
    onLink: (Link) -> Unit,
    onNote: (String) -> Unit,
    onLongPress: (BlockOffset) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val box = IntSize(constraints.maxWidth, constraints.maxHeight)
        val perSpread = if (box.width >= box.height * SpreadAspect) 2 else 1
        val pageSize = IntSize(box.width / perSpread, box.height)
        val rtl = book.manifest.rtl

        val current = rememberChapterView(book, paging.chapter)
        val fitted = rememberFittedChapters(book, current, paging.chapter, pageFit, box)
        val shown = fitted?.takeIf { it.isNotEmpty() }?.let { v -> v.minOf { it.chapterIndex }..v.maxOf { it.chapterIndex } }
            ?: paging.chapter..paging.chapter
        val prevView = rememberChapterView(book, shown.first - 1)
        val nextView = rememberChapterView(book, shown.last + 1)
        val layout = rememberChapterLayout(current.takeIf { fitted == null }, typography, pageSize, book.manifest)
        val prevLayout = rememberChapterLayout(prevView.takeIf { it?.page == null }, typography, pageSize, book.manifest)
        val nextLayout = rememberChapterLayout(nextView.takeIf { it?.page == null }, typography, pageSize, book.manifest)

        SideEffect {
            paging.view = current
            paging.layout = layout
            paging.fitted = fitted
            paging.pagesPerSpread = perSpread
        }

        fun spreadStart(page: Int) = page - page % perSpread
        val currentCount = when {
            fitted != null -> 1
            layout != null -> layout.pages.size
            else -> 0
        }
        if (currentCount == 0 || (fitted != null && fitted.isEmpty())) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = typography.theme.linkColor)
            return@BoxWithConstraints
        }
        val currentStep = if (fitted != null) 1 else perSpread
        val items = buildList {
            when {
                prevView?.page != null -> add(PageRef(prevView.chapterIndex, 0))
                prevView != null && prevLayout != null -> add(PageRef(prevView.chapterIndex, spreadStart(prevLayout.pages.lastIndex)))
            }
            for (p in 0 until currentCount step currentStep) add(PageRef(paging.chapter, p))
            when {
                nextView?.page != null -> add(PageRef(nextView.chapterIndex, 0))
                nextView != null && nextLayout != null -> add(PageRef(nextView.chapterIndex, 0))
            }
        }
        val target = items.indexOf(PageRef(paging.chapter, paging.page)).coerceAtLeast(0)
        val itemsState by rememberUpdatedState(items)
        val pagerState = rememberPagerState(initialPage = target) { itemsState.size }
        val scope = rememberCoroutineScope()

        SideEffect {
            paging.turn = { delta ->
                val to = pagerState.currentPage + delta
                if (to in itemsState.indices) {
                    scope.launch { pagerState.animateScrollToPage(to) }
                    true
                } else false
            }
        }

        LaunchedEffect(items, target) {
            if (pagerState.currentPage != target && !pagerState.isScrollInProgress) pagerState.scrollToPage(target)
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { index ->
                val ref = itemsState.getOrNull(index) ?: return@collect
                if (ref == PageRef(paging.chapter, paging.page)) return@collect
                val refLayout = when (ref.chapter) {
                    paging.chapter -> paging.layout
                    prevView?.chapterIndex -> prevLayout
                    else -> nextLayout
                }
                val anchor = if (paging.fitted != null && ref.chapter == paging.chapter) BlockOffset()
                    else refLayout?.positionAtPageStart(ref.page) ?: BlockOffset()
                paging.settle(ref.chapter, anchor)
            }
        }

        fun layoutOf(chapter: Int): Pair<ChapterView, ChapterLayout?>? = when (chapter) {
            current?.chapterIndex -> current to layout
            prevView?.chapterIndex -> prevView to prevLayout
            nextView?.chapterIndex -> nextView to nextLayout
            else -> null
        }

        HorizontalPager(
            state = pagerState,
            reverseLayout = rtl,
            key = { itemsState.getOrNull(it)?.pagerKey ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val ref = itemsState.getOrNull(index) ?: return@HorizontalPager
            val (view, refLayout) = layoutOf(ref.chapter) ?: return@HorizontalPager
            val isCurrent = ref.chapter == paging.chapter
            when {
                isCurrent && fitted != null -> FittedPages(fitted, book, typography, fillWidth = pageFit == ReaderPageFit.Width)
                view.page != null -> FittedPages(listOf(view), book, typography, fillWidth = pageFit == ReaderPageFit.Width)
                refLayout != null -> Row(Modifier.fillMaxSize()) {
                    for (slot in 0 until perSpread) {
                        val pageIndex = ref.page + slot
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (pageIndex < refLayout.pages.size) {
                                PageView(
                                    chapter = refLayout,
                                    pageIndex = pageIndex,
                                    blocks = view.blocks,
                                    styles = book.manifest.styles,
                                    typography = typography,
                                    imageModel = book::assetUrl,
                                    highlights = if (isCurrent) highlights else emptyList(),
                                    cursorBlock = if (isCurrent) cursorBlock else null,
                                    onTap = { _, at -> tapTurn(at, pageSize.width, slot, perSpread, rtl, paging, book.lastChapter) },
                                    onLink = onLink,
                                    onNote = onNote,
                                    onLongPress = onLongPress,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Outer thirds of the spread turn the page; the middle is left to the chrome. */
private fun tapTurn(at: Offset, pageWidth: Int, slot: Int, perSpread: Int, rtl: Boolean, paging: ReaderPaging, lastChapter: Int) {
    val x = at.x + slot * pageWidth
    val spreadWidth = pageWidth * perSpread
    val back = when {
        x < spreadWidth / 3f -> !rtl
        x > spreadWidth * 2f / 3f -> rtl
        else -> return
    }
    paging.turnPage(if (back) -1 else 1, lastChapter)
}
