package dev.kobbler.app.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.ui.reader.layout.ChapterLayout
import dev.kobbler.app.ui.reader.layout.PageView
import dev.kobbler.app.ui.reader.layout.ReaderTypography
import dev.kobbler.app.ui.reader.layout.TextHighlight
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.ImageRef
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
    onImage: (ImageRef) -> Unit,
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
        val scope = rememberCoroutineScope()
        val layouts = remember(book) { ReaderLayouts(book, scope) }
        val spec = LayoutSpec(typography, pageSize, LocalDensity.current, LocalLayoutDirection.current, LocalFontFamilyResolver.current)
        val laidOut = current.takeIf { fitted == null }
        val prevLaidOut = prevView.takeIf { it?.page == null }
        val nextLaidOut = nextView.takeIf { it?.page == null }
        SideEffect {
            layouts.want(
                spec,
                focus = paging.chapter.takeIf { current != null },
                listOfNotNull(
                    laidOut?.let { LayoutRequest(it, paging.anchor) },
                    nextLaidOut?.let { LayoutRequest(it, BlockOffset()) },
                    prevLaidOut?.let { LayoutRequest(it, null) },
                ),
            )
        }
        val layout = laidOut?.let { layouts.layoutOf(it.chapterIndex, spec) }
        val prevLayout = prevLaidOut?.let { layouts.layoutOf(it.chapterIndex, spec) }
        val nextLayout = nextLaidOut?.let { layouts.layoutOf(it.chapterIndex, spec) }

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
            // Until the chapter is paginated its end is not known, so nothing can follow it yet.
            if (fitted != null || layout?.paginated == true) when {
                nextView?.page != null -> add(PageRef(nextView.chapterIndex, 0))
                nextView != null && nextLayout != null -> add(PageRef(nextView.chapterIndex, 0))
            }
        }
        // From this composition's layout: paging.layout only catches up in the SideEffect, so
        // right after a chapter turn it still holds the old chapter's pages.
        val target = items.indexOf(PageRef(paging.chapter, paging.pageIn(layout.takeIf { fitted == null }, perSpread))).coerceAtLeast(0)
        val itemsState by rememberUpdatedState(items)
        val currentLayout by rememberUpdatedState(layout.takeIf { fitted == null })
        val pagerState = rememberPagerState(initialPage = target) { itemsState.size }

        SideEffect {
            paging.turn = { delta ->
                val to = pagerState.currentPage + delta
                when {
                    to in itemsState.indices -> {
                        scope.launch { pagerState.animateScrollToPage(to) }
                        true
                    }
                    else -> delta > 0 && currentLayout?.paginated == false
                }
            }
        }

        val shownLayouts by rememberUpdatedState(
            listOfNotNull(current?.let { it.chapterIndex to layout }, prevView?.let { it.chapterIndex to prevLayout }, nextView?.let { it.chapterIndex to nextLayout }).toMap(),
        )

        LaunchedEffect(items, target) {
            if (pagerState.currentPage != target && !pagerState.isScrollInProgress) pagerState.scrollToPage(target)
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { index ->
                val ref = itemsState.getOrNull(index) ?: return@collect
                if (ref == PageRef(paging.chapter, paging.page)) return@collect
                val refLayout = shownLayouts[ref.chapter]
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
            fun Modifier.fittedTaps(views: List<ChapterView>) = pointerInput(views, rtl, box) {
                detectTapGestures { at ->
                    if (!tapTurn(at, box.width, 0, 1, rtl, paging, book.lastChapter)) pictureAt(views, at.x, box.width)?.let(onImage)
                }
            }
            when {
                isCurrent && fitted != null ->
                    FittedPages(fitted, book, typography, fillWidth = pageFit == ReaderPageFit.Width, modifier = Modifier.fittedTaps(fitted))
                view.page != null ->
                    FittedPages(listOf(view), book, typography, fillWidth = pageFit == ReaderPageFit.Width, modifier = Modifier.fittedTaps(listOf(view)))
                refLayout != null -> Row(Modifier.fillMaxSize()) {
                    for (slot in 0 until perSpread) {
                        val pageIndex = ref.page + slot
                        val onTap = { at: Offset -> tapTurn(at, pageSize.width, slot, perSpread, rtl, paging, book.lastChapter) }
                        val blank = pageIndex >= refLayout.pages.size
                        Box(
                            Modifier.weight(1f).fillMaxHeight().then(
                                if (blank) Modifier.pointerInput(slot, perSpread, rtl, pageSize) { detectTapGestures(onTap = { onTap(it) }) } else Modifier,
                            ),
                        ) {
                            if (!blank) {
                                PageView(
                                    chapter = refLayout,
                                    pageIndex = pageIndex,
                                    blocks = view.blocks,
                                    styles = book.manifest.styles,
                                    typography = typography,
                                    imageModel = book::assetUrl,
                                    highlights = if (isCurrent) highlights else emptyList(),
                                    cursorBlock = if (isCurrent) cursorBlock else null,
                                    onTap = { _, at -> onTap(at) },
                                    onLink = onLink,
                                    onNote = onNote,
                                    onLongPress = onLongPress,
                                    // A float always sits at an edge, so it may not leave its taps to the page-turn zones there.
                                    onImageTap = { image, at, floating -> if (floating || !onTap(at)) onImage(image) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Outer thirds of the spread turn the page; false for the middle, which is left to the chrome. */
private fun tapTurn(at: Offset, pageWidth: Int, slot: Int, perSpread: Int, rtl: Boolean, paging: ReaderPaging, lastChapter: Int): Boolean {
    val x = at.x + slot * pageWidth
    val spreadWidth = pageWidth * perSpread
    val back = when {
        x < spreadWidth / 3f -> !rtl
        x > spreadWidth * 2f / 3f -> rtl
        else -> return false
    }
    paging.turnPage(if (back) -1 else 1, lastChapter)
    return true
}

/** The picture under [x] when fitted pages sit side by side, left to right, across [width]. */
private fun pictureAt(views: List<ChapterView>, x: Float, width: Int): ImageRef? {
    val view = if (views.size > 1 && x >= width / 2f) views.last() else views.firstOrNull()
    return view?.page?.block?.image
}
