package dev.kobbler.app.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaginationTest {

    @Test
    fun nextPageInLargeChapterAdvancesByViewport() {
        assertEquals(600, nextScrollTarget(currentScroll = 0, viewportHeight = 600, maxScroll = 1200))
        assertEquals(1200, nextScrollTarget(currentScroll = 600, viewportHeight = 600, maxScroll = 1200))
    }

    @Test
    fun nextPageClampsToMaxScrollWhenLastPageIsPartial() {
        // Reproduces the reported "stuck on page" bug:
        // contentHeight=900, viewport=600 → maxScroll=300. Old code tried to scroll to 600
        // (one viewport ahead), got clamped to 300, and currentPage stayed 0 → infinite stuck.
        assertEquals(300, nextScrollTarget(currentScroll = 0, viewportHeight = 600, maxScroll = 300))
    }

    @Test
    fun nextPageReturnsNullAtMaxScrollSoCallerCanAdvanceChapter() {
        assertNull(nextScrollTarget(currentScroll = 300, viewportHeight = 600, maxScroll = 300))
        assertNull(nextScrollTarget(currentScroll = 1200, viewportHeight = 600, maxScroll = 1200))
    }

    @Test
    fun nextPageReturnsNullForUnscrollableChapter() {
        assertNull(nextScrollTarget(currentScroll = 0, viewportHeight = 600, maxScroll = 0))
    }

    @Test
    fun previousPageGoesBackByViewport() {
        assertEquals(0, previousScrollTarget(currentScroll = 600, viewportHeight = 600))
        assertEquals(600, previousScrollTarget(currentScroll = 1200, viewportHeight = 600))
    }

    @Test
    fun previousPageClampsToZero() {
        assertEquals(0, previousScrollTarget(currentScroll = 300, viewportHeight = 600))
    }

    @Test
    fun previousPageReturnsNullAtTop() {
        assertNull(previousScrollTarget(currentScroll = 0, viewportHeight = 600))
    }

    @Test
    fun layoutPacksSmallBlocksOnOnePage() {
        // Three 100px blocks fit in a 600px page; total rounds up to one page.
        val r = computePaginatedLayout(intArrayOf(100, 100, 100), pageHeight = 600)
        assertEquals(600, r.totalHeight)
        assertEquals(listOf(0, 100, 200), r.blockYs.toList())
    }

    @Test
    fun layoutPushesOverflowBlockToNextPageBoundary() {
        // First block 300px (fits), second 400px would push past 600 → second snaps to page 1.
        // No content gets cut — page 0 has 300px of text + 300px whitespace.
        val r = computePaginatedLayout(intArrayOf(300, 400), pageHeight = 600)
        assertEquals(1200, r.totalHeight)
        assertEquals(listOf(0, 600), r.blockYs.toList())
    }

    @Test
    fun layoutForceSplitsOversizedBlockAcrossPages() {
        // A single 1500px block > 600 page can't sit cleanly anywhere; let it bleed across
        // pages so the user can still scroll through it. Total rounds up to 3 pages.
        val r = computePaginatedLayout(intArrayOf(1500), pageHeight = 600)
        assertEquals(1800, r.totalHeight)
        assertEquals(listOf(0), r.blockYs.toList())
    }

    @Test
    fun layoutChainsMultiplePageBreaks() {
        // 400 fits page 0 (with 200px to spare), 400 needs page 1, 400 needs page 2.
        val r = computePaginatedLayout(intArrayOf(400, 400, 400), pageHeight = 600)
        assertEquals(1800, r.totalHeight)
        assertEquals(listOf(0, 600, 1200), r.blockYs.toList())
    }

    @Test
    fun layoutEmptyChapterHasZeroHeight() {
        val r = computePaginatedLayout(intArrayOf(), pageHeight = 600)
        assertEquals(0, r.totalHeight)
        assertEquals(emptyList(), r.blockYs.toList())
    }

    @Test
    fun layoutFallsBackToStackedWhenPageHeightUnknown() {
        // viewportHeight=0 (not measured yet) → just stack blocks linearly, no rounding.
        val r = computePaginatedLayout(intArrayOf(100, 200, 300), pageHeight = 0)
        assertEquals(600, r.totalHeight)
        assertEquals(listOf(0, 100, 300), r.blockYs.toList())
    }

    @Test
    fun rapidPageTurnsEachAdvanceAFullPage() {
        // Press 1 commits 600 and starts animating. Press 2 lands while the scroll is only
        // part-way there (250) — computing from the live offset gives 850, a half page that
        // also leaves every later turn off the page grid.
        val viewport = 600
        val max = 3000
        val first = nextScrollTarget(pageTurnBase(null, liveScroll = 0, viewportHeight = viewport), viewport, max)
        assertEquals(600, first)
        val second = nextScrollTarget(pageTurnBase(first, liveScroll = 250, viewportHeight = viewport), viewport, max)
        assertEquals(1200, second)
        val third = nextScrollTarget(pageTurnBase(second, liveScroll = 700, viewportHeight = viewport), viewport, max)
        assertEquals(1800, third)
    }

    @Test
    fun pageTurnBaseFallsBackToLiveScrollAfterAFreeScroll() {
        // A touch drag beyond a page means the committed offset no longer describes where
        // the reader is; the live offset takes over.
        assertEquals(2400, pageTurnBase(committed = 600, liveScroll = 2400, viewportHeight = 600))
        assertEquals(400, pageTurnBase(committed = null, liveScroll = 400, viewportHeight = 600))
        assertEquals(400, pageTurnBase(committed = 600, liveScroll = 400, viewportHeight = 0))
    }
}
