package dev.kobbler.app.ui.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kobbler.app.ui.reader.layout.ChapterLayout
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.Position
import kotlin.math.min

internal data class PageRef(val chapter: Int, val page: Int)

/**
 * Where the reader is: a chapter and an anchor character in it. The page is derived from
 * the anchor through the current layout, so a font or window change re-paginates and
 * lands on the page holding the same text. A null anchor is the chapter's last page —
 * where paging back into a chapter arrives before its layout exists.
 */
@Stable
internal class ReaderPaging(initial: Position) {
    var chapter by mutableIntStateOf(initial.chapter)
        private set
    var anchor by mutableStateOf<BlockOffset?>(BlockOffset(initial.block, initial.offset))
        private set

    var view by mutableStateOf<ChapterView?>(null)
        internal set
    var layout by mutableStateOf<ChapterLayout?>(null)
        internal set
    var fitted by mutableStateOf<List<ChapterView>?>(null)
        internal set
    var pagesPerSpread by mutableIntStateOf(1)
        internal set
    internal var turn: ((Int) -> Boolean)? = null

    val page: Int
        get() {
            if (fitted != null) return 0
            val l = layout ?: return 0
            val raw = anchor?.let { l.pageOf(it.block, it.offset) } ?: l.pages.lastIndex
            return raw - raw % pagesPerSpread
        }

    val pageCount: Int get() = if (fitted != null) 1 else layout?.pages?.size?.coerceAtLeast(1) ?: 1

    val shownPages: List<Int> get() = (page until min(page + pagesPerSpread, pageCount)).toList()

    val shownChapters: IntRange
        get() = fitted?.takeIf { it.isNotEmpty() }?.let { views -> views.minOf { it.chapterIndex }..views.maxOf { it.chapterIndex } }
            ?: chapter..chapter

    /** (page, total) for the progress label, counted in spreads when two pages show. */
    val counter: Pair<Int, Int>
        get() = (page / pagesPerSpread) to ((pageCount + pagesPerSpread - 1) / pagesPerSpread)

    val position: Position
        get() {
            val start = layout?.takeIf { fitted == null }?.positionAtPageStart(page) ?: anchor ?: BlockOffset()
            return Position(chapter, start.block, start.offset)
        }

    fun goTo(position: Position) {
        chapter = position.chapter
        anchor = BlockOffset(position.block, position.offset)
    }

    fun goToChapter(index: Int) = goTo(Position(index, 0, 0))

    fun goToChapterEnd(index: Int) {
        chapter = index
        anchor = null
    }

    /** Turn [delta] spreads, crossing into a neighbouring chapter when that is where the
     *  next page is. False when there is nothing that way. */
    fun turnPage(delta: Int, lastChapter: Int): Boolean {
        if (turn?.invoke(delta) == true) return true
        val range = shownChapters
        return when {
            delta > 0 && range.last < lastChapter -> { goToChapter(range.last + 1); true }
            delta < 0 && range.first > 0 -> { goToChapterEnd(range.first - 1); true }
            else -> false
        }
    }

    internal fun settle(chapter: Int, anchor: BlockOffset?) {
        this.chapter = chapter
        this.anchor = anchor
    }
}
